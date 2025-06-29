package org.webrtc;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CameraDevice.StateCallback;
import android.hardware.camera2.CaptureRequest.Builder;
import android.os.Handler;
import android.util.Range;
import android.view.Surface;
import androidx.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

class Camera2Session implements CameraSession {
   private static final String TAG = "Camera2Session";
   private static final Histogram camera2StartTimeMsHistogram = Histogram.createCounts("WebRTC.Android.Camera2.StartTimeMs", 1, 10000, 50);
   private static final Histogram camera2StopTimeMsHistogram = Histogram.createCounts("WebRTC.Android.Camera2.StopTimeMs", 1, 10000, 50);
   private static final Histogram camera2ResolutionHistogram;
   private final Handler cameraThreadHandler;
   private final CameraSession.CreateSessionCallback callback;
   private final CameraSession.Events events;
   private final Context applicationContext;
   private final CameraManager cameraManager;
   private final SurfaceTextureHelper surfaceTextureHelper;
   private final String cameraId;
   private final int width;
   private final int height;
   private final int framerate;
   private CameraCharacteristics cameraCharacteristics;
   private int cameraOrientation;
   private boolean isCameraFrontFacing;
   private int fpsUnitFactor;
   private CameraEnumerationAndroid.CaptureFormat captureFormat;
   @Nullable
   private CameraDevice cameraDevice;
   @Nullable
   private Surface surface;
   @Nullable
   private CameraCaptureSession captureSession;
   private Camera2Session.SessionState state;
   private boolean firstFrameReported;
   private final long constructionTimeNs;

   public static void create(CameraSession.CreateSessionCallback callback, CameraSession.Events events, Context applicationContext, CameraManager cameraManager, SurfaceTextureHelper surfaceTextureHelper, String cameraId, int width, int height, int framerate) {
      new Camera2Session(callback, events, applicationContext, cameraManager, surfaceTextureHelper, cameraId, width, height, framerate);
   }

   private Camera2Session(CameraSession.CreateSessionCallback callback, CameraSession.Events events, Context applicationContext, CameraManager cameraManager, SurfaceTextureHelper surfaceTextureHelper, String cameraId, int width, int height, int framerate) {
      this.state = Camera2Session.SessionState.RUNNING;
      Logging.d("Camera2Session", "Create new camera2 session on camera " + cameraId);
      this.constructionTimeNs = System.nanoTime();
      this.cameraThreadHandler = new Handler();
      this.callback = callback;
      this.events = events;
      this.applicationContext = applicationContext;
      this.cameraManager = cameraManager;
      this.surfaceTextureHelper = surfaceTextureHelper;
      this.cameraId = cameraId;
      this.width = width;
      this.height = height;
      this.framerate = framerate;
      this.start();
   }

   private void start() {
      this.checkIsOnCameraThread();
      Logging.d("Camera2Session", "start");

      try {
         this.cameraCharacteristics = this.cameraManager.getCameraCharacteristics(this.cameraId);
      } catch (IllegalArgumentException | CameraAccessException var2) {
         this.reportError("getCameraCharacteristics(): " + var2.getMessage());
         return;
      }

      this.cameraOrientation = (Integer)this.cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
      this.isCameraFrontFacing = (Integer)this.cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == 0;
      this.findCaptureFormat();
      if (this.captureFormat != null) {
         this.openCamera();
      }
   }

   private void findCaptureFormat() {
      this.checkIsOnCameraThread();
      Range<Integer>[] fpsRanges = (Range[])this.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
      this.fpsUnitFactor = Camera2Enumerator.getFpsUnitFactor(fpsRanges);
      List<CameraEnumerationAndroid.CaptureFormat.FramerateRange> framerateRanges = Camera2Enumerator.convertFramerates(fpsRanges, this.fpsUnitFactor);
      List<Size> sizes = Camera2Enumerator.getSupportedSizes(this.cameraCharacteristics);
      Logging.d("Camera2Session", "Available preview sizes: " + sizes);
      Logging.d("Camera2Session", "Available fps ranges: " + framerateRanges);
      if (!framerateRanges.isEmpty() && !sizes.isEmpty()) {
         CameraEnumerationAndroid.CaptureFormat.FramerateRange bestFpsRange = CameraEnumerationAndroid.getClosestSupportedFramerateRange(framerateRanges, this.framerate);
         Size bestSize = CameraEnumerationAndroid.getClosestSupportedSize(sizes, this.width, this.height);
         CameraEnumerationAndroid.reportCameraResolution(camera2ResolutionHistogram, bestSize);
         this.captureFormat = new CameraEnumerationAndroid.CaptureFormat(bestSize.width, bestSize.height, bestFpsRange);
         Logging.d("Camera2Session", "Using capture format: " + this.captureFormat);
      } else {
         this.reportError("No supported capture formats.");
      }
   }

   private void openCamera() {
      this.checkIsOnCameraThread();
      Logging.d("Camera2Session", "Opening camera " + this.cameraId);
      this.events.onCameraOpening();

      try {
         this.cameraManager.openCamera(this.cameraId, new Camera2Session.CameraStateCallback(), this.cameraThreadHandler);
      } catch (IllegalArgumentException | SecurityException | CameraAccessException var2) {
         this.reportError("Failed to open camera: " + var2);
      }
   }

   public void stop() {
      Logging.d("Camera2Session", "Stop camera2 session on camera " + this.cameraId);
      this.checkIsOnCameraThread();
      if (this.state != Camera2Session.SessionState.STOPPED) {
         long stopStartTime = System.nanoTime();
         this.state = Camera2Session.SessionState.STOPPED;
         this.stopInternal();
         int stopTimeMs = (int)TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
         camera2StopTimeMsHistogram.addSample(stopTimeMs);
      }

   }

   private void stopInternal() {
      Logging.d("Camera2Session", "Stop internal");
      this.checkIsOnCameraThread();
      this.surfaceTextureHelper.stopListening();
      if (this.captureSession != null) {
         this.captureSession.close();
         this.captureSession = null;
      }

      if (this.surface != null) {
         this.surface.release();
         this.surface = null;
      }

      if (this.cameraDevice != null) {
         this.cameraDevice.close();
         this.cameraDevice = null;
      }

      Logging.d("Camera2Session", "Stop done");
   }

   private void reportError(String error) {
      this.checkIsOnCameraThread();
      Logging.e("Camera2Session", "Error: " + error);
      boolean startFailure = this.captureSession == null && this.state != Camera2Session.SessionState.STOPPED;
      this.state = Camera2Session.SessionState.STOPPED;
      this.stopInternal();
      if (startFailure) {
         this.callback.onFailure(CameraSession.FailureType.ERROR, error);
      } else {
         this.events.onCameraError(this, error);
      }

   }

   private int getFrameOrientation() {
      int rotation = CameraSession.getDeviceOrientation(this.applicationContext);
      if (!this.isCameraFrontFacing) {
         rotation = 360 - rotation;
      }

      return (this.cameraOrientation + rotation) % 360;
   }

   private void checkIsOnCameraThread() {
      if (Thread.currentThread() != this.cameraThreadHandler.getLooper().getThread()) {
         throw new IllegalStateException("Wrong thread");
      }
   }

   static {
      camera2ResolutionHistogram = Histogram.createEnumeration("WebRTC.Android.Camera2.Resolution", CameraEnumerationAndroid.COMMON_RESOLUTIONS.size());
   }

   private static enum SessionState {
      RUNNING,
      STOPPED;

      // $FF: synthetic method
      private static Camera2Session.SessionState[] $values() {
         return new Camera2Session.SessionState[]{RUNNING, STOPPED};
      }
   }

   private class CameraStateCallback extends StateCallback {
      private String getErrorDescription(int errorCode) {
         switch(errorCode) {
         case 1:
            return "Camera device is in use already.";
         case 2:
            return "Camera device could not be opened because there are too many other open camera devices.";
         case 3:
            return "Camera device could not be opened due to a device policy.";
         case 4:
            return "Camera device has encountered a fatal error.";
         case 5:
            return "Camera service has encountered a fatal error.";
         default:
            return "Unknown camera error: " + errorCode;
         }
      }

      public void onDisconnected(CameraDevice camera) {
         Camera2Session.this.checkIsOnCameraThread();
         boolean startFailure = Camera2Session.this.captureSession == null && Camera2Session.this.state != Camera2Session.SessionState.STOPPED;
         Camera2Session.this.state = Camera2Session.SessionState.STOPPED;
         Camera2Session.this.stopInternal();
         if (startFailure) {
            Camera2Session.this.callback.onFailure(CameraSession.FailureType.DISCONNECTED, "Camera disconnected / evicted.");
         } else {
            Camera2Session.this.events.onCameraDisconnected(Camera2Session.this);
         }

      }

      public void onError(CameraDevice camera, int errorCode) {
         Camera2Session.this.checkIsOnCameraThread();
         Camera2Session.this.reportError(this.getErrorDescription(errorCode));
      }

      public void onOpened(CameraDevice camera) {
         Camera2Session.this.checkIsOnCameraThread();
         Logging.d("Camera2Session", "Camera opened.");
         Camera2Session.this.cameraDevice = camera;
         Camera2Session.this.surfaceTextureHelper.setTextureSize(Camera2Session.this.captureFormat.width, Camera2Session.this.captureFormat.height);
         Camera2Session.this.surface = new Surface(Camera2Session.this.surfaceTextureHelper.getSurfaceTexture());

         try {
            camera.createCaptureSession(Arrays.asList(Camera2Session.this.surface), Camera2Session.this.new CaptureSessionCallback(), Camera2Session.this.cameraThreadHandler);
         } catch (CameraAccessException var3) {
            Camera2Session.this.reportError("Failed to create capture session. " + var3);
         }
      }

      public void onClosed(CameraDevice camera) {
         Camera2Session.this.checkIsOnCameraThread();
         Logging.d("Camera2Session", "Camera device closed.");
         Camera2Session.this.events.onCameraClosed(Camera2Session.this);
      }
   }

   private static class CameraCaptureCallback extends CaptureCallback {
      public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
         Logging.d("Camera2Session", "Capture failed: " + failure);
      }
   }

   private class CaptureSessionCallback extends android.hardware.camera2.CameraCaptureSession.StateCallback {
      public void onConfigureFailed(CameraCaptureSession session) {
         Camera2Session.this.checkIsOnCameraThread();
         session.close();
         Camera2Session.this.reportError("Failed to configure capture session.");
      }

      public void onConfigured(CameraCaptureSession session) {
         Camera2Session.this.checkIsOnCameraThread();
         Logging.d("Camera2Session", "Camera capture session configured.");
         Camera2Session.this.captureSession = session;

         try {
            Builder captureRequestBuilder = Camera2Session.this.cameraDevice.createCaptureRequest(3);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range(Camera2Session.this.captureFormat.framerate.min / Camera2Session.this.fpsUnitFactor, Camera2Session.this.captureFormat.framerate.max / Camera2Session.this.fpsUnitFactor));
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, 1);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
            this.chooseStabilizationMode(captureRequestBuilder);
            this.chooseFocusMode(captureRequestBuilder);
            captureRequestBuilder.addTarget(Camera2Session.this.surface);
            session.setRepeatingRequest(captureRequestBuilder.build(), new Camera2Session.CameraCaptureCallback(), Camera2Session.this.cameraThreadHandler);
         } catch (CameraAccessException var3) {
            Camera2Session.this.reportError("Failed to start capture request. " + var3);
            return;
         }

         Camera2Session.this.surfaceTextureHelper.startListening((frame) -> {
            Camera2Session.this.checkIsOnCameraThread();
            if (Camera2Session.this.state != Camera2Session.SessionState.RUNNING) {
               Logging.d("Camera2Session", "Texture frame captured but camera is no longer running.");
            } else {
               if (!Camera2Session.this.firstFrameReported) {
                  Camera2Session.this.firstFrameReported = true;
                  int startTimeMs = (int)TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - Camera2Session.this.constructionTimeNs);
                  Camera2Session.camera2StartTimeMsHistogram.addSample(startTimeMs);
               }

               VideoFrame modifiedFrame = new VideoFrame(CameraSession.createTextureBufferWithModifiedTransformMatrix((TextureBufferImpl)frame.getBuffer(), Camera2Session.this.isCameraFrontFacing, -Camera2Session.this.cameraOrientation), Camera2Session.this.getFrameOrientation(), frame.getTimestampNs());
               Camera2Session.this.events.onFrameCaptured(Camera2Session.this, modifiedFrame);
               modifiedFrame.release();
            }
         });
         Logging.d("Camera2Session", "Camera device successfully started.");
         Camera2Session.this.callback.onDone(Camera2Session.this);
      }

      private void chooseStabilizationMode(Builder captureRequestBuilder) {
         int[] availableOpticalStabilization = (int[])Camera2Session.this.cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
         int[] availableVideoStabilization;
         int var5;
         int mode;
         if (availableOpticalStabilization != null) {
            availableVideoStabilization = availableOpticalStabilization;
            int var4 = availableOpticalStabilization.length;

            for(var5 = 0; var5 < var4; ++var5) {
               mode = availableVideoStabilization[var5];
               if (mode == 1) {
                  captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 1);
                  captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 0);
                  Logging.d("Camera2Session", "Using optical stabilization.");
                  return;
               }
            }
         }

         availableVideoStabilization = (int[])Camera2Session.this.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
         if (availableVideoStabilization != null) {
            int[] var8 = availableVideoStabilization;
            var5 = availableVideoStabilization.length;

            for(mode = 0; mode < var5; ++mode) {
               int modex = var8[mode];
               if (modex == 1) {
                  captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 1);
                  captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 0);
                  Logging.d("Camera2Session", "Using video stabilization.");
                  return;
               }
            }
         }

         Logging.d("Camera2Session", "Stabilization not available.");
      }

      private void chooseFocusMode(Builder captureRequestBuilder) {
         int[] availableFocusModes = (int[])Camera2Session.this.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
         int[] var3 = availableFocusModes;
         int var4 = availableFocusModes.length;

         for(int var5 = 0; var5 < var4; ++var5) {
            int mode = var3[var5];
            if (mode == 3) {
               captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, 3);
               Logging.d("Camera2Session", "Using continuous video auto-focus.");
               return;
            }
         }

         Logging.d("Camera2Session", "Auto-focus is not available.");
      }
   }
}
