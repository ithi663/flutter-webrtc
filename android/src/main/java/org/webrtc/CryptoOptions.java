package org.webrtc;

public final class CryptoOptions {
   private final CryptoOptions.Srtp srtp;
   private final CryptoOptions.SFrame sframe;

   private CryptoOptions(boolean enableGcmCryptoSuites, boolean enableAes128Sha1_32CryptoCipher, boolean enableEncryptedRtpHeaderExtensions, boolean requireFrameEncryption) {
      this.srtp = new CryptoOptions.Srtp(enableGcmCryptoSuites, enableAes128Sha1_32CryptoCipher, enableEncryptedRtpHeaderExtensions);
      this.sframe = new CryptoOptions.SFrame(requireFrameEncryption);
   }

   public static CryptoOptions.Builder builder() {
      return new CryptoOptions.Builder();
   }

   @CalledByNative
   public CryptoOptions.Srtp getSrtp() {
      return this.srtp;
   }

   @CalledByNative
   public CryptoOptions.SFrame getSFrame() {
      return this.sframe;
   }

   public final class Srtp {
      private final boolean enableGcmCryptoSuites;
      private final boolean enableAes128Sha1_32CryptoCipher;
      private final boolean enableEncryptedRtpHeaderExtensions;

      private Srtp(boolean enableGcmCryptoSuites, boolean enableAes128Sha1_32CryptoCipher, boolean enableEncryptedRtpHeaderExtensions) {
         this.enableGcmCryptoSuites = enableGcmCryptoSuites;
         this.enableAes128Sha1_32CryptoCipher = enableAes128Sha1_32CryptoCipher;
         this.enableEncryptedRtpHeaderExtensions = enableEncryptedRtpHeaderExtensions;
      }

      @CalledByNative("Srtp")
      public boolean getEnableGcmCryptoSuites() {
         return this.enableGcmCryptoSuites;
      }

      @CalledByNative("Srtp")
      public boolean getEnableAes128Sha1_32CryptoCipher() {
         return this.enableAes128Sha1_32CryptoCipher;
      }

      @CalledByNative("Srtp")
      public boolean getEnableEncryptedRtpHeaderExtensions() {
         return this.enableEncryptedRtpHeaderExtensions;
      }
   }

   public final class SFrame {
      private final boolean requireFrameEncryption;

      private SFrame(boolean requireFrameEncryption) {
         this.requireFrameEncryption = requireFrameEncryption;
      }

      @CalledByNative("SFrame")
      public boolean getRequireFrameEncryption() {
         return this.requireFrameEncryption;
      }
   }

   public static class Builder {
      private boolean enableGcmCryptoSuites;
      private boolean enableAes128Sha1_32CryptoCipher;
      private boolean enableEncryptedRtpHeaderExtensions;
      private boolean requireFrameEncryption;

      private Builder() {
      }

      public CryptoOptions.Builder setEnableGcmCryptoSuites(boolean enableGcmCryptoSuites) {
         this.enableGcmCryptoSuites = enableGcmCryptoSuites;
         return this;
      }

      public CryptoOptions.Builder setEnableAes128Sha1_32CryptoCipher(boolean enableAes128Sha1_32CryptoCipher) {
         this.enableAes128Sha1_32CryptoCipher = enableAes128Sha1_32CryptoCipher;
         return this;
      }

      public CryptoOptions.Builder setEnableEncryptedRtpHeaderExtensions(boolean enableEncryptedRtpHeaderExtensions) {
         this.enableEncryptedRtpHeaderExtensions = enableEncryptedRtpHeaderExtensions;
         return this;
      }

      public CryptoOptions.Builder setRequireFrameEncryption(boolean requireFrameEncryption) {
         this.requireFrameEncryption = requireFrameEncryption;
         return this;
      }

      public CryptoOptions createCryptoOptions() {
         return new CryptoOptions(this.enableGcmCryptoSuites, this.enableAes128Sha1_32CryptoCipher, this.enableEncryptedRtpHeaderExtensions, this.requireFrameEncryption);
      }
   }
}
