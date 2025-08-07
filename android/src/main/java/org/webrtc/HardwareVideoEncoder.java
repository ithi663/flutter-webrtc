package org.webrtc;

import android.graphics.Matrix;
import android.media.MediaCodecInfo;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.opengl.GLES20;
import android.os.Bundle;
import android.view.Surface;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

class HardwareVideoEncoder implements VideoEncoder {
   private static final String TAG = "HardwareVideoEncoder";
   private static final int MAX_VIDEO_FRAMERATE = 30;
   private static final int MAX_ENCODER_Q_SIZE = 6; // Increased with better stuck detection
   private static final int MEDIA_CODEC_RELEASE_TIMEOUT_MS = 5000;
   private static final int DEQUEUE_OUTPUT_BUFFER_TIMEOUT_US = 100000;
   private static final int REQUIRED_RESOLUTION_ALIGNMENT = 2;
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
   private final BlockingDeque<EncodedImage.Builder> outputBuilders = new LinkedBlockingDeque();
   private final ThreadUtils.ThreadChecker encodeThreadChecker = new ThreadUtils.ThreadChecker();
   private final ThreadUtils.ThreadChecker outputThreadChecker = new ThreadUtils.ThreadChecker();
   private final HardwareVideoEncoder.BusyCount outputBuffersBusyCount = new HardwareVideoEncoder.BusyCount();
   private VideoEncoder.Callback callback;
   private boolean automaticResizeOn;
   @Nullable
   private MediaCodecWrapper codec;
   @Nullable
   private Thread outputThread;
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
   private long lastSuccessfulEncodeTimeMs = 0;
   private static final long ENCODER_TIMEOUT_MS = 5000; // 5 seconds
   private int consecutiveBufferTypeMismatches = 0;
   private static final int MAX_BUFFER_TYPE_MISMATCHES = 5; // Allow reset after 5 consecutive mismatches

   // Enhanced stuck detection
   private long lastOutputBufferTimeMs = 0;
   private static final long ENCODER_STUCK_TIMEOUT_MS = 2000; // 2 seconds - more aggressive
   private int consecutiveStuckChecks = 0;
   private static final int MAX_STUCK_CHECKS = 3; // Force restart after 3 consecutive stuck detections

   // Track when queue becomes full for immediate recovery
   private long queueFullSinceMs = 0;
   private static final long QUEUE_FULL_TIMEOUT_MS = 1000; // 1 second at full capacity triggers immediate restart

   // Enhanced buffer error recovery system fields
   private int consecutiveBufferErrors = 0;
   private static final int MAX_CONSECUTIVE_BUFFER_ERRORS = 3;
   private long lastBufferErrorTimeMs = 0;
   private static final long BUFFER_ERROR_RECOVERY_TIMEOUT_MS = 5000; // 5 seconds
   private boolean isInErrorRecoveryMode = false;
   private int errorRecoveryAttempts = 0;
   private static final int MAX_ERROR_RECOVERY_ATTEMPTS = 5;
   private long lastSuccessfulFrameTimeMs = 0;
   private static final long STREAM_HEALTH_CHECK_INTERVAL_MS = 10000; // 10 seconds
   private int droppedFramesDuringError = 0;
   private static final int MAX_DROPPED_FRAMES_DURING_ERROR = 30; // ~1 second at 30fps

   // Frame corruption prevention fields
   private boolean requiresFrameSync = false;
   private int framesSinceRecovery = 0;
   private static final int FRAME_SYNC_THRESHOLD = 3; // Wait for 3 clean frames after recovery
   private boolean pendingKeyframeRequest = false;
   private long lastKeyframeRequestTimeMs = 0;
   private static final long KEYFRAME_REQUEST_TIMEOUT_MS = 1000; // 1 second timeout for keyframe requests
   private ByteBuffer lastValidConfigBuffer = null;

   public HardwareVideoEncoder(MediaCodecWrapperFactory mediaCodecWrapperFactory, String codecName,
         VideoCodecMimeType codecType, Integer surfaceColorFormat, Integer yuvColorFormat, Map<String, String> params,
         int keyFrameIntervalSec, int forceKeyFrameIntervalMs, BitrateAdjuster bitrateAdjuster,
         EglBase14.Context sharedContext) {
      this.mediaCodecWrapperFactory = mediaCodecWrapperFactory;
      this.codecName = codecName;
      this.codecType = codecType;
      this.surfaceColorFormat = surfaceColorFormat;
      this.yuvColorFormat = yuvColorFormat;
      this.params = params;
      this.keyFrameIntervalSec = keyFrameIntervalSec;
      this.forcedKeyFrameNs = TimeUnit.MILLISECONDS.toNanos((long) forceKeyFrameIntervalMs);
      this.bitrateAdjuster = bitrateAdjuster;
      this.sharedContext = sharedContext;
      this.encodeThreadChecker.detachThread();

      // Initialize error recovery system
      this.resetErrorState();
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
            this.bitrateAdjuster.setTargets(settings.startBitrate * 1000, (double) settings.maxFramerate);
         }

         this.adjustedBitrate = this.bitrateAdjuster.getAdjustedBitrateBps();
         Logging.d("HardwareVideoEncoder",
               "initEncode name: " + this.codecName + " type: " + this.codecType + " width: " + this.width + " height: "
                     + this.height + " framerate_fps: " + settings.maxFramerate + " bitrate_kbps: "
                     + settings.startBitrate + " surface mode: " + this.useSurfaceMode);
         return this.initEncodeInternal();
      } else {
         Logging.e("HardwareVideoEncoder", "MediaCodec requires 2x2 alignment.");
         return VideoCodecStatus.ERR_SIZE;
      }
   }

   private VideoCodecStatus initEncodeInternal() {
      this.encodeThreadChecker.checkIsOnValidThread();
      this.lastSuccessfulEncodeTimeMs = System.currentTimeMillis();
      this.nextPresentationTimestampUs = 0L;
      this.lastKeyFrameNs = -1L;
      this.isEncodingStatisticsEnabled = false;
      this.lastOutputBufferTimeMs = System.currentTimeMillis();
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
         format.setFloat("frame-rate", (float) this.bitrateAdjuster.getAdjustedFramerateFps());
         format.setInteger("i-frame-interval", this.keyFrameIntervalSec);
         if (this.codecType == VideoCodecMimeType.H264) {
            String profileLevelId = (String) this.params.get("profile-level-id");
            if (profileLevelId == null) {
               profileLevelId = "42e01f";
            }

            byte var5 = -1;
            switch (profileLevelId.hashCode()) {
               case 1537948542:
                  if (profileLevelId.equals("42e01f")) {
                     var5 = 1;
                  }
                  break;
               case 1595523974:
                  if (profileLevelId.equals("640c1f")) {
                     var5 = 0;
                  }
            }

            switch (var5) {
               case 0:
                  format.setInteger("profile", 8);
                  format.setInteger("level", 256);
               case 1:
                  break;
               default:
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
         this.codec.configure(format, (Surface) null, (MediaCrypto) null, 1);
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
      this.outputThreadChecker.detachThread();
      this.outputThread = this.createOutputThread();
      this.outputThread.start();
      return VideoCodecStatus.OK;
   }

   public VideoCodecStatus release() {
      this.encodeThreadChecker.checkIsOnValidThread();
      VideoCodecStatus returnValue;
      if (this.outputThread == null) {
         returnValue = VideoCodecStatus.OK;
      } else {
         this.running = false;
         if (!ThreadUtils.joinUninterruptibly(this.outputThread, 5000L)) {
            Logging.e("HardwareVideoEncoder", "Media encoder release timeout");
            returnValue = VideoCodecStatus.TIMEOUT;
         } else if (this.shutdownException != null) {
            Logging.e("HardwareVideoEncoder", "Media encoder release exception", this.shutdownException);
            returnValue = VideoCodecStatus.ERROR;
         } else {
            returnValue = VideoCodecStatus.OK;
         }
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

      this.outputBuilders.clear();
      this.codec = null;
      this.outputThread = null;
      this.encodeThreadChecker.detachThread();
      return returnValue;
   }

   public int getPendingInputFrames() {
      return this.outputBuilders.size();
   }

   public VideoCodecStatus encode(VideoFrame videoFrame, VideoEncoder.EncodeInfo encodeInfo) {
      this.encodeThreadChecker.checkIsOnValidThread();
      if (this.codec == null) {
         return VideoCodecStatus.UNINITIALIZED;
      } else {
         // Handle frame synchronization during recovery to prevent corrupted frames
         if (!this.handleFrameSynchronization(videoFrame)) {
            // Frame is not ready for encoding during recovery, drop it
            if (this.isInErrorRecoveryMode) {
               this.droppedFramesDuringError++;
               Logging.d("HardwareVideoEncoder",
                     "Dropping frame during recovery synchronization, dropped: " + this.droppedFramesDuringError);
            }
            return VideoCodecStatus.NO_OUTPUT;
         }
         boolean isTextureBuffer = videoFrame.getBuffer() instanceof VideoFrame.TextureBuffer;
         int frameWidth = videoFrame.getBuffer().getWidth();
         int frameHeight = videoFrame.getBuffer().getHeight();
         boolean shouldUseSurfaceMode = this.canUseSurface() && isTextureBuffer;

         // Only reset codec for significant changes, avoid surface mode switching
         boolean needsReset = false;
         if (frameWidth != this.width || frameHeight != this.height) {
            needsReset = true;
            Logging.w("HardwareVideoEncoder", "Resolution changed from " + this.width + "x" + this.height + " to "
                  + frameWidth + "x" + frameHeight);
            this.consecutiveBufferTypeMismatches = 0; // Reset mismatch counter on resolution change
         } else if (shouldUseSurfaceMode != this.useSurfaceMode) {
            this.consecutiveBufferTypeMismatches++;
            if (this.consecutiveBufferTypeMismatches >= MAX_BUFFER_TYPE_MISMATCHES) {
               // Allow reset after consistent mismatches to adapt to actual buffer type
               needsReset = true;
               Logging.w("HardwareVideoEncoder", "Surface mode adaptation: " + this.useSurfaceMode + " -> "
                     + shouldUseSurfaceMode + " after " + this.consecutiveBufferTypeMismatches + " mismatches");
               this.consecutiveBufferTypeMismatches = 0;
            } else {
               Logging.d("HardwareVideoEncoder", "Surface mode change detected: " + this.useSurfaceMode + " -> "
                     + shouldUseSurfaceMode + ", mismatch count: " + this.consecutiveBufferTypeMismatches);
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

         // Enhanced stuck detection with more aggressive recovery
         long currentTime = System.currentTimeMillis();
         boolean isEncoderStuck = false;

         // Thread-safe queue size checking with hard limit enforcement
         int currentQueueSize = this.outputBuilders.size();

         // Absolute hard limit - never allow queue to grow beyond this
         if (currentQueueSize >= MAX_ENCODER_Q_SIZE) {
            // Track how long queue has been full
            if (this.queueFullSinceMs == 0) {
               this.queueFullSinceMs = currentTime;
               Logging.w("HardwareVideoEncoder", "Encoder queue reached hard limit, starting timeout tracking");
            }

            Logging.e("HardwareVideoEncoder",
                  "Dropped frame, encoder queue at hard limit (size: " + currentQueueSize + ")");
            return VideoCodecStatus.NO_OUTPUT;
         } else {
            // Reset queue full tracking when queue has space
            this.queueFullSinceMs = 0;
         }

         // Early prevention: if queue is approaching full and encoder isn't producing
         // output
         if (currentQueueSize >= (MAX_ENCODER_Q_SIZE - 1) &&
               (currentTime - this.lastOutputBufferTimeMs > ENCODER_STUCK_TIMEOUT_MS / 2)) {
            Logging.w("HardwareVideoEncoder", "Encoder becoming unresponsive, dropping frame preventively. Queue: " +
                  currentQueueSize + ", last output: " + (currentTime - this.lastOutputBufferTimeMs) + "ms ago");
            return VideoCodecStatus.NO_OUTPUT;
         }

         // More aggressive stuck detection - trigger when queue is near full
         if (currentQueueSize >= (MAX_ENCODER_Q_SIZE - 2)) {
            // Check for multiple types of stuck conditions with more lenient timeouts
            boolean queueStuck = (currentTime - this.lastSuccessfulEncodeTimeMs > ENCODER_STUCK_TIMEOUT_MS);
            boolean outputStuck = (currentTime - this.lastOutputBufferTimeMs > ENCODER_STUCK_TIMEOUT_MS);

            // Also check if queue is completely full for immediate action
            boolean queueFull = (currentQueueSize >= MAX_ENCODER_Q_SIZE);
            boolean queueFullTooLong = (this.queueFullSinceMs > 0
                  && currentTime - this.queueFullSinceMs > QUEUE_FULL_TIMEOUT_MS);

            if (queueStuck || outputStuck || queueFull || queueFullTooLong) {
               this.consecutiveStuckChecks++;
               isEncoderStuck = true;

               Logging.w("HardwareVideoEncoder", "Encoder stuck detection #" + this.consecutiveStuckChecks +
                     " - Queue: " + currentQueueSize + "/" + MAX_ENCODER_Q_SIZE +
                     ", Queue stuck: " + queueStuck + " (" + (currentTime - this.lastSuccessfulEncodeTimeMs) + "ms)" +
                     ", Output stuck: " + outputStuck + " (" + (currentTime - this.lastOutputBufferTimeMs) + "ms)" +
                     ", Queue full: " + queueFull + ", Queue full too long: " + queueFullTooLong +
                     (this.queueFullSinceMs > 0 ? " (" + (currentTime - this.queueFullSinceMs) + "ms)" : ""));

               if (this.consecutiveStuckChecks >= MAX_STUCK_CHECKS || queueFull || queueFullTooLong) {
                  Logging.e("HardwareVideoEncoder", "Encoder permanently stuck after " + this.consecutiveStuckChecks
                        + " checks (or queue issues), forcing restart");

                  // Force a complete codec restart to recover
                  try {
                     VideoCodecStatus resetStatus = this.resetCodec(this.width, this.height, this.useSurfaceMode);
                     if (resetStatus == VideoCodecStatus.OK) {
                        Logging.d("HardwareVideoEncoder", "Successfully restarted stuck encoder");
                        this.consecutiveStuckChecks = 0;
                        this.lastSuccessfulEncodeTimeMs = currentTime;
                        this.lastOutputBufferTimeMs = currentTime;
                        this.queueFullSinceMs = 0;
                        // Don't return immediately, try to encode this frame
                     } else {
                        Logging.e("HardwareVideoEncoder", "Failed to restart stuck encoder: " + resetStatus);
                        return VideoCodecStatus.ERROR;
                     }
                  } catch (Exception e) {
                     Logging.e("HardwareVideoEncoder", "Exception during forced encoder restart", e);
                     return VideoCodecStatus.ERROR;
                  }
               } else {
                  // Progressive recovery attempts
                  this.outputBuilders.clear();
                  this.lastSuccessfulEncodeTimeMs = currentTime;
                  this.queueFullSinceMs = 0;

                  if (this.consecutiveStuckChecks == 1) {
                     // First attempt: Try to kickstart with keyframe
                     try {
                        Bundle b = new Bundle();
                        b.putInt("request-sync", 0);
                        this.codec.setParameters(b);
                        Logging.w("HardwareVideoEncoder",
                              "Requested keyframe for stuck recovery #" + this.consecutiveStuckChecks);
                     } catch (Exception e) {
                        Logging.e("HardwareVideoEncoder", "Failed to request keyframe for recovery", e);
                     }
                  } else if (this.consecutiveStuckChecks == 2) {
                     // Second attempt: Try flushing the codec
                     try {
                        Logging.w("HardwareVideoEncoder", "Attempting codec flush for stuck recovery");
                        this.codec.flush();
                        this.lastOutputBufferTimeMs = currentTime; // Reset output timer after flush
                     } catch (Exception e) {
                        Logging.e("HardwareVideoEncoder", "Failed to flush codec for recovery", e);
                     }
                  }

                  return VideoCodecStatus.NO_OUTPUT;
               }
            }
         } else {
            // Reset stuck detection when queue is healthy
            if (this.consecutiveStuckChecks > 0) {
               Logging.d("HardwareVideoEncoder", "Encoder recovered, resetting stuck detection (was at "
                     + this.consecutiveStuckChecks + " checks)");
               this.consecutiveStuckChecks = 0;
            }
         }

         // Check for buffer type mismatches early - don't queue builders for frames
         // we'll drop
         if (this.useSurfaceMode && !isTextureBuffer) {
            this.consecutiveBufferTypeMismatches++;
            Logging.d("HardwareVideoEncoder",
                  "Buffer type mismatch (useSurfaceMode: " + this.useSurfaceMode + ", isTextureBuffer: "
                        + isTextureBuffer + "), dropping frame. Mismatch count: "
                        + this.consecutiveBufferTypeMismatches);
            return VideoCodecStatus.NO_OUTPUT;
         }
         if (!this.useSurfaceMode && isTextureBuffer) {
            this.consecutiveBufferTypeMismatches++;
            Logging.d("HardwareVideoEncoder",
                  "Buffer type mismatch (useSurfaceMode: " + this.useSurfaceMode + ", isTextureBuffer: "
                        + isTextureBuffer + "), dropping frame. Mismatch count: "
                        + this.consecutiveBufferTypeMismatches);
            return VideoCodecStatus.NO_OUTPUT;
         }

         // Reset consecutive mismatches when buffer types align
         this.consecutiveBufferTypeMismatches = 0;

         boolean requestedKeyFrame = false;
         EncodedImage.FrameType[] var8 = encodeInfo.frameTypes;
         int var9 = var8.length;

         for (int var10 = 0; var10 < var9; ++var10) {
            EncodedImage.FrameType frameType = var8[var10];
            if (frameType == EncodedImage.FrameType.VideoFrameKey) {
               requestedKeyFrame = true;
            }
         }

         if (requestedKeyFrame || this.shouldForceKeyFrame(videoFrame.getTimestampNs())) {
            this.requestKeyFrame(videoFrame.getTimestampNs());
         }

         // Prepare builder but don't add to queue yet
         EncodedImage.Builder builder = EncodedImage.builder().setCaptureTimeNs(videoFrame.getTimestampNs())
               .setEncodedWidth(videoFrame.getBuffer().getWidth()).setEncodedHeight(videoFrame.getBuffer().getHeight())
               .setRotation(videoFrame.getRotation());

         long presentationTimestampUs = this.nextPresentationTimestampUs;
         long frameDurationUs = (long) ((double) TimeUnit.SECONDS.toMicros(1L)
               / this.bitrateAdjuster.getAdjustedFramerateFps());
         this.nextPresentationTimestampUs += frameDurationUs;
         VideoCodecStatus returnValue;

         // Handle mixed buffer types gracefully
         if (this.useSurfaceMode && isTextureBuffer) {
            returnValue = this.encodeTextureBuffer(videoFrame, presentationTimestampUs);
         } else if (!this.useSurfaceMode && !isTextureBuffer) {
            returnValue = this.encodeByteBuffer(videoFrame, presentationTimestampUs);
         } else {
            // This shouldn't happen now since we check above, but just in case
            Logging.e("HardwareVideoEncoder", "Unexpected buffer type mismatch");
            return VideoCodecStatus.NO_OUTPUT;
         }

         // Only add builder to queue if encoding was successful and queue has space
         if (returnValue == VideoCodecStatus.OK) {
            // Final safety check before adding to queue
            if (this.outputBuilders.size() < MAX_ENCODER_Q_SIZE) {
               this.outputBuilders.offer(builder);
               this.lastSuccessfulEncodeTimeMs = System.currentTimeMillis();

               // Update successful frame timestamp and potentially exit recovery mode
               if (this.isInErrorRecoveryMode) {
                  this.lastSuccessfulFrameTimeMs = System.currentTimeMillis();
                  // Check if we can exit recovery mode (successful frame after some recovery
                  // attempts)
                  if (this.errorRecoveryAttempts > 0) {
                     Logging.d("HardwareVideoEncoder", "Successful frame during recovery, resetting error state");
                     this.resetErrorState();
                  }
               } else {
                  this.lastSuccessfulFrameTimeMs = System.currentTimeMillis();
                  this.consecutiveBufferErrors = 0; // Reset on successful encode
               }

               Logging.v("HardwareVideoEncoder",
                     "Frame encoded successfully, queue size: " + this.outputBuilders.size());
            } else {
               Logging.e("HardwareVideoEncoder",
                     "Queue full at final check, dropping encoded frame. Queue size: " + this.outputBuilders.size());
               // This is a buffer overflow condition even after successful encoding
               return this.handleBufferError("queueFullAfterEncode",
                     new RuntimeException("Queue full after successful encode"));
            }
         } else {
            Logging.w("HardwareVideoEncoder",
                  "Encode failed with status: " + returnValue + ", queue size: " + this.outputBuilders.size());

            // Handle encode failures as potential buffer errors
            if (returnValue == VideoCodecStatus.ERROR) {
               return this.handleBufferError("encodeFailed", new RuntimeException("Encode returned ERROR status"));
            }
         }

         // Perform periodic stream health checks
         if (!this.checkStreamHealth()) {
            Logging.w("HardwareVideoEncoder", "Stream health check failed, initiating recovery");
            return this.enterErrorRecoveryMode("Stream health check failed");
         }

         return returnValue;
      }
   }

   private VideoCodecStatus encodeTextureBuffer(VideoFrame videoFrame, long presentationTimestampUs) {
      this.encodeThreadChecker.checkIsOnValidThread();

      try {
         GLES20.glClear(16384);
         VideoFrame derotatedFrame = new VideoFrame(videoFrame.getBuffer(), 0, videoFrame.getTimestampNs());
         this.videoFrameDrawer.drawFrame(derotatedFrame, this.textureDrawer, (Matrix) null);
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
         return this.handleBufferError("dequeueInputBuffer", var9);
      }

      if (index == -1) {
         // Check if this is part of a pattern of buffer unavailability
         if (this.isInErrorRecoveryMode) {
            this.droppedFramesDuringError++;
            Logging.d("HardwareVideoEncoder", "Dropped frame during recovery, no input buffers available ("
                  + this.droppedFramesDuringError + " total)");
         } else {
            Logging.d("HardwareVideoEncoder", "Dropped frame, no input buffers available");
         }
         return VideoCodecStatus.NO_OUTPUT;
      } else {
         ByteBuffer buffer;
         try {
            buffer = this.codec.getInputBuffer(index);
         } catch (IllegalStateException var8) {
            Logging.e("HardwareVideoEncoder", "getInputBuffer with index=" + index + " failed", var8);
            return this.handleBufferError("getInputBuffer", var8);
         }

         if (buffer.capacity() < this.frameSizeBytes) {
            int var10001 = buffer.capacity();
            Logging.e("HardwareVideoEncoder",
                  "Input buffer size: " + var10001 + " is smaller than frame size: " + this.frameSizeBytes);
            return this.handleBufferError("bufferCapacityCheck",
                  new RuntimeException("Buffer too small: " + var10001 + " < " + this.frameSizeBytes));
         } else {
            this.fillInputBuffer(buffer, videoFrame.getBuffer());

            try {
               this.codec.queueInputBuffer(index, 0, this.frameSizeBytes, presentationTimestampUs, 0);

               // Success - reset consecutive errors if not in recovery mode
               if (!this.isInErrorRecoveryMode) {
                  this.consecutiveBufferErrors = 0;
                  this.lastSuccessfulFrameTimeMs = System.currentTimeMillis();
               }

            } catch (IllegalStateException var7) {
               Logging.e("HardwareVideoEncoder", "queueInputBuffer failed", var7);
               return this.handleBufferError("queueInputBuffer", var7);
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

      this.bitrateAdjuster.setTargets(bitrateAllocation.getSum(), (double) framerate);
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

   /**
    * Comprehensive buffer error detection and recovery system.
    * This method handles cases where frame dropping fails and buffer errors occur.
    */
   private VideoCodecStatus handleBufferError(String errorContext, Exception exception) {
      long currentTime = System.currentTimeMillis();
      this.consecutiveBufferErrors++;
      this.lastBufferErrorTimeMs = currentTime;

      Logging.e("HardwareVideoEncoder", "Buffer error in " + errorContext +
            ", consecutive errors: " + this.consecutiveBufferErrors +
            ", recovery mode: " + this.isInErrorRecoveryMode, exception);

      // Check if we need to enter error recovery mode
      if (!this.isInErrorRecoveryMode && this.consecutiveBufferErrors >= MAX_CONSECUTIVE_BUFFER_ERRORS) {
         return this.enterErrorRecoveryMode("Consecutive buffer errors threshold reached");
      }

      // If already in recovery mode, check if we've exceeded max attempts
      if (this.isInErrorRecoveryMode) {
         this.droppedFramesDuringError++;

         if (this.droppedFramesDuringError >= MAX_DROPPED_FRAMES_DURING_ERROR) {
            return this.escalateErrorRecovery("Too many frames dropped during error recovery");
         }

         if (this.errorRecoveryAttempts >= MAX_ERROR_RECOVERY_ATTEMPTS) {
            return this.performCriticalRecovery("Max error recovery attempts exceeded");
         }
      }

      return VideoCodecStatus.NO_OUTPUT;
   }

   /**
    * Enter error recovery mode with progressive recovery strategies.
    */
   private VideoCodecStatus enterErrorRecoveryMode(String reason) {
      this.isInErrorRecoveryMode = true;
      this.errorRecoveryAttempts = 0;
      this.droppedFramesDuringError = 0;

      // Enable frame synchronization to prevent corrupted frames
      this.requiresFrameSync = true;
      this.framesSinceRecovery = 0;
      this.pendingKeyframeRequest = false;

      Logging.w("HardwareVideoEncoder", "Entering error recovery mode: " + reason + ", frame sync enabled");

      return this.attemptProgressiveRecovery();
   }

   /**
    * Progressive recovery attempts with increasing severity.
    */
   private VideoCodecStatus attemptProgressiveRecovery() {
      this.errorRecoveryAttempts++;
      long currentTime = System.currentTimeMillis();

      Logging.w("HardwareVideoEncoder", "Attempting recovery #" + this.errorRecoveryAttempts);

      try {
         switch (this.errorRecoveryAttempts) {
            case 1:
               // Level 1: Clear queues and request keyframe
               return this.performLightRecovery();

            case 2:
               // Level 2: Flush codec and reset timing
               return this.performMediumRecovery();

            case 3:
               // Level 3: Reset codec with current settings
               return this.performHeavyRecovery();

            case 4:
               // Level 4: Reduce quality and reset
               return this.performQualityReductionRecovery();

            default:
               // Level 5: Critical recovery
               return this.performCriticalRecovery("Progressive recovery failed");
         }
      } catch (Exception e) {
         Logging.e("HardwareVideoEncoder", "Recovery attempt #" + this.errorRecoveryAttempts + " failed", e);
         return this.escalateErrorRecovery("Recovery attempt threw exception");
      }
   }

   /**
    * Level 1 Recovery: Light recovery with queue clearing and keyframe request.
    */
   private VideoCodecStatus performLightRecovery() {
      Logging.w("HardwareVideoEncoder", "Performing light recovery: clearing queues and requesting keyframe");

      // Clear output builders queue
      int clearedBuilders = this.outputBuilders.size();
      this.outputBuilders.clear();

      // Reset timing variables
      long currentTime = System.currentTimeMillis();
      this.lastSuccessfulEncodeTimeMs = currentTime;
      this.lastOutputBufferTimeMs = currentTime;
      this.queueFullSinceMs = 0;

      // Request immediate keyframe
      try {
         Bundle params = new Bundle();
         params.putInt("request-sync", 0);
         this.codec.setParameters(params);

         Logging.d("HardwareVideoEncoder",
               "Light recovery completed: cleared " + clearedBuilders + " builders, requested keyframe");
         return VideoCodecStatus.OK;
      } catch (Exception e) {
         Logging.e("HardwareVideoEncoder", "Failed to request keyframe during light recovery", e);
         return this.escalateErrorRecovery("Light recovery keyframe request failed");
      }
   }

   /**
    * Level 2 Recovery: Medium recovery with codec flush.
    */
   private VideoCodecStatus performMediumRecovery() {
      Logging.w("HardwareVideoEncoder", "Performing medium recovery: flushing codec");

      try {
         // Flush the codec to clear internal buffers
         this.codec.flush();

         // Clear our queues
         this.outputBuilders.clear();

         // Reset all timing and state variables
         long currentTime = System.currentTimeMillis();
         this.lastSuccessfulEncodeTimeMs = currentTime;
         this.lastOutputBufferTimeMs = currentTime;
         this.queueFullSinceMs = 0;
         this.consecutiveStuckChecks = 0;
         this.consecutiveBufferTypeMismatches = 0;

         Logging.d("HardwareVideoEncoder", "Medium recovery completed: codec flushed, state reset");
         return VideoCodecStatus.OK;
      } catch (Exception e) {
         Logging.e("HardwareVideoEncoder", "Failed to flush codec during medium recovery", e);
         return this.escalateErrorRecovery("Medium recovery codec flush failed");
      }
   }

   /**
    * Level 3 Recovery: Heavy recovery with codec reset.
    */
   private VideoCodecStatus performHeavyRecovery() {
      Logging.w("HardwareVideoEncoder", "Performing heavy recovery: resetting codec");

      try {
         VideoCodecStatus resetStatus = this.resetCodec(this.width, this.height, this.useSurfaceMode);
         if (resetStatus == VideoCodecStatus.OK) {
            // Reset all error tracking
            this.resetErrorState();
            Logging.d("HardwareVideoEncoder", "Heavy recovery completed: codec reset successful");
            return VideoCodecStatus.OK;
         } else {
            Logging.e("HardwareVideoEncoder", "Heavy recovery failed: codec reset returned " + resetStatus);
            return this.escalateErrorRecovery("Heavy recovery codec reset failed");
         }
      } catch (Exception e) {
         Logging.e("HardwareVideoEncoder", "Exception during heavy recovery", e);
         return this.escalateErrorRecovery("Heavy recovery threw exception");
      }
   }

   /**
    * Level 4 Recovery: Quality reduction recovery.
    */
   private VideoCodecStatus performQualityReductionRecovery() {
      Logging.w("HardwareVideoEncoder", "Performing quality reduction recovery: reducing bitrate and framerate");

      try {
         // Reduce bitrate by 50%
         int reducedBitrate = this.adjustedBitrate / 2;
         this.bitrateAdjuster.setTargets(reducedBitrate, this.bitrateAdjuster.getAdjustedFramerateFps() * 0.75);

         // Reset codec with reduced quality
         VideoCodecStatus resetStatus = this.resetCodec(this.width, this.height, this.useSurfaceMode);
         if (resetStatus == VideoCodecStatus.OK) {
            this.resetErrorState();
            Logging.d("HardwareVideoEncoder",
                  "Quality reduction recovery completed: bitrate reduced to " + reducedBitrate);
            return VideoCodecStatus.OK;
         } else {
            Logging.e("HardwareVideoEncoder", "Quality reduction recovery failed: codec reset returned " + resetStatus);
            return this.escalateErrorRecovery("Quality reduction recovery failed");
         }
      } catch (Exception e) {
         Logging.e("HardwareVideoEncoder", "Exception during quality reduction recovery", e);
         return this.escalateErrorRecovery("Quality reduction recovery threw exception");
      }
   }

   /**
    * Level 5 Recovery: Critical recovery - last resort.
    */
   private VideoCodecStatus performCriticalRecovery(String reason) {
      Logging.e("HardwareVideoEncoder", "Performing critical recovery: " + reason);

      try {
         // Force complete shutdown and restart
         this.running = false;

         // Release everything
         VideoCodecStatus releaseStatus = this.release();
         if (releaseStatus != VideoCodecStatus.OK) {
            Logging.e("HardwareVideoEncoder", "Critical recovery: release failed with status " + releaseStatus);
         }

         // Reset to minimal quality settings
         int minBitrate = Math.max(this.adjustedBitrate / 4, 100000); // Minimum 100kbps
         this.bitrateAdjuster.setTargets(minBitrate, 15.0); // 15 fps

         // Reinitialize
         VideoCodecStatus initStatus = this.initEncodeInternal();
         if (initStatus == VideoCodecStatus.OK) {
            this.resetErrorState();
            this.running = true;
            Logging.w("HardwareVideoEncoder",
                  "Critical recovery completed: encoder reinitialized with minimal quality");
            return VideoCodecStatus.OK;
         } else {
            Logging.e("HardwareVideoEncoder", "Critical recovery failed: initialization returned " + initStatus);
            return VideoCodecStatus.ERROR;
         }
      } catch (Exception e) {
         Logging.e("HardwareVideoEncoder", "Critical recovery threw exception", e);
         return VideoCodecStatus.ERROR;
      }
   }

   /**
    * Escalate error recovery to the next level.
    */
   private VideoCodecStatus escalateErrorRecovery(String reason) {
      Logging.w("HardwareVideoEncoder", "Escalating error recovery: " + reason);
      return this.attemptProgressiveRecovery();
   }

   /**
    * Reset all error tracking state.
    */
   private void resetErrorState() {
      this.consecutiveBufferErrors = 0;
      this.isInErrorRecoveryMode = false;
      this.errorRecoveryAttempts = 0;
      this.droppedFramesDuringError = 0;
      this.lastSuccessfulFrameTimeMs = System.currentTimeMillis();
      this.consecutiveStuckChecks = 0;
      this.consecutiveBufferTypeMismatches = 0;
      this.queueFullSinceMs = 0;

      // Reset frame synchronization state
      this.requiresFrameSync = false;
      this.framesSinceRecovery = 0;
      this.pendingKeyframeRequest = false;
      this.lastKeyframeRequestTimeMs = 0;
   }

   /**
    * Check overall stream health and trigger recovery if needed.
    */
   private boolean checkStreamHealth() {
      long currentTime = System.currentTimeMillis();

      // Check if we haven't had a successful frame in too long
      if (this.lastSuccessfulFrameTimeMs > 0 &&
            currentTime - this.lastSuccessfulFrameTimeMs > STREAM_HEALTH_CHECK_INTERVAL_MS) {
         Logging.w("HardwareVideoEncoder", "Stream health check failed: no successful frames for " +
               (currentTime - this.lastSuccessfulFrameTimeMs) + "ms");
         return false;
      }

      // Check if we're stuck in error recovery mode too long
      if (this.isInErrorRecoveryMode &&
            currentTime - this.lastBufferErrorTimeMs > BUFFER_ERROR_RECOVERY_TIMEOUT_MS) {
         Logging.w("HardwareVideoEncoder", "Stream health check failed: stuck in error recovery for " +
               (currentTime - this.lastBufferErrorTimeMs) + "ms");
         return false;
      }

      return true;
   }

   /**
    * Handle frame synchronization after recovery to prevent corrupted frames.
    * This ensures we get clean frames from the camera before resuming normal
    * encoding.
    */
   private boolean handleFrameSynchronization(VideoFrame videoFrame) {
      if (!this.requiresFrameSync) {
         return true; // No synchronization needed
      }

      // Check if we need to request a keyframe
      long currentTime = System.currentTimeMillis();
      if (!this.pendingKeyframeRequest ||
            currentTime - this.lastKeyframeRequestTimeMs > KEYFRAME_REQUEST_TIMEOUT_MS) {
         this.requestKeyFrameForRecovery();
         this.pendingKeyframeRequest = true;
         this.lastKeyframeRequestTimeMs = currentTime;
      }

      // Validate frame integrity
      if (this.isFrameValid(videoFrame)) {
         this.framesSinceRecovery++;
         Logging.d("HardwareVideoEncoder",
               "Valid frame during sync: " + this.framesSinceRecovery + "/" + FRAME_SYNC_THRESHOLD);

         if (this.framesSinceRecovery >= FRAME_SYNC_THRESHOLD) {
            // We've received enough clean frames, exit sync mode
            this.requiresFrameSync = false;
            this.framesSinceRecovery = 0;
            this.pendingKeyframeRequest = false;
            Logging.d("HardwareVideoEncoder", "Frame synchronization completed, resuming normal encoding");
            return true;
         }
      } else {
         // Reset counter if we get a potentially corrupted frame
         this.framesSinceRecovery = 0;
         Logging.w("HardwareVideoEncoder", "Potentially corrupted frame detected during sync, resetting counter");
      }

      // During sync, we encode frames but monitor them closely
      return true;
   }

   /**
    * Validate frame integrity to detect potential corruption.
    */
   private boolean isFrameValid(VideoFrame videoFrame) {
      if (videoFrame == null || videoFrame.getBuffer() == null) {
         return false;
      }

      VideoFrame.Buffer buffer = videoFrame.getBuffer();

      // Basic validation checks
      if (buffer.getWidth() <= 0 || buffer.getHeight() <= 0) {
         return false;
      }

      // Check for reasonable dimensions (not corrupted)
      if (buffer.getWidth() > 4096 || buffer.getHeight() > 4096) {
         Logging.w("HardwareVideoEncoder", "Frame dimensions seem corrupted: " +
               buffer.getWidth() + "x" + buffer.getHeight());
         return false;
      }

      // Additional validation for texture buffers
      if (buffer instanceof VideoFrame.TextureBuffer) {
         VideoFrame.TextureBuffer textureBuffer = (VideoFrame.TextureBuffer) buffer;
         if (textureBuffer.getTextureId() <= 0) {
            return false;
         }
      }

      return true;
   }

   /**
    * Request keyframe specifically for recovery purposes with enhanced error
    * handling.
    */
   private void requestKeyFrameForRecovery() {
      this.encodeThreadChecker.checkIsOnValidThread();

      try {
         Bundle params = new Bundle();
         params.putInt("request-sync", 0);

         // Add additional parameters for recovery
         params.putInt("drop-input-frames", 1); // Drop any pending input frames
         params.putInt("prepend-sps-pps-to-idr-frames", 1); // Ensure SPS/PPS are included

         this.codec.setParameters(params);

         // Reset keyframe timestamp to force immediate keyframe
         this.lastKeyFrameNs = 0;

         Logging.d("HardwareVideoEncoder", "Recovery keyframe requested with enhanced parameters");
      } catch (Exception e) {
         Logging.e("HardwareVideoEncoder", "Failed to request recovery keyframe", e);
         // If keyframe request fails, we'll rely on natural keyframe interval
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

   private Thread createOutputThread() {
      return new Thread() {
         public void run() {
            while (HardwareVideoEncoder.this.running) {
               HardwareVideoEncoder.this.deliverEncodedImage();
            }

            HardwareVideoEncoder.this.releaseCodecOnOutputThread();
         }
      };
   }

   protected void deliverEncodedImage() {
      this.outputThreadChecker.checkIsOnValidThread();

      try {
         BufferInfo info = new BufferInfo();
         int index = this.codec.dequeueOutputBuffer(info, 100000L);
         if (index < 0) {
            if (index == -3) {
               // Don't wait indefinitely - this can cause the output thread to hang
               Logging.d("HardwareVideoEncoder", "Output format changed, waiting for buffers to be released");
               // this.outputBuffersBusyCount.waitForZero(); // Removed to prevent hanging
            } else if (index == -1) {
               Logging.v("HardwareVideoEncoder",
                     "No output buffer available, queue size: " + this.outputBuilders.size());
            } else {
               Logging.d("HardwareVideoEncoder",
                     "dequeueOutputBuffer returned: " + index + ", queue size: " + this.outputBuilders.size());
            }

            // Check if we have a backlog of builders but no output - this indicates encoder
            // issues
            if (this.outputBuilders.size() >= MAX_ENCODER_Q_SIZE) {
               long currentTime = System.currentTimeMillis();
               if (currentTime - this.lastOutputBufferTimeMs > ENCODER_STUCK_TIMEOUT_MS) {
                  Logging.w("HardwareVideoEncoder", "Encoder not producing output with " + this.outputBuilders.size()
                        + " builders queued for " + (currentTime - this.lastOutputBufferTimeMs) + "ms");

                  // This could indicate a buffer error condition
                  if (!this.isInErrorRecoveryMode) {
                     this.handleBufferError("outputBufferStuck", new RuntimeException(
                           "Output buffer stuck for " + (currentTime - this.lastOutputBufferTimeMs) + "ms"));
                  }
               }
            }

            return;
         }

         // Update output buffer timestamp when we successfully get a buffer
         this.lastOutputBufferTimeMs = System.currentTimeMillis();

         // Check if we have builders available
         if (this.outputBuilders.isEmpty()) {
            Logging.w("HardwareVideoEncoder",
                  "MediaCodec produced output but no builders available, dropping frame. Index: " + index);
            this.codec.releaseOutputBuffer(index, false);
            return;
         }

         ByteBuffer outputBuffer = this.codec.getOutputBuffer(index);
         outputBuffer.position(info.offset);
         outputBuffer.limit(info.offset + info.size);
         if ((info.flags & 2) != 0) {
            Logging.d("HardwareVideoEncoder",
                  "Config frame generated. Offset: " + info.offset + ". Size: " + info.size);
            if (info.size > 0
                  && (this.codecType == VideoCodecMimeType.H264 || this.codecType == VideoCodecMimeType.H265)) {
               this.configBuffer = ByteBuffer.allocateDirect(info.size);
               this.configBuffer.put(outputBuffer);
            }

            // Release the config frame buffer and return without consuming a builder
            this.codec.releaseOutputBuffer(index, false);
            return;
         }

         this.bitrateAdjuster.reportEncodedFrame(info.size);
         if (this.adjustedBitrate != this.bitrateAdjuster.getAdjustedBitrateBps()) {
            this.updateBitrate();
         }

         boolean isKeyFrame = (info.flags & 1) != 0;
         if (isKeyFrame) {
            Logging.d("HardwareVideoEncoder", "Sync frame generated");
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
            int var10001 = this.configBuffer.capacity();
            Logging.d("HardwareVideoEncoder", "Prepending config buffer of size " + var10001
                  + " to output buffer with offset " + info.offset + ", size " + info.size);
            frameBuffer = ByteBuffer.allocateDirect(info.size + this.configBuffer.capacity());
            this.configBuffer.rewind();
            frameBuffer.put(this.configBuffer);
            frameBuffer.put(outputBuffer);
            frameBuffer.rewind();
            this.codec.releaseOutputBuffer(index, false);
            releaseCallback = null;
         } else {
            frameBuffer = outputBuffer.slice();
            this.outputBuffersBusyCount.increment();
            releaseCallback = () -> {
               try {
                  this.codec.releaseOutputBuffer(index, false);
               } catch (Exception var3) {
                  Logging.e("HardwareVideoEncoder", "releaseOutputBuffer failed", var3);
               }

               this.outputBuffersBusyCount.decrement();
            };
         }

         EncodedImage.FrameType frameType = isKeyFrame ? EncodedImage.FrameType.VideoFrameKey
               : EncodedImage.FrameType.VideoFrameDelta;
         EncodedImage.Builder builder = (EncodedImage.Builder) this.outputBuilders.poll();
         builder.setBuffer(frameBuffer, releaseCallback);
         builder.setFrameType(frameType);
         builder.setQp(qp);
         EncodedImage encodedImage = builder.createEncodedImage();
         this.callback.onEncodedFrame(encodedImage, new VideoEncoder.CodecSpecificInfo());
         encodedImage.release();

         // Update successful output timestamp
         if (!this.isInErrorRecoveryMode) {
            this.lastSuccessfulFrameTimeMs = System.currentTimeMillis();
         }

      } catch (IllegalStateException var11) {
         Logging.e("HardwareVideoEncoder", "deliverOutput failed", var11);
         // Handle this as a buffer error since it indicates codec state issues
         this.handleBufferError("deliverOutput", var11);
      } catch (Exception var12) {
         Logging.e("HardwareVideoEncoder", "Unexpected error in deliverOutput", var12);
         // Handle any other unexpected errors as potential buffer issues
         this.handleBufferError("deliverOutputUnexpected", var12);
      }

   }

   private void releaseCodecOnOutputThread() {
      this.outputThreadChecker.checkIsOnValidThread();
      Logging.d("HardwareVideoEncoder", "Releasing MediaCodec on output thread");
      this.outputBuffersBusyCount.waitForZero();

      try {
         this.codec.stop();
      } catch (Exception var3) {
         Logging.e("HardwareVideoEncoder", "Media encoder stop failed", var3);
      }

      try {
         this.codec.release();
      } catch (Exception var2) {
         Logging.e("HardwareVideoEncoder", "Media encoder release failed", var2);
         this.shutdownException = var2;
      }

      this.configBuffer = null;
      Logging.d("HardwareVideoEncoder", "Release on output thread done");
   }

   private VideoCodecStatus updateBitrate() {
      this.outputThreadChecker.checkIsOnValidThread();
      this.adjustedBitrate = this.bitrateAdjuster.getAdjustedBitrateBps();

      try {
         Bundle params = new Bundle();
         params.putInt("video-bitrate", this.adjustedBitrate);
         this.codec.setParameters(params);
         return VideoCodecStatus.OK;
      } catch (IllegalStateException var2) {
         Logging.e("HardwareVideoEncoder", "updateBitrate failed", var2);
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

      Logging.d("HardwareVideoEncoder",
            "updateInputFormat format: " + format + " stride: " + this.stride + " sliceHeight: " + this.sliceHeight
                  + " isSemiPlanar: " + this.isSemiPlanar + " frameSizeBytes: " + this.frameSizeBytes);
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
         YuvHelper.I420ToNV12(i420.getDataY(), i420.getStrideY(), i420.getDataU(), i420.getStrideU(), i420.getDataV(),
               i420.getStrideV(), buffer, i420.getWidth(), i420.getHeight(), this.stride, this.sliceHeight);
      } else {
         YuvHelper.I420Copy(i420.getDataY(), i420.getStrideY(), i420.getDataU(), i420.getStrideU(), i420.getDataV(),
               i420.getStrideV(), buffer, i420.getWidth(), i420.getHeight(), this.stride, this.sliceHeight);
      }

      i420.release();
   }

   protected boolean isSemiPlanar(int colorFormat) {
      switch (colorFormat) {
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

   private static class BusyCount {
      private final Object countLock = new Object();
      private int count;

      public void increment() {
         synchronized (this.countLock) {
            ++this.count;
         }
      }

      public void decrement() {
         synchronized (this.countLock) {
            --this.count;
            if (this.count == 0) {
               this.countLock.notifyAll();
            }

         }
      }

      public void waitForZero() {
         boolean wasInterrupted = false;
         synchronized (this.countLock) {
            while (this.count > 0) {
               try {
                  this.countLock.wait();
               } catch (InterruptedException var5) {
                  Logging.e("HardwareVideoEncoder", "Interrupted while waiting on busy count", var5);
                  wasInterrupted = true;
               }
            }
         }

         if (wasInterrupted) {
            Thread.currentThread().interrupt();
         }

      }
   }
}
