package org.webrtc;

import androidx.annotation.Nullable;
import java.util.Arrays;
import java.util.LinkedHashSet;

public class WrappedVideoDecoderFactory implements VideoDecoderFactory {
   private final VideoDecoderFactory hardwareVideoDecoderFactory;
   private final VideoDecoderFactory hardwareVideoDecoderFactoryWithoutEglContext = new HardwareVideoDecoderFactory((EglBase.Context)null);
   private final VideoDecoderFactory softwareVideoDecoderFactory = new SoftwareVideoDecoderFactory();
   @Nullable
   private final VideoDecoderFactory platformSoftwareVideoDecoderFactory;

   public WrappedVideoDecoderFactory(@Nullable EglBase.Context eglContext) {
      this.hardwareVideoDecoderFactory = new HardwareVideoDecoderFactory(eglContext);
      this.platformSoftwareVideoDecoderFactory = new PlatformSoftwareVideoDecoderFactory(eglContext);
   }

   public VideoDecoder createDecoder(VideoCodecInfo codecType) {
      VideoDecoder softwareDecoder = this.softwareVideoDecoderFactory.createDecoder(codecType);
      VideoDecoder hardwareDecoder = this.hardwareVideoDecoderFactory.createDecoder(codecType);
      if (softwareDecoder == null && this.platformSoftwareVideoDecoderFactory != null) {
         softwareDecoder = this.platformSoftwareVideoDecoderFactory.createDecoder(codecType);
      }

      if (hardwareDecoder != null && this.disableSurfaceTextureFrame(hardwareDecoder.getImplementationName())) {
         hardwareDecoder.release();
         hardwareDecoder = this.hardwareVideoDecoderFactoryWithoutEglContext.createDecoder(codecType);
      }

      if (hardwareDecoder != null && softwareDecoder != null) {
         return new VideoDecoderFallback(softwareDecoder, hardwareDecoder);
      } else {
         return hardwareDecoder != null ? hardwareDecoder : softwareDecoder;
      }
   }

   private boolean disableSurfaceTextureFrame(String name) {
      return name.startsWith("OMX.qcom.") || name.startsWith("OMX.hisi.");
   }

   public VideoCodecInfo[] getSupportedCodecs() {
      LinkedHashSet<VideoCodecInfo> supportedCodecInfos = new LinkedHashSet();
      supportedCodecInfos.addAll(Arrays.asList(this.softwareVideoDecoderFactory.getSupportedCodecs()));
      supportedCodecInfos.addAll(Arrays.asList(this.hardwareVideoDecoderFactory.getSupportedCodecs()));
      if (this.platformSoftwareVideoDecoderFactory != null) {
         supportedCodecInfos.addAll(Arrays.asList(this.platformSoftwareVideoDecoderFactory.getSupportedCodecs()));
      }

      return (VideoCodecInfo[])supportedCodecInfos.toArray(new VideoCodecInfo[supportedCodecInfos.size()]);
   }
}
