#import <Foundation/Foundation.h>
#import <WebRTC/RTCAudioRenderer.h>
#import <AVFoundation/AVFoundation.h>

NS_ASSUME_NONNULL_BEGIN

@class MediaRecorderImpl;

@interface AudioRenderer : NSObject <RTCAudioRenderer>

- (instancetype)init;
- (void)renderPCMBuffer:(AVAudioPCMBuffer *)pcmBuffer;

// Methods to set and clear the recorder
- (void)setRecorder:(MediaRecorderImpl *)recorder;
- (void)clearRecorder;

@property (nonatomic, weak) MediaRecorderImpl *recorder;

@end

NS_ASSUME_NONNULL_END 