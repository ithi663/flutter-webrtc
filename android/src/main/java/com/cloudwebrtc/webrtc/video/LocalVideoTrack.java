package com.cloudwebrtc.webrtc.video;

import androidx.annotation.Nullable;
import android.util.Log;

import com.cloudwebrtc.webrtc.LocalTrack;

import org.webrtc.JavaI420Buffer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoProcessor;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class LocalVideoTrack extends LocalTrack implements VideoProcessor, VideoSink {
    private static final String TAG = "LocalVideoTrack";
    
    public interface ExternalVideoFrameProcessing {
        /**
         * Process a video frame.
         * @param frame
         * @return The processed video frame.
         */
        public abstract VideoFrame onFrame(VideoFrame frame);
    }

    public LocalVideoTrack(VideoTrack videoTrack) {
        super(videoTrack);
    }

    List<ExternalVideoFrameProcessing> processors = new ArrayList<>();
    private ExternalVideoFrameProcessing externalVideoFrameProcessing = null;
    private List<VideoSink> sinks = new ArrayList<>();

    public void addProcessor(ExternalVideoFrameProcessing processor) {
        synchronized (processors) {
            processors.add(processor);
        }
    }

    public void removeProcessor(ExternalVideoFrameProcessing processor) {
        synchronized (processors) {
            processors.remove(processor);
        }
    }

    public void setExternalVideoFrameProcessing(ExternalVideoFrameProcessing processing) {
        this.externalVideoFrameProcessing = processing;
    }

    public void addSink(VideoSink sink) {
        synchronized (sinks) {
            sinks.add(sink);
        }
    }

    public void removeSink(VideoSink sink) {
        synchronized (sinks) {
            sinks.remove(sink);
        }
    }

    private VideoSink sink = null;

    @Override
    public void setSink(@Nullable VideoSink videoSink) {
        sink = videoSink;
    }

    @Override
    public void onCapturerStarted(boolean b) {}

    @Override
    public void onCapturerStopped() {}

    // Frame buffering for live translation stability
    private final Object liveFrameBufferLock = new Object();
    private VideoFrame lastValidLiveFrame = null;
    private long lastLiveFrameTimestamp = 0;
    private int liveDroppedFrameCount = 0;
    private boolean liveEncoderOverloaded = false;
    private static final int MAX_LIVE_DROPPED_FRAMES = 2; // More aggressive for live
    private static final long LIVE_FRAME_TIMEOUT_MS = 50; // 50ms timeout for live

    @Override
    public void onFrame(VideoFrame frame) {
        synchronized (liveFrameBufferLock) {
            long currentTime = System.currentTimeMillis();
            
            // Check if live encoder recovered from overload
            if (currentTime - lastLiveFrameTimestamp > LIVE_FRAME_TIMEOUT_MS) {
                if (liveDroppedFrameCount > 0) {
                    Log.d(TAG, "Live encoder recovered, dropped frames: " + liveDroppedFrameCount);
                    liveEncoderOverloaded = false;
                    liveDroppedFrameCount = 0;
                }
            }
            
            // Validate and fix frame for live translation
            VideoFrame processedFrame = validateAndFixLiveFrame(frame);
            if (processedFrame == null) {
                Log.w(TAG, "Live frame validation failed");
                if (lastValidLiveFrame != null && !liveEncoderOverloaded) {
                    processedFrame = lastValidLiveFrame;
                    liveDroppedFrameCount++;
                } else {
                    return; // Skip corrupted frame
                }
            }
            
            // Detect live encoder overload
            if (liveDroppedFrameCount >= MAX_LIVE_DROPPED_FRAMES) {
                if (!liveEncoderOverloaded) {
                    Log.w(TAG, "Live encoder overload detected");
                    liveEncoderOverloaded = true;
                }
                
                // More aggressive frame skipping for live translation
                if (liveDroppedFrameCount % 3 == 0) {
                    Log.d(TAG, "Skipping live frame due to overload");
                    liveDroppedFrameCount++;
                    return;
                }
            }
            
            try {
                // Store last valid frame for live recovery
                if (processedFrame != null && !liveEncoderOverloaded) {
                    if (lastValidLiveFrame != null) {
                        lastValidLiveFrame.release();
                    }
                    lastValidLiveFrame = new VideoFrame(processedFrame.getBuffer(), 
                                                      processedFrame.getRotation(), 
                                                      processedFrame.getTimestampNs());
                }
                
                // Process external video frame processing if available
                synchronized (processors) {
                    for (ExternalVideoFrameProcessing processor : processors) {
                        processedFrame = processor.onFrame(processedFrame);
                    }
                }
                
                // Send to main sink if available
                if (sink != null) {
                    sink.onFrame(processedFrame);
                }
                
                // Send to additional sinks
                synchronized (sinks) {
                    for (VideoSink videoSink : sinks) {
                        videoSink.onFrame(processedFrame);
                    }
                }
                
                lastLiveFrameTimestamp = currentTime;
                
                // Reset dropped frame count on successful processing
                if (liveDroppedFrameCount > 0) {
                    liveDroppedFrameCount = Math.max(0, liveDroppedFrameCount - 1);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error processing live frame: " + e.getMessage());
                liveDroppedFrameCount++;
                if (liveDroppedFrameCount > MAX_LIVE_DROPPED_FRAMES) {
                    liveEncoderOverloaded = true;
                }
            }
        }
    }

    /**
     * Validates and fixes frame issues for live translation without breaking the stream
     */
    private VideoFrame validateAndFixLiveFrame(VideoFrame frame) {
        if (frame == null || frame.getBuffer() == null) {
            return null;
        }
        
        try {
            VideoFrame.Buffer buffer = frame.getBuffer();
            if (!(buffer instanceof VideoFrame.I420Buffer)) {
                VideoFrame.I420Buffer i420Buffer = buffer.toI420();
                return new VideoFrame(i420Buffer, frame.getRotation(), frame.getTimestampNs());
            }
            
            VideoFrame.I420Buffer i420Buffer = (VideoFrame.I420Buffer) buffer;
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            int strideY = i420Buffer.getStrideY();
            int strideU = i420Buffer.getStrideU();
            int strideV = i420Buffer.getStrideV();
            int expectedChromaWidth = (width + 1) / 2;
            
            // Check for stride misalignment in live translation
            boolean needsAlignment = (strideY != width || 
                                    strideU != expectedChromaWidth || 
                                    strideV != expectedChromaWidth);
            
            if (needsAlignment) {
                Log.d(TAG, String.format("Live frame alignment needed: %dx%d, strides Y:%d U:%d V:%d", 
                    width, height, strideY, strideU, strideV));
                
                if (width > 0 && height > 0 && strideY >= width && 
                    strideU >= expectedChromaWidth && strideV >= expectedChromaWidth) {
                    return createAlignedFrame(frame);
                } else {
                    Log.w(TAG, "Invalid live frame dimensions, skipping alignment");
                    return null;
                }
            }
            
            return frame; // Frame is already properly aligned
            
        } catch (Exception e) {
            Log.e(TAG, "Live frame validation error: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void onFrameCaptured(VideoFrame videoFrame) {
        if (sink != null) {
            synchronized (processors) {
                VideoFrame processedFrame = videoFrame;
                for (ExternalVideoFrameProcessing processor : processors) {
                    processedFrame = processor.onFrame(processedFrame);
                }
                sink.onFrame(processedFrame);
            }
        }
    }
    
    /**
     * Creates a properly aligned video frame to prevent green lines caused by stride misalignment
     */
    private VideoFrame createAlignedFrame(VideoFrame originalFrame) {
        long startTime = System.nanoTime();
        try {
            VideoFrame.Buffer buffer = originalFrame.getBuffer();
            VideoFrame.I420Buffer i420Buffer = buffer.toI420();
            
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            int chromaWidth = (width + 1) / 2;
            int chromaHeight = (height + 1) / 2;
            
            // Create new buffers with proper alignment
            ByteBuffer alignedY = ByteBuffer.allocateDirect(width * height);
            ByteBuffer alignedU = ByteBuffer.allocateDirect(chromaWidth * chromaHeight);
            ByteBuffer alignedV = ByteBuffer.allocateDirect(chromaWidth * chromaHeight);
            
            // Copy Y plane row by row to remove stride padding
            ByteBuffer srcY = i420Buffer.getDataY();
            int srcStrideY = i420Buffer.getStrideY();
            for (int row = 0; row < height; row++) {
                srcY.position(row * srcStrideY);
                srcY.limit(srcY.position() + width);
                alignedY.put(srcY);
            }
            
            // Copy U plane row by row to remove stride padding
            ByteBuffer srcU = i420Buffer.getDataU();
            int srcStrideU = i420Buffer.getStrideU();
            for (int row = 0; row < chromaHeight; row++) {
                srcU.position(row * srcStrideU);
                srcU.limit(srcU.position() + chromaWidth);
                alignedU.put(srcU);
            }
            
            // Copy V plane row by row to remove stride padding
            ByteBuffer srcV = i420Buffer.getDataV();
            int srcStrideV = i420Buffer.getStrideV();
            for (int row = 0; row < chromaHeight; row++) {
                srcV.position(row * srcStrideV);
                srcV.limit(srcV.position() + chromaWidth);
                alignedV.put(srcV);
            }
            
            alignedY.rewind();
            alignedU.rewind();
            alignedV.rewind();
            
            // Create new I420Buffer with aligned data
            VideoFrame.I420Buffer alignedI420Buffer = JavaI420Buffer.wrap(
                width, height,
                alignedY, width,
                alignedU, chromaWidth,
                alignedV, chromaWidth,
                null // release callback
            );
            
            i420Buffer.release();
            
            long endTime = System.nanoTime();
            long durationMicros = (endTime - startTime) / 1000;
            Log.d(TAG, String.format("Live frame alignment took %d μs for %dx%d frame", durationMicros, width, height));
            
            return new VideoFrame(alignedI420Buffer, originalFrame.getRotation(), originalFrame.getTimestampNs());
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create aligned frame for live translation", e);
            return null;
        }
    }
    
    /**
     * Clean up resources when the track is disposed
     */
    public void dispose() {
        synchronized (liveFrameBufferLock) {
            if (lastValidLiveFrame != null) {
                lastValidLiveFrame.release();
                lastValidLiveFrame = null;
            }
            liveEncoderOverloaded = false;
            liveDroppedFrameCount = 0;
        }
    }
}
