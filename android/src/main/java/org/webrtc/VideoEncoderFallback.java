package org.webrtc;

public class VideoEncoderFallback extends WrappedNativeVideoEncoder {
   private final VideoEncoder fallback;
   private final VideoEncoder primary;

   public VideoEncoderFallback(VideoEncoder fallback, VideoEncoder primary) {
      this.fallback = fallback;
      this.primary = primary;
   }

   public long createNative(long webrtcEnvRef) {
      return nativeCreate(webrtcEnvRef, this.fallback, this.primary);
   }

   public boolean isHardwareEncoder() {
      return this.primary.isHardwareEncoder();
   }

   private static native long nativeCreate(long var0, VideoEncoder var2, VideoEncoder var3);
}
