#import "NightVisionProcessor.h"
#import <CoreVideo/CoreVideo.h>
#import <AVFoundation/AVFoundation.h>

@implementation NightVisionProcessor {
    // Processing statistics
    NSUInteger _frameCount;
    NSTimeInterval _processingTimeSum;
    NSDate *_lastLogTime;

    // Custom Core Image kernel for advanced processing
    CIKernel *_nightVisionKernel;

    // Thread safety
    dispatch_queue_t _processingQueue;
    NSLock *_configLock;
}

- (instancetype)init {
    return [self initWithMetalDevice:MTLCreateSystemDefaultDevice()];
}

- (instancetype)initWithMetalDevice:(id<MTLDevice>)device {
    self = [super init];
    if (self) {
        _metalDevice = device;
        _enabled = NO;
        _intensity = 0.7f;
        _gamma = 0.6f;
        _brightnessThreshold = 0.3f;

        _frameCount = 0;
        _processingTimeSum = 0;
        _lastLogTime = [NSDate date];

        _configLock = [[NSLock alloc] init];
        _processingQueue = dispatch_queue_create("com.cloudwebrtc.nightvision.processing",
                                               DISPATCH_QUEUE_SERIAL);

        [self setupCoreImageContext];
        [self setupFilters];
        [self setupCustomKernel];

        NSLog(@"[NightVisionProcessor] Initialized with Metal device: %@", device.name);
    }
    return self;
}

- (void)setupCoreImageContext {
    if (_metalDevice) {
        // Use Metal-backed Core Image context for best performance
        _ciContext = [CIContext contextWithMTLDevice:_metalDevice];
        NSLog(@"[NightVisionProcessor] Core Image context created with GPU acceleration");
    } else {
        // Fallback to CPU context
        _ciContext = [CIContext context];
        NSLog(@"[NightVisionProcessor] Warning: Using CPU-based Core Image context");
    }
}

- (void)setupFilters {
    // Initialize Core Image filters for night vision processing
    _gammaFilter = [CIFilter filterWithName:@"CIGammaAdjust"];
    _contrastFilter = [CIFilter filterWithName:@"CIColorControls"];
    _noiseReductionFilter = [CIFilter filterWithName:@"CINoiseReduction"];
    _brightnessFilter = [CIFilter filterWithName:@"CIColorControls"];

    // Set default values
    [_gammaFilter setValue:@(0.6) forKey:kCIInputPowerKey];
    [_contrastFilter setValue:@(1.2) forKey:kCIInputContrastKey];
    [_noiseReductionFilter setValue:@(0.02) forKey:kCIInputNoiseReductionLevelKey];
    [_brightnessFilter setValue:@(0.1) forKey:kCIInputBrightnessKey];

    NSLog(@"[NightVisionProcessor] Core Image filters initialized");
}

- (void)setupCustomKernel {
    // For now, skip custom kernel setup and use built-in filters
    // Custom CIKernel implementation can be added later if needed
    _nightVisionKernel = nil;
    NSLog(@"[NightVisionProcessor] Using built-in Core Image filters for processing");
}

#pragma mark - ExternalVideoProcessingDelegate

- (RTCVideoFrame *)onFrame:(RTCVideoFrame *)frame {
    return [self processFrame:frame];
}

#pragma mark - Public Methods

- (RTCVideoFrame *)processFrame:(RTCVideoFrame *)frame {
    return [self processFrameInternal:frame isRemote:NO];
}

- (RTCVideoFrame *)processRemoteFrame:(RTCVideoFrame *)frame {
    return [self processFrameInternal:frame isRemote:YES];
}

- (RTCVideoFrame *)processFrameInternal:(RTCVideoFrame *)frame isRemote:(BOOL)isRemote {
    [_configLock lock];
    BOOL enabled = _enabled;
    float intensity = _intensity;
    float gamma = _gamma;
    float brightnessThreshold = _brightnessThreshold;
    [_configLock unlock];

    if (!enabled) {
        return frame;
    }

    NSDate *startTime = [NSDate date];

    @try {
        // Convert frame to CVPixelBuffer
        CVPixelBufferRef pixelBuffer = [self pixelBufferFromFrame:frame];
        if (!pixelBuffer) {
            NSLog(@"[NightVisionProcessor] Failed to extract pixel buffer from frame");
            return frame;
        }

        // Create CIImage from pixel buffer
        CIImage *inputImage = [CIImage imageWithCVPixelBuffer:pixelBuffer];

        // Apply night vision enhancement
        CIImage *enhancedImage = [self enhanceImage:inputImage
                                          intensity:intensity
                                              gamma:gamma
                                          threshold:brightnessThreshold];

        // Convert back to CVPixelBuffer
        CVPixelBufferRef outputBuffer = [self renderImageToPixelBuffer:enhancedImage
                                                                  size:CGSizeMake(frame.width, frame.height)];

        if (!outputBuffer) {
            NSLog(@"[NightVisionProcessor] Failed to render enhanced image");
            CVPixelBufferRelease(pixelBuffer);
            return frame;
        }

        // Create new RTCVideoFrame with enhanced buffer
        RTCCVPixelBuffer *rtcPixelBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:outputBuffer];
        RTCVideoFrame *enhancedFrame = [[RTCVideoFrame alloc] initWithBuffer:rtcPixelBuffer
                                                                    rotation:frame.rotation
                                                                 timeStampNs:frame.timeStampNs];

        // Update statistics
        [self updateStatistics:startTime isRemote:isRemote];

        // Clean up
        CVPixelBufferRelease(pixelBuffer);
        CVPixelBufferRelease(outputBuffer);

        return enhancedFrame;

    } @catch (NSException *exception) {
        NSLog(@"[NightVisionProcessor] Error processing frame: %@", exception.reason);
        return frame;
    }
}

- (CIImage *)enhanceImage:(CIImage *)inputImage
                intensity:(float)intensity
                    gamma:(float)gamma
                threshold:(float)threshold {

    // Use built-in filters for night vision enhancement
    return [self enhanceImageWithBuiltinFilters:inputImage
                                       intensity:intensity
                                           gamma:gamma
                                       threshold:threshold];
}

- (CIImage *)enhanceImageWithBuiltinFilters:(CIImage *)inputImage
                                   intensity:(float)intensity
                                       gamma:(float)gamma
                                   threshold:(float)threshold {

    CIImage *workingImage = inputImage;

    // Apply gamma correction
    [_gammaFilter setValue:workingImage forKey:kCIInputImageKey];
    [_gammaFilter setValue:@(1.0f/gamma) forKey:kCIInputPowerKey];
    workingImage = _gammaFilter.outputImage;

    // Apply contrast enhancement
    [_contrastFilter setValue:workingImage forKey:kCIInputImageKey];
    [_contrastFilter setValue:@(1.0f + intensity * 0.5f) forKey:kCIInputContrastKey];
    [_contrastFilter setValue:@(intensity * 0.1f) forKey:kCIInputBrightnessKey];
    workingImage = _contrastFilter.outputImage;

    // Apply noise reduction if intensity is high
    if (intensity > 0.5f) {
        [_noiseReductionFilter setValue:workingImage forKey:kCIInputImageKey];
        [_noiseReductionFilter setValue:@(intensity * 0.04f) forKey:kCIInputNoiseReductionLevelKey];
        workingImage = _noiseReductionFilter.outputImage;
    }

    return workingImage;
}

- (CVPixelBufferRef)pixelBufferFromFrame:(RTCVideoFrame *)frame {
    id<RTCVideoFrameBuffer> buffer = frame.buffer;

    if ([buffer isKindOfClass:[RTCCVPixelBuffer class]]) {
        RTCCVPixelBuffer *cvPixelBuffer = (RTCCVPixelBuffer *)buffer;
        CVPixelBufferRetain(cvPixelBuffer.pixelBuffer);
        return cvPixelBuffer.pixelBuffer;
    } else {
        // Convert I420 or other formats to CVPixelBuffer
        return [self convertFrameBufferToCVPixelBuffer:buffer];
    }
}

- (CVPixelBufferRef)convertFrameBufferToCVPixelBuffer:(id<RTCVideoFrameBuffer>)frameBuffer {
    // Convert to I420 first
    id<RTCI420Buffer> i420Buffer = [frameBuffer toI420];

    // Create CVPixelBuffer from I420
    int width = i420Buffer.width;
    int height = i420Buffer.height;

    NSDictionary *attributes = @{
        (id)kCVPixelBufferIOSurfacePropertiesKey: @{}
    };

    CVPixelBufferRef pixelBuffer;
    CVReturn result = CVPixelBufferCreate(kCFAllocatorDefault,
                                         width, height,
                                         kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                                         (__bridge CFDictionaryRef)attributes,
                                         &pixelBuffer);

    if (result != kCVReturnSuccess) {
        NSLog(@"[NightVisionProcessor] Failed to create pixel buffer: %d", result);
        return NULL;
    }

    // Copy I420 data to CVPixelBuffer
    CVPixelBufferLockBaseAddress(pixelBuffer, 0);

    // Copy Y plane
    uint8_t *dstY = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0);
    const uint8_t *srcY = i420Buffer.dataY;
    const int srcStrideY = i420Buffer.strideY;
    const int dstStrideY = (int)CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0);

    for (int row = 0; row < height; row++) {
        memcpy(dstY + row * dstStrideY, srcY + row * srcStrideY, width);
    }

    // Copy UV plane (convert from planar to semi-planar)
    uint8_t *dstUV = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1);
    const uint8_t *srcU = i420Buffer.dataU;
    const uint8_t *srcV = i420Buffer.dataV;
    const int srcStrideU = i420Buffer.strideU;
    const int srcStrideV = i420Buffer.strideV;
    const int dstStrideUV = (int)CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1);

    for (int row = 0; row < height / 2; row++) {
        for (int col = 0; col < width / 2; col++) {
            dstUV[row * dstStrideUV + col * 2] = srcU[row * srcStrideU + col];
            dstUV[row * dstStrideUV + col * 2 + 1] = srcV[row * srcStrideV + col];
        }
    }

    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);

    return pixelBuffer;
}

- (CVPixelBufferRef)renderImageToPixelBuffer:(CIImage *)image size:(CGSize)size {
    NSDictionary *attributes = @{
        (id)kCVPixelBufferIOSurfacePropertiesKey: @{}
    };

    CVPixelBufferRef pixelBuffer;
    CVReturn result = CVPixelBufferCreate(kCFAllocatorDefault,
                                         (size_t)size.width, (size_t)size.height,
                                         kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                                         (__bridge CFDictionaryRef)attributes,
                                         &pixelBuffer);

    if (result != kCVReturnSuccess) {
        NSLog(@"[NightVisionProcessor] Failed to create output pixel buffer: %d", result);
        return NULL;
    }

    // Render CIImage to CVPixelBuffer
    [_ciContext render:image toCVPixelBuffer:pixelBuffer];

    return pixelBuffer;
}

- (void)updateStatistics:(NSDate *)startTime isRemote:(BOOL)isRemote {
    _frameCount++;
    _processingTimeSum += [[NSDate date] timeIntervalSinceDate:startTime];

    // Log statistics every 5 seconds for local, 10 seconds for remote
    NSTimeInterval logInterval = isRemote ? 10.0 : 5.0;
    if ([[NSDate date] timeIntervalSinceDate:_lastLogTime] > logInterval) {
        double avgProcessingTime = _processingTimeSum / _frameCount * 1000.0; // Convert to ms
        NSLog(@"[NightVisionProcessor] %@ processing stats - Frames: %lu, Avg time: %.2f ms",
              isRemote ? @"Remote" : @"Local", (unsigned long)_frameCount, avgProcessingTime);

        _lastLogTime = [NSDate date];
        _frameCount = 0;
        _processingTimeSum = 0;
    }
}

#pragma mark - Configuration

- (void)setEnabled:(BOOL)enabled {
    [_configLock lock];
    _enabled = enabled;
    [_configLock unlock];
    NSLog(@"[NightVisionProcessor] Night vision %@", enabled ? @"enabled" : @"disabled");
}

- (void)setIntensity:(float)intensity {
    [_configLock lock];
    _intensity = MAX(0.0f, MIN(1.0f, intensity));
    _gamma = 0.8f - (_intensity * 0.4f); // Adjust gamma based on intensity
    [_configLock unlock];
    NSLog(@"[NightVisionProcessor] Intensity set to %.2f, gamma: %.2f", _intensity, _gamma);
}

- (void)setBrightnessThreshold:(float)threshold {
    [_configLock lock];
    _brightnessThreshold = MAX(0.0f, MIN(1.0f, threshold));
    [_configLock unlock];
    NSLog(@"[NightVisionProcessor] Brightness threshold set to %.2f", _brightnessThreshold);
}

- (void)dispose {
    _ciContext = nil;
    _gammaFilter = nil;
    _contrastFilter = nil;
    _noiseReductionFilter = nil;
    _brightnessFilter = nil;
    _nightVisionKernel = nil;
    _metalDevice = nil;

    NSLog(@"[NightVisionProcessor] Disposed");
}

@end