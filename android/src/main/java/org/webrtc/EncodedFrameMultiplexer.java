package org.webrtc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the distribution of encoded frames from HardwareVideoEncoder to
 * multiple listeners.
 * This class acts as a central hub for the multiplexing system, allowing
 * components like
 * VideoFileRenderer to register as listeners and receive encoded frames without
 * requiring
 * direct access to encoder instances.
 */
public class EncodedFrameMultiplexer {
    private static EncodedFrameMultiplexer instance;
    private final List<EncodedFrameListener> listeners = new CopyOnWriteArrayList<>();

    private EncodedFrameMultiplexer() {
    }

    public static synchronized EncodedFrameMultiplexer getInstance() {
        if (instance == null) {
            instance = new EncodedFrameMultiplexer();
        }
        return instance;
    }

    /**
     * Adds a listener to receive encoded frames from all active encoders.
     */
    public void addListener(EncodedFrameListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a listener from receiving encoded frames.
     */
    public void removeListener(EncodedFrameListener listener) {
        listeners.remove(listener);
    }

    /**
     * Distributes encoded frames to all registered listeners.
     * This method is called by HardwareVideoEncoder instances.
     */
    public void distributeEncodedFrame(java.nio.ByteBuffer buffer, android.media.MediaCodec.BufferInfo info) {
        for (EncodedFrameListener listener : listeners) {
            try {
                // Create a slice of the buffer for each listener to avoid interference
                java.nio.ByteBuffer listenerBuffer = buffer.slice();
                listener.onEncodedFrame(listenerBuffer, info);
            } catch (Exception e) {
                // Log error but continue with other listeners
                android.util.Log.e("EncodedFrameMultiplexer", "Error distributing frame to listener", e);
            }
        }
    }

    /**
     * Clears all listeners. Used for cleanup.
     */
    public void clearListeners() {
        listeners.clear();
    }

    /**
     * Returns the number of active listeners.
     */
    public int getListenerCount() {
        return listeners.size();
    }
}