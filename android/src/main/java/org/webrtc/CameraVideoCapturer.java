package org.webrtc;

import android.media.MediaRecorder;

public interface CameraVideoCapturer extends VideoCapturer {
   void switchCamera(CameraVideoCapturer.CameraSwitchHandler var1);

   void switchCamera(CameraVideoCapturer.CameraSwitchHandler var1, String var2);

   /** @deprecated */
   @Deprecated
   default void addMediaRecorderToCamera(MediaRecorder mediaRecorder, CameraVideoCapturer.MediaRecorderHandler resultHandler) {
      throw new UnsupportedOperationException("Deprecatedd and not implemented.");
   }

   /** @deprecated */
   @Deprecated
   default void removeMediaRecorderFromCamera(CameraVideoCapturer.MediaRecorderHandler resultHandler) {
      throw new UnsupportedOperationException("Deprecatedd and not implemented.");
   }

   public static class CameraStatistics {
      private static final String TAG = "CameraStatistics";
      private static final int CAMERA_OBSERVER_PERIOD_MS = 2000;
      private static final int CAMERA_FREEZE_REPORT_TIMOUT_MS = 4000;
      private final SurfaceTextureHelper surfaceTextureHelper;
      private final CameraVideoCapturer.CameraEventsHandler eventsHandler;
      private int frameCount;
      private int freezePeriodCount;
      private volatile boolean released = false;
      private final Runnable cameraObserver = new Runnable() {
         public void run() {
            // Check if CameraStatistics has been released to prevent race condition
            if (released) {
               return;
            }
            
            int cameraFps = Math.round((float)frameCount * 1000.0F / 2000.0F);
            Logging.d("CameraStatistics", "Camera fps: " + cameraFps + ".");
            if (frameCount == 0) {
               ++freezePeriodCount;
               if (2000 * freezePeriodCount >= 4000 && eventsHandler != null) {
                  Logging.e("CameraStatistics", "Camera freezed.");
                  if (surfaceTextureHelper.isTextureInUse()) {
                     eventsHandler.onCameraFreezed("Camera failure. Client must return video buffers.");
                  } else {
                     eventsHandler.onCameraFreezed("Camera failure.");
                  }

                  return;
               }
            } else {
               freezePeriodCount = 0;
            }

            frameCount = 0;
            // Check again before scheduling next run to prevent further execution if released
            if (!released) {
               surfaceTextureHelper.getHandler().postDelayed(this, 2000L);
            }
         }
      };

      public CameraStatistics(SurfaceTextureHelper surfaceTextureHelper, CameraVideoCapturer.CameraEventsHandler eventsHandler) {
         if (surfaceTextureHelper == null) {
            throw new IllegalArgumentException("SurfaceTextureHelper is null");
         } else {
            this.surfaceTextureHelper = surfaceTextureHelper;
            this.eventsHandler = eventsHandler;
            this.frameCount = 0;
            this.freezePeriodCount = 0;
            surfaceTextureHelper.getHandler().postDelayed(this.cameraObserver, 2000L);
         }
      }

      private void checkThread() {
         if (Thread.currentThread() != this.surfaceTextureHelper.getHandler().getLooper().getThread()) {
            throw new IllegalStateException("Wrong thread");
         }
      }

      public void addFrame() {
         this.checkThread();
         if (!released) {
            ++this.frameCount;
         }
      }

      public void release() {
         this.released = true;
         this.surfaceTextureHelper.getHandler().removeCallbacks(this.cameraObserver);
      }
   }

   /** @deprecated */
   @Deprecated
   public interface MediaRecorderHandler {
      void onMediaRecorderSuccess();

      void onMediaRecorderError(String var1);
   }

   public interface CameraSwitchHandler {
      void onCameraSwitchDone(boolean var1);

      void onCameraSwitchError(String var1);
   }

   public interface CameraEventsHandler {
      void onCameraError(String var1);

      void onCameraDisconnected();

      void onCameraFreezed(String var1);

      void onCameraOpening(String var1);

      void onFirstFrameAvailable();

      void onCameraClosed();
   }
}
