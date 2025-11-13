#import <Foundation/Foundation.h>
#import <WebRTC/WebRTC.h>

@interface FlutterRtpStreamCapturer : RTCVideoCapturer

- (instancetype)initWithDelegate:(id<RTCVideoCapturerDelegate>)delegate;
- (void)startCaptureWithURL:(NSString*)url options:(NSDictionary*)options;
- (void)stopCapture;
- (void)stopCaptureWithCompletionHandler:(nullable void (^)(void))completionHandler;

@end
