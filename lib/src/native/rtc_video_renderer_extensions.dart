import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:webrtc_interface/webrtc_interface.dart';

/// Extension methods for RTCVideoRenderer to add night vision functionality
extension RTCVideoRendererNightVision on RTCVideoRenderer {
  Future<void> setRemoteNightVision(bool enabled) async {
    // Native implementation for night vision
    if (textureId == null) {
      throw Exception('Cannot set night vision: RTCVideoRenderer not initialized');
    }

    await WebRTC.invokeMethod('videoRendererSetNightVision', <String, dynamic>{
      'textureId': textureId,
      'enabled': enabled,
      'isRemote': true,
    });
  }

  Future<void> setRemoteNightVisionIntensity(double intensity) async {
    // Native implementation for night vision intensity
    if (textureId == null) {
      throw Exception('Cannot set night vision intensity: RTCVideoRenderer not initialized');
    }
    if (intensity < 0.0 || intensity > 1.0) {
      throw Exception('Night vision intensity must be between 0.0 and 1.0');
    }

    await WebRTC.invokeMethod('videoRendererSetNightVisionIntensity', <String, dynamic>{
      'textureId': textureId,
      'intensity': intensity,
      'isRemote': true,
    });
  }
}

/// Extension methods for MediaStreamTrack to add night vision functionality
extension MediaStreamTrackNightVision on MediaStreamTrack {
  Future<void> setNightVision(bool enabled, {String? peerConnectionId}) async {
    // Native implementation for night vision
    if (kind != 'video') {
      throw Exception('Night vision is only available for video tracks');
    }
    await WebRTC.invokeMethod('videoTrackSetNightVision', <String, dynamic>{
      'trackId': id,
      'peerConnectionId': peerConnectionId ?? '',
      'enabled': enabled,
    });
  }

  Future<void> setNightVisionIntensity(double intensity, {String? peerConnectionId}) async {
    // Native implementation for night vision intensity
    if (kind != 'video') {
      throw Exception('Night vision is only available for video tracks');
    }
    if (intensity < 0.0 || intensity > 1.0) {
      throw Exception('Night vision intensity must be between 0.0 and 1.0');
    }
    await WebRTC.invokeMethod('videoTrackSetNightVisionIntensity', <String, dynamic>{
      'trackId': id,
      'peerConnectionId': peerConnectionId ?? '',
      'intensity': intensity,
    });
  }
}
