package org.webrtc;

import androidx.annotation.Nullable;
import java.nio.ByteBuffer;

public class ExternalAudioProcessingFactory implements AudioProcessingFactory {
   private long apmPtr = nativeGetDefaultApm();
   private long capturePostProcessingPtr = 0L;
   private long renderPreProcessingPtr = 0L;

   public long createNative() {
      if (this.apmPtr == 0L) {
         this.apmPtr = nativeGetDefaultApm();
      }

      return this.apmPtr;
   }

   public void setCapturePostProcessing(@Nullable ExternalAudioProcessingFactory.AudioProcessing processing) {
      this.checkExternalAudioProcessorExists();
      long newPtr = nativeSetCapturePostProcessing(processing);
      if (this.capturePostProcessingPtr != 0L) {
         JniCommon.nativeReleaseRef(this.capturePostProcessingPtr);
         this.capturePostProcessingPtr = 0L;
      }

      this.capturePostProcessingPtr = newPtr;
   }

   public void setRenderPreProcessing(@Nullable ExternalAudioProcessingFactory.AudioProcessing processing) {
      this.checkExternalAudioProcessorExists();
      long newPtr = nativeSetRenderPreProcessing(processing);
      if (this.renderPreProcessingPtr != 0L) {
         JniCommon.nativeReleaseRef(this.renderPreProcessingPtr);
         this.renderPreProcessingPtr = 0L;
      }

      this.renderPreProcessingPtr = newPtr;
   }

   public void setBypassFlagForCapturePost(boolean bypass) {
      this.checkExternalAudioProcessorExists();
      nativeSetBypassFlagForCapturePost(bypass);
   }

   public void setBypassFlagForRenderPre(boolean bypass) {
      this.checkExternalAudioProcessorExists();
      nativeSetBypassFlagForRenderPre(bypass);
   }

   public void destroy() {
      this.checkExternalAudioProcessorExists();
      if (this.renderPreProcessingPtr != 0L) {
         JniCommon.nativeReleaseRef(this.renderPreProcessingPtr);
         this.renderPreProcessingPtr = 0L;
      }

      if (this.capturePostProcessingPtr != 0L) {
         JniCommon.nativeReleaseRef(this.capturePostProcessingPtr);
         this.capturePostProcessingPtr = 0L;
      }

      nativeDestroy();
      this.apmPtr = 0L;
   }

   private void checkExternalAudioProcessorExists() {
      if (this.apmPtr == 0L) {
         throw new IllegalStateException("ExternalAudioProcessor has been disposed.");
      }
   }

   private static native long nativeGetDefaultApm();

   private static native long nativeSetCapturePostProcessing(ExternalAudioProcessingFactory.AudioProcessing var0);

   private static native long nativeSetRenderPreProcessing(ExternalAudioProcessingFactory.AudioProcessing var0);

   private static native void nativeSetBypassFlagForCapturePost(boolean var0);

   private static native void nativeSetBypassFlagForRenderPre(boolean var0);

   private static native void nativeDestroy();

   public interface AudioProcessing {
      @CalledByNative("AudioProcessing")
      void initialize(int var1, int var2);

      @CalledByNative("AudioProcessing")
      void reset(int var1);

      @CalledByNative("AudioProcessing")
      void process(int var1, int var2, ByteBuffer var3);
   }
}
