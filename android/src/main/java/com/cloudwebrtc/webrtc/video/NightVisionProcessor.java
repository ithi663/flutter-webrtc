package com.cloudwebrtc.webrtc.video;

import android.util.Log;

import org.webrtc.JavaI420Buffer;
import org.webrtc.VideoFrame;

import java.nio.ByteBuffer;

/**
 * Night Vision Video Frame Processor
 *
 * Implements ExternalVideoFrameProcessing interface for local video track
 * processing.
 * Uses NightVisionRenderer for GPU-accelerated real-time night vision
 * enhancement.
 *
 * @deprecated Android now uses a GPU drawer-based renderer path exclusively.
 *             The CPU processing path is disabled and this processor is no longer
 *             invoked from the Android handlers. Kept for compatibility only.
 */
@Deprecated
public class NightVisionProcessor implements com.cloudwebrtc.webrtc.video.LocalVideoTrack.ExternalVideoFrameProcessing {
    private static final String TAG = "NightVisionProcessor";

    // Night vision configuration
    private float intensity = 0.6f;
    private float gamma = 0.4f;
    private float brightnessThreshold = 0.3f;
    private float contrast = 1.8f;
    private float noiseReduction = 0.3f;
    private boolean enabled = false;

    // Performance statistics
    private long frameCount = 0;
    private long lastStatsTime = 0;
    private long totalProcessingTime = 0;
    private NightVisionRenderer renderer;

    // Lookup tables for performance optimization
    private int[] gammaLUT = new int[256];
    private int[] contrastLUT = new int[256];
    private boolean lutNeedsUpdate = true;

    public NightVisionProcessor() {
        renderer = new NightVisionRenderer();
        updateLookupTables();
        Log.d(TAG, "NightVisionProcessor created with CPU processing");
    }

    @Override
    public VideoFrame onFrame(VideoFrame frame) {
        if (!enabled || intensity <= 0.0f) {
            return frame; // Pass through without processing
        }

        long startTime = System.nanoTime();

        try {
            // Update lookup tables if parameters changed (only when needed)
            if (lutNeedsUpdate) {
                updateLookupTables();
                lutNeedsUpdate = false;
            }

            // Log processing info occasionally
            if (frameCount % 30 == 0) { // Every 30 frames (~1 second at 30fps)
                Log.d(TAG, String.format("Processing frame %d: %dx%d, buffer type: %s",
                        frameCount, frame.getBuffer().getWidth(), frame.getBuffer().getHeight(),
                        frame.getBuffer().getClass().getSimpleName()));
            }

            // Process the frame based on buffer type
            VideoFrame processedFrame = processVideoFrame(frame);

            updateStats(System.nanoTime() - startTime);
            return processedFrame;

        } catch (Exception e) {
            Log.e(TAG, "Error processing frame", e);
            return frame; // Return original frame on error
        }
    }

    /**
     * Process video frame based on buffer type
     */
    private VideoFrame processVideoFrame(VideoFrame frame) {
        VideoFrame.Buffer buffer = frame.getBuffer();

        if (buffer instanceof VideoFrame.I420Buffer) {
            return processI420Frame((VideoFrame.I420Buffer) buffer, frame);
        } else if (buffer instanceof VideoFrame.TextureBuffer) {
            // For TextureBuffer, try converting to I420 and processing
            try {
                VideoFrame.I420Buffer i420Buffer = buffer.toI420();
                if (i420Buffer != null) {
                    VideoFrame processedFrame = processI420Frame(i420Buffer, frame);
                    i420Buffer.release();
                    return processedFrame;
                } else {
                    Log.w(TAG, "Failed to convert TextureBuffer to I420");
                    return frame;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error converting TextureBuffer to I420", e);
                return frame;
            }
        } else {
            Log.w(TAG, "Unsupported buffer type: " + buffer.getClass().getSimpleName() +
                    ", trying to convert to I420");
            // Try to convert any buffer type to I420
            try {
                VideoFrame.I420Buffer i420Buffer = buffer.toI420();
                if (i420Buffer != null) {
                    VideoFrame processedFrame = processI420Frame(i420Buffer, frame);
                    i420Buffer.release();
                    return processedFrame;
                } else {
                    Log.w(TAG, "Could not convert buffer to I420, passing through");
                    return frame;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error converting unknown buffer type to I420", e);
                return frame;
            }
        }
    }

    /**
     * Process I420 video frame with night vision enhancement
     */
    private VideoFrame processI420Frame(VideoFrame.I420Buffer i420Buffer, VideoFrame originalFrame) {
        try {
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();

            // Get original buffers
            ByteBuffer yPlane = i420Buffer.getDataY();
            ByteBuffer uPlane = i420Buffer.getDataU();
            ByteBuffer vPlane = i420Buffer.getDataV();

            int yStride = i420Buffer.getStrideY();
            int uStride = i420Buffer.getStrideU();
            int vStride = i420Buffer.getStrideV();

            // Log processing details occasionally
            if (frameCount % 60 == 0) { // Every 60 frames (~2 seconds at 30fps)
                Log.d(TAG,
                        String.format(
                                "Processing I420 frame: %dx%d, Y:%d/%d, U:%d/%d, V:%d/%d, strides Y:%d U:%d V:%d",
                                width, height,
                                yPlane.capacity(), yStride * height,
                                uPlane.capacity(), uStride * ((height + 1) / 2),
                                vPlane.capacity(), vStride * ((height + 1) / 2),
                                yStride, uStride, vStride));
            }

            // Validate buffer sizes to prevent underflow
            int expectedYSize = yStride * height;
            int expectedUSize = uStride * ((height + 1) / 2);
            int expectedVSize = vStride * ((height + 1) / 2);

            if (yPlane.capacity() < expectedYSize || uPlane.capacity() < expectedUSize
                    || vPlane.capacity() < expectedVSize) {
                Log.w(TAG,
                        String.format("Buffer size mismatch - Y: %d < %d, U: %d < %d, V: %d < %d, falling back to copy",
                                yPlane.capacity(), expectedYSize, uPlane.capacity(), expectedUSize, vPlane.capacity(),
                                expectedVSize));
                return originalFrame; // Just return original frame if sizes don't match
            }

            // Create new buffers for processed data
            ByteBuffer processedY = ByteBuffer.allocateDirect(yPlane.capacity());
            ByteBuffer processedU = ByteBuffer.allocateDirect(uPlane.capacity());
            ByteBuffer processedV = ByteBuffer.allocateDirect(vPlane.capacity());

            // Process Y plane (luminance) with night vision enhancement
            enhanceYPlane(yPlane, processedY, width, height, yStride);

            // Process UV planes for color adjustments
            int uvWidth = (width + 1) / 2;
            int uvHeight = (height + 1) / 2;
            enhanceUVPlanes(uPlane, vPlane, processedU, processedV, uvWidth, uvHeight, uStride, vStride);

            // Reset buffer positions
            processedY.rewind();
            processedU.rewind();
            processedV.rewind();

            // Create new I420Buffer with processed data
            VideoFrame.I420Buffer processedBuffer = JavaI420Buffer.wrap(
                    width, height,
                    processedY, yStride,
                    processedU, uStride,
                    processedV, vStride,
                    null // release callback
            );

            return new VideoFrame(processedBuffer, originalFrame.getRotation(), originalFrame.getTimestampNs());

        } catch (Exception e) {
            Log.e(TAG, "Failed to process I420 frame", e);
            return originalFrame;
        }
    }

    /**
     * Enhance Y plane (luminance) with night vision effects
     */
    private void enhanceYPlane(ByteBuffer source, ByteBuffer dest, int width, int height, int stride) {
        try {
            source.rewind();
            dest.clear();

            for (int y = 0; y < height; y++) {
                // Calculate how many bytes we can safely read for this row
                int remaining = source.remaining();
                int bytesToRead = Math.min(stride, remaining);

                if (bytesToRead <= 0) {
                    Log.w(TAG, String.format("Not enough bytes remaining at Y row %d: %d", y, remaining));
                    break;
                }

                byte[] rowBuffer = new byte[bytesToRead];
                source.get(rowBuffer, 0, bytesToRead);

                // Process pixels in this row (but only up to width)
                int pixelsToProcess = Math.min(width, bytesToRead);

                for (int x = 0; x < pixelsToProcess; x++) {
                    int pixel = rowBuffer[x] & 0xFF;

                    // Apply night vision enhancement using lookup tables
                    int enhanced = gammaLUT[pixel];

                    // Apply contrast enhancement
                    enhanced = contrastLUT[enhanced];

                    // Additional boost for very dark pixels
                    if (pixel < (brightnessThreshold * 255)) {
                        float darkBoost = intensity * (1.0f - pixel / (brightnessThreshold * 255));
                        enhanced = (int) (enhanced + darkBoost * 60); // Boost dark areas
                    }

                    // Clamp to valid range
                    enhanced = Math.max(0, Math.min(255, enhanced));
                    rowBuffer[x] = (byte) enhanced;
                }

                dest.put(rowBuffer, 0, bytesToRead);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing Y plane", e);
            // Copy source to dest as fallback
            try {
                source.rewind();
                dest.clear();
                dest.put(source);
            } catch (Exception fallbackError) {
                Log.e(TAG, "Fallback Y copy also failed", fallbackError);
            }
        }
    }

    /**
     * Enhance UV planes with night vision color characteristics
     */
    private void enhanceUVPlanes(ByteBuffer sourceU, ByteBuffer sourceV, ByteBuffer destU, ByteBuffer destV,
            int width, int height, int strideU, int strideV) {
        try {
            sourceU.rewind();
            sourceV.rewind();
            destU.clear();
            destV.clear();

            // Reduce saturation and add green tint for night vision effect
            float saturationReduction = intensity * 0.2f;
            float greenTint = intensity * 0.1f;

            // Process row by row, being careful with buffer bounds
            for (int y = 0; y < height; y++) {
                // Calculate how many bytes we can safely read for this row
                int remainingU = sourceU.remaining();
                int remainingV = sourceV.remaining();
                int bytesToReadU = Math.min(strideU, remainingU);
                int bytesToReadV = Math.min(strideV, remainingV);

                if (bytesToReadU <= 0 || bytesToReadV <= 0) {
                    Log.w(TAG, String.format("Not enough bytes remaining at row %d: U=%d, V=%d", y, remainingU,
                            remainingV));
                    break;
                }

                byte[] rowBufferU = new byte[bytesToReadU];
                byte[] rowBufferV = new byte[bytesToReadV];

                sourceU.get(rowBufferU, 0, bytesToReadU);
                sourceV.get(rowBufferV, 0, bytesToReadV);

                // Process pixels in this row (but only up to width)
                int pixelsToProcess = Math.min(width, Math.min(bytesToReadU, bytesToReadV));

                for (int x = 0; x < pixelsToProcess; x++) {
                    // U component (Cb - blue-yellow axis)
                    int u = rowBufferU[x] & 0xFF;
                    u = (int) (128 + (u - 128) * (1.0f - saturationReduction));
                    u = Math.max(0, Math.min(255, u));
                    rowBufferU[x] = (byte) u;

                    // V component (Cr - red-green axis) - add green tint
                    int v = rowBufferV[x] & 0xFF;
                    v = (int) (128 + (v - 128) * (1.0f - saturationReduction));
                    v = (int) (v - greenTint * 25); // Reduce red to add green tint
                    v = Math.max(0, Math.min(255, v));
                    rowBufferV[x] = (byte) v;
                }

                destU.put(rowBufferU, 0, bytesToReadU);
                destV.put(rowBufferV, 0, bytesToReadV);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing UV planes", e);
            // Copy source to dest as fallback
            try {
                sourceU.rewind();
                sourceV.rewind();
                destU.clear();
                destV.clear();
                destU.put(sourceU);
                destV.put(sourceV);
            } catch (Exception fallbackError) {
                Log.e(TAG, "Fallback copy also failed", fallbackError);
            }
        }
    }

    /**
     * Update lookup tables for performance optimization
     */
    private void updateLookupTables() {
        // Build gamma correction lookup table
        for (int i = 0; i < 256; i++) {
            float normalized = i / 255.0f;
            float gammaCorrected = (float) Math.pow(normalized, gamma);
            gammaLUT[i] = Math.round(gammaCorrected * 255.0f);
        }

        // Build contrast enhancement lookup table
        for (int i = 0; i < 256; i++) {
            float normalized = i / 255.0f;
            float contrasted = ((normalized - 0.5f) * contrast) + 0.5f;
            contrasted = Math.max(0.0f, Math.min(1.0f, contrasted));
            contrastLUT[i] = Math.round(contrasted * 255.0f);
        }

        Log.d(TAG, "Lookup tables updated for intensity=" + intensity + ", gamma=" + gamma + ", contrast=" + contrast);
    }

    /**
     * Set night vision intensity (0.0 - 1.0)
     */
    public void setIntensity(float intensity) {
        if (Math.abs(this.intensity - intensity) > 0.01f) {
            this.intensity = Math.max(0.0f, Math.min(1.0f, intensity));
            this.gamma = 0.3f + (this.intensity * 0.5f); // Adaptive gamma (0.3-0.8)
            lutNeedsUpdate = true;
            Log.d(TAG, String.format("Night vision intensity set to %.2f, gamma: %.2f",
                    this.intensity, this.gamma));
        }
    }

    /**
     * Set brightness threshold for enhancement (0.0 - 1.0)
     */
    public void setBrightnessThreshold(float threshold) {
        this.brightnessThreshold = Math.max(0.0f, Math.min(1.0f, threshold));
        Log.d(TAG, String.format("Brightness threshold set to %.2f", this.brightnessThreshold));
    }

    /**
     * Set contrast enhancement factor (0.5 - 3.0)
     */
    public void setContrast(float contrast) {
        if (Math.abs(this.contrast - contrast) > 0.01f) {
            this.contrast = Math.max(0.5f, Math.min(3.0f, contrast));
            lutNeedsUpdate = true;
            Log.d(TAG, String.format("Contrast set to %.2f", this.contrast));
        }
    }

    /**
     * Set noise reduction level (0.0 - 1.0)
     */
    public void setNoiseReduction(float noiseReduction) {
        this.noiseReduction = Math.max(0.0f, Math.min(1.0f, noiseReduction));
        Log.d(TAG, String.format("Noise reduction set to %.2f", this.noiseReduction));
    }

    /**
     * Enable or disable night vision processing
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.d(TAG, String.format("Night vision %s", enabled ? "enabled" : "disabled"));

        if (enabled) {
            // Reset stats when enabling
            frameCount = 0;
            totalProcessingTime = 0;
            lastStatsTime = System.currentTimeMillis();
        }
    }

    /**
     * Check if night vision is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get current intensity
     */
    public float getIntensity() {
        return intensity;
    }

    /**
     * Get current gamma value
     */
    public float getGamma() {
        return gamma;
    }

    /**
     * Get current brightness threshold
     */
    public float getBrightnessThreshold() {
        return brightnessThreshold;
    }

    /**
     * Get current contrast value
     */
    public float getContrast() {
        return contrast;
    }

    /**
     * Get current noise reduction level
     */
    public float getNoiseReduction() {
        return noiseReduction;
    }

    /**
     * Get the underlying night vision renderer for use in rendering pipeline
     */
    public NightVisionRenderer getRenderer() {
        return renderer;
    }

    /**
     * Update performance statistics
     */
    private void updateStats(long processingTimeNs) {
        frameCount++;
        totalProcessingTime += processingTimeNs;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastStatsTime >= 5000) { // Log every 5 seconds
            double avgFrameTime = (double) totalProcessingTime / frameCount / 1_000_000.0; // Convert to ms
            double fps = 1000.0 / avgFrameTime;

            Log.d(TAG, String.format(
                    "Night vision processor stats: %.1f FPS, %.2f ms avg frame time, %d frames processed, enabled=%s",
                    fps, avgFrameTime, frameCount, enabled));

            frameCount = 0;
            totalProcessingTime = 0;
            lastStatsTime = currentTime;
        }
    }

    /**
     * Get performance statistics
     */
    public String getPerformanceStats() {
        if (frameCount > 0) {
            double avgFrameTime = (double) totalProcessingTime / frameCount / 1_000_000.0;
            double fps = 1000.0 / avgFrameTime;
            return String.format("NightVisionProcessor: %.1f FPS, %.2f ms avg", fps, avgFrameTime);
        }
        return renderer.getPerformanceStats();
    }

    /**
     * Release resources
     */
    public void release() {
        Log.d(TAG, "Releasing night vision processor resources");
        if (renderer != null) {
            renderer.release();
            renderer = null;
        }
        enabled = false;
        Log.d(TAG, "Night vision processor resources released");
    }
}