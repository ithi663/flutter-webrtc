package com.cloudwebrtc.webrtc.video;

import android.util.Log;
import org.webrtc.VideoFrame;

/**
 * Night vision video frame processor for local video tracks.
 * Implements the ExternalVideoFrameProcessing interface to integrate
 * with LocalVideoTrack's processing pipeline.
 */
public class NightVisionProcessor implements LocalVideoTrack.ExternalVideoFrameProcessing {
    private static final String TAG = "NightVisionProcessor";

    private NightVisionRenderer renderer;
    private boolean enabled = false;
    private float intensity = 0.7f;

    // Performance tracking
    private long frameCount = 0;
    private long lastLogTime = 0;

    public NightVisionProcessor() {
        Log.d(TAG, "NightVisionProcessor created");
    }

    /**
     * Initialize the processor with GPU resources
     */
    public void initialize() {
        if (renderer == null) {
            renderer = new NightVisionRenderer();
            renderer.initialize();
            Log.d(TAG, "NightVisionProcessor initialized with renderer");
        }
    }

    /**
     * Process a video frame using night vision enhancement
     * This method is called by LocalVideoTrack for each frame
     */
    @Override
    public VideoFrame onFrame(VideoFrame frame) {
        if (!enabled || renderer == null) {
            return frame; // Return original frame if disabled
        }

        try {
            // Update renderer settings
            renderer.setIntensity(intensity);

            // Process the frame
            VideoFrame processedFrame = renderer.processFrame(frame);

            // Update performance tracking
            updatePerformanceStats();

            return processedFrame;
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame: " + e.getMessage());
            return frame; // Return original frame on error
        }
    }

    /**
     * Enable or disable night vision processing
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.d(TAG, "Night vision processing " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Set the night vision intensity (0.0 - 1.0)
     */
    public void setIntensity(float intensity) {
        this.intensity = Math.max(0.0f, Math.min(1.0f, intensity));
        Log.d(TAG, String.format("Night vision intensity set to %.2f", this.intensity));

        if (renderer != null) {
            renderer.setIntensity(this.intensity);
        }
    }

    /**
     * Get the current intensity
     */
    public float getIntensity() {
        return intensity;
    }

    /**
     * Check if night vision is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Clean up resources
     */
    public void dispose() {
        Log.d(TAG, "NightVisionProcessor disposing");
        enabled = false;

        if (renderer != null) {
            renderer.dispose();
            renderer = null;
        }
    }

    /**
     * Update performance statistics
     */
    private void updatePerformanceStats() {
        frameCount++;
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastLogTime > 5000) { // Log every 5 seconds
            float fps = frameCount / 5.0f;
            Log.d(TAG, String.format("Night vision processing FPS: %.1f", fps));
            frameCount = 0;
            lastLogTime = currentTime;
        }
    }
}