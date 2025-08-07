package org.webrtc;

import androidx.annotation.Nullable;

public class FrameCryptor {
   private long nativeFrameCryptor;
   private long observerPtr;

   public long getNativeFrameCryptor() {
      return this.nativeFrameCryptor;
   }

   @CalledByNative
   public FrameCryptor(long nativeFrameCryptor) {
      this.nativeFrameCryptor = nativeFrameCryptor;
      this.observerPtr = 0L;
   }

   public void setEnabled(boolean enabled) {
      this.checkFrameCryptorExists();
      nativeSetEnabled(this.nativeFrameCryptor, enabled);
   }

   public boolean isEnabled() {
      this.checkFrameCryptorExists();
      return nativeIsEnabled(this.nativeFrameCryptor);
   }

   public int getKeyIndex() {
      this.checkFrameCryptorExists();
      return nativeGetKeyIndex(this.nativeFrameCryptor);
   }

   public void setKeyIndex(int index) {
      this.checkFrameCryptorExists();
      nativeSetKeyIndex(this.nativeFrameCryptor, index);
   }

   public void dispose() {
      this.checkFrameCryptorExists();
      nativeUnSetObserver(this.nativeFrameCryptor);
      JniCommon.nativeReleaseRef(this.nativeFrameCryptor);
      this.nativeFrameCryptor = 0L;
      if (this.observerPtr != 0L) {
         JniCommon.nativeReleaseRef(this.observerPtr);
         this.observerPtr = 0L;
      }

   }

   public void setObserver(@Nullable FrameCryptor.Observer observer) {
      this.checkFrameCryptorExists();
      // First, release the old observer peer if it exists.
      if (this.observerPtr != 0L) {
         nativeUnSetObserver(this.nativeFrameCryptor);
         JniCommon.nativeReleaseRef(this.observerPtr);
         this.observerPtr = 0L;
      }
      // Then, set the new one and store its native peer handle.
      if (observer != null) {
         this.observerPtr = nativeSetObserver(this.nativeFrameCryptor, observer);
      }
   }

   private void checkFrameCryptorExists() {
      if (this.nativeFrameCryptor == 0L) {
         throw new IllegalStateException("FrameCryptor has been disposed.");
      }
   }

   private static native void nativeSetEnabled(long var0, boolean var2);

   private static native boolean nativeIsEnabled(long var0);

   private static native void nativeSetKeyIndex(long var0, int var2);

   private static native int nativeGetKeyIndex(long var0);

   private static native long nativeSetObserver(long var0, FrameCryptor.Observer var2);

   private static native void nativeUnSetObserver(long var0);

   public interface Observer {
      @CalledByNative("Observer")
      void onFrameCryptionStateChanged(String var1, FrameCryptor.FrameCryptionState var2);
   }

   public static enum FrameCryptionState {
      NEW,
      OK,
      ENCRYPTIONFAILED,
      DECRYPTIONFAILED,
      MISSINGKEY,
      KEYRATCHETED,
      INTERNALERROR;

      @CalledByNative("FrameCryptionState")
      static FrameCryptor.FrameCryptionState fromNativeIndex(int nativeIndex) {
         return values()[nativeIndex];
      }

      // $FF: synthetic method
      private static FrameCryptor.FrameCryptionState[] $values() {
         return new FrameCryptor.FrameCryptionState[]{NEW, OK, ENCRYPTIONFAILED, DECRYPTIONFAILED, MISSINGKEY, KEYRATCHETED, INTERNALERROR};
      }
   }
}
