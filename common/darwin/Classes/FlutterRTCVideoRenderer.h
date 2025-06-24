#import "FlutterWebRTCPlugin.h"

#import <WebRTC/RTCMediaStream.h>
#import <WebRTC/RTCVideoFrame.h>
#import <WebRTC/RTCVideoRenderer.h>
#import <WebRTC/RTCVideoTrack.h>
#import "NightVisionProcessor.h"

@interface FlutterRTCVideoRenderer
    : NSObject <FlutterTexture, RTCVideoRenderer, FlutterStreamHandler>

/**
 * The {@link RTCVideoTrack}, if any, which this instance renders.
 */
@property(nonatomic, strong) RTCVideoTrack* videoTrack;
@property(nonatomic) int64_t textureId;
@property(nonatomic, weak) id<FlutterTextureRegistry> registry;
@property(nonatomic, strong) FlutterEventSink eventSink;

// Night vision support for remote streams
@property(nonatomic, strong) NightVisionProcessor* _Nullable nightVisionProcessor;
@property(nonatomic, assign) BOOL remoteNightVisionEnabled;

- (instancetype)initWithTextureRegistry:(id<FlutterTextureRegistry>)registry
                              messenger:(NSObject<FlutterBinaryMessenger>*)messenger;

- (void)dispose;

@end

@interface FlutterWebRTCPlugin (FlutterVideoRendererManager)

- (FlutterRTCVideoRenderer*)createWithTextureRegistry:(id<FlutterTextureRegistry>)registry
                                            messenger:(NSObject<FlutterBinaryMessenger>*)messenger;

- (void)rendererSetSrcObject:(FlutterRTCVideoRenderer*)renderer stream:(RTCVideoTrack*)videoTrack;

@end
