package com.cloudwebrtc.webrtc.video;

import androidx.annotation.Nullable;
import android.util.Log;

import com.cloudwebrtc.webrtc.LocalTrack;

import org.webrtc.VideoFrame;
import org.webrtc.VideoProcessor;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

public class LocalVideoTrack extends LocalTrack {
    private static final String TAG = "LocalVideoTrack";

    public interface ExternalVideoFrameProcessing {
        /**
         * Process a video frame.
         *
         * @param frame
         * @return The processed video frame.
         */
        public abstract VideoFrame onFrame(VideoFrame frame);
    }

    private VideoSource videoSource;
    private List<ExternalVideoFrameProcessing> processors = new ArrayList<>();

    // Night vision processor for enhancing low-light video
    public NightVisionProcessor nightVisionProcessor = null;

    // Video processor that integrates with WebRTC pipeline
    private LocalVideoProcessor localVideoProcessor;

    public LocalVideoTrack(VideoTrack videoTrack) {
        super(videoTrack);
        initializeProcessing();
    }

    private void initializeProcessing() {
        try {
            // Get the video source from the track to integrate processing
            // This is where we'll hook into the actual video pipeline
            localVideoProcessor = new LocalVideoProcessor();
            Log.d(TAG, "LocalVideoTrack processing initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize video processing: " + e.getMessage());
        }
    }

    public void addProcessor(ExternalVideoFrameProcessing processor) {
        synchronized (processors) {
            processors.add(processor);
            Log.d(TAG, "Added video processor, total: " + processors.size());
        }
    }

    public void removeProcessor(ExternalVideoFrameProcessing processor) {
        synchronized (processors) {
            processors.remove(processor);
            Log.d(TAG, "Removed video processor, total: " + processors.size());
        }
    }

    /**
     * Internal video processor that integrates with WebRTC's video pipeline
     */
    private class LocalVideoProcessor implements VideoProcessor {
        private VideoSink originalSink;
        private boolean isProcessing = false;

        @Override
        public void setSink(@Nullable VideoSink videoSink) {
            this.originalSink = videoSink;

            // If we have processors, intercept the frames
            synchronized (processors) {
                if (!processors.isEmpty() && !isProcessing) {
                    isProcessing = true;
                    Log.d(TAG, "Started intercepting video frames for processing");
                }
            }
        }

        @Override
        public void onCapturerStarted(boolean success) {
            Log.d(TAG, "Video capturer started: " + success);
        }

        @Override
        public void onCapturerStopped() {
            Log.d(TAG, "Video capturer stopped");
        }

        @Override
        public void onFrameCaptured(VideoFrame videoFrame) {
            VideoFrame processedFrame = videoFrame;

            // Apply all processors
            synchronized (processors) {
                if (!processors.isEmpty()) {
                    Log.d(TAG, "Processing frame through " + processors.size() + " processors");
                    for (ExternalVideoFrameProcessing processor : processors) {
                        try {
                            processedFrame = processor.onFrame(processedFrame);
                        } catch (Exception e) {
                            Log.e(TAG, "Error in video processor: " + e.getMessage());
                        }
                    }
                }
            }

            // Forward to original sink
            if (originalSink != null) {
                originalSink.onFrame(processedFrame);
            }
        }
    }

    /**
     * Get the video processor to integrate with WebRTC pipeline
     */
    public VideoProcessor getVideoProcessor() {
        return localVideoProcessor;
    }
}
