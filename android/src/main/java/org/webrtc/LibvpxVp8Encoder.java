package org.webrtc;

public class LibvpxVp8Encoder extends WrappedNativeVideoEncoder {
   public long createNative(long webrtcEnvRef) {
      return nativeCreate(webrtcEnvRef);
   }

   static native long nativeCreate(long var0);

   public boolean isHardwareEncoder() {
      return false;
   }
}
