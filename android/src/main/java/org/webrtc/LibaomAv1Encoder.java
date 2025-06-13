package org.webrtc;

import java.util.List;

public class LibaomAv1Encoder extends WrappedNativeVideoEncoder {
   public long createNative(long webrtcEnvRef) {
      return nativeCreate(webrtcEnvRef);
   }

   static native long nativeCreate(long var0);

   public boolean isHardwareEncoder() {
      return false;
   }

   static List<String> scalabilityModes() {
      return nativeGetSupportedScalabilityModes();
   }

   static native List<String> nativeGetSupportedScalabilityModes();
}
