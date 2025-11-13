import 'package:webrtc_interface/webrtc_interface.dart';

import 'media_stream_track_impl.dart';
import 'utils.dart';

class NetworkVideoTrack {
  static Future<MediaStreamTrack> create({
    required String url,
    String? username,
    String? password,
    int? width,
    int? height,
    int? fps,
    String? decoder,
    bool? hwAccel,
  }) async {
    final args = <String, dynamic>{'url': url};
    if (username != null) args['username'] = username;
    if (password != null) args['password'] = password;
    if (width != null) args['width'] = width;
    if (height != null) args['height'] = height;
    if (fps != null) args['fps'] = fps;
    if (decoder != null) args['decoder'] = decoder;
    if (hwAccel != null) args['hwAccel'] = hwAccel;

    final response = await WebRTC.invokeMethod('createNetworkVideoTrack', args);
    final trackId = (response as Map)['trackId'] as String;
    return MediaStreamTrackNative(trackId, trackId, 'video', true, '');
  }

  static Future<void> dispose(MediaStreamTrack track) async {
    await WebRTC.invokeMethod('disposeNetworkVideoTrack', <String, dynamic>{
      'trackId': track.id,
    });
  }
}
