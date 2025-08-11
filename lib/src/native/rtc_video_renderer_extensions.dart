import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:webrtc_interface/webrtc_interface.dart';

/// Extension methods for RTCVideoRenderer to add night vision functionality
extension RTCVideoRendererNightVision on RTCVideoRenderer {
  /// Enable/disable night vision on this renderer.
  ///
  /// Android: `isRemote` is ignored and the GPU drawer path is always used
  /// for any renderer (local preview or remote).
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

  /// Set night vision intensity on this renderer.
  ///
  /// Android: `isRemote` is ignored; updates uniforms only (no drawer reinit).
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
  /// Enable/disable CPU-based night vision on a local video track.
  ///
  /// Android: CPU path is disabled and this call is a no-op with a warning.
  /// Prefer using RTCVideoRendererNightVision methods instead.
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

  /// Set CPU-based night vision intensity on a local video track.
  ///
  /// Android: CPU path is disabled and this call is a no-op with a warning.
  /// Prefer using RTCVideoRendererNightVision methods instead.
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
