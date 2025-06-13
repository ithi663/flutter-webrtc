package org.webrtc;

import java.util.IdentityHashMap;
import java.util.Iterator;

public class AudioTrack extends MediaStreamTrack {
   private final IdentityHashMap<AudioTrackSink, Long> sinks = new IdentityHashMap();

   public AudioTrack(long nativeTrack) {
      super(nativeTrack);
   }

   public void setVolume(double volume) {
      nativeSetVolume(this.getNativeAudioTrack(), volume);
   }

   public void addSink(AudioTrackSink sink) {
      if (sink == null) {
         throw new IllegalArgumentException("The AudioTrackSink is not allowed to be null");
      } else {
         if (!this.sinks.containsKey(sink)) {
            long nativeSink = nativeWrapSink(sink);
            this.sinks.put(sink, nativeSink);
            nativeAddSink(this.getNativeMediaStreamTrack(), nativeSink);
         }

      }
   }

   public void removeSink(AudioTrackSink sink) {
      Long nativeSink = (Long)this.sinks.remove(sink);
      if (nativeSink != null) {
         nativeRemoveSink(this.getNativeMediaStreamTrack(), nativeSink);
         nativeFreeSink(nativeSink);
      }

   }

   public void dispose() {
      Iterator var1 = this.sinks.values().iterator();

      while(var1.hasNext()) {
         long nativeSink = (Long)var1.next();
         nativeRemoveSink(this.getNativeMediaStreamTrack(), nativeSink);
         nativeFreeSink(nativeSink);
      }

      this.sinks.clear();
      super.dispose();
   }

   long getNativeAudioTrack() {
      return this.getNativeMediaStreamTrack();
   }

   private static native void nativeSetVolume(long var0, double var2);

   private static native void nativeAddSink(long var0, long var2);

   private static native void nativeRemoveSink(long var0, long var2);

   private static native long nativeWrapSink(AudioTrackSink var0);

   private static native void nativeFreeSink(long var0);
}
