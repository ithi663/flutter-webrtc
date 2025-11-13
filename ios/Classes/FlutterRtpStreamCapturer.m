 #include <mach/mach_time.h>
#import <CoreVideo/CoreVideo.h>
#import <WebRTC/RTCCVPixelBuffer.h>
#import <WebRTC/RTCVideoFrame.h>
#import "FlutterRtpStreamCapturer.h"
@protocol FlutterRtpDecoder
- (void)startWithURL:(NSString*)url options:(NSDictionary*)options frameHandler:(void (^)(CVPixelBufferRef pixelBuffer))frameHandler;
- (void)stop;
@end

@interface FlutterSyntheticDecoder : NSObject <FlutterRtpDecoder>
@end

@implementation FlutterSyntheticDecoder {
  dispatch_queue_t _queue;
  dispatch_source_t _timer;
  NSInteger _fps;
  size_t _width;
  size_t _height;
  uint32_t _frameCount;
}

- (instancetype)init {
  self = [super init];
  if (self) {
    _queue = dispatch_queue_create("com.flutterwebrtc.networkcapturer", DISPATCH_QUEUE_SERIAL);
    _timer = nil;
    _fps = 15;
    _width = 640;
    _height = 360;
    _frameCount = 0;
  }
  return self;
}

- (void)startWithURL:(NSString*)url options:(NSDictionary*)options frameHandler:(void (^)(CVPixelBufferRef pixelBuffer))frameHandler {
  NSNumber* w = options[@"width"]; if (w && [w isKindOfClass:[NSNumber class]]) _width = w.unsignedIntegerValue;
  NSNumber* h = options[@"height"]; if (h && [h isKindOfClass:[NSNumber class]]) _height = h.unsignedIntegerValue;
  NSNumber* f = options[@"fps"]; if (f && [f isKindOfClass:[NSNumber class]]) _fps = f.integerValue;

  if (_fps <= 0) _fps = 15;
  if (_width == 0) _width = 640;
  if (_height == 0) _height = 360;

  uint64_t intervalNs = (uint64_t)(NSEC_PER_SEC / _fps);
  _timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, _queue);
  dispatch_source_set_timer(_timer, dispatch_time(DISPATCH_TIME_NOW, 0), intervalNs, intervalNs / 10);
  __weak __typeof__(self) weakSelf = self;
  dispatch_source_set_event_handler(_timer, ^{
    CVPixelBufferRef pixelBuffer = NULL;
    CVReturn status = CVPixelBufferCreate(kCFAllocatorDefault,
                                          weakSelf->_width,
                                          weakSelf->_height,
                                          kCVPixelFormatType_32BGRA,
                                          NULL,
                                          &pixelBuffer);
    if (status != kCVReturnSuccess || pixelBuffer == NULL) {
      return;
    }

    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    uint8_t* base = (uint8_t*)CVPixelBufferGetBaseAddress(pixelBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer);

    uint8_t r = (uint8_t)((weakSelf->_frameCount * 3) % 255);
    uint8_t g = (uint8_t)((weakSelf->_frameCount * 5) % 255);
    uint8_t b = (uint8_t)((weakSelf->_frameCount * 7) % 255);

    for (size_t y = 0; y < weakSelf->_height; ++y) {
      uint8_t* row = base + y * bytesPerRow;
      for (size_t x = 0; x < weakSelf->_width; ++x) {
        size_t idx = x * 4;
        row[idx + 0] = (uint8_t)((b + x) & 0xFF);
        row[idx + 1] = (uint8_t)((g + y) & 0xFF);
        row[idx + 2] = (uint8_t)((r + ((x + y) >> 1)) & 0xFF);
        row[idx + 3] = 0xFF;
      }
    }
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);

    frameHandler(pixelBuffer);
    weakSelf->_frameCount++;
    CVPixelBufferRelease(pixelBuffer);
  });
  dispatch_resume(_timer);
}

- (void)stop {
  if (_timer) {
    dispatch_source_cancel(_timer);
    _timer = nil;
  }
}

@end

@interface FlutterGStreamerDecoder : NSObject <FlutterRtpDecoder>
@end

@implementation FlutterGStreamerDecoder {
  id<FlutterRtpDecoder> _fallback;
}

- (instancetype)init {
  self = [super init];
  if (self) {
    _fallback = [[FlutterSyntheticDecoder alloc] init];
  }
  return self;
}

- (void)startWithURL:(NSString*)url options:(NSDictionary*)options frameHandler:(void (^)(CVPixelBufferRef pixelBuffer))frameHandler {
  [_fallback startWithURL:url options:options frameHandler:frameHandler];
}

- (void)stop {
  [_fallback stop];
}

@end

@implementation FlutterRtpStreamCapturer {
  BOOL _isCapturing;
  mach_timebase_info_data_t _timebaseInfo;
  int64_t _startTimeStampNs;
  id<FlutterRtpDecoder> _decoder;
}

- (instancetype)initWithDelegate:(id<RTCVideoCapturerDelegate>)delegate {
  self = [super initWithDelegate:delegate];
  if (self) {
    _isCapturing = NO;
    _startTimeStampNs = -1;
    _decoder = nil;
    mach_timebase_info(&_timebaseInfo);
  }
  return self;
}

- (void)startCaptureWithURL:(NSString*)url options:(NSDictionary*)options {
  _isCapturing = YES;
  _startTimeStampNs = -1;
  __weak __typeof__(self) weakSelf = self;
  id decoderOption = options[@"decoder"];
  if ([decoderOption isKindOfClass:[NSString class]] &&
      [[(NSString*)decoderOption lowercaseString] isEqualToString:@"gstreamer"]) {
    _decoder = [[FlutterGStreamerDecoder alloc] init];
  } else {
    _decoder = [[FlutterSyntheticDecoder alloc] init];
  }
  [_decoder startWithURL:url options:options frameHandler:^(CVPixelBufferRef pixelBuffer) {
    if (!weakSelf || !weakSelf->_isCapturing) { return; }
    int64_t now = mach_absolute_time();
    int64_t nowNs = now * weakSelf->_timebaseInfo.numer / weakSelf->_timebaseInfo.denom;
    if (weakSelf->_startTimeStampNs < 0) {
      weakSelf->_startTimeStampNs = nowNs;
    }
    int64_t tsNs = nowNs - weakSelf->_startTimeStampNs;
    RTCCVPixelBuffer* rtcPixelBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:pixelBuffer];
    RTCVideoFrame* frame = [[RTCVideoFrame alloc] initWithBuffer:[rtcPixelBuffer toI420]
                                                       rotation:RTCVideoRotation_0
                                                    timeStampNs:tsNs];
    [weakSelf.delegate capturer:weakSelf didCaptureVideoFrame:frame];
  }];
}

- (void)stopCapture {
  _isCapturing = NO;
  if (_decoder) {
    [_decoder stop];
    _decoder = nil;
  }
}

- (void)stopCaptureWithCompletionHandler:(nullable void (^)(void))completionHandler {
  [self stopCapture];
  if (completionHandler != nil) {
    completionHandler();
  }
}

@end
