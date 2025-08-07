import 'package:flutter_webrtc/flutter_webrtc.dart';

/// Extension methods for RTCVideoRenderer to add night vision functionality
extension RTCVideoRendererNightVision on RTCVideoRenderer {
  Future<void> setRemoteNightVision(bool enabled) async {
    // Empty implementation for web
  }

  Future<void> setRemoteNightVisionIntensity(double intensity) async {
    // Empty implementation for web
  }
}

/// Extension methods for MediaStreamTrack to add night vision functionality
extension MediaStreamTrackNightVision on MediaStreamTrack {
  Future<void> setNightVision(bool enabled, {String? peerConnectionId}) async {
    // Empty implementation for web
  }

  Future<void> setNightVisionIntensity(double intensity, {String? peerConnectionId}) async {
    // Empty implementation for web
  }
}
