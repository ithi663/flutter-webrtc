package org.webrtc;

import android.graphics.Matrix;
import android.media.MediaCodecInfo;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class HardwareVideoEncoder implements VideoEncoder {
   private static final String TAG = "HardwareVideoEncoder";
   private static final int MAX_VIDEO_FRAMERATE = 30;
   @IntRange(from = 1, to = 10)
   private static final int MAX_ENCODER_Q_SIZE = 6; // Increased with better stuck detection
   private static final int MEDIA_CODEC_RELEASE_TIMEOUT_MS = 5000;
   private static final int REQUIRED_RESOLUTION_ALIGNMENT = 2;
   
   // Object pool constants
   private static final int BUILDER_POOL_SIZE = MAX_ENCODER_Q_SIZE * 2;
   
   private final MediaCodecWrapperFactory mediaCodecWrapperFactory;
   private final String codecName;
   private final VideoCodecMimeType codecType;
   private final Integer surfaceColorFormat;
   private final Integer yuvColorFormat;
   private final Map<String, String> params;
   private final int keyFrameIntervalSec;
   private final long forcedKeyFrameNs;
   private final BitrateAdjuster bitrateAdjuster;
   private final EglBase14.Context sharedContext;
   private final GlRectDrawer textureDrawer = new GlRectDrawer();
   private final VideoFrameDrawer videoFrameDrawer = new VideoFrameDrawer();
   
   // Replaced LinkedBlockingDeque with ArrayDeque for better performance
   private final ArrayDeque<EncodedImage.Builder> pendingBuilders = new ArrayDeque<>();
   
   // Object pool for builders to reduce allocations
   private final ArrayDeque<EncodedImage.Builder> builderPool = new ArrayDeque<>(BUILDER_POOL_SIZE);
   
   private final ThreadUtils.ThreadChecker encodeThreadChecker = new ThreadUtils.ThreadChecker();
   
   // Replaced BusyCount with AtomicInteger for better performance
   private final AtomicInteger outputBuffersBusyCount = new AtomicInteger(0);
   
   private VideoEncoder.Callback callback;
   private boolean automaticResizeOn;
   
   // Async MediaCodec handling
   @Nullable
   private MediaCodecWrapper codec;
   @Nullable
   @VisibleForTesting
   HandlerThread codecThread;
   @Nullable
   private Handler codecHandler;
   
   @Nullable
   private EglBase14 textureEglBase;
   @Nullable
   private Surface textureInputSurface;
   private int width;
   private int height;
   private int stride;
   private int sliceHeight;
   private boolean isSemiPlanar;
   private int frameSizeBytes;
   private boolean useSurfaceMode;
   private long nextPresentationTimestampUs;
   private long lastKeyFrameNs;
   @Nullable
   private ByteBuffer configBuffer;
   private int adjustedBitrate;
   private volatile boolean running;
   @Nullable
   private volatile Exception shutdownException;
   private boolean isEncodingStatisticsEnabled;
   
   // Timing optimizations - use monotonic clock
   private long lastSuccessfulEncodeTimeMs = 0;
   private static final long ENCODER_TIMEOUT_MS = 5000; // 5 seconds
   private int consecutiveBufferTypeMismatches = 0;
   private static final int MAX_BUFFER_TYPE_MISMATCHES = 5;
   
   // Simplified stuck detection
   private long lastOutputBufferTimeMs = 0;
   private static final long ENCODER_STUCK_TIMEOUT_MS = 2000; // 2 seconds
   private int consecutiveStuckChecks = 0;
   private static final int MAX_STUCK_CHECKS = 3;
   
   // Queue management
   private long queueFullSinceMs = 0;
   private static final long QUEUE_FULL_TIMEOUT_MS = 1000;
   
   // Release synchronization
   @Nullable
   private CountDownLatch releaseLatch;

   public HardwareVideoEncoder(MediaCodecWrapperFactory mediaCodecWrapperFactory, String codecName, VideoCodecMimeType codecType, Integer surfaceColorFormat, Integer yuvColorFormat, Map<String, String> params, int keyFrameIntervalSec, int forceKeyFrameIntervalMs, BitrateAdjuster bitrateAdjuster, EglBase14.Context sharedContext) {
      this.mediaCodecWrapperFactory = mediaCodecWrapperFactory;
      this.codecName = codecName;
      this.codecType = codecType;
      this.surfaceColorFormat = surfaceColorFormat;
      this.yuvColorFormat = yuvColorFormat;
      this.params = params != null ? params : Collections.emptyMap();
      this.keyFrameIntervalSec = keyFrameIntervalSec;
      this.forcedKeyFrameNs = TimeUnit.MILLISECONDS.toNanos((long)forceKeyFrameIntervalMs);
      this.bitrateAdjuster = bitrateAdjuster;
      this.sharedContext = sharedContext;
      this.encodeThreadChecker.detachThread();
   }

   // Object pool methods for EncodedImage.Builder
   private EncodedImage.Builder obtainBuilder() {
      EncodedImage.Builder builder = builderPool.pollFirst();
      return builder != null ? builder : EncodedImage.builder();
   }

   private void recycleBuilder(EncodedImage.Builder builder) {
      // Reset builder state if needed (assuming EncodedImage.Builder has a reset method)
      if (builderPool.size() < BUILDER_POOL_SIZE) {
         builderPool.addFirst(builder);
      }
   }

   // Simplified stuck detection
   private boolean isEncoderStuck(long nowMs, int queueSize) {
      long delta = nowMs - lastOutputBufferTimeMs;
      return delta > ENCODER_STUCK_TIMEOUT_MS && queueSize >= MAX_ENCODER_Q_SIZE - 2;
   }

   // Wait for busy count to reach zero using CountDownLatch
   private void waitForOutputBuffersToBeReleased() {
      if (outputBuffersBusyCount.get() == 0) {
         return;
      }
      
      releaseLatch = new CountDownLatch(1);
      // Wait with timeout to avoid hanging
      try {
         if (!releaseLatch.await(MEDIA_CODEC_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Logging.w(TAG, "Timeout waiting for output buffers to be released");
         }
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         Logging.w(TAG, "Interrupted while waiting for output buffers", e);
      }
   }

   public VideoCodecStatus initEncode(VideoEncoder.Settings settings, VideoEncoder.Callback callback) {
      this.encodeThreadChecker.checkIsOnValidThread();
      this.callback = callback;
      this.automaticResizeOn = settings.automaticResizeOn;
      if (settings.width % 2 == 0 && settings.height % 2 == 0) {
         this.width = settings.width;
         this.height = settings.height;
         this.useSurfaceMode = this.canUseSurface();
         if (settings.startBitrate != 0 && settings.maxFramerate != 0) {
            this.bitrateAdjuster.setTargets(settings.startBitrate * 1000, (double)settings.maxFramerate);
         }

         this.adjustedBitrate = this.bitrateAdjuster.getAdjustedBitrateBps();
         Logging.d("HardwareVideoEncoder", "initEncode name: " + this.codecName + " type: " + this.codecType + " width: " + this.width + " height: " + this.height + " framerate_fps: " + settings.maxFramerate + " bitrate_kbps: " + settings.startBitrate + " surface mode: " + this.useSurfaceMode);
         return this.initEncodeInternal();
      } else {
         Logging.e("HardwareVideoEncoder", "MediaCodec requires 2x2 alignment.");
         return VideoCodecStatus.ERR_SIZE;
      }
   }

   private VideoCodecStatus initEncodeInternal() {
      this.encodeThreadChecker.checkIsOnValidThread();
      long currentTime = SystemClock.elapsedRealtime();
      this.lastSuccessfulEncodeTimeMs = currentTime;
      this.nextPresentationTimestampUs = 0L;
      this.lastKeyFrameNs = -1L;
      this.isEncodingStatisticsEnabled = false;
      this.lastOutputBufferTimeMs = currentTime;
      this.consecutiveStuckChecks = 0;
      this.queueFullSinceMs = 0;

      try {
         this.codec = this.mediaCodecWrapperFactory.createByCodecName(this.codecName);
      } catch (IllegalArgumentException | IOException var6) {
         Logging.e("HardwareVideoEncoder", "Cannot create media encoder " + this.codecName);
         return VideoCodecStatus.FALLBACK_SOFTWARE;
      }

      int colorFormat = this.useSurfaceMode ? this.surfaceColorFormat : this.yuvColorFormat;

      try {
         MediaFormat format = MediaFormat.createVideoFormat(this.codecType.mimeType(), this.width, this.height);
         format.setInteger("bitrate", this.adjustedBitrate);
         format.setInteger("bitrate-mode", 2);
         format.setInteger("color-format", colorFormat);
         format.setFloat("frame-rate", (float)this.bitrateAdjuster.getAdjustedFramerateFps());
         format.setInteger("i-frame-interval", this.keyFrameIntervalSec);
         if (this.codecType == VideoCodecMimeType.H264) {
            String profileLevelId = (String)this.params.get("profile-level-id");
            if (profileLevelId == null) {
               profileLevelId = "42e01f";
            }

            if ("640c1f".equals(profileLevelId)) {
               format.setInteger("profile", 8);
               format.setInteger("level", 256);
            } else if (!"42e01f".equals(profileLevelId)) {
               Logging.w("HardwareVideoEncoder", "Unknown profile level id: " + profileLevelId);
            }
         }

         if (this.codecName.equals("c2.google.av1.encoder")) {
            format.setInteger("vendor.google-av1enc.encoding-preset.int32.value", 1);
         }

         if (this.isEncodingStatisticsSupported()) {
            format.setInteger("video-encoding-statistics-level", 1);
            this.isEncodingStatisticsEnabled = true;
         }

         Logging.d("HardwareVideoEncoder", "Format: " + format);
         this.codec.configure(format, (Surface)null, (MediaCrypto)null, 1);
         
         if (this.useSurfaceMode) {
            this.textureEglBase = EglBase.createEgl14(this.sharedContext, EglBase.CONFIG_RECORDABLE);
            this.textureInputSurface = this.codec.createInputSurface();
            this.textureEglBase.createSurface(this.textureInputSurface);
            this.textureEglBase.makeCurrent();
         }

         this.updateInputFormat(this.codec.getInputFormat());
         this.codec.start();
      } catch (IllegalStateException | IllegalArgumentException var7) {
         Logging.e("HardwareVideoEncoder", "initEncodeInternal failed", var7);
         this.release();
         return VideoCodecStatus.FALLBACK_SOFTWARE;
      }

      this.running = true;
      this.encodeThreadChecker.detachThread();
      
      // Create optimized output thread
      this.codecThread = new HandlerThread("HardwareVideoEncoder-" + this.codecName);
      this.codecThread.start();
      this.codecHandler = new Handler(this.codecThread.getLooper());
      
      // Start output polling on the codec thread
      this.codecHandler.post(this::outputPollingLoop);
      
      return VideoCodecStatus.OK;
   }

   // Optimized polling loop instead of async callback due to MediaCodecWrapper limitations
   private void outputPollingLoop() {
      while (this.running) {
         try {
            this.deliverEncodedImage();
         } catch (Exception e) {
            Logging.e(TAG, "Error in output polling loop", e);
            this.shutdownException = e;
            break;
         }
      }
      
      // Don't release codec here - let the explicit release() call handle it
      Logging.d(TAG, "Output polling loop finished");
   }

   public VideoCodecStatus release() {
      this.encodeThreadChecker.checkIsOnValidThread();
      this.running = false;
      
      VideoCodecStatus returnValue = VideoCodecStatus.OK;
      
      // Release codec on the codec thread
      if (this.codecThread != null && this.codecHandler != null) {
         CountDownLatch codecReleaseLatch = new CountDownLatch(1);
         this.codecHandler.post(() -> {
            try {
               releaseCodecOnCodecThread();
            } finally {
               codecReleaseLatch.countDown();
            }
         });
         
         try {
            if (!codecReleaseLatch.await(MEDIA_CODEC_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
               Logging.e(TAG, "Media encoder release timeout");
               returnValue = VideoCodecStatus.TIMEOUT;
            }
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logging.e(TAG, "Interrupted during codec release", e);
            returnValue = VideoCodecStatus.ERROR;
         }
         
         // Quit codec thread
         this.codecThread.quitSafely();
         if (!ThreadUtils.joinUninterruptibly(this.codecThread, 1000L)) {
            Logging.w(TAG, "Codec thread did not finish in time");
         }
      }

      if (this.shutdownException != null) {
         Logging.e(TAG, "Media encoder release exception", this.shutdownException);
         returnValue = VideoCodecStatus.ERROR;
      }

      this.textureDrawer.release();
      this.videoFrameDrawer.release();
      if (this.textureEglBase != null) {
         this.textureEglBase.release();
         this.textureEglBase = null;
      }

      if (this.textureInputSurface != null) {
         this.textureInputSurface.release();
         this.textureInputSurface = null;
      }

      this.pendingBuilders.clear();
      this.builderPool.clear();
      this.codec = null;
      this.codecThread = null;
      this.codecHandler = null;
      this.encodeThreadChecker.detachThread();
      return returnValue;
   }

   public int getPendingInputFrames() {
      return this.pendingBuilders.size();
   }

   public VideoCodecStatus encode(VideoFrame videoFrame, VideoEncoder.EncodeInfo encodeInfo) {
      this.encodeThreadChecker.checkIsOnValidThread();
      if (this.codec == null) {
         return VideoCodecStatus.UNINITIALIZED;
      } else {
         boolean isTextureBuffer = videoFrame.getBuffer() instanceof VideoFrame.TextureBuffer;
         int frameWidth = videoFrame.getBuffer().getWidth();
         int frameHeight = videoFrame.getBuffer().getHeight();
         boolean shouldUseSurfaceMode = this.canUseSurface() && isTextureBuffer;
         
         // Only reset codec for significant changes, avoid surface mode switching
         boolean needsReset = false;
         if (frameWidth != this.width || frameHeight != this.height) {
            needsReset = true;
            Logging.w("HardwareVideoEncoder", "Resolution changed from " + this.width + "x" + this.height + " to " + frameWidth + "x" + frameHeight);
            this.consecutiveBufferTypeMismatches = 0; // Reset mismatch counter on resolution change
         } else if (shouldUseSurfaceMode != this.useSurfaceMode) {
            this.consecutiveBufferTypeMismatches++;
            if (this.consecutiveBufferTypeMismatches >= MAX_BUFFER_TYPE_MISMATCHES) {
               // Allow reset after consistent mismatches to adapt to actual buffer type
               needsReset = true;
               Logging.w("HardwareVideoEncoder", "Surface mode adaptation: " + this.useSurfaceMode + " -> " + shouldUseSurfaceMode + " after " + this.consecutiveBufferTypeMismatches + " mismatches");
               this.consecutiveBufferTypeMismatches = 0;
            } else {
               Logging.d("HardwareVideoEncoder", "Surface mode change detected: " + this.useSurfaceMode + " -> " + shouldUseSurfaceMode + ", mismatch count: " + this.consecutiveBufferTypeMismatches);
            }
         } else {
            // Reset mismatch counter when buffer types align
            this.consecutiveBufferTypeMismatches = 0;
         }
         
         if (needsReset) {
            VideoCodecStatus status = this.resetCodec(frameWidth, frameHeight, shouldUseSurfaceMode);
            if (status != VideoCodecStatus.OK) {
               return status;
            }
         }

         // Simplified stuck detection using monotonic time
         long currentTime = SystemClock.elapsedRealtime();
         int currentQueueSize = this.pendingBuilders.size();
         
         // Absolute hard limit - never allow queue to grow beyond this
         if (currentQueueSize >= MAX_ENCODER_Q_SIZE) {
            Logging.e("HardwareVideoEncoder", "Dropped frame, encoder queue at hard limit (size: " + currentQueueSize + ")");
            return VideoCodecStatus.NO_OUTPUT;
         }
         
         // Simplified stuck detection
         if (this.isEncoderStuck(currentTime, currentQueueSize)) {
            this.consecutiveStuckChecks++;
            Logging.w("HardwareVideoEncoder", "Encoder stuck detection #" + this.consecutiveStuckChecks + 
                     " - Queue: " + currentQueueSize + "/" + MAX_ENCODER_Q_SIZE);
            
            if (this.consecutiveStuckChecks >= MAX_STUCK_CHECKS) {
               Logging.e("HardwareVideoEncoder", "Encoder permanently stuck, forcing restart");
               VideoCodecStatus resetStatus = this.resetCodec(this.width, this.height, this.useSurfaceMode);
               if (resetStatus != VideoCodecStatus.OK) {
                  return resetStatus;
               }
               this.consecutiveStuckChecks = 0;
               this.lastSuccessfulEncodeTimeMs = currentTime;
               this.lastOutputBufferTimeMs = currentTime;
            } else {
               // Clear pending builders and request keyframe
               this.pendingBuilders.clear();
               this.requestKeyFrame(videoFrame.getTimestampNs());
               return VideoCodecStatus.NO_OUTPUT;
            }
         } else if (this.consecutiveStuckChecks > 0) {
            // Reset when encoder recovers
            this.consecutiveStuckChecks = 0;
         }

         // Check for buffer type mismatches early - don't queue builders for frames we'll drop
         if (this.useSurfaceMode && !isTextureBuffer) {
            this.consecutiveBufferTypeMismatches++;
            Logging.d("HardwareVideoEncoder", "Buffer type mismatch (useSurfaceMode: " + this.useSurfaceMode + ", isTextureBuffer: " + isTextureBuffer + "), dropping frame. Mismatch count: " + this.consecutiveBufferTypeMismatches);
            return VideoCodecStatus.NO_OUTPUT;
         }
         if (!this.useSurfaceMode && isTextureBuffer) {
            this.consecutiveBufferTypeMismatches++;
            Logging.d("HardwareVideoEncoder", "Buffer type mismatch (useSurfaceMode: " + this.useSurfaceMode + ", isTextureBuffer: " + isTextureBuffer + "), dropping frame. Mismatch count: " + this.consecutiveBufferTypeMismatches);
            return VideoCodecStatus.NO_OUTPUT;
         }

         // Reset consecutive mismatches when buffer types align
         this.consecutiveBufferTypeMismatches = 0;

         boolean requestedKeyFrame = false;
         EncodedImage.FrameType[] var8 = encodeInfo.frameTypes;
         int var9 = var8.length;

         for(int var10 = 0; var10 < var9; ++var10) {
            EncodedImage.FrameType frameType = var8[var10];
            if (frameType == EncodedImage.FrameType.VideoFrameKey) {
               requestedKeyFrame = true;
            }
         }

         if (requestedKeyFrame || this.shouldForceKeyFrame(videoFrame.getTimestampNs())) {
            this.requestKeyFrame(videoFrame.getTimestampNs());
         }

         // Use object pool for builder
         EncodedImage.Builder builder = this.obtainBuilder()
               .setCaptureTimeNs(videoFrame.getTimestampNs())
               .setEncodedWidth(videoFrame.getBuffer().getWidth())
               .setEncodedHeight(videoFrame.getBuffer().getHeight())
               .setRotation(videoFrame.getRotation());
         
         long presentationTimestampUs = this.nextPresentationTimestampUs;
         long frameDurationUs = (long)((double)TimeUnit.SECONDS.toMicros(1L) / this.bitrateAdjuster.getAdjustedFramerateFps());
         this.nextPresentationTimestampUs += frameDurationUs;
         VideoCodecStatus returnValue;
         
         // Handle buffer types
         if (this.useSurfaceMode && isTextureBuffer) {
            returnValue = this.encodeTextureBuffer(videoFrame, presentationTimestampUs);
         } else if (!this.useSurfaceMode && !isTextureBuffer) {
            returnValue = this.encodeByteBuffer(videoFrame, presentationTimestampUs);
         } else {
            Logging.e("HardwareVideoEncoder", "Buffer type mismatch");
            this.recycleBuilder(builder);
            return VideoCodecStatus.NO_OUTPUT;
         }

         // Only add builder to queue if encoding was successful
         if (returnValue == VideoCodecStatus.OK) {
            this.pendingBuilders.offer(builder);
            this.lastSuccessfulEncodeTimeMs = currentTime;
            Logging.v("HardwareVideoEncoder", "Frame encoded successfully, queue size: " + this.pendingBuilders.size());
         } else {
            this.recycleBuilder(builder);
            Logging.w("HardwareVideoEncoder", "Encode failed with status: " + returnValue);
         }

         return returnValue;
      }
   }

   private VideoCodecStatus encodeTextureBuffer(VideoFrame videoFrame, long presentationTimestampUs) {
      this.encodeThreadChecker.checkIsOnValidThread();

      try {
         GLES20.glClear(16384);
         // Direct texture drawing without unnecessary VideoFrame wrapper
         this.videoFrameDrawer.drawFrame(videoFrame, this.textureDrawer, (Matrix)null);
         this.textureEglBase.swapBuffers(TimeUnit.MICROSECONDS.toNanos(presentationTimestampUs));
      } catch (RuntimeException var5) {
         Logging.e("HardwareVideoEncoder", "encodeTexture failed", var5);
         return VideoCodecStatus.ERROR;
      }

      return VideoCodecStatus.OK;
   }

   private VideoCodecStatus encodeByteBuffer(VideoFrame videoFrame, long presentationTimestampUs) {
      this.encodeThreadChecker.checkIsOnValidThread();

      int index;
      try {
         index = this.codec.dequeueInputBuffer(0L);
      } catch (IllegalStateException var9) {
         Logging.e("HardwareVideoEncoder", "dequeueInputBuffer failed", var9);
         return VideoCodecStatus.ERROR;
      }

      if (index == -1) {
         Logging.d("HardwareVideoEncoder", "Dropped frame, no input buffers available");
         return VideoCodecStatus.NO_OUTPUT;
      } else {
         ByteBuffer buffer;
         try {
            buffer = this.codec.getInputBuffer(index);
         } catch (IllegalStateException var8) {
            Logging.e("HardwareVideoEncoder", "getInputBuffer with index=" + index + " failed", var8);
            return VideoCodecStatus.ERROR;
         }

         if (buffer.capacity() < this.frameSizeBytes) {
            int var10001 = buffer.capacity();
            Logging.e("HardwareVideoEncoder", "Input buffer size: " + var10001 + " is smaller than frame size: " + this.frameSizeBytes);
            return VideoCodecStatus.ERROR;
         } else {
            this.fillInputBuffer(buffer, videoFrame.getBuffer());

            try {
               this.codec.queueInputBuffer(index, 0, this.frameSizeBytes, presentationTimestampUs, 0);
            } catch (IllegalStateException var7) {
               Logging.e("HardwareVideoEncoder", "queueInputBuffer failed", var7);
               return VideoCodecStatus.ERROR;
            }

            return VideoCodecStatus.OK;
         }
      }
   }

   public VideoCodecStatus setRateAllocation(VideoEncoder.BitrateAllocation bitrateAllocation, int framerate) {
      this.encodeThreadChecker.checkIsOnValidThread();
      if (framerate > 30) {
         framerate = 30;
      }

      this.bitrateAdjuster.setTargets(bitrateAllocation.getSum(), (double)framerate);
      return VideoCodecStatus.OK;
   }

   public VideoCodecStatus setRates(VideoEncoder.RateControlParameters rcParameters) {
      this.encodeThreadChecker.checkIsOnValidThread();
      this.bitrateAdjuster.setTargets(rcParameters.bitrate.getSum(), rcParameters.framerateFps);
      return VideoCodecStatus.OK;
   }

   public VideoEncoder.ScalingSettings getScalingSettings() {
      if (this.automaticResizeOn) {
         boolean kLowH264QpThreshold;
         boolean kHighH264QpThreshold;
         if (this.codecType == VideoCodecMimeType.VP8) {
            kLowH264QpThreshold = true;
            kHighH264QpThreshold = true;
            return new VideoEncoder.ScalingSettings(29, 95);
         }

         if (this.codecType == VideoCodecMimeType.H264) {
            kLowH264QpThreshold = true;
            kHighH264QpThreshold = true;
            return new VideoEncoder.ScalingSettings(24, 37);
         }
      }

      return VideoEncoder.ScalingSettings.OFF;
   }

   public String getImplementationName() {
      return this.codecName;
   }

   public VideoEncoder.EncoderInfo getEncoderInfo() {
      return new VideoEncoder.EncoderInfo(2, false);
   }

   private VideoCodecStatus resetCodec(int newWidth, int newHeight, boolean newUseSurfaceMode) {
      this.encodeThreadChecker.checkIsOnValidThread();
      VideoCodecStatus status = this.release();
      if (status != VideoCodecStatus.OK) {
         return status;
      } else if (newWidth % 2 == 0 && newHeight % 2 == 0) {
         this.width = newWidth;
         this.height = newHeight;
         this.useSurfaceMode = newUseSurfaceMode;
         return this.initEncodeInternal();
      } else {
         Logging.e("HardwareVideoEncoder", "MediaCodec requires 2x2 alignment.");
         return VideoCodecStatus.ERR_SIZE;
      }
   }

   private boolean shouldForceKeyFrame(long presentationTimestampNs) {
      this.encodeThreadChecker.checkIsOnValidThread();
      return this.forcedKeyFrameNs > 0L && presentationTimestampNs > this.lastKeyFrameNs + this.forcedKeyFrameNs;
   }

   private void requestKeyFrame(long presentationTimestampNs) {
      this.encodeThreadChecker.checkIsOnValidThread();

      try {
         Bundle b = new Bundle();
         b.putInt("request-sync", 0);
         this.codec.setParameters(b);
      } catch (IllegalStateException var4) {
         Logging.e("HardwareVideoEncoder", "requestKeyFrame failed", var4);
         return;
      }

      this.lastKeyFrameNs = presentationTimestampNs;
   }

      // Optimized polling-based delivery method
   private void deliverEncodedImage() {
      // Safety check - codec might be null during shutdown
      if (this.codec == null || !this.running) {
         return;
      }
      
      try {
         BufferInfo info = new BufferInfo();
         int index = this.codec.dequeueOutputBuffer(info, 10000L); // Reduced timeout for better responsiveness
         
         if (index < 0) {
            if (index == -3) {
               Logging.d(TAG, "Output format changed");
               this.updateInputFormat(this.codec.getOutputFormat());
            } else if (index == -1) {
               // No output available - this is normal, just continue
            } else {
               Logging.d(TAG, "dequeueOutputBuffer returned: " + index);
            }
            return;
         }

         // Update output buffer timestamp when we successfully get a buffer
         this.lastOutputBufferTimeMs = SystemClock.elapsedRealtime();

         // Check if we have builders available
         if (this.pendingBuilders.isEmpty()) {
            Logging.w(TAG, "MediaCodec produced output but no builders available, dropping frame. Index: " + index);
            this.codec.releaseOutputBuffer(index, false);
            return;
         }

         ByteBuffer outputBuffer = this.codec.getOutputBuffer(index);
         outputBuffer.position(info.offset);
         outputBuffer.limit(info.offset + info.size);

         if ((info.flags & 2) != 0) {
            Logging.d(TAG, "Config frame generated. Offset: " + info.offset + ". Size: " + info.size);
            if (info.size > 0 && (this.codecType == VideoCodecMimeType.H264 || this.codecType == VideoCodecMimeType.H265)) {
               // Pre-allocate config buffer to avoid repeated allocations
               if (this.configBuffer == null || this.configBuffer.capacity() < info.size) {
                  this.configBuffer = ByteBuffer.allocateDirect(info.size);
               }
               this.configBuffer.clear();
               this.configBuffer.put(outputBuffer);
            }
            this.codec.releaseOutputBuffer(index, false);
            return;
         }

         this.bitrateAdjuster.reportEncodedFrame(info.size);
         if (this.adjustedBitrate != this.bitrateAdjuster.getAdjustedBitrateBps()) {
            this.updateBitrate();
         }

         boolean isKeyFrame = (info.flags & 1) != 0;
         if (isKeyFrame) {
            Logging.d(TAG, "Sync frame generated");
         }

         Integer qp = null;
         if (this.isEncodingStatisticsEnabled) {
            MediaFormat format = this.codec.getOutputFormat(index);
            if (format != null && format.containsKey("video-qp-average")) {
               qp = format.getInteger("video-qp-average");
            }
         }

         Runnable releaseCallback;
         ByteBuffer frameBuffer;
         if (isKeyFrame && this.configBuffer != null) {
            Logging.d(TAG, "Prepending config buffer of size " + this.configBuffer.capacity() + 
                      " to output buffer with offset " + info.offset + ", size " + info.size);
            frameBuffer = ByteBuffer.allocateDirect(info.size + this.configBuffer.capacity());
            this.configBuffer.rewind();
            frameBuffer.put(this.configBuffer);
            frameBuffer.put(outputBuffer);
            frameBuffer.rewind();
            this.codec.releaseOutputBuffer(index, false);
            releaseCallback = null;
         } else {
            frameBuffer = outputBuffer.slice();
            this.outputBuffersBusyCount.incrementAndGet();
            releaseCallback = () -> {
               try {
                  this.codec.releaseOutputBuffer(index, false);
               } catch (Exception e) {
                  Logging.e(TAG, "releaseOutputBuffer failed", e);
               }
               int remaining = this.outputBuffersBusyCount.decrementAndGet();
               if (remaining == 0 && this.releaseLatch != null) {
                  this.releaseLatch.countDown();
               }
            };
         }

         EncodedImage.FrameType frameType = isKeyFrame ? EncodedImage.FrameType.VideoFrameKey : EncodedImage.FrameType.VideoFrameDelta;
         EncodedImage.Builder builder = this.pendingBuilders.poll();
         builder.setBuffer(frameBuffer, releaseCallback);
         builder.setFrameType(frameType);
         builder.setQp(qp);
         EncodedImage encodedImage = builder.createEncodedImage();
         this.callback.onEncodedFrame(encodedImage, new VideoEncoder.CodecSpecificInfo());
         encodedImage.release();
         this.recycleBuilder(builder);
      } catch (IllegalStateException e) {
         Logging.e(TAG, "deliverEncodedImage failed", e);
      }
   }

   private void releaseCodecOnCodecThread() {
      Logging.d(TAG, "Releasing MediaCodec on codec thread");
      
      // Safety check to prevent double release
      if (this.codec == null) {
         Logging.d(TAG, "Codec already released, skipping");
         return;
      }
      
      this.waitForOutputBuffersToBeReleased();

      try {
         this.codec.stop();
      } catch (Exception e) {
         Logging.e(TAG, "Media encoder stop failed", e);
      }

      try {
         this.codec.release();
      } catch (Exception e) {
         Logging.e(TAG, "Media encoder release failed", e);
         this.shutdownException = e;
      }

      this.codec = null; // Clear reference after release
      this.configBuffer = null;
      Logging.d(TAG, "Release on codec thread done");
   }

   private VideoCodecStatus updateBitrate() {
      // Called from codec thread via async callback
      this.adjustedBitrate = this.bitrateAdjuster.getAdjustedBitrateBps();

      try {
         Bundle params = new Bundle();
         params.putInt("video-bitrate", this.adjustedBitrate);
         this.codec.setParameters(params);
         return VideoCodecStatus.OK;
      } catch (IllegalStateException e) {
         Logging.e(TAG, "updateBitrate failed", e);
         return VideoCodecStatus.ERROR;
      }
   }

   private boolean canUseSurface() {
      return this.sharedContext != null && this.surfaceColorFormat != null;
   }

   private void updateInputFormat(MediaFormat format) {
      this.stride = this.width;
      this.sliceHeight = this.height;
      if (format != null) {
         if (format.containsKey("stride")) {
            this.stride = format.getInteger("stride");
            this.stride = Math.max(this.stride, this.width);
         }

         if (format.containsKey("slice-height")) {
            this.sliceHeight = format.getInteger("slice-height");
            this.sliceHeight = Math.max(this.sliceHeight, this.height);
         }
      }

      this.isSemiPlanar = this.isSemiPlanar(this.yuvColorFormat);
      int chromaHeight;
      if (this.isSemiPlanar) {
         chromaHeight = (this.height + 1) / 2;
         this.frameSizeBytes = this.sliceHeight * this.stride + chromaHeight * this.stride;
      } else {
         chromaHeight = (this.stride + 1) / 2;
         int chromaSliceHeight = (this.sliceHeight + 1) / 2;
         this.frameSizeBytes = this.sliceHeight * this.stride + chromaSliceHeight * chromaHeight * 2;
      }

      Logging.d("HardwareVideoEncoder", "updateInputFormat format: " + format + " stride: " + this.stride + " sliceHeight: " + this.sliceHeight + " isSemiPlanar: " + this.isSemiPlanar + " frameSizeBytes: " + this.frameSizeBytes);
   }

   protected boolean isEncodingStatisticsSupported() {
      if (this.codecType != VideoCodecMimeType.VP8 && this.codecType != VideoCodecMimeType.VP9) {
         MediaCodecInfo codecInfo = this.codec.getCodecInfo();
         if (codecInfo == null) {
            return false;
         } else {
            CodecCapabilities codecCaps = codecInfo.getCapabilitiesForType(this.codecType.mimeType());
            return codecCaps == null ? false : codecCaps.isFeatureSupported("encoding-statistics");
         }
      } else {
         return false;
      }
   }

   protected void fillInputBuffer(ByteBuffer buffer, VideoFrame.Buffer frame) {
      VideoFrame.I420Buffer i420 = frame.toI420();
      if (this.isSemiPlanar) {
         YuvHelper.I420ToNV12(i420.getDataY(), i420.getStrideY(), i420.getDataU(), i420.getStrideU(), i420.getDataV(), i420.getStrideV(), buffer, i420.getWidth(), i420.getHeight(), this.stride, this.sliceHeight);
      } else {
         YuvHelper.I420Copy(i420.getDataY(), i420.getStrideY(), i420.getDataU(), i420.getStrideU(), i420.getDataV(), i420.getStrideV(), buffer, i420.getWidth(), i420.getHeight(), this.stride, this.sliceHeight);
      }

      i420.release();
   }

   protected boolean isSemiPlanar(int colorFormat) {
      switch(colorFormat) {
      case 19:
         return false;
      case 21:
      case 2141391872:
      case 2141391876:
         return true;
      default:
         throw new IllegalArgumentException("Unsupported colorFormat: " + colorFormat);
      }
   }
}

