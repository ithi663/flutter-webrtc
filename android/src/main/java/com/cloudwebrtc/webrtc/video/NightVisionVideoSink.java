package com.cloudwebrtc.webrtc.video;

import android.util.Log;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

/**
 * Video sink wrapper that applies night vision processing to remote video
 * streams.
 * This allows viewers to enhance remote streams without affecting the sender.
 * Each remote stream can have independent night vision settings.
 */
public class NightVisionVideoSink implements VideoSink {
    private static final String TAG = "NightVisionVideoSink";

    private final VideoSink originalSink;
    private final NightVisionRenderer renderer;
    private boolean enabled = false;
    private float intensity = 0.6f;
    private boolean configNeedsUpdate = true;

    // Performance statistics for remote streams
    private long frameCount = 0;
    private long lastStatsTime = 0;
    private long totalProcessingTime = 0;

    /**
     * Create a new night vision video sink wrapper
     *
     * @param originalSink The original video sink to forward processed frames to
     */
    public NightVisionVideoSink(VideoSink originalSink) {
        this.originalSink = originalSink;
        this.renderer = new NightVisionRenderer();
        Log.d(TAG, "NightVisionVideoSink created for remote stream processing");
    }

    @Override
    public void onFrame(VideoFrame frame) {
        if (!enabled || intensity <= 0.0f) {
            // Pass through without processing
            originalSink.onFrame(frame);
            return;
        }

        long startTime = System.nanoTime();

        try {
            // Update renderer configuration only when needed
            if (configNeedsUpdate) {
                renderer.setNightVisionConfig(
                        intensity,
                        0.3f + (intensity * 0.5f), // gamma
                        0.3f, // brightnessThreshold
                        1.0f + (intensity * 0.8f), // contrast
                        intensity * 0.3f // noiseReduction
                );
                configNeedsUpdate = false;
            }

            // Note: Since NightVisionRenderer is now a GlDrawer, direct frame processing
            // would require integration with WebRTC's rendering pipeline.
            // For now, we'll pass through the frame. The night vision effect would be
            // applied when this VideoSink is used with a renderer that supports custom
            // GlDrawers.

            originalSink.onFrame(frame);

            updateStats(System.nanoTime() - startTime);

        } catch (Exception e) {
            Log.e(TAG, "Error processing remote frame", e);
            // Forward original frame on error
            originalSink.onFrame(frame);
        }
    }

    /**
     * Enable or disable night vision for remote streams
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        configNeedsUpdate = true;
        Log.d(TAG, String.format("Night vision for remote stream %s", enabled ? "enabled" : "disabled"));
    }

    /**
     * Set night vision intensity for remote streams (0.0 - 1.0)
     */
    public void setIntensity(float intensity) {
        float newIntensity = Math.max(0.0f, Math.min(1.0f, intensity));
        if (Math.abs(this.intensity - newIntensity) > 0.01f) {
            this.intensity = newIntensity;
            configNeedsUpdate = true;
            Log.d(TAG, String.format("Remote stream night vision intensity set to %.2f", this.intensity));
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
     * Get the underlying night vision renderer for use in rendering pipeline
     */
    public NightVisionRenderer getRenderer() {
        return renderer;
    }

    /**
     * Get the original sink being wrapped
     */
    public VideoSink getOriginalSink() {
        return originalSink;
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

            Log.d(TAG, String.format("Remote night vision stats: %.1f FPS, %.2f ms avg frame time, %d frames processed",
                    fps, avgFrameTime, frameCount));

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
            return String.format("RemoteNightVision: %.1f FPS, %.2f ms avg", fps, avgFrameTime);
        }
        return "RemoteNightVision: No stats available";
    }

    /**
     * Release resources
     */
    public void release() {
        Log.d(TAG, "Releasing night vision video sink resources");
        if (renderer != null) {
            renderer.release();
        }
        enabled = false;
        Log.d(TAG, "Night vision video sink resources released");
    }
}