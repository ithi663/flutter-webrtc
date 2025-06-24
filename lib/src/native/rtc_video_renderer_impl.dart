import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'package:webrtc_interface/webrtc_interface.dart';

import '../helper.dart';
import 'utils.dart';

class RTCVideoRenderer extends ValueNotifier<RTCVideoValue> implements VideoRenderer {
  RTCVideoRenderer() : super(RTCVideoValue.empty);
  Completer? _initializing;
  int? _textureId;
  bool _disposed = false;
  MediaStream? _srcObject;
  StreamSubscription<dynamic>? _eventSubscription;

  @override
  Future<void> initialize() async {
    if (_initializing != null) {
      await _initializing!.future;
      return;
    }
    _initializing = Completer();
    final response = await WebRTC.invokeMethod('createVideoRenderer', {});
    _textureId = response['textureId'];
    _eventSubscription = EventChannel(
      'FlutterWebRTC/Texture$textureId',
    ).receiveBroadcastStream().listen(eventListener, onError: errorListener);
    _initializing!.complete(null);
  }

  @override
  int get videoWidth => value.width.toInt();

  @override
  int get videoHeight => value.height.toInt();

  @override
  RTCVideoValue get videoValue => value;

  @override
  int? get textureId => _textureId;

  @override
  MediaStream? get srcObject => _srcObject;

  @override
  Function? onResize;

  @override
  Function? onFirstFrameRendered;

  @override
  set srcObject(MediaStream? stream) {
    if (_disposed) {
      throw 'Can\'t set srcObject: The RTCVideoRenderer is disposed';
    }
    if (textureId == null) throw 'Call initialize before setting the stream';
    _srcObject = stream;
    WebRTC.invokeMethod('videoRendererSetSrcObject', <String, dynamic>{
          'textureId': textureId,
          'streamId': stream?.id ?? '',
          'ownerTag': stream?.ownerTag ?? '',
        })
        .then((_) {
          value = (stream == null) ? RTCVideoValue.empty : value.copyWith(renderVideo: renderVideo);
        })
        .catchError((e) {
          print('Got exception for RTCVideoRenderer::setSrcObject: ${e.message}');
        }, test: (e) => e is PlatformException);
  }

  Future<void> setSrcObject({MediaStream? stream, String? trackId}) async {
    if (_disposed) {
      throw 'Can\'t set srcObject: The RTCVideoRenderer is disposed';
    }
    if (_textureId == null) throw 'Call initialize before setting the stream';
    _srcObject = stream;
    var oldTextureId = _textureId;
    try {
      await WebRTC.invokeMethod('videoRendererSetSrcObject', <String, dynamic>{
        'textureId': _textureId,
        'streamId': stream?.id ?? '',
        'ownerTag': stream?.ownerTag ?? '',
        'trackId': trackId ?? '0',
      });
      value = (stream == null) ? RTCVideoValue.empty : value.copyWith(renderVideo: renderVideo);
    } on PlatformException catch (e) {
      throw 'Got exception for RTCVideoRenderer::setSrcObject: textureId $oldTextureId [disposed: $_disposed] with stream ${stream?.id}, error: ${e.message}';
    }
  }

  @override
  Future<void> dispose() async {
    if (_disposed) return;
    await _eventSubscription?.cancel();
    _eventSubscription = null;
    if (_textureId != null) {
      try {
        await WebRTC.invokeMethod('videoRendererDispose', <String, dynamic>{
          'textureId': _textureId,
        });
        _textureId = null;
        _disposed = true;
      } on PlatformException catch (e) {
        throw 'Failed to RTCVideoRenderer::dispose: ${e.message}';
      }
    }

    return super.dispose();
  }

  void eventListener(dynamic event) {
    if (_disposed) return;
    final Map<dynamic, dynamic> map = event;
    switch (map['event']) {
      case 'didTextureChangeRotation':
        value = value.copyWith(rotation: map['rotation'], renderVideo: renderVideo);
        onResize?.call();
        break;
      case 'didTextureChangeVideoSize':
        value = value.copyWith(
          width: 0.0 + map['width'],
          height: 0.0 + map['height'],
          renderVideo: renderVideo,
        );
        onResize?.call();
        break;
      case 'didFirstFrameRendered':
        value = value.copyWith(renderVideo: renderVideo);
        onFirstFrameRendered?.call();
        break;
    }
  }

  void errorListener(Object obj) {
    if (obj is Exception) {
      throw obj;
    }
  }

  @override
  bool get renderVideo => _textureId != null && _srcObject != null;

  @override
  bool get muted => _srcObject?.getAudioTracks()[0].muted ?? true;

  @override
  set muted(bool mute) {
    if (_disposed) {
      throw Exception('Can\'t be muted: The RTCVideoRenderer is disposed');
    }
    if (_srcObject == null) {
      throw Exception('Can\'t be muted: The MediaStream is null');
    }
    if (_srcObject!.ownerTag != 'local') {
      throw Exception('You\'re trying to mute a remote track, this is not supported');
    }
    if (_srcObject!.getAudioTracks().isEmpty) {
      throw Exception('Can\'t be muted: The MediaStreamTrack(audio) is empty');
    }

    Helper.setMicrophoneMute(mute, _srcObject!.getAudioTracks()[0]);
  }

  @override
  Future<bool> audioOutput(String deviceId) async {
    try {
      await Helper.selectAudioOutput(deviceId);
    } catch (e) {
      print('Helper.selectAudioOutput ${e.toString()}');
      return false;
    }
    return true;
  }

  /// Enable or disable night vision processing for remote streams
  /// This allows viewers to enhance incoming video without affecting the sender
  Future<void> setRemoteNightVision(bool enabled) async {
    if (_disposed) {
      throw Exception('Cannot set night vision: RTCVideoRenderer is disposed');
    }
    if (_textureId == null) {
      throw Exception('Cannot set night vision: RTCVideoRenderer not initialized');
    }

    await WebRTC.invokeMethod('videoRendererSetNightVision', <String, dynamic>{
      'textureId': _textureId,
      'enabled': enabled,
      'isRemote': true,
    });
  }

  /// Set the intensity of night vision processing for remote streams (0.0 - 1.0)
  /// Higher values provide more enhancement but may introduce artifacts
  Future<void> setRemoteNightVisionIntensity(double intensity) async {
    if (_disposed) {
      throw Exception('Cannot set night vision intensity: RTCVideoRenderer is disposed');
    }
    if (_textureId == null) {
      throw Exception('Cannot set night vision intensity: RTCVideoRenderer not initialized');
    }
    if (intensity < 0.0 || intensity > 1.0) {
      throw Exception('Night vision intensity must be between 0.0 and 1.0');
    }

    await WebRTC.invokeMethod('videoRendererSetNightVisionIntensity', <String, dynamic>{
      'textureId': _textureId,
      'intensity': intensity,
      'isRemote': true,
    });
  }
}
