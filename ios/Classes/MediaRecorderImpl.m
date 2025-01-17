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
        
        if (_videoTrack) {
            [_videoTrack addRenderer:self];
        }
    }
    return self;
}

- (void)startRecording:(NSString*)filePath error:(NSError**)error {
    if (_isRecording) {
        if (error) {
            *error = [NSError errorWithDomain:@"MediaRecorder"
                                       code:1
                                   userInfo:@{NSLocalizedDescriptionKey: @"Already recording"}];
        }
        return;
    }
    
    _filePath = filePath;
    NSURL* fileURL = [NSURL fileURLWithPath:filePath];
    
    _assetWriter = [[AVAssetWriter alloc] initWithURL:fileURL
                                            fileType:AVFileTypeMPEG4
                                               error:error];
    if (*error) {
        return;
    }
    
    // Video settings
    NSDictionary* videoSettings = @{
        AVVideoCodecKey: AVVideoCodecTypeH264,
        AVVideoWidthKey: @1280,
        AVVideoHeightKey: @720,
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
    }
    
    // Start writing
    if ([_assetWriter startWriting]) {
        [_assetWriter startSessionAtSourceTime:CMTimeMake(0, 1000)];
        _isRecording = YES;
    } else {
        if (error) {
            *error = _assetWriter.error;
        }
    }
}

- (void)stopRecording {
    if (!_isRecording) {
        return;
    }
    
    _isRecording = NO;
    
    if (_videoTrack) {
        [_videoTrack removeRenderer:self];
    }
    
    [_videoInput markAsFinished];
    
    __weak MediaRecorderImpl* weakSelf = self;
    [_assetWriter finishWritingWithCompletionHandler:^{
        MediaRecorderImpl* strongSelf = weakSelf;
        if (strongSelf) {
            strongSelf.assetWriter = nil;
            strongSelf.videoInput = nil;
            strongSelf.audioInput = nil;
        }
    }];
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
		return;
	}
	
	CMTime timestamp = CMTimeMake(frame.timeStampNs / 1000000, 1000);
	CVPixelBufferRef pixelBuffer = NULL;
	
	if ([frame.buffer isKindOfClass:[RTCCVPixelBuffer class]]) {
		RTCCVPixelBuffer *rtcPixelBuffer = (RTCCVPixelBuffer *)frame.buffer;
		pixelBuffer = rtcPixelBuffer.pixelBuffer;
		CVPixelBufferRetain(pixelBuffer);
	} else if ([frame.buffer conformsToProtocol:@protocol(RTCI420Buffer)]) {
		id<RTCI420Buffer> i420Buffer = (id<RTCI420Buffer>)frame.buffer;
		pixelBuffer = [self pixelBufferFromI420Buffer:i420Buffer];
	}
	
	if (pixelBuffer) {
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
										kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, // NV12 format
										(__bridge CFDictionaryRef)pixelAttributes,
										&pixelBuffer);
	
	if (result != kCVReturnSuccess) {
		NSLog(@"Failed to create pixel buffer: %d", result);
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

- (void)dealloc {
    if (_isRecording) {
        [self stopRecording];
    }
}

@end 
