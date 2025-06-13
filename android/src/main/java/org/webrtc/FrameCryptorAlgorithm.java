package org.webrtc;

public enum FrameCryptorAlgorithm {
   AES_GCM,
   AES_CBC;

   // $FF: synthetic method
   private static FrameCryptorAlgorithm[] $values() {
      return new FrameCryptorAlgorithm[]{AES_GCM, AES_CBC};
   }
}
