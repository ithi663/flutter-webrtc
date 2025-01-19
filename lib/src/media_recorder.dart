import 'package:webrtc_interface/webrtc_interface.dart' as rtc;

import '../flutter_webrtc.dart';
import 'native/media_recorder_impl.dart';

class MediaRecorder extends rtc.MediaRecorder {
  MediaRecorder() : _delegate = mediaRecorder();
  final rtc.MediaRecorder _delegate;

  @override
  Future<void> start(String path,
          {MediaStreamTrack? videoTrack, RecorderAudioChannel? audioChannel}) =>
      startWithDimensions(path,
          videoTrack: videoTrack, audioChannel: audioChannel);

  /// Start recording with optional width and height parameters.
  /// If width and height are not provided, the recorder will use the default dimensions.
  Future<void> startWithDimensions(String path,
          {MediaStreamTrack? videoTrack,
          RecorderAudioChannel? audioChannel,
          int? width,
          int? height}) =>
      (_delegate as MediaRecorderNative).start(path,
          videoTrack: videoTrack,
          audioChannel: audioChannel,
          width: width,
          height: height);

  @override
  Future stop() => _delegate.stop();

  @override
  void startWeb(
    MediaStream stream, {
    Function(dynamic blob, bool isLastOne)? onDataChunk,
    String? mimeType,
    int timeSlice = 1000,
  }) =>
      _delegate.startWeb(
        stream,
        onDataChunk: onDataChunk,
        mimeType: mimeType ?? 'video/webm',
        timeSlice: timeSlice,
      );
}
