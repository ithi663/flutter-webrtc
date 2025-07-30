package org.webrtc;

import android.media.MediaCodec;
import java.nio.ByteBuffer;

/**
 * Interface for listening to encoded frames from HardwareVideoEncoder.
 * This allows multiple components to receive the same encoded video data
 * without requiring multiple encoding operations.
 */
public interface EncodedFrameListener {
    /**
     * Called when a new encoded frame is available.
     *
     * @param buffer The encoded frame data
     * @param info   Buffer information including size, offset, flags, and
     *               presentation time
     */
    void onEncodedFrame(ByteBuffer buffer, MediaCodec.BufferInfo info);
}