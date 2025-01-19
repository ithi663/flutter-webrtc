#ifndef MediaRecorderImpl_h
#define MediaRecorderImpl_h

#import <Foundation/Foundation.h>
#import <WebRTC/WebRTC.h>
#import <AVFoundation/AVFoundation.h>

@protocol RTCAudioRenderer;

NS_ASSUME_NONNULL_BEGIN

@interface MediaRecorderImpl : NSObject <RTCVideoRenderer>

@property(nonatomic, readonly) NSString* filePath;
@property(nonatomic, readonly) BOOL isRecording;

- (instancetype)initWithId:(NSNumber*)recorderId
                videoTrack:(RTCVideoTrack* _Nullable)videoTrack
           audioInterceptor:(id<RTCAudioRenderer> _Nullable)audioInterceptor;

- (void)startRecording:(NSString*)filePath
            withWidth:(NSInteger)width
           withHeight:(NSInteger)height
               error:(NSError**)error;
- (void)stopRecording;
- (NSString*)getRecordFilePath;

@end

NS_ASSUME_NONNULL_END

#endif /* MediaRecorderImpl_h */ 