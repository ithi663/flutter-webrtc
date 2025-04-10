import 'dart:async';

import 'package:webrtc_interface/webrtc_interface.dart';

import '../desktop_capturer.dart';
import 'desktop_capturer_impl.dart';
import 'frame_cryptor_impl.dart';
import 'media_recorder_impl.dart';
import 'media_stream_impl.dart';
import 'mediadevices_impl.dart';
import 'navigator_impl.dart';
import 'rtc_peerconnection_impl.dart';
import 'rtc_video_renderer_impl.dart';
import 'utils.dart';

class RTCFactoryNative extends RTCFactory {
  RTCFactoryNative._internal();

  static final RTCFactory instance = RTCFactoryNative._internal();

  @override
  Future<MediaStream> createLocalMediaStream(String label) async {
    final response = await WebRTC.invokeMethod('createLocalMediaStream');
    if (response == null) {
      throw Exception('createLocalMediaStream return null, something wrong');
    }
    return MediaStreamNative(response['streamId'], label);
  }

  @override
  Future<RTCPeerConnection> createPeerConnection(
      Map<String, dynamic> configuration,
      [Map<String, dynamic> constraints = const {}]) async {
    var defaultConstraints = <String, dynamic>{
      'mandatory': {},
      'optional': [
        {'DtlsSrtpKeyAgreement': true},
      ],
    };

    final response = await WebRTC.invokeMethod(
      'createPeerConnection',
      <String, dynamic>{
        'configuration': configuration,
        'constraints': constraints.isEmpty ? defaultConstraints : constraints
      },
    );

    String peerConnectionId = response['peerConnectionId'];
    return RTCPeerConnectionNative(peerConnectionId, configuration);
  }

  @override
  MediaRecorder mediaRecorder() {
    return MediaRecorderNative();
  }

  @override
  VideoRenderer videoRenderer() {
    return RTCVideoRenderer();
  }

  @override
  Navigator get navigator => NavigatorNative.instance;

  @override
  FrameCryptorFactory get frameCryptorFactory =>
      FrameCryptorFactoryImpl.instance;

  @override
  Future<RTCRtpCapabilities> getRtpReceiverCapabilities(String kind) async {
    final response = await WebRTC.invokeMethod(
      'getRtpReceiverCapabilities',
      <String, dynamic>{
        'kind': kind,
      },
    );
    return RTCRtpCapabilities.fromMap(response);
  }

  @override
  Future<RTCRtpCapabilities> getRtpSenderCapabilities(String kind) async {
    final response = await WebRTC.invokeMethod(
      'getRtpSenderCapabilities',
      <String, dynamic>{
        'kind': kind,
      },
    );
    return RTCRtpCapabilities.fromMap(response);
  }

  /// Disposes of all WebRTC resources
  ///
  /// This method can be used to clean up all WebRTC resources when
  /// the application is being closed or when WebRTC functionality
  /// is no longer needed.
  Future<void> disposeAll() async {
    await WebRTC.invokeMethod('disposeAll');
  }

  /// Disposes only of the getUserMedia resources
  ///
  /// This method is useful when you want to release camera, microphone, and
  /// other media device resources without destroying peer connections or
  /// other WebRTC components.
  Future<void> disposeGetUserMedia() async {
    await WebRTC.invokeMethod('disposeGetUserMedia');
  }

  /// Stops all WebRTC media resources but keeps the WebRTC infrastructure intact
  ///
  /// This method will:
  /// - Stop all media tracks (audio/video)
  /// - Release camera resources
  /// - Stop all recordings
  /// - Close all streams
  /// But it maintains the PeerConnection factory and core infrastructure
  /// so you can easily start playing or watching new streams without reinitializing everything.
  Future<void> stopAll() async {
    await WebRTC.invokeMethod('stopAll');
  }
}

Future<RTCPeerConnection> createPeerConnection(
    Map<String, dynamic> configuration,
    [Map<String, dynamic> constraints = const {}]) async {
  return RTCFactoryNative.instance
      .createPeerConnection(configuration, constraints);
}

Future<MediaStream> createLocalMediaStream(String label) async {
  return RTCFactoryNative.instance.createLocalMediaStream(label);
}

Future<RTCRtpCapabilities> getRtpReceiverCapabilities(String kind) async {
  return RTCFactoryNative.instance.getRtpReceiverCapabilities(kind);
}

Future<RTCRtpCapabilities> getRtpSenderCapabilities(String kind) async {
  return RTCFactoryNative.instance.getRtpSenderCapabilities(kind);
}

MediaRecorder mediaRecorder() {
  return RTCFactoryNative.instance.mediaRecorder();
}

Navigator get navigator => RTCFactoryNative.instance.navigator;

DesktopCapturer get desktopCapturer => DesktopCapturerNative.instance;

MediaDevices get mediaDevices => MediaDeviceNative.instance;

FrameCryptorFactory get frameCryptorFactory => FrameCryptorFactoryImpl.instance;

/// Disposes of all WebRTC resources
///
/// This method can be used to clean up all WebRTC resources when
/// the application is being closed or when WebRTC functionality
/// is no longer needed.
Future<void> disposeAll() async {
  await WebRTC.invokeMethod('disposeAll');
}

/// Disposes only of the getUserMedia resources
///
/// This method is useful when you want to release camera, microphone, and
/// other media device resources without destroying peer connections or
/// other WebRTC components.
Future<void> disposeGetUserMedia() async {
  await WebRTC.invokeMethod('disposeGetUserMedia');
}

/// Stops all WebRTC media resources but keeps the WebRTC infrastructure intact
///
/// This method will:
/// - Stop all media tracks (audio/video)
/// - Release camera resources
/// - Stop all recordings
/// - Close all streams
/// But it maintains the PeerConnection factory and core infrastructure
/// so you can easily start playing or watching new streams without reinitializing everything.
Future<void> stopAll() async {
  await WebRTC.invokeMethod('stopAll');
}
