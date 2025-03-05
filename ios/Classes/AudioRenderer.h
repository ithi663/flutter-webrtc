#import <Foundation/Foundation.h>
#import <WebRTC/RTCAudioRenderer.h>
#import <AVFoundation/AVFoundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface AudioRenderer : NSObject <RTCAudioRenderer>

- (instancetype)init;
- (void)renderPCMBuffer:(AVAudioPCMBuffer *)pcmBuffer;

@end

NS_ASSUME_NONNULL_END 