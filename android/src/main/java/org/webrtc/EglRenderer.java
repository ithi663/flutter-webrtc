package org.webrtc;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Bitmap.Config;
import android.opengl.GLES20;
import android.view.Surface;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EglRenderer implements VideoSink {
   private static final String TAG = "EglRenderer";
   private static final long LOG_INTERVAL_SEC = 4L;
   protected final String name;
   private final Object threadLock;
   @GuardedBy("threadLock")
   @Nullable
   private EglThread eglThread;
   private final Runnable eglExceptionCallback;
   private final ArrayList<EglRenderer.FrameListenerAndParams> frameListeners;
   private volatile EglRenderer.ErrorCallback errorCallback;
   private final Object fpsReductionLock;
   private long nextFrameTimeNs;
   private long minRenderPeriodNs;
   @Nullable
   private EglBase eglBase;
   private final VideoFrameDrawer frameDrawer;
   @Nullable
   private RendererCommon.GlDrawer drawer;
   private boolean usePresentationTimeStamp;
   private final Matrix drawMatrix;
   private final Object frameLock;
   @Nullable
   private VideoFrame pendingFrame;
   private final Object layoutLock;
   private float layoutAspectRatio;
   private boolean mirrorHorizontally;
   private boolean mirrorVertically;
   private final Object statisticsLock;
   private int framesReceived;
   private int framesDropped;
   private int framesRendered;
   private long statisticsStartTimeNs;
   private long renderTimeNs;
   private long renderSwapBufferTimeNs;
   private final GlTextureFrameBuffer bitmapTextureFramebuffer;
   private final Runnable logStatisticsRunnable;
   private final EglRenderer.EglSurfaceCreation eglSurfaceCreationRunnable;

   public EglRenderer(String name) {
      this(name, new VideoFrameDrawer());
   }

   public EglRenderer(String name, VideoFrameDrawer videoFrameDrawer) {
      this.threadLock = new Object();
      this.eglExceptionCallback = new Runnable() {
         public void run() {
            synchronized(EglRenderer.this.threadLock) {
               EglRenderer.this.eglThread = null;
            }
         }
      };
      this.frameListeners = new ArrayList();
      this.fpsReductionLock = new Object();
      this.drawMatrix = new Matrix();
      this.frameLock = new Object();
      this.layoutLock = new Object();
      this.statisticsLock = new Object();
      this.bitmapTextureFramebuffer = new GlTextureFrameBuffer(6408);
      this.logStatisticsRunnable = new Runnable() {
         public void run() {
            EglRenderer.this.logStatistics();
            synchronized(EglRenderer.this.threadLock) {
               if (EglRenderer.this.eglThread != null) {
                  EglRenderer.this.eglThread.getHandler().removeCallbacks(EglRenderer.this.logStatisticsRunnable);
                  EglRenderer.this.eglThread.getHandler().postDelayed(EglRenderer.this.logStatisticsRunnable, TimeUnit.SECONDS.toMillis(4L));
               }

            }
         }
      };
      this.eglSurfaceCreationRunnable = new EglRenderer.EglSurfaceCreation();
      this.name = name;
      this.frameDrawer = videoFrameDrawer;
   }

   public void init(EglThread eglThread, RendererCommon.GlDrawer drawer, boolean usePresentationTimeStamp) {
      synchronized(this.threadLock) {
         if (this.eglThread != null) {
            throw new IllegalStateException(this.name + "Already initialized");
         } else {
            this.logD("Initializing EglRenderer");
            this.eglThread = eglThread;
            this.drawer = drawer;
            this.usePresentationTimeStamp = usePresentationTimeStamp;
            eglThread.addExceptionCallback(this.eglExceptionCallback);
            this.eglBase = eglThread.createEglBaseWithSharedConnection();
            eglThread.getHandler().post(this.eglSurfaceCreationRunnable);
            long currentTimeNs = System.nanoTime();
            this.resetStatistics(currentTimeNs);
            eglThread.getHandler().postDelayed(this.logStatisticsRunnable, TimeUnit.SECONDS.toMillis(4L));
         }
      }
   }

   public void init(@Nullable EglBase.Context sharedContext, int[] configAttributes, RendererCommon.GlDrawer drawer, boolean usePresentationTimeStamp) {
      EglThread thread = EglThread.create((EglThread.ReleaseMonitor)null, sharedContext, configAttributes);
      this.init(thread, drawer, usePresentationTimeStamp);
   }

   public void init(@Nullable EglBase.Context sharedContext, int[] configAttributes, RendererCommon.GlDrawer drawer) {
      this.init(sharedContext, configAttributes, drawer, false);
   }

   public void createEglSurface(Surface surface) {
      this.createEglSurfaceInternal(surface);
   }

   public void createEglSurface(SurfaceTexture surfaceTexture) {
      this.createEglSurfaceInternal(surfaceTexture);
   }

   private void createEglSurfaceInternal(Object surface) {
      this.eglSurfaceCreationRunnable.setSurface(surface);
      this.postToRenderThread(this.eglSurfaceCreationRunnable);
   }

   public void release() {
      this.logD("Releasing.");
      CountDownLatch eglCleanupBarrier = new CountDownLatch(1);
      synchronized(this.threadLock) {
         if (this.eglThread == null) {
            this.logD("Already released");
            return;
         }

         this.eglThread.getHandler().removeCallbacks(this.logStatisticsRunnable);
         this.eglThread.removeExceptionCallback(this.eglExceptionCallback);
         this.eglThread.getHandler().postAtFrontOfQueue(() -> {
            synchronized(EglBase.lock) {
               GLES20.glUseProgram(0);
            }

            if (this.drawer != null) {
               this.drawer.release();
               this.drawer = null;
            }

            this.frameDrawer.release();
            this.bitmapTextureFramebuffer.release();
            if (this.eglBase != null) {
               this.logD("eglBase detach and release.");
               this.eglBase.detachCurrent();
               this.eglBase.release();
               this.eglBase = null;
            }

            this.frameListeners.clear();
            eglCleanupBarrier.countDown();
         });
         this.eglThread.release();
         this.eglThread = null;
      }

      ThreadUtils.awaitUninterruptibly(eglCleanupBarrier);
      synchronized(this.frameLock) {
         if (this.pendingFrame != null) {
            this.pendingFrame.release();
            this.pendingFrame = null;
         }
      }

      this.logD("Releasing done.");
   }

   private void resetStatistics(long currentTimeNs) {
      synchronized(this.statisticsLock) {
         this.statisticsStartTimeNs = currentTimeNs;
         this.framesReceived = 0;
         this.framesDropped = 0;
         this.framesRendered = 0;
         this.renderTimeNs = 0L;
         this.renderSwapBufferTimeNs = 0L;
      }
   }

   public void printStackTrace() {
      synchronized(this.threadLock) {
         Thread renderThread = this.eglThread == null ? null : this.eglThread.getHandler().getLooper().getThread();
         if (renderThread != null) {
            StackTraceElement[] renderStackTrace = renderThread.getStackTrace();
            if (renderStackTrace.length > 0) {
               this.logW("EglRenderer stack trace:");
               StackTraceElement[] var4 = renderStackTrace;
               int var5 = renderStackTrace.length;

               for(int var6 = 0; var6 < var5; ++var6) {
                  StackTraceElement traceElem = var4[var6];
                  this.logW(traceElem.toString());
               }
            }
         }

      }
   }

   public void setMirror(boolean mirror) {
      synchronized(this.layoutLock) {
         this.mirrorHorizontally = mirror;
      }
   }

   public void setMirrorVertically(boolean mirrorVertically) {
      synchronized(this.layoutLock) {
         this.mirrorVertically = mirrorVertically;
      }
   }

   public void setLayoutAspectRatio(float layoutAspectRatio) {
      synchronized(this.layoutLock) {
         this.layoutAspectRatio = layoutAspectRatio;
      }
   }

   public void setFpsReduction(float fps) {
      synchronized(this.fpsReductionLock) {
         long previousRenderPeriodNs = this.minRenderPeriodNs;
         if (fps <= 0.0F) {
            this.minRenderPeriodNs = Long.MAX_VALUE;
         } else {
            this.minRenderPeriodNs = (long)((float)TimeUnit.SECONDS.toNanos(1L) / fps);
         }

         if (this.minRenderPeriodNs != previousRenderPeriodNs) {
            this.nextFrameTimeNs = System.nanoTime();
         }

      }
   }

   public void disableFpsReduction() {
      this.setFpsReduction(Float.POSITIVE_INFINITY);
   }

   public void pauseVideo() {
      this.setFpsReduction(0.0F);
   }

   public void addFrameListener(EglRenderer.FrameListener listener, float scale) {
      this.addFrameListener(listener, scale, (RendererCommon.GlDrawer)null, false);
   }

   public void addFrameListener(EglRenderer.FrameListener listener, float scale, RendererCommon.GlDrawer drawerParam) {
      this.addFrameListener(listener, scale, drawerParam, false);
   }

   public void addFrameListener(EglRenderer.FrameListener listener, float scale, @Nullable RendererCommon.GlDrawer drawerParam, boolean applyFpsReduction) {
      this.postToRenderThread(() -> {
         RendererCommon.GlDrawer listenerDrawer = drawerParam == null ? this.drawer : drawerParam;
         this.frameListeners.add(new EglRenderer.FrameListenerAndParams(listener, scale, listenerDrawer, applyFpsReduction));
      });
   }

   public void removeFrameListener(EglRenderer.FrameListener listener) {
      CountDownLatch latch = new CountDownLatch(1);
      synchronized(this.threadLock) {
         if (this.eglThread == null) {
            return;
         }

         if (Thread.currentThread() == this.eglThread.getHandler().getLooper().getThread()) {
            throw new RuntimeException("removeFrameListener must not be called on the render thread.");
         }

         this.postToRenderThread(() -> {
            latch.countDown();
            Iterator iter = this.frameListeners.iterator();

            while(iter.hasNext()) {
               if (((EglRenderer.FrameListenerAndParams)iter.next()).listener == listener) {
                  iter.remove();
               }
            }

         });
      }

      ThreadUtils.awaitUninterruptibly(latch);
   }

   public void setErrorCallback(EglRenderer.ErrorCallback errorCallback) {
      this.errorCallback = errorCallback;
   }

   public void onFrame(VideoFrame frame) {
      synchronized(this.statisticsLock) {
         ++this.framesReceived;
      }

      boolean dropOldFrame;
      synchronized(this.threadLock) {
         if (this.eglThread == null) {
            this.logD("Dropping frame - Not initialized or already released.");
            return;
         }

         synchronized(this.frameLock) {
            dropOldFrame = this.pendingFrame != null;
            if (dropOldFrame) {
               this.pendingFrame.release();
            }

            this.pendingFrame = frame;
            this.pendingFrame.retain();
            this.eglThread.getHandler().post(this::renderFrameOnRenderThread);
         }
      }

      if (dropOldFrame) {
         synchronized(this.statisticsLock) {
            ++this.framesDropped;
         }
      }

   }

   public void releaseEglSurface(Runnable completionCallback) {
      this.eglSurfaceCreationRunnable.setSurface((Object)null);
      synchronized(this.threadLock) {
         if (this.eglThread != null) {
            this.eglThread.getHandler().removeCallbacks(this.eglSurfaceCreationRunnable);
            this.eglThread.getHandler().postAtFrontOfQueue(() -> {
               if (this.eglBase != null) {
                  this.eglBase.detachCurrent();
                  this.eglBase.releaseSurface();
               }

               completionCallback.run();
            });
            return;
         }
      }

      completionCallback.run();
   }

   private void postToRenderThread(Runnable runnable) {
      synchronized(this.threadLock) {
         if (this.eglThread != null) {
            this.eglThread.getHandler().post(runnable);
         }

      }
   }

   private void clearSurfaceOnRenderThread(float r, float g, float b, float a) {
      if (this.eglBase != null && this.eglBase.hasSurface()) {
         this.logD("clearSurface");
         this.eglBase.makeCurrent();
         GLES20.glClearColor(r, g, b, a);
         GLES20.glClear(16384);
         this.eglBase.swapBuffers();
      }

   }

   public void clearImage() {
      this.clearImage(0.0F, 0.0F, 0.0F, 0.0F);
   }

   public void clearImage(float r, float g, float b, float a) {
      synchronized(this.threadLock) {
         if (this.eglThread != null) {
            this.eglThread.getHandler().postAtFrontOfQueue(() -> {
               this.clearSurfaceOnRenderThread(r, g, b, a);
            });
         }
      }
   }

   private void swapBuffersOnRenderThread(VideoFrame frame, long swapBuffersStartTimeNs) {
      synchronized(this.threadLock) {
         if (this.eglThread != null) {
            this.eglThread.scheduleRenderUpdate((runsInline) -> {
               if (!runsInline) {
                  if (this.eglBase == null || !this.eglBase.hasSurface()) {
                     return;
                  }

                  this.eglBase.makeCurrent();
               }

               if (this.usePresentationTimeStamp) {
                  this.eglBase.swapBuffers(frame.getTimestampNs());
               } else {
                  this.eglBase.swapBuffers();
               }

               synchronized(this.statisticsLock) {
                  this.renderSwapBufferTimeNs += System.nanoTime() - swapBuffersStartTimeNs;
               }
            });
         }

      }
   }

   private void renderFrameOnRenderThread() {
      VideoFrame frame;
      synchronized(this.frameLock) {
         if (this.pendingFrame == null) {
            return;
         }

         frame = this.pendingFrame;
         this.pendingFrame = null;
      }

      if (this.eglBase != null && this.eglBase.hasSurface()) {
         this.eglBase.makeCurrent();
         boolean shouldRenderFrame;
         synchronized(this.fpsReductionLock) {
            if (this.minRenderPeriodNs == Long.MAX_VALUE) {
               shouldRenderFrame = false;
            } else if (this.minRenderPeriodNs <= 0L) {
               shouldRenderFrame = true;
            } else {
               long currentTimeNs = System.nanoTime();
               if (currentTimeNs < this.nextFrameTimeNs) {
                  this.logD("Skipping frame rendering - fps reduction is active.");
                  shouldRenderFrame = false;
               } else {
                  this.nextFrameTimeNs += this.minRenderPeriodNs;
                  this.nextFrameTimeNs = Math.max(this.nextFrameTimeNs, currentTimeNs);
                  shouldRenderFrame = true;
               }
            }
         }

         long startTimeNs = System.nanoTime();
         float frameAspectRatio = (float)frame.getRotatedWidth() / (float)frame.getRotatedHeight();
         float drawnAspectRatio;
         synchronized(this.layoutLock) {
            drawnAspectRatio = this.layoutAspectRatio != 0.0F ? this.layoutAspectRatio : frameAspectRatio;
         }

         float scaleX;
         float scaleY;
         if (frameAspectRatio > drawnAspectRatio) {
            scaleX = drawnAspectRatio / frameAspectRatio;
            scaleY = 1.0F;
         } else {
            scaleX = 1.0F;
            scaleY = frameAspectRatio / drawnAspectRatio;
         }

         this.drawMatrix.reset();
         this.drawMatrix.preTranslate(0.5F, 0.5F);
         this.drawMatrix.preScale(this.mirrorHorizontally ? -1.0F : 1.0F, this.mirrorVertically ? -1.0F : 1.0F);
         this.drawMatrix.preScale(scaleX, scaleY);
         this.drawMatrix.preTranslate(-0.5F, -0.5F);

         try {
            if (shouldRenderFrame) {
               GLES20.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
               GLES20.glClear(16384);
               this.frameDrawer.drawFrame(frame, this.drawer, this.drawMatrix, 0, 0, this.eglBase.surfaceWidth(), this.eglBase.surfaceHeight());
               long swapBuffersStartTimeNs = System.nanoTime();
               this.swapBuffersOnRenderThread(frame, swapBuffersStartTimeNs);
               synchronized(this.statisticsLock) {
                  ++this.framesRendered;
                  this.renderTimeNs += swapBuffersStartTimeNs - startTimeNs;
               }
            }

            this.notifyCallbacks(frame, shouldRenderFrame);
         } catch (GlUtil.GlOutOfMemoryException var23) {
            this.logE("Error while drawing frame", var23);
            EglRenderer.ErrorCallback errorCallback = this.errorCallback;
            if (errorCallback != null) {
               errorCallback.onGlOutOfMemory();
            }

            this.drawer.release();
            this.frameDrawer.release();
            this.bitmapTextureFramebuffer.release();
         } finally {
            frame.release();
         }

      } else {
         this.logD("Dropping frame - No surface");
         frame.release();
      }
   }

   private void notifyCallbacks(VideoFrame frame, boolean wasRendered) {
      if (!this.frameListeners.isEmpty()) {
         this.drawMatrix.reset();
         this.drawMatrix.preTranslate(0.5F, 0.5F);
         this.drawMatrix.preScale(this.mirrorHorizontally ? -1.0F : 1.0F, this.mirrorVertically ? -1.0F : 1.0F);
         this.drawMatrix.preScale(1.0F, -1.0F);
         this.drawMatrix.preTranslate(-0.5F, -0.5F);
         Iterator it = this.frameListeners.iterator();

         while(true) {
            while(true) {
               EglRenderer.FrameListenerAndParams listenerAndParams;
               do {
                  if (!it.hasNext()) {
                     return;
                  }

                  listenerAndParams = (EglRenderer.FrameListenerAndParams)it.next();
               } while(!wasRendered && listenerAndParams.applyFpsReduction);

               it.remove();
               int scaledWidth = (int)(listenerAndParams.scale * (float)frame.getRotatedWidth());
               int scaledHeight = (int)(listenerAndParams.scale * (float)frame.getRotatedHeight());
               if (scaledWidth != 0 && scaledHeight != 0) {
                  this.bitmapTextureFramebuffer.setSize(scaledWidth, scaledHeight);
                  GLES20.glBindFramebuffer(36160, this.bitmapTextureFramebuffer.getFrameBufferId());
                  GLES20.glFramebufferTexture2D(36160, 36064, 3553, this.bitmapTextureFramebuffer.getTextureId(), 0);
                  GLES20.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                  GLES20.glClear(16384);
                  this.frameDrawer.drawFrame(frame, listenerAndParams.drawer, this.drawMatrix, 0, 0, scaledWidth, scaledHeight);
                  ByteBuffer bitmapBuffer = ByteBuffer.allocateDirect(scaledWidth * scaledHeight * 4);
                  GLES20.glViewport(0, 0, scaledWidth, scaledHeight);
                  GLES20.glReadPixels(0, 0, scaledWidth, scaledHeight, 6408, 5121, bitmapBuffer);
                  GLES20.glBindFramebuffer(36160, 0);
                  GlUtil.checkNoGLES2Error("EglRenderer.notifyCallbacks");
                  Bitmap bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Config.ARGB_8888);
                  bitmap.copyPixelsFromBuffer(bitmapBuffer);
                  listenerAndParams.listener.onFrame(bitmap);
               } else {
                  listenerAndParams.listener.onFrame((Bitmap)null);
               }
            }
         }
      }
   }

   private String averageTimeAsString(long sumTimeNs, int count) {
      return count <= 0 ? "NA" : TimeUnit.NANOSECONDS.toMicros(sumTimeNs / (long)count) + " us";
   }

   private void logStatistics() {
      DecimalFormat fpsFormat = new DecimalFormat("#.0");
      long currentTimeNs = System.nanoTime();
      synchronized(this.statisticsLock) {
         long elapsedTimeNs = currentTimeNs - this.statisticsStartTimeNs;
         if (elapsedTimeNs > 0L && (this.minRenderPeriodNs != Long.MAX_VALUE || this.framesReceived != 0)) {
            float renderFps = (float)((long)this.framesRendered * TimeUnit.SECONDS.toNanos(1L)) / (float)elapsedTimeNs;
            long var10001 = TimeUnit.NANOSECONDS.toMillis(elapsedTimeNs);
            this.logD("Duration: " + var10001 + " ms. Frames received: " + this.framesReceived + ". Dropped: " + this.framesDropped + ". Rendered: " + this.framesRendered + ". Render fps: " + fpsFormat.format((double)renderFps) + ". Average render time: " + this.averageTimeAsString(this.renderTimeNs, this.framesRendered) + ". Average swapBuffer time: " + this.averageTimeAsString(this.renderSwapBufferTimeNs, this.framesRendered) + ".");
            this.resetStatistics(currentTimeNs);
         }
      }
   }

   private void logE(String string, Throwable e) {
      Logging.e("EglRenderer", this.name + string, e);
   }

   private void logD(String string) {
      Logging.d("EglRenderer", this.name + string);
   }

   private void logW(String string) {
      Logging.w("EglRenderer", this.name + string);
   }

   private class EglSurfaceCreation implements Runnable {
      private Object surface;

      public synchronized void setSurface(Object surface) {
         this.surface = surface;
      }

      public synchronized void run() {
         if (this.surface != null && EglRenderer.this.eglBase != null && !EglRenderer.this.eglBase.hasSurface()) {
            if (this.surface instanceof Surface) {
               EglRenderer.this.eglBase.createSurface((Surface)this.surface);
            } else {
               if (!(this.surface instanceof SurfaceTexture)) {
                  throw new IllegalStateException("Invalid surface: " + this.surface);
               }

               EglRenderer.this.eglBase.createSurface((SurfaceTexture)this.surface);
            }

            EglRenderer.this.eglBase.makeCurrent();
            GLES20.glPixelStorei(3317, 1);
         }

      }
   }

   public interface FrameListener {
      void onFrame(Bitmap var1);
   }

   public interface ErrorCallback {
      void onGlOutOfMemory();
   }

   private static class FrameListenerAndParams {
      public final EglRenderer.FrameListener listener;
      public final float scale;
      public final RendererCommon.GlDrawer drawer;
      public final boolean applyFpsReduction;

      public FrameListenerAndParams(EglRenderer.FrameListener listener, float scale, RendererCommon.GlDrawer drawer, boolean applyFpsReduction) {
         this.listener = listener;
         this.scale = scale;
         this.drawer = drawer;
         this.applyFpsReduction = applyFpsReduction;
      }
   }
}
