#import "MediaRecorderImpl.h"

@interface MediaRecorderImpl () <RTCAudioRenderer>

@property (nonatomic, strong) NSNumber *recorderId;
@property (nonatomic, strong) RTCVideoTrack *videoTrack;
@property (nonatomic, strong, readwrite) id<RTCAudioRenderer> audioInterceptor;
@property (nonatomic, strong) AVAssetWriter *assetWriter;
@property (nonatomic, strong) AVAssetWriterInput *videoInput;
@property (nonatomic, strong) AVAssetWriterInput *audioInput;
@property (nonatomic, strong) dispatch_queue_t videoQueue;
@property (nonatomic, strong) dispatch_queue_t audioQueue;
@property (nonatomic, assign, readwrite) BOOL isRecording;
@property (nonatomic, strong, readwrite) NSString *filePath;
@property (nonatomic, strong) dispatch_semaphore_t completionSemaphore;
@property (nonatomic, assign) NSInteger droppedFrameCount;
@property (nonatomic, assign) NSInteger processedFrameCount;
@property (nonatomic, strong) NSDate *recordingStartTime;
@property (nonatomic, assign) NSTimeInterval completionTimeout;
@property (nonatomic, copy) void (^recordingErrorHandler)(NSError *error);
@property (nonatomic, assign) BOOL useVoiceProcessing;
@property (nonatomic, assign) double expectedAudioSampleRate;
@property (nonatomic, assign) int expectedAudioChannels;
@property (nonatomic, assign) float audioGainFactor;

@end

@implementation MediaRecorderImpl

- (instancetype)initWithId:(NSNumber *)recorderId
                 videoTrack:(RTCVideoTrack *)videoTrack
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
        _useVoiceProcessing = YES; // Enable voice processing by default
        
        // Set expected audio format (WebRTC typically uses 48kHz)
        _expectedAudioSampleRate = 48000.0;
        _expectedAudioChannels = 2;
        
        // Initialize audio gain factor for controlled volume reduction
        _audioGainFactor = 0.01f;
        
        [[NSNotificationCenter defaultCenter] addObserver:self
                                                 selector:@selector(handleMemoryWarning)
                                                     name:UIApplicationDidReceiveMemoryWarningNotification
                                                   object:nil];

        NSLog(@"[MediaRecorder-%@] Initialized with videoTrack: %@, audioInterceptor: %@, Audio format: %.0f Hz, %d ch, Audio gain: %.2f",
              recorderId, videoTrack ? @"YES" : @"NO", audioInterceptor ? @"YES" : @"NO", _expectedAudioSampleRate, _expectedAudioChannels, _audioGainFactor);

        if (_videoTrack) {
            [_videoTrack addRenderer:self];
            NSLog(@"[MediaRecorder-%@] Added video track renderer", recorderId);
        }
    }
    return self;
}

- (void)startRecording:(NSString *)filePath
             withWidth:(NSInteger)width
            withHeight:(NSInteger)height
                 error:(NSError **)error {
    NSLog(@"[MediaRecorder-%@] Attempting to start recording to path: %@ with dimensions: %ldx%ld, videoTrack: %@, audioInterceptor: %@",
          _recorderId, filePath, (long)width, (long)height, _videoTrack ? @"YES" : @"NO", _audioInterceptor ? @"YES" : @"NO");
    
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
    // Validate dimensions
    if (width <= 0 || height <= 0) {
        NSLog(@"[MediaRecorder-%@] Error: Invalid dimensions: %ldx%ld", _recorderId, (long)width, (long)height);
        if (error) {
            *error = [NSError errorWithDomain:@"MediaRecorder"
                                         code:8
                                     userInfo:@{NSLocalizedDescriptionKey: @"Invalid dimensions"}];
        }
        return;
    }
    // Ensure directory exists
    NSString *directory = [filePath stringByDeletingLastPathComponent];
    NSFileManager *fileManager = [NSFileManager defaultManager];
    if (![fileManager fileExistsAtPath:directory]) {
        NSError *dirError = nil;
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
        NSError *removeError = nil;
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
    NSURL *fileURL = [NSURL fileURLWithPath:filePath];
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
    // Use provided dimensions for video settings
    NSDictionary *videoSettings = @{
        AVVideoCodecKey : AVVideoCodecTypeH264,
        AVVideoWidthKey : @(width),
        AVVideoHeightKey : @(height),
        AVVideoCompressionPropertiesKey : @{
            AVVideoAverageBitRateKey : @2000000,
            AVVideoMaxKeyFrameIntervalKey : @60,
            AVVideoProfileLevelKey : AVVideoProfileLevelH264HighAutoLevel
        }
    };
    _videoInput = [[AVAssetWriterInput alloc] initWithMediaType:AVMediaTypeVideo
                                                 outputSettings:videoSettings];
    _videoInput.expectsMediaDataInRealTime = YES;
    if ([_assetWriter canAddInput:_videoInput]) {
        [_assetWriter addInput:_videoInput];
        NSLog(@"[MediaRecorder-%@] Added video input to asset writer with dimensions: %ldx%ld",
              _recorderId, (long)width, (long)height);
    } else {
        NSLog(@"[MediaRecorder-%@] Error: Could not add video input to asset writer", _recorderId);
        if (error) {
            *error = [NSError errorWithDomain:@"MediaRecorder"
                                         code:6
                                     userInfo:@{NSLocalizedDescriptionKey: @"Could not add video input"}];
        }
        return;
    }
    // Configure audio settings
    AudioChannelLayout channelLayout;
    memset(&channelLayout, 0, sizeof(AudioChannelLayout));
    channelLayout.mChannelLayoutTag = kAudioChannelLayoutTag_Stereo;
    
    NSDictionary *audioSettings = @{
        AVFormatIDKey: @(kAudioFormatMPEG4AAC),
        AVSampleRateKey: @(self.expectedAudioSampleRate),
        AVNumberOfChannelsKey: @(self.expectedAudioChannels),
        AVChannelLayoutKey: [NSData dataWithBytes:&channelLayout length:sizeof(AudioChannelLayout)],
        AVEncoderBitRateKey: @(128000)
    };
    
    NSLog(@"[MediaRecorder-%@] Configuring audio with settings: %@", _recorderId, audioSettings);
    
    _audioInput = [[AVAssetWriterInput alloc] initWithMediaType:AVMediaTypeAudio
                                                outputSettings:audioSettings];
    _audioInput.expectsMediaDataInRealTime = YES;
    
    if ([_assetWriter canAddInput:_audioInput]) {
        [_assetWriter addInput:_audioInput];
        NSLog(@"[MediaRecorder-%@] Successfully added audio input to asset writer", _recorderId);
    } else {
        NSLog(@"[MediaRecorder-%@] Error: Could not add audio input to asset writer. Error: %@", 
              _recorderId, _assetWriter.error ? _assetWriter.error.localizedDescription : @"Unknown error");
        if (error) {
            *error = [NSError errorWithDomain:@"MediaRecorder"
                                       code:9
                                   userInfo:@{NSLocalizedDescriptionKey: @"Could not add audio input"}];
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
    NSLog(@"[MediaRecorder-%@] Attempting to stop recording. Current state - isRecording: %@, videoTrack: %@, audioInterceptor: %@",
          _recorderId, _isRecording ? @"YES" : @"NO", _videoTrack ? @"YES" : @"NO", _audioInterceptor ? @"YES" : @"NO");
    
    if (!_isRecording) {
        NSLog(@"[MediaRecorder-%@] Warning: Not currently recording", _recorderId);
        return;
    }
    
    _isRecording = NO;
    
    if (_videoTrack) {
        [_videoTrack removeRenderer:self];
        NSLog(@"[MediaRecorder-%@] Removed video track renderer", _recorderId);
    }
    
    if (_videoInput) {
        [_videoInput markAsFinished];
        NSLog(@"[MediaRecorder-%@] Marked video input as finished", _recorderId);
    }
    
    if (_audioInput) {
        [_audioInput markAsFinished];
        NSLog(@"[MediaRecorder-%@] Marked audio input as finished", _recorderId);
    }
    
    __weak MediaRecorderImpl *weakSelf = self;
    [_assetWriter finishWritingWithCompletionHandler:^{
        MediaRecorderImpl *strongSelf = weakSelf;
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

- (NSString *)getRecordFilePath {
    return _filePath;
}

#pragma mark - RTCVideoRenderer

- (void)setSize:(CGSize)size {
    // Not needed for recording
}

- (void)renderFrame:(RTCVideoFrame *)frame {
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
    NSDictionary *pixelAttributes = @{(id)kCVPixelBufferIOSurfacePropertiesKey : @{}};
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

#pragma mark - RTCAudioRenderer

- (void)renderPCMBuffer:(AVAudioPCMBuffer *)pcmBuffer {
    static NSInteger audioBufferCounter = 0;
    static dispatch_once_t onceToken;
    
    // Use a local copy of the gain factor for thread safety
    float currentGainFactor = _audioGainFactor;
    
    audioBufferCounter++;
    
    if (!_isRecording) {
        if (audioBufferCounter % 100 == 0) {
            NSLog(@"[MediaRecorder-%@] Warning: Received audio buffer #%ld but not recording", _recorderId, (long)audioBufferCounter);
        }
        return;
    }
    
    if (!_audioInput) {
        if (audioBufferCounter % 100 == 0) {
            NSLog(@"[MediaRecorder-%@] Error: Audio input is nil. Buffer #%ld dropped", _recorderId, (long)audioBufferCounter);
        }
        return;
    }
    
    if (!_audioInput.isReadyForMoreMediaData) {
        if (audioBufferCounter % 100 == 0) {
            NSLog(@"[MediaRecorder-%@] Warning: Audio input not ready for more data. Buffer #%ld dropped", _recorderId, (long)audioBufferCounter);
        }
        return;
    }
    
    // Log format information for the first audio buffer
    dispatch_once(&onceToken, ^{
        NSLog(@"[MediaRecorder-%@] First audio buffer format: Sample Rate=%.0f Hz, Channels=%d, Frame Length=%d",
              self.recorderId,
              pcmBuffer.format.sampleRate,
              (int)pcmBuffer.format.channelCount,
              (int)pcmBuffer.frameLength);
              
        if (fabs(pcmBuffer.format.sampleRate - self.expectedAudioSampleRate) > 1.0 ||
            pcmBuffer.format.channelCount != self.expectedAudioChannels) {
            NSLog(@"[MediaRecorder-%@] WARNING: Audio format mismatch! Incoming: %.0f Hz, %d ch - Expected: %.0f Hz, %d ch",
                  self.recorderId,
                  pcmBuffer.format.sampleRate,
                  (int)pcmBuffer.format.channelCount,
                  self.expectedAudioSampleRate,
                  self.expectedAudioChannels);
        }
    });
    
    if (audioBufferCounter % 100 == 0) {
        NSLog(@"[MediaRecorder-%@] Processing audio buffer #%ld - Channels: %d, Sample Rate: %.0f Hz, Frame Length: %d, Gain: %.2f", 
              _recorderId, 
              (long)audioBufferCounter,
              (int)pcmBuffer.format.channelCount,
              pcmBuffer.format.sampleRate,
              (int)pcmBuffer.frameLength,
              currentGainFactor);
    }
    
    NSTimeInterval elapsedTime = [[NSDate date] timeIntervalSinceDate:_recordingStartTime];
    CMTime timestamp = CMTimeMakeWithSeconds(elapsedTime, (int32_t)self.expectedAudioSampleRate);
    
    dispatch_async(_audioQueue, ^{
        if (!self.audioInput.isReadyForMoreMediaData) {
            if (audioBufferCounter % 100 == 0) {
                NSLog(@"[MediaRecorder-%@] Warning: Audio input not ready in async queue. Buffer #%ld dropped", self.recorderId, (long)audioBufferCounter);
            }
            return;
        }
        
        // Create audio buffer
        AudioBufferList audioBufferList;
        audioBufferList.mNumberBuffers = 1; // AAC expects interleaved stereo
        audioBufferList.mBuffers[0].mNumberChannels = pcmBuffer.format.channelCount;
        audioBufferList.mBuffers[0].mDataByteSize = pcmBuffer.frameLength * sizeof(float) * pcmBuffer.format.channelCount;
        
        // Allocate temporary buffer for interleaved data
        float *interleavedData = (float *)malloc(audioBufferList.mBuffers[0].mDataByteSize);
        if (!interleavedData) {
            NSLog(@"[MediaRecorder-%@] Error: Failed to allocate memory for audio buffer #%ld", self.recorderId, (long)audioBufferCounter);
            return;
        }
        
        // Process audio samples - applying controlled gain reduction
        for (UInt32 frame = 0; frame < pcmBuffer.frameLength; frame++) {
            for (UInt32 channel = 0; channel < pcmBuffer.format.channelCount; channel++) {
                float *channelData = pcmBuffer.floatChannelData[channel];
                float sample = channelData[frame];
                
                // Apply fixed gain reduction to all samples
                sample *= currentGainFactor;
                
                interleavedData[frame * pcmBuffer.format.channelCount + channel] = sample;
            }
        }
        
        audioBufferList.mBuffers[0].mData = interleavedData;
        
        // Create format description
        AudioStreamBasicDescription asbd = {0};
        asbd.mSampleRate = pcmBuffer.format.sampleRate;
        asbd.mFormatID = kAudioFormatLinearPCM;
        asbd.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
        asbd.mBytesPerPacket = sizeof(float) * pcmBuffer.format.channelCount;
        asbd.mFramesPerPacket = 1;
        asbd.mBytesPerFrame = sizeof(float) * pcmBuffer.format.channelCount;
        asbd.mChannelsPerFrame = pcmBuffer.format.channelCount;
        asbd.mBitsPerChannel = 32;
        
        // Create format description
        CMFormatDescriptionRef format = NULL;
        OSStatus status = CMAudioFormatDescriptionCreate(kCFAllocatorDefault,
                                                      &asbd,
                                                      0,
                                                      NULL,
                                                      0,
                                                      NULL,
                                                      NULL,
                                                      &format);
        
        if (status != noErr) {
            NSLog(@"[MediaRecorder-%@] Error: Failed to create audio format description: %d", self.recorderId, (int)status);
            free(interleavedData);
            return;
        }
        
        // Create a block buffer for the audio data
        CMBlockBufferRef blockBuffer = NULL;
        status = CMBlockBufferCreateWithMemoryBlock(
            kCFAllocatorDefault,
            interleavedData,
            audioBufferList.mBuffers[0].mDataByteSize,
            kCFAllocatorDefault,
            NULL,
            0,
            audioBufferList.mBuffers[0].mDataByteSize,
            0,
            &blockBuffer);
        
        if (status != noErr) {
            NSLog(@"[MediaRecorder-%@] Error: Failed to create block buffer: %d", self.recorderId, (int)status);
            CFRelease(format);
            free(interleavedData);
            return;
        }
        
        // Calculate the correct duration for the sample buffer
        CMTime duration = CMTimeMake(pcmBuffer.frameLength, (int32_t)pcmBuffer.format.sampleRate);
        
        // Create timing info
        CMSampleTimingInfo timing;
        timing.duration = duration;
        timing.presentationTimeStamp = timestamp;
        timing.decodeTimeStamp = kCMTimeInvalid;
        
        // Calculate sample size (bytes per frame)
        size_t sampleSize = pcmBuffer.frameLength * sizeof(float) * pcmBuffer.format.channelCount;
        
        // Create a new audio format description
        CMAudioFormatDescriptionRef audioFormatDescription = NULL;
        status = CMAudioFormatDescriptionCreate(
            kCFAllocatorDefault,
            &asbd,
            0, NULL,
            0, NULL,
            NULL,
            &audioFormatDescription);
        
        if (status != noErr) {
            NSLog(@"[MediaRecorder-%@] Error: Failed to create audio format description: %d", self.recorderId, (int)status);
            CFRelease(blockBuffer);
            free(interleavedData);
            return;
        }
        
        // Create a sample buffer
        CMSampleBufferRef sampleBuffer = NULL;
        status = CMSampleBufferCreate(
            kCFAllocatorDefault,
            blockBuffer,
            true,
            NULL,
            NULL,
            audioFormatDescription,
            1, // numSamples
            1, // numSampleTimingEntries
            &timing,
            1, // numSampleSizeEntries
            &sampleSize,
            &sampleBuffer);
        
        if (status != noErr) {
            NSLog(@"[MediaRecorder-%@] Error: Failed to create sample buffer: %d", self.recorderId, (int)status);
            CFRelease(audioFormatDescription);
            CFRelease(blockBuffer);
            free(interleavedData);
            return;
        }
        
        // Append the sample buffer to the audio input
        if (sampleBuffer) {
            if ([self.audioInput appendSampleBuffer:sampleBuffer]) {
                if (audioBufferCounter % 100 == 0) {
                    NSLog(@"[MediaRecorder-%@] Successfully appended audio buffer #%ld", self.recorderId, (long)audioBufferCounter);
                }
            } else {
                NSLog(@"[MediaRecorder-%@] Failed to append audio buffer #%ld. Writer status: %ld, Error: %@", 
                      self.recorderId, 
                      (long)audioBufferCounter, 
                      (long)self.assetWriter.status, 
                      self.assetWriter.error ? self.assetWriter.error.localizedDescription : @"Unknown");
                
                // Handle recording error if writer is in failed state
                if (self.assetWriter.status == AVAssetWriterStatusFailed) {
                    dispatch_async(dispatch_get_main_queue(), ^{
                        [self handleRecordingError:self.assetWriter.error];
                    });
                }
            }
            CFRelease(sampleBuffer);
        }
        
        if (audioFormatDescription) {
            CFRelease(audioFormatDescription);
        }
        
        if (blockBuffer) {
            CFRelease(blockBuffer);
        }
        
        // Note: We don't need to free interleavedData here because it's now owned by the block buffer
    });
}

#pragma mark - Statistics and Error Handling

- (void)handleRecordingError:(NSError *)error {
    if (self.recordingErrorHandler) {
        self.recordingErrorHandler(error);
    }
    [self stopRecording];
}

- (NSDictionary *)getRecordingStats {
    return @{
        @"processedFrames" : @(self.processedFrameCount),
        @"droppedFrames" : @(self.droppedFrameCount),
        @"duration" : @([[NSDate date] timeIntervalSinceDate:self.recordingStartTime]),
        @"isRecording" : @(self.isRecording)
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

- (void)setAudioGain:(float)gain {
    // Clamp gain between 0.0 and 1.0 (prevent negative gain or excessive amplification)
    _audioGainFactor = fmaxf(0.0f, fminf(gain, 1.0f));
    NSLog(@"[MediaRecorder-%@] Audio gain set to: %.2f", _recorderId, _audioGainFactor);
}

@end