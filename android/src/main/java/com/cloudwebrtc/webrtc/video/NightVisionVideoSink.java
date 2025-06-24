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
    private final NightVisionRenderer processor;
    private final Object lock = new Object();

    // Night vision settings
    private boolean enabled = false;
    private float intensity = 0.7f; // 0.0 = disabled, 1.0 = maximum enhancement
    private float gamma = 0.6f; // Dynamic gamma correction factor
    private float brightnessThreshold = 0.3f; // Threshold for applying enhancement

    // Processing statistics
    private long frameCount = 0;
    private long processingTimeSum = 0;
    private long lastLogTime = 0;
    private static final long LOG_INTERVAL = 10000; // Log stats every 10 seconds (less frequent for remote)

    /**
     * Create a new night vision video sink wrapper
     *
     * @param originalSink The original video sink to forward processed frames to
     * @param processor    The night vision renderer for processing frames
     */
    public NightVisionVideoSink(VideoSink originalSink, NightVisionRenderer processor) {
        this.originalSink = originalSink;
        this.processor = processor;
        Log.d(TAG, "NightVisionVideoSink created for remote stream processing");
    }

    @Override
    public void onFrame(VideoFrame frame) {
        synchronized (lock) {
            VideoFrame processedFrame = frame;

            if (enabled && processor != null) {
                long startTime = System.nanoTime();

                try {
                    // Set processor settings before processing
                    processor.setIntensity(intensity);
                    processedFrame = processor.processFrame(frame);

                    // Update statistics
                    frameCount++;
                    long processingTime = System.nanoTime() - startTime;
                    processingTimeSum += processingTime;

                    // Log performance statistics periodically (less frequent for remote streams)
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime > LOG_INTERVAL) {
                        double avgProcessingTime = (double) processingTimeSum / frameCount / 1_000_000.0; // Convert to
                                                                                                          // ms
                        Log.d(TAG, String.format(
                                "Remote night vision processing stats - Frames: %d, Avg processing time: %.2f ms",
                                frameCount, avgProcessingTime));
                        lastLogTime = currentTime;

                        // Reset counters for next interval
                        frameCount = 0;
                        processingTimeSum = 0;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing remote frame in night vision: " + e.getMessage(), e);
                    // Use original frame on error
                }
            }

            // Forward to original sink
            originalSink.onFrame(processedFrame);
        }
    }

    /**
     * Enable or disable night vision processing for this remote stream
     *
     * @param enabled True to enable night vision, false to disable
     */
    public void setEnabled(boolean enabled) {
        synchronized (lock) {
            this.enabled = enabled;
            Log.d(TAG, "Remote stream night vision " + (enabled ? "enabled" : "disabled"));
        }
    }

    /**
     * Check if night vision is currently enabled
     *
     * @return True if enabled, false otherwise
     */
    public boolean isEnabled() {
        synchronized (lock) {
            return enabled;
        }
    }

    /**
     * Set night vision enhancement intensity for this remote stream
     *
     * @param intensity Enhancement intensity (0.0 - 1.0)
     */
    public void setIntensity(float intensity) {
        synchronized (lock) {
            this.intensity = Math.max(0f, Math.min(1f, intensity));
            Log.d(TAG, "Remote stream night vision intensity set to: " + this.intensity);
        }
    }

    /**
     * Get current night vision intensity
     *
     * @return Current intensity value (0.0 - 1.0)
     */
    public float getIntensity() {
        synchronized (lock) {
            return intensity;
        }
    }

    /**
     * Set gamma correction factor for this remote stream
     *
     * @param gamma Gamma correction factor (0.1 - 2.0)
     */
    public void setGamma(float gamma) {
        synchronized (lock) {
            this.gamma = Math.max(0.1f, Math.min(2.0f, gamma));
            Log.d(TAG, "Remote stream night vision gamma set to: " + this.gamma);
        }
    }

    /**
     * Get current gamma correction factor
     *
     * @return Current gamma value
     */
    public float getGamma() {
        synchronized (lock) {
            return gamma;
        }
    }

    /**
     * Set brightness threshold for applying enhancement
     *
     * @param threshold Brightness threshold (0.0 - 1.0)
     */
    public void setBrightnessThreshold(float threshold) {
        synchronized (lock) {
            this.brightnessThreshold = Math.max(0f, Math.min(1f, threshold));
            Log.d(TAG, "Remote stream night vision brightness threshold set to: " + this.brightnessThreshold);
        }
    }

    /**
     * Get current brightness threshold
     *
     * @return Current brightness threshold
     */
    public float getBrightnessThreshold() {
        synchronized (lock) {
            return brightnessThreshold;
        }
    }

    /**
     * Get the original video sink that frames are forwarded to
     *
     * @return The original video sink
     */
    public VideoSink getOriginalSink() {
        return originalSink;
    }

    /**
     * Get processing statistics for this remote stream
     *
     * @return Statistics string for debugging
     */
    public String getStatistics() {
        synchronized (lock) {
            if (frameCount == 0) {
                return "No remote frames processed yet";
            }
            double avgProcessingTime = (double) processingTimeSum / frameCount / 1_000_000.0;
            return String.format("Remote stream - Frames: %d, Avg time: %.2f ms, Enabled: %s, Intensity: %.2f",
                    frameCount, avgProcessingTime, enabled, intensity);
        }
    }

    /**
     * Clean up resources when the sink is no longer needed
     */
    public void dispose() {
        synchronized (lock) {
            Log.d(TAG, "NightVisionVideoSink disposed");
            // Note: We don't dispose the processor here as it might be shared with local
            // processing
            // The processor should be managed by the higher-level component
        }
    }
}