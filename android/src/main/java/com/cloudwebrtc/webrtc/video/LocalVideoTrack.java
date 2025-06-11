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

public class LocalVideoTrack extends LocalTrack implements VideoProcessor {
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

    private VideoSink sink = null;

    @Override
    public void setSink(@Nullable VideoSink videoSink) {
        sink = videoSink;
    }

    @Override
    public void onCapturerStarted(boolean b) {}

    @Override
    public void onCapturerStopped() {}

    @Override
    public void onFrameCaptured(VideoFrame videoFrame) {
        if (sink != null) {
            // Validate and fix stride alignment before processing
            VideoFrame processedFrame = validateAndFixStride(videoFrame);
            
            synchronized (processors) {
                for (ExternalVideoFrameProcessing processor : processors) {
                    processedFrame = processor.onFrame(processedFrame);
                }
            }
            sink.onFrame(processedFrame);
        }
    }
    
    /**
     * Validates frame stride alignment and fixes it if necessary to prevent green lines
     */
    private VideoFrame validateAndFixStride(VideoFrame originalFrame) {
        VideoFrame.Buffer buffer = originalFrame.getBuffer();
        if (buffer instanceof VideoFrame.I420Buffer) {
            VideoFrame.I420Buffer i420Buffer = (VideoFrame.I420Buffer) buffer;
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            int strideY = i420Buffer.getStrideY();
            int strideU = i420Buffer.getStrideU();
            int strideV = i420Buffer.getStrideV();
            
            // Log stride information for debugging
            Log.d(TAG, String.format("Live frame: %dx%d, Y stride: %d, U stride: %d, V stride: %d", 
                width, height, strideY, strideU, strideV));
            
            // Check for stride alignment issues that cause green lines
            int expectedChromaWidth = (width + 1) / 2;
            if (strideY != width || strideU != expectedChromaWidth || strideV != expectedChromaWidth) {
                Log.w(TAG, "Stride mismatch detected in live translation - fixing to prevent green lines");
                Log.w(TAG, String.format("Expected: Y=%d, U=%d, V=%d | Actual: Y=%d, U=%d, V=%d", 
                    width, expectedChromaWidth, expectedChromaWidth, strideY, strideU, strideV));
                
                // Create a properly aligned frame
                VideoFrame alignedFrame = createAlignedFrame(originalFrame);
                if (alignedFrame != null) {
                    return alignedFrame;
                }
            }
        }
        
        return originalFrame;
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
}
