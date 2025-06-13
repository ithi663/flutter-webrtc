package org.webrtc;

public class FrameCryptorFactory {
   public static FrameCryptorKeyProvider createFrameCryptorKeyProvider(boolean sharedKey, byte[] ratchetSalt, int ratchetWindowSize, byte[] uncryptedMagicBytes, int failureTolerance, int keyRingSize, boolean discardFrameWhenCryptorNotReady) {
      return nativeCreateFrameCryptorKeyProvider(sharedKey, ratchetSalt, ratchetWindowSize, uncryptedMagicBytes, failureTolerance, keyRingSize, discardFrameWhenCryptorNotReady);
   }

   public static FrameCryptor createFrameCryptorForRtpSender(PeerConnectionFactory factory, RtpSender rtpSender, String participantId, FrameCryptorAlgorithm algorithm, FrameCryptorKeyProvider keyProvider) {
      return nativeCreateFrameCryptorForRtpSender(factory.getNativeOwnedFactoryAndThreads(), rtpSender.getNativeRtpSender(), participantId, algorithm.ordinal(), keyProvider.getNativeKeyProvider());
   }

   public static FrameCryptor createFrameCryptorForRtpReceiver(PeerConnectionFactory factory, RtpReceiver rtpReceiver, String participantId, FrameCryptorAlgorithm algorithm, FrameCryptorKeyProvider keyProvider) {
      return nativeCreateFrameCryptorForRtpReceiver(factory.getNativeOwnedFactoryAndThreads(), rtpReceiver.getNativeRtpReceiver(), participantId, algorithm.ordinal(), keyProvider.getNativeKeyProvider());
   }

   private static native FrameCryptor nativeCreateFrameCryptorForRtpSender(long var0, long var2, String var4, int var5, long var6);

   private static native FrameCryptor nativeCreateFrameCryptorForRtpReceiver(long var0, long var2, String var4, int var5, long var6);

   private static native FrameCryptorKeyProvider nativeCreateFrameCryptorKeyProvider(boolean var0, byte[] var1, int var2, byte[] var3, int var4, int var5, boolean var6);
}
