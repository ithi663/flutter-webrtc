package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.media.MediaCodec.BufferInfo;
import android.os.Bundle;
import android.view.Surface;
import java.nio.ByteBuffer;

interface MediaCodecWrapper {
   void configure(MediaFormat var1, Surface var2, MediaCrypto var3, int var4);

   void start();

   void flush();

   void stop();

   void release();

   int dequeueInputBuffer(long var1);

   void queueInputBuffer(int var1, int var2, int var3, long var4, int var6);

   int dequeueOutputBuffer(BufferInfo var1, long var2);

   void releaseOutputBuffer(int var1, boolean var2);

   MediaFormat getInputFormat();

   MediaFormat getOutputFormat();

   MediaFormat getOutputFormat(int var1);

   ByteBuffer getInputBuffer(int var1);

   ByteBuffer getOutputBuffer(int var1);

   Surface createInputSurface();

   void setParameters(Bundle var1);

   MediaCodecInfo getCodecInfo();
}
