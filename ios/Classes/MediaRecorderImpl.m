#import "MediaRecorderImpl.h"

@interface MediaRecorderImpl ()

@property(nonatomic, strong) NSNumber* recorderId;
@property(nonatomic, strong) RTCVideoTrack* videoTrack;
@property(nonatomic, strong) id<RTCAudioRenderer> audioInterceptor;
@property(nonatomic, strong) AVAssetWriter* assetWriter;
@property(nonatomic, strong) AVAssetWriterInput* videoInput;
@property(nonatomic, strong) AVAssetWriterInput* audioInput;
@property(nonatomic, strong) dispatch_queue_t videoQueue;
@property(nonatomic, strong) dispatch_queue_t audioQueue;
@property(nonatomic, assign) BOOL isRecording;
@property(nonatomic, strong) NSString* filePath;
@property(nonatomic, strong) dispatch_semaphore_t completionSemaphore;
@property(nonatomic, assign) NSInteger droppedFrameCount;
@property(nonatomic, assign) NSInteger processedFrameCount;
@property(nonatomic, strong) NSDate* recordingStartTime;
@property(nonatomic, assign) NSTimeInterval completionTimeout;
@property(nonatomic, copy) void (^recordingErrorHandler)(NSError* error);

@end

@implementation MediaRecorderImpl

- (instancetype)initWithId:(NSNumber*)recorderId
                videoTrack:(RTCVideoTrack*)videoTrack
           audioInterceptor:(id<RTCAudioRenderer>)audioInterceptor {
    if (self = [super init]) {
        _recorderId = recorderId;
        _videoTrack = videoTrack;
        _audioInterceptor = audioInterceptor;
        _videoQueue = dispatch_queue_create("VideoQueue", DISPATCH_QUEUE_SERIAL);
        _audioQueue = dispatch_queue_create("AudioQueue", DISPATCH_QUEUE_SERIAL);
        _isRecording = NO;
        _completionSemaphore = dispatch_semaphore_create(0);
        _droppedFrameCount = 0;
        _processedFrameCount = 0;
        _completionTimeout = 10.0; // 10 seconds default
        
        [[NSNotificationCenter defaultCenter] addObserver:self
                                              selector:@selector(handleMemoryWarning)
                                                  name:UIApplicationDidReceiveMemoryWarningNotification
                                                object:nil];
        
        NSLog(@"[MediaRecorder-%@] Initialized with videoTrack: %@, audioInterceptor: %@", 
              recorderId, videoTrack ? @"YES" : @"NO", audioInterceptor ? @"YES" : @"NO");
        
        if (_videoTrack) {
            [_videoTrack addRenderer:self];
            NSLog(@"[MediaRecorder-%@] Added video track renderer", recorderId);
        }
    }
    return self;
}

- (void)startRecording:(NSString*)filePath error:(NSError**)error {
    NSLog(@"[MediaRecorder-%@] Attempting to start recording to path: %@", _recorderId, filePath);
    
    if (_isRecording) {
        NSLog(@"[MediaRecorder-%@] Error: Already recording", _recorderId);
        if (error) {
            *error = [NSError errorWithDomain:@"MediaRecorder"
                                       code:1
                                   userInfo:@{NSLocalizedDescriptionKey: @"Already recording"}];
        }
        return;
    }

    // Validate file path
    if (!filePath || [filePath length] == 0) {
        NSLog(@"[MediaRecorder-%@] Error: Invalid file path", _recorderId);
        if (error) {
            *error = [NSError errorWithDomain:@"MediaRecorder"
                                       code:2
                                   userInfo:@{NSLocalizedDescriptionKey: @"Invalid file path"}];
        }
        return;
    }

    // Ensure directory exists
    NSString* directory = [filePath stringByDeletingLastPathComponent];
    NSFileManager* fileManager = [NSFileManager defaultManager];
    if (![fileManager fileExistsAtPath:directory]) {
        NSError* dirError = nil;
        BOOL created = [fileManager createDirectoryAtPath:directory
                                withIntermediateDirectories:YES
                                                attributes:nil
                                                    error:&dirError];
        if (!created) {
            NSLog(@"[MediaRecorder-%@] Error: Failed to create directory: %@", _recorderId, dirError.localizedDescription);
            if (error) {
                *error = [NSError errorWithDomain:@"MediaRecorder"
                                           code:3
                                       userInfo:@{NSLocalizedDescriptionKey: @"Failed to create directory"}];
            }
            return;
        }
    }

    // Remove existing file if it exists
    if ([fileManager fileExistsAtPath:filePath]) {
        NSError* removeError = nil;
        if (![fileManager removeItemAtPath:filePath error:&removeError]) {
            NSLog(@"[MediaRecorder-%@] Error: Failed to remove existing file: %@", _recorderId, removeError.localizedDescription);
            if (error) {
                *error = [NSError errorWithDomain:@"MediaRecorder"
                                           code:4
                                       userInfo:@{NSLocalizedDescriptionKey: @"Failed to remove existing file"}];
            }
            return;
        }
    }
    
    _filePath = filePath;
    NSURL* fileURL = [NSURL fileURLWithPath:filePath];
    
    _assetWriter = [[AVAssetWriter alloc] initWithURL:fileURL
                                            fileType:AVFileTypeMPEG4
                                               error:error];
    if (*error) {
        NSLog(@"[MediaRecorder-%@] Failed to create asset writer: %@", _recorderId, (*error).localizedDescription);
        return;
    }

    if (!_videoTrack) {
        NSLog(@"[MediaRecorder-%@] Error: No video track available", _recorderId);
        if (error) {
            *error = [NSError errorWithDomain:@"MediaRecorder"
                                       code:5
                                   userInfo:@{NSLocalizedDescriptionKey: @"No video track available"}];
        }
        return;
    }
    
    // Reset statistics
    self.recordingStartTime = [NSDate date];
    self.droppedFrameCount = 0;
    self.processedFrameCount = 0;
    
    // Video settings with dynamic dimensions
    CGSize videoSize = CGSizeMake(1280, 720); // Default size
    if ([_videoTrack isKindOfClass:[RTCVideoTrack class]]) {
        // We'll update the dimensions when we receive the first frame
        // For now, use a common HD resolution as default
        NSLog(@"[MediaRecorder-%@] Using default video size: %.0fx%.0f", _recorderId, videoSize.width, videoSize.height);
    }
    
    NSDictionary* videoSettings = @{
        AVVideoCodecKey: AVVideoCodecTypeH264,
        AVVideoWidthKey: @((NSInteger)videoSize.width),
        AVVideoHeightKey: @((NSInteger)videoSize.height),
        AVVideoCompressionPropertiesKey: @{
            AVVideoAverageBitRateKey: @2000000,
            AVVideoMaxKeyFrameIntervalKey: @60,
            AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel
        }
    };
    
    _videoInput = [[AVAssetWriterInput alloc] initWithMediaType:AVMediaTypeVideo
                                                outputSettings:videoSettings];
    _videoInput.expectsMediaDataInRealTime = YES;
    
    if ([_assetWriter canAddInput:_videoInput]) {
        [_assetWriter addInput:_videoInput];
        NSLog(@"[MediaRecorder-%@] Added video input to asset writer", _recorderId);
    } else {
        NSLog(@"[MediaRecorder-%@] Error: Could not add video input to asset writer", _recorderId);
        if (error) {
            *error = [NSError errorWithDomain:@"MediaRecorder"
                                       code:6
                                   userInfo:@{NSLocalizedDescriptionKey: @"Could not add video input"}];
        }
        return;
    }
    
    // Start writing
    if ([_assetWriter startWriting]) {
        [_assetWriter startSessionAtSourceTime:CMTimeMake(0, 1000000000)];
        _isRecording = YES;
        _recordingStartTime = [NSDate date];
        NSLog(@"[MediaRecorder-%@] Successfully started recording", _recorderId);
    } else {
        NSLog(@"[MediaRecorder-%@] Failed to start recording: %@", _recorderId, _assetWriter.error.localizedDescription);
        if (error) {
            *error = _assetWriter.error;
            if (!error) {
                *error = [NSError errorWithDomain:@"MediaRecorder"
                                           code:7
                                       userInfo:@{NSLocalizedDescriptionKey: @"Failed to start recording"}];
            }
        }
    }
}

- (void)stopRecording {
    NSLog(@"[MediaRecorder-%@] Attempting to stop recording", _recorderId);
    
    if (!_isRecording) {
        NSLog(@"[MediaRecorder-%@] Warning: Not currently recording", _recorderId);
        return;
    }
    
    _isRecording = NO;
    
    if (_videoTrack) {
        [_videoTrack removeRenderer:self];
        NSLog(@"[MediaRecorder-%@] Removed video track renderer", _recorderId);
    }
    
    [_videoInput markAsFinished];
    
    __weak MediaRecorderImpl* weakSelf = self;
    [_assetWriter finishWritingWithCompletionHandler:^{
        MediaRecorderImpl* strongSelf = weakSelf;
        if (strongSelf) {
            NSLog(@"[MediaRecorder-%@] Successfully finished writing recording", strongSelf.recorderId);
            
            // Validate and log the recorded file properties
            NSFileManager *fileManager = [NSFileManager defaultManager];
            NSError *error = nil;
            NSDictionary *fileAttributes = [fileManager attributesOfItemAtPath:strongSelf.filePath error:&error];
            
            if (error) {
                NSLog(@"[MediaRecorder-%@] Error getting file attributes: %@", strongSelf.recorderId, error.localizedDescription);
            } else {
                unsigned long long fileSize = [fileAttributes fileSize];
                NSString *fileSizeMB = [NSString stringWithFormat:@"%.2f MB", fileSize / (1024.0 * 1024.0)];
                NSDate *creationDate = [fileAttributes fileCreationDate];
                
                // Get video properties using AVAsset
                AVAsset *asset = [AVAsset assetWithURL:[NSURL fileURLWithPath:strongSelf.filePath]];
                AVAssetTrack *videoTrack = [[asset tracksWithMediaType:AVMediaTypeVideo] firstObject];
                
                NSString *dimensions = @"unknown";
                NSString *duration = @"unknown";
                NSString *codec = @"unknown";
                
                if (videoTrack) {
                    CGSize size = videoTrack.naturalSize;
                    dimensions = [NSString stringWithFormat:@"%.0fx%.0f", size.width, size.height];
                    
                    // More accurate duration calculation
                    CMTime actualDuration = videoTrack.timeRange.duration;
                    if (CMTIME_IS_VALID(actualDuration) && !CMTIME_IS_INDEFINITE(actualDuration)) {
                        Float64 durationInSeconds = CMTimeGetSeconds(actualDuration);
                        if (durationInSeconds > 0 && durationInSeconds < 86400) { // Sanity check: max 24 hours
                            duration = [NSString stringWithFormat:@"%.2f seconds", durationInSeconds];
                        } else {
                            duration = @"invalid duration";
                            NSLog(@"[MediaRecorder-%@] Warning: Invalid duration value: %f seconds", strongSelf.recorderId, durationInSeconds);
                        }
                    } else {
                        duration = @"unknown";
                        NSLog(@"[MediaRecorder-%@] Warning: Could not determine valid duration", strongSelf.recorderId);
                    }
                    
                    // Get video codec
                    NSArray *formatDescriptions = [videoTrack formatDescriptions];
                    if ([formatDescriptions count] > 0) {
                        CMFormatDescriptionRef desc = (__bridge CMFormatDescriptionRef)[formatDescriptions objectAtIndex:0];
                        FourCharCode mediaSubType = CMFormatDescriptionGetMediaSubType(desc);
                        char fourCC[5] = {0};
                        fourCC[0] = (char)((mediaSubType >> 24) & 0xFF);
                        fourCC[1] = (char)((mediaSubType >> 16) & 0xFF);
                        fourCC[2] = (char)((mediaSubType >> 8) & 0xFF);
                        fourCC[3] = (char)(mediaSubType & 0xFF);
                        codec = [NSString stringWithUTF8String:fourCC];
                    }
                }
                
                NSLog(@"[MediaRecorder-%@] Recording validation:", strongSelf.recorderId);
                NSLog(@"[MediaRecorder-%@] - File path: %@", strongSelf.recorderId, strongSelf.filePath);
                NSLog(@"[MediaRecorder-%@] - File size: %@", strongSelf.recorderId, fileSizeMB);
                NSLog(@"[MediaRecorder-%@] - Creation date: %@", strongSelf.recorderId, creationDate);
                NSLog(@"[MediaRecorder-%@] - Dimensions: %@", strongSelf.recorderId, dimensions);
                NSLog(@"[MediaRecorder-%@] - Duration: %@", strongSelf.recorderId, duration);
                NSLog(@"[MediaRecorder-%@] - Video codec: %@", strongSelf.recorderId, codec);
            }
            
            strongSelf.assetWriter = nil;
            strongSelf.videoInput = nil;
            strongSelf.audioInput = nil;
            
            dispatch_semaphore_signal(strongSelf.completionSemaphore);
        }
    }];
    
    // Wait for completion with a timeout
    dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(self.completionTimeout * NSEC_PER_SEC));
    dispatch_semaphore_wait(_completionSemaphore, timeout);
    
    // Log final statistics
    NSLog(@"[MediaRecorder-%@] Recording stopped. Final stats: %@", _recorderId, [self getRecordingStats]);
}

- (NSString*)getRecordFilePath {
    return _filePath;
}

#pragma mark - RTCVideoRenderer

- (void)setSize:(CGSize)size {
    // Not needed for recording
}

- (void)renderFrame:(RTCVideoFrame*)frame {
    if (!_isRecording || !_videoInput.isReadyForMoreMediaData) {
        self.droppedFrameCount++;
        if (self.droppedFrameCount % 100 == 0) {
            NSLog(@"[MediaRecorder-%@] Dropped %ld frames", _recorderId, (long)self.droppedFrameCount);
        }
        return;
    }
    
    // Validate frame
    if (!frame || frame.width == 0 || frame.height == 0) {
        NSLog(@"[MediaRecorder-%@] Warning: Invalid frame received", _recorderId);
        return;
    }
    
    // Log frame info periodically
    self.processedFrameCount++;
    if (self.processedFrameCount % 100 == 0) {
        NSLog(@"[MediaRecorder-%@] Frame stats - Processed: %ld, Dropped: %ld, Size: %.0fx%.0f, Type: %@", 
              _recorderId, 
              (long)self.processedFrameCount,
              (long)self.droppedFrameCount,
              frame.width,
              frame.height,
              [frame.buffer isKindOfClass:[RTCCVPixelBuffer class]] ? @"CVPixelBuffer" : @"I420Buffer");
    }
    
    // Calculate relative timestamp from start of recording
    NSTimeInterval elapsedTime = [[NSDate date] timeIntervalSinceDate:_recordingStartTime];
    CMTime timestamp = CMTimeMakeWithSeconds(elapsedTime, 1000000000);
    
    CVPixelBufferRef pixelBuffer = NULL;
    
    if ([frame.buffer isKindOfClass:[RTCCVPixelBuffer class]]) {
        RTCCVPixelBuffer *rtcPixelBuffer = (RTCCVPixelBuffer *)frame.buffer;
        pixelBuffer = rtcPixelBuffer.pixelBuffer;
        
        // Validate pixel buffer content
        if (pixelBuffer) {
            CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
            void *baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer);
            CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
            
            if (!baseAddress) {
                NSLog(@"[MediaRecorder-%@] Warning: Empty pixel buffer received", _recorderId);
                return;
            }
            
            CVPixelBufferRetain(pixelBuffer);
        }
    } else if ([frame.buffer conformsToProtocol:@protocol(RTCI420Buffer)]) {
        id<RTCI420Buffer> i420Buffer = (id<RTCI420Buffer>)frame.buffer;
        if (!i420Buffer.dataY || !i420Buffer.dataU || !i420Buffer.dataV) {
            NSLog(@"[MediaRecorder-%@] Warning: Invalid I420 buffer received", _recorderId);
            return;
        }
        pixelBuffer = [self pixelBufferFromI420Buffer:i420Buffer];
    }
    
    if (!pixelBuffer) {
        NSLog(@"[MediaRecorder-%@] Warning: Failed to create pixel buffer for frame", _recorderId);
        return;
    }
    
    dispatch_async(_videoQueue, ^{
        if (self.videoInput.isReadyForMoreMediaData) {
            CMSampleBufferRef sampleBuffer = [self sampleBufferFromPixelBuffer:pixelBuffer
                                                                   timestamp:timestamp];
            if (sampleBuffer) {
                [self.videoInput appendSampleBuffer:sampleBuffer];
                CFRelease(sampleBuffer);
            }
        }
        CVPixelBufferRelease(pixelBuffer);
    });
}

- (CVPixelBufferRef)pixelBufferFromI420Buffer:(id<RTCI420Buffer>)i420Buffer {
    CVPixelBufferRef pixelBuffer = NULL;
    int width = i420Buffer.width;
    int height = i420Buffer.height;
    
    // Create pixel buffer
    NSDictionary *pixelAttributes = @{(id)kCVPixelBufferIOSurfacePropertiesKey: @{}};
    CVReturn result = CVPixelBufferCreate(kCFAllocatorDefault,
                                        width,
                                        height,
                                        kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                                        (__bridge CFDictionaryRef)pixelAttributes,
                                        &pixelBuffer);
    
    if (result != kCVReturnSuccess) {
        NSLog(@"[MediaRecorder-%@] Failed to create pixel buffer: %d", _recorderId, result);
        return NULL;
    }
    
    // Lock the pixel buffer
    if (CVPixelBufferLockBaseAddress(pixelBuffer, 0) != kCVReturnSuccess) {
        CFRelease(pixelBuffer);
        return NULL;
    }
    
    // Get the Y plane base address
    uint8_t *dstY = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0);
    const uint8_t *srcY = i420Buffer.dataY;
    const int srcStrideY = i420Buffer.strideY;
    const int dstStrideY = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0);
    
    // Copy Y plane
    for (int row = 0; row < height; row++) {
        memcpy(dstY + row * dstStrideY, srcY + row * srcStrideY, width);
    }
    
    // Get UV plane base address
    uint8_t *dstUV = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1);
    const uint8_t *srcU = i420Buffer.dataU;
    const uint8_t *srcV = i420Buffer.dataV;
    const int srcStrideU = i420Buffer.strideU;
    const int srcStrideV = i420Buffer.strideV;
    const int dstStrideUV = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1);
    
    // Convert U and V planes from planar to semi-planar (interleaved)
    for (int row = 0; row < height / 2; row++) {
        uint8_t *dstRow = dstUV + row * dstStrideUV;
        const uint8_t *srcURow = srcU + row * srcStrideU;
        const uint8_t *srcVRow = srcV + row * srcStrideV;
        
        for (int col = 0; col < width / 2; col++) {
            dstRow[col * 2] = srcURow[col];     // U value
            dstRow[col * 2 + 1] = srcVRow[col]; // V value
        }
    }
    
    // Unlock the pixel buffer
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
    
    return pixelBuffer;
}

- (CMSampleBufferRef)sampleBufferFromPixelBuffer:(CVPixelBufferRef)pixelBuffer
                                      timestamp:(CMTime)timestamp {
    CMSampleBufferRef sampleBuffer = NULL;
    CMVideoFormatDescriptionRef videoInfo = NULL;
    
    CMVideoFormatDescriptionCreateForImageBuffer(NULL, pixelBuffer, &videoInfo);
    
    CMSampleTimingInfo timing = {CMTimeMake(1, 1000), timestamp, timestamp};
    
    CMSampleBufferCreateForImageBuffer(kCFAllocatorDefault,
                                     pixelBuffer,
                                     true,
                                     NULL,
                                     NULL,
                                     videoInfo,
                                     &timing,
                                     &sampleBuffer);
    
    CFRelease(videoInfo);
    return sampleBuffer;
}

#pragma mark - Statistics and Error Handling

- (void)handleRecordingError:(NSError*)error {
    if (self.recordingErrorHandler) {
        self.recordingErrorHandler(error);
    }
    [self stopRecording];
}

- (NSDictionary*)getRecordingStats {
    return @{
        @"processedFrames": @(self.processedFrameCount),
        @"droppedFrames": @(self.droppedFrameCount),
        @"duration": @([[NSDate date] timeIntervalSinceDate:self.recordingStartTime]),
        @"isRecording": @(self.isRecording)
    };
}

- (void)handleMemoryWarning {
    NSLog(@"[MediaRecorder-%@] Received memory warning. Current stats: %@", 
          self.recorderId, [self getRecordingStats]);
}

- (void)dealloc {
    NSLog(@"[MediaRecorder-%@] Deallocating recorder", _recorderId);
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    if (_isRecording) {
        [self stopRecording];
    }
    _completionSemaphore = nil;
}

@end 
