package org.webrtc;

public abstract class WrappedNativeVideoEncoder implements VideoEncoder {
   public abstract long createNative(long var1);

   public abstract boolean isHardwareEncoder();

   public final VideoCodecStatus initEncode(VideoEncoder.Settings settings, VideoEncoder.Callback encodeCallback) {
      throw new UnsupportedOperationException("Not implemented.");
   }

   public final VideoCodecStatus release() {
      throw new UnsupportedOperationException("Not implemented.");
   }

   public final VideoCodecStatus encode(VideoFrame frame, VideoEncoder.EncodeInfo info) {
      throw new UnsupportedOperationException("Not implemented.");
   }

   public final VideoCodecStatus setRateAllocation(VideoEncoder.BitrateAllocation allocation, int framerate) {
      throw new UnsupportedOperationException("Not implemented.");
   }

   public final VideoEncoder.ScalingSettings getScalingSettings() {
      throw new UnsupportedOperationException("Not implemented.");
   }

   public final String getImplementationName() {
      throw new UnsupportedOperationException("Not implemented.");
   }
}
