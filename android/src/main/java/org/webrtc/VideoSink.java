package org.webrtc;

public interface VideoSink {
   @CalledByNative
   void onFrame(VideoFrame var1);
}
