import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'utils.dart';

/// Extension methods for RTCVideoRenderer to add night vision functionality
extension RTCVideoRendererNightVision on RTCVideoRenderer {
  /// Enable or disable night vision processing for remote streams
  /// This allows viewers to enhance incoming video without affecting the sender
  Future<void> setRemoteNightVision(bool enabled) async {
    if (textureId == null) {
      throw Exception('Cannot set night vision: RTCVideoRenderer not initialized');
    }

    await WebRTC.invokeMethod('videoRendererSetNightVision', <String, dynamic>{
      'textureId': textureId,
      'enabled': enabled,
      'isRemote': true,
    });
  }

  /// Set the intensity of night vision processing for remote streams (0.0 - 1.0)
  /// Higher values provide more enhancement but may introduce artifacts
  Future<void> setRemoteNightVisionIntensity(double intensity) async {
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
  /// Enable or disable night vision processing for this video track
  /// Only works for local video tracks
  Future<void> setNightVision(bool enabled, {String? peerConnectionId}) async {
    if (kind != 'video') {
      throw Exception('Night vision is only available for video tracks');
    }

    await WebRTC.invokeMethod('videoTrackSetNightVision', <String, dynamic>{
      'trackId': id,
      'enabled': enabled,
      'peerConnectionId': peerConnectionId ?? '',
    });
  }

  /// Set the intensity of night vision processing (0.0 - 1.0)
  /// Higher values provide more enhancement but may introduce artifacts
  Future<void> setNightVisionIntensity(double intensity, {String? peerConnectionId}) async {
    if (kind != 'video') {
      throw Exception('Night vision is only available for video tracks');
    }
    if (intensity < 0.0 || intensity > 1.0) {
      throw Exception('Night vision intensity must be between 0.0 and 1.0');
    }

    await WebRTC.invokeMethod('videoTrackSetNightVisionIntensity', <String, dynamic>{
      'trackId': id,
      'intensity': intensity,
      'peerConnectionId': peerConnectionId ?? '',
    });
  }
}
