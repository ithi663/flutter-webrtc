package org.webrtc;

class Histogram {
   private long handle;

   private Histogram(long handle) {
      this.handle = handle;
   }

   public static Histogram createCounts(String name, int min, int max, int bucketCount) {
      return new Histogram(nativeCreateCounts(name, min, max, bucketCount));
   }

   public static Histogram createEnumeration(String name, int max) {
      return new Histogram(nativeCreateEnumeration(name, max));
   }

   public void addSample(int sample) {
      nativeAddSample(this.handle, sample);
   }

   public void release() {
      if (handle != 0) {
         nativeRelease(handle);
         handle = 0;
      }
   }

   private static native long nativeCreateCounts(String var0, int var1, int var2, int var3);

   private static native long nativeCreateEnumeration(String var0, int var1);

   private static native void nativeAddSample(long var0, int var2);

   private static native void nativeRelease(long handle);
}
