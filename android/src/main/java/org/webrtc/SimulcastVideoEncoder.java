package org.webrtc;

public class SimulcastVideoEncoder extends WrappedNativeVideoEncoder {
   VideoEncoderFactory primary;
   VideoEncoderFactory fallback;
   VideoCodecInfo info;

   static native long nativeCreateEncoder(long var0, VideoEncoderFactory var2, VideoEncoderFactory var3, VideoCodecInfo var4);

   public SimulcastVideoEncoder(VideoEncoderFactory primary, VideoEncoderFactory fallback, VideoCodecInfo info) {
      this.primary = primary;
      this.fallback = fallback;
      this.info = info;
   }

   public long createNative(long webrtcEnvRef) {
      return nativeCreateEncoder(webrtcEnvRef, this.primary, this.fallback, this.info);
   }

   public boolean isHardwareEncoder() {
      return false;
   }
}
