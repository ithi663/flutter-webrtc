import 'dart:async';
import 'dart:math';

import 'package:webrtc_interface/webrtc_interface.dart';

import 'media_stream_track_impl.dart';
import 'utils.dart';

class MediaRecorderNative extends MediaRecorder {
  static final _random = Random();
  final _recorderId = _random.nextInt(0x7FFFFFFF);

  @override
  Future<void> start(String path,
      {MediaStreamTrack? videoTrack,
      RecorderAudioChannel? audioChannel,
      int? width,
      int? height
      // TODO(cloudwebrtc): add codec/quality options
      }) async {
    if (audioChannel == null && videoTrack == null) {
      throw Exception('Neither audio nor video track were provided');
    }

    await WebRTC.invokeMethod('startRecordToFile', {
      'path': path,
      if (audioChannel != null) 'audioChannel': audioChannel.index,
      if (videoTrack != null) 'videoTrackId': videoTrack.id,
      if (width != null) 'width': width,
      if (height != null) 'height': height,
      'recorderId': _recorderId,
      'peerConnectionId': videoTrack is MediaStreamTrackNative
          ? videoTrack.peerConnectionId
          : null
    });
  }

  @override
  void startWeb(MediaStream stream,
      {Function(dynamic blob, bool isLastOne)? onDataChunk,
      String? mimeType,
      int timeSlice = 1000}) {
    throw 'It\'s for Flutter Web only';
  }

  @override
  Future<dynamic> stop() async => await WebRTC.invokeMethod(
      'stopRecordToFile', {'recorderId': _recorderId});
}
