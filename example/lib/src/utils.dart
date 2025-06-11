import 'package:flutter_webrtc/flutter_webrtc.dart';

// void setPreferredCodec(RTCSessionDescription description,
//     {String audio = 'opus', String video = 'vp8'}) {
//   var capSel = CodecCapabilitySelector(description.sdp!);
//   var acaps = capSel.getCapabilities('audio');
//   if (acaps != null) {
//     acaps.codecs =
//         acaps.codecs.where((e) => (e['codec'] as String).toLowerCase() == audio).toList();
//     acaps.setCodecPreferences('audio', acaps.codecs);
//     capSel.setCapabilities(acaps);
//   }

//   var vcaps = capSel.getCapabilities('video');
//   if (vcaps != null) {
//     vcaps.codecs =
//         vcaps.codecs.where((e) => (e['codec'] as String).toLowerCase() == video).toList();
//     vcaps.setCodecPreferences('video', vcaps.codecs);
//     capSel.setCapabilities(vcaps);
//   }
//   description.sdp = capSel.sdp();
// }

class CodecCapability {
  CodecCapability(this.kind, this.payloads, this.codecs, this.fmtp, this.rtcpFb) {
    codecs.forEach((element) {
      element['orign_payload'] = element['payload'];
    });
  }
  String kind;
  List<dynamic> rtcpFb;
  List<dynamic> fmtp;
  List<String> payloads;
  List<dynamic> codecs;
  bool setCodecPreferences(String kind, List<dynamic>? newCodecs) {
    if (newCodecs == null) {
      return false;
    }
    var newRtcpFb = <dynamic>[];
    var newFmtp = <dynamic>[];
    var newPayloads = <String>[];
    newCodecs.forEach((element) {
      var orign_payload = element['orign_payload'] as int;
      var payload = element['payload'] as int;
      // change payload type
      if (payload != orign_payload) {
        newRtcpFb.addAll(rtcpFb.where((e) {
          if (e['payload'] == orign_payload) {
            e['payload'] = payload;
            return true;
          }
          return false;
        }).toList());
        newFmtp.addAll(fmtp.where((e) {
          if (e['payload'] == orign_payload) {
            e['payload'] = payload;
            return true;
          }
          return false;
        }).toList());
        if (payloads.contains('$orign_payload')) {
          newPayloads.add('$payload');
        }
      } else {
        newRtcpFb.addAll(rtcpFb.where((e) => e['payload'] == payload).toList());
        newFmtp.addAll(fmtp.where((e) => e['payload'] == payload).toList());
        newPayloads.addAll(payloads.where((e) => e == '$payload').toList());
      }
    });
    rtcpFb = newRtcpFb;
    fmtp = newFmtp;
    payloads = newPayloads;
    codecs = newCodecs;
    return true;
  }
}
