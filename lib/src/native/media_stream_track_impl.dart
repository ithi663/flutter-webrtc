import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:path_provider/path_provider.dart';
import 'package:webrtc_interface/webrtc_interface.dart';

import '../helper.dart';
import 'utils.dart';

class MediaStreamTrackNative extends MediaStreamTrack {
  MediaStreamTrackNative(
    this._trackId,
    this._label,
    this._kind,
    this._enabled,
    this._peerConnectionId, [
    this.settings_ = const {},
  ]);

  factory MediaStreamTrackNative.fromMap(Map<dynamic, dynamic> map, String peerConnectionId) {
    return MediaStreamTrackNative(
      map['id'],
      map['label'],
      map['kind'],
      map['enabled'],
      peerConnectionId,
      map['settings'] ?? {},
    );
  }
  final String _trackId;
  final String _label;
  final String _kind;
  final String _peerConnectionId;
  final Map<Object?, Object?> settings_;

  bool _enabled;

  bool _muted = false;

  String get peerConnectionId => _peerConnectionId;

  @override
  set enabled(bool enabled) {
    WebRTC.invokeMethod('mediaStreamTrackSetEnable', <String, dynamic>{
      'trackId': _trackId,
      'enabled': enabled,
      'peerConnectionId': _peerConnectionId,
    });
    _enabled = enabled;

    if (kind == 'audio') {
      _muted = !enabled;
      muted ? onMute?.call() : onUnMute?.call();
    }
  }

  @override
  bool get enabled => _enabled;

  @override
  String get label => _label;

  @override
  String get kind => _kind;

  @override
  String get id => _trackId;

  @override
  bool get muted => _muted;

  @override
  Future<bool> hasTorch() => WebRTC.invokeMethod('mediaStreamTrackHasTorch', <String, dynamic>{
    'trackId': _trackId,
  }).then((value) => value ?? false);

  @override
  Future<void> setTorch(bool torch) => WebRTC.invokeMethod(
    'mediaStreamTrackSetTorch',
    <String, dynamic>{'trackId': _trackId, 'torch': torch},
  );

  @override
  Future<bool> switchCamera() => Helper.switchCamera(this);

  Future<void> setZoom(double zoomLevel) => Helper.setZoom(this, zoomLevel);

  @Deprecated('Use Helper.setSpeakerphoneOn instead')
  @override
  void enableSpeakerphone(bool enable) async {
    return Helper.setSpeakerphoneOn(enable);
  }

  @override
  Future<ByteBuffer> captureFrame() async {
    var filePath = await getTemporaryDirectory();
    await WebRTC.invokeMethod('captureFrame', <String, dynamic>{
      'trackId': _trackId,
      'peerConnectionId': _peerConnectionId,
      'path': '${filePath.path}/captureFrame.png',
    });
    return File('${filePath.path}/captureFrame.png').readAsBytes().then((value) => value.buffer);
  }

  @override
  Future<void> captureFrameToFile(String path) async {
    await WebRTC.invokeMethod('captureFrame', <String, dynamic>{
      'trackId': _trackId,
      'peerConnectionId': _peerConnectionId,
      'path': path,
    });
  }

  @override
  Future<void> applyConstraints([Map<String, dynamic>? constraints]) {
    if (constraints == null) return Future.value();

    var current = getConstraints();
    if (constraints.containsKey('volume') && current['volume'] != constraints['volume']) {
      Helper.setVolume(constraints['volume'], this);
    }

    return Future.value();
  }

  @override
  Map<String, dynamic> getSettings() {
    return settings_.map((key, value) => MapEntry(key.toString(), value));
  }

  @override
  Future<void> dispose() async {
    return stop();
  }

  @override
  Future<void> stop() async {
    await WebRTC.invokeMethod('trackDispose', <String, dynamic>{'trackId': _trackId});
  }

  /// Enable or disable night vision processing for this video track
  /// Only works for local video tracks
  Future<void> setNightVision(bool enabled) async {
    if (_kind != 'video') {
      throw Exception('Night vision is only available for video tracks');
    }
    await WebRTC.invokeMethod('videoTrackSetNightVision', <String, dynamic>{
      'trackId': _trackId,
      'enabled': enabled,
      'peerConnectionId': _peerConnectionId,
    });
  }

  /// Set the intensity of night vision processing (0.0 - 1.0)
  /// Higher values provide more enhancement but may introduce artifacts
  Future<void> setNightVisionIntensity(double intensity) async {
    if (_kind != 'video') {
      throw Exception('Night vision is only available for video tracks');
    }
    if (intensity < 0.0 || intensity > 1.0) {
      throw Exception('Night vision intensity must be between 0.0 and 1.0');
    }
    await WebRTC.invokeMethod('videoTrackSetNightVisionIntensity', <String, dynamic>{
      'trackId': _trackId,
      'intensity': intensity,
      'peerConnectionId': _peerConnectionId,
    });
  }
}
