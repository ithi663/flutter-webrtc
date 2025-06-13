package org.webrtc;

import java.nio.ByteBuffer;

public interface AudioTrackSink {
   @CalledByNative
   void onData(ByteBuffer var1, int var2, int var3, int var4, int var5, long var6);
}
