package org.webrtc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

abstract class CameraCapturer implements CameraVideoCapturer {
   private static final String TAG = "CameraCapturer";
   private static final int MAX_OPEN_CAMERA_ATTEMPTS = 3;
   private static final int OPEN_CAMERA_DELAY_MS = 500;
   private static final int OPEN_CAMERA_TIMEOUT = 10000;
   private final CameraEnumerator cameraEnumerator;
   private final CameraVideoCapturer.CameraEventsHandler eventsHandler;
   private final Handler uiThreadHandler;
   @Nullable
   private final CameraSession.CreateSessionCallback createSessionCallback = new CameraSession.CreateSessionCallback() {
      public void onDone(CameraSession session) {
         CameraCapturer.this.checkIsOnCameraThread();
         Logging.d("CameraCapturer", "Create session done. Switch state: " + CameraCapturer.this.switchState);
         CameraCapturer.this.uiThreadHandler.removeCallbacks(CameraCapturer.this.openCameraTimeoutRunnable);
         synchronized(CameraCapturer.this.stateLock) {
            CameraCapturer.this.capturerObserver.onCapturerStarted(true);
            CameraCapturer.this.sessionOpening = false;
            CameraCapturer.this.currentSession = session;
            CameraCapturer.this.cameraStatistics = new CameraVideoCapturer.CameraStatistics(CameraCapturer.this.surfaceHelper, CameraCapturer.this.eventsHandler);
            CameraCapturer.this.firstFrameObserved = false;
            CameraCapturer.this.frameSkipCounter = 0;
            CameraCapturer.this.frameSkipRatio = 1;
            CameraCapturer.this.consecutiveOverloadCount = 0;
            CameraCapturer.this.stateLock.notifyAll();
            if (CameraCapturer.this.switchState == CameraCapturer.SwitchState.IN_PROGRESS) {
               CameraCapturer.this.switchState = CameraCapturer.SwitchState.IDLE;
               if (CameraCapturer.this.switchEventsHandler != null) {
                  CameraCapturer.this.switchEventsHandler.onCameraSwitchDone(CameraCapturer.this.cameraEnumerator.isFrontFacing(CameraCapturer.this.cameraName));
                  CameraCapturer.this.switchEventsHandler = null;
               }
            } else if (CameraCapturer.this.switchState == CameraCapturer.SwitchState.PENDING) {
               String selectedCameraName = CameraCapturer.this.pendingCameraName;
               CameraCapturer.this.pendingCameraName = null;
               CameraCapturer.this.switchState = CameraCapturer.SwitchState.IDLE;
               CameraCapturer.this.switchCameraInternal(CameraCapturer.this.switchEventsHandler, selectedCameraName);
            }

         }
      }

      public void onFailure(CameraSession.FailureType failureType, String error) {
         CameraCapturer.this.checkIsOnCameraThread();
         CameraCapturer.this.uiThreadHandler.removeCallbacks(CameraCapturer.this.openCameraTimeoutRunnable);
         synchronized(CameraCapturer.this.stateLock) {
            CameraCapturer.this.capturerObserver.onCapturerStarted(false);
            --CameraCapturer.this.openAttemptsRemaining;
            if (CameraCapturer.this.openAttemptsRemaining <= 0) {
               Logging.w("CameraCapturer", "Opening camera failed, passing: " + error);
               CameraCapturer.this.sessionOpening = false;
               CameraCapturer.this.stateLock.notifyAll();
               if (CameraCapturer.this.switchState != CameraCapturer.SwitchState.IDLE) {
                  if (CameraCapturer.this.switchEventsHandler != null) {
                     CameraCapturer.this.switchEventsHandler.onCameraSwitchError(error);
                     CameraCapturer.this.switchEventsHandler = null;
                  }

                  CameraCapturer.this.switchState = CameraCapturer.SwitchState.IDLE;
               }

               if (failureType == CameraSession.FailureType.DISCONNECTED) {
                  CameraCapturer.this.eventsHandler.onCameraDisconnected();
               } else {
                  CameraCapturer.this.eventsHandler.onCameraError(error);
               }
            } else {
               Logging.w("CameraCapturer", "Opening camera failed, retry: " + error);
               CameraCapturer.this.createSessionInternal(500);
            }

         }
      }
   };
   @Nullable
   private final CameraSession.Events cameraSessionEventsHandler = new CameraSession.Events() {
      public void onCameraOpening() {
         CameraCapturer.this.checkIsOnCameraThread();
         synchronized(CameraCapturer.this.stateLock) {
            if (CameraCapturer.this.currentSession != null) {
               Logging.w("CameraCapturer", "onCameraOpening while session was open.");
            } else {
               CameraCapturer.this.eventsHandler.onCameraOpening(CameraCapturer.this.cameraName);
            }
         }
      }

      public void onCameraError(CameraSession session, String error) {
         CameraCapturer.this.checkIsOnCameraThread();
         synchronized(CameraCapturer.this.stateLock) {
            if (session != CameraCapturer.this.currentSession) {
               Logging.w("CameraCapturer", "onCameraError from another session: " + error);
            } else {
               CameraCapturer.this.eventsHandler.onCameraError(error);
               CameraCapturer.this.stopCapture();
            }
         }
      }

      public void onCameraDisconnected(CameraSession session) {
         CameraCapturer.this.checkIsOnCameraThread();
         synchronized(CameraCapturer.this.stateLock) {
            if (session != CameraCapturer.this.currentSession) {
               Logging.w("CameraCapturer", "onCameraDisconnected from another session.");
            } else {
               CameraCapturer.this.eventsHandler.onCameraDisconnected();
               CameraCapturer.this.stopCapture();
            }
         }
      }

      public void onCameraClosed(CameraSession session) {
         CameraCapturer.this.checkIsOnCameraThread();
         synchronized(CameraCapturer.this.stateLock) {
            if (session != CameraCapturer.this.currentSession && CameraCapturer.this.currentSession != null) {
               Logging.d("CameraCapturer", "onCameraClosed from another session.");
            } else {
               CameraCapturer.this.eventsHandler.onCameraClosed();
            }
         }
      }

      public void onFrameCaptured(CameraSession session, VideoFrame frame) {
         CameraCapturer.this.checkIsOnCameraThread();
         synchronized(CameraCapturer.this.stateLock) {
            if (session != CameraCapturer.this.currentSession) {
               Logging.w("CameraCapturer", "onFrameCaptured from another session.");
               return;
            }
            
            // Implement frame skipping for backpressure
            CameraCapturer.this.frameSkipCounter++;
            if (CameraCapturer.this.frameSkipCounter % CameraCapturer.this.frameSkipRatio != 0) {
               // Skip this frame
               if (CameraCapturer.this.frameSkipRatio > 1) {
                  Logging.d("CameraCapturer", "Skipping frame (ratio: " + CameraCapturer.this.frameSkipRatio + ", counter: " + CameraCapturer.this.frameSkipCounter + ")");
               }
               return;
            }
            
            // Check encoder queue before processing frame
            if (CameraCapturer.this.encoder != null) {
               int pendingFrames = CameraCapturer.this.encoder.getPendingInputFrames();
               if (pendingFrames >= 3) { // Drop frames earlier to prevent queue overflow
                  // Drop frame to prevent encoder queue overflow
                  CameraCapturer.this.consecutiveOverloadCount++;
                  Logging.d("CameraCapturer", "Dropping frame, encoder queue full (pending: " + pendingFrames + ", consecutive overloads: " + CameraCapturer.this.consecutiveOverloadCount + ")");
                  
                  // If consistently overloaded, increase frame skipping aggressively
                  if (CameraCapturer.this.consecutiveOverloadCount >= MAX_CONSECUTIVE_OVERLOADS) {
                     CameraCapturer.this.frameSkipRatio = Math.min(CameraCapturer.this.frameSkipRatio + 2, 8); // More aggressive skipping
                     CameraCapturer.this.consecutiveOverloadCount = 0; // Reset counter
                     Logging.w("CameraCapturer", "Encoder consistently overloaded, increasing frame skip ratio to " + CameraCapturer.this.frameSkipRatio);
                  }
                  return;
               } else {
                  // Reset overload counter when encoder is not overloaded
                  CameraCapturer.this.consecutiveOverloadCount = 0;
               }
            }
            
            if (!CameraCapturer.this.firstFrameObserved) {
               CameraCapturer.this.eventsHandler.onFirstFrameAvailable();
               CameraCapturer.this.firstFrameObserved = true;
            }

            CameraCapturer.this.cameraStatistics.addFrame();
            CameraCapturer.this.capturerObserver.onFrameCaptured(frame);
            CameraCapturer.this.maybeAdaptFps();
         }
      }
   };
   private final Runnable openCameraTimeoutRunnable = new Runnable() {
      public void run() {
         CameraCapturer.this.eventsHandler.onCameraError("Camera failed to start within timeout.");
      }
   };
   private Handler cameraThreadHandler;
   private Context applicationContext;
   private CapturerObserver capturerObserver;
   private SurfaceTextureHelper surfaceHelper;
   private final Object stateLock = new Object();
   private boolean sessionOpening;
   @Nullable
   private CameraSession currentSession;
   private String cameraName;
   private String pendingCameraName;
   private int width;
   private int height;
   private int framerate;
   private int openAttemptsRemaining;
   private CameraCapturer.SwitchState switchState;
   @Nullable
   private CameraVideoCapturer.CameraSwitchHandler switchEventsHandler;
   @Nullable
   private CameraVideoCapturer.CameraStatistics cameraStatistics;
   private boolean firstFrameObserved;
   @Nullable
   private HardwareVideoEncoder encoder;
   private int targetFramerate;
   private long lastFpsAdaptationTimeMs = 0;
   private static final int FPS_ADAPTATION_INTERVAL_MS = 100;
   private int frameSkipCounter = 0;
   private int frameSkipRatio = 1; // 1 = no skipping, 2 = skip every other frame, etc.
   private int consecutiveOverloadCount = 0;
   private static final int MAX_CONSECUTIVE_OVERLOADS = 10;

   public CameraCapturer(String cameraName, @Nullable CameraVideoCapturer.CameraEventsHandler eventsHandler, CameraEnumerator cameraEnumerator) {
      this.switchState = CameraCapturer.SwitchState.IDLE;
      if (eventsHandler == null) {
         eventsHandler = new CameraVideoCapturer.CameraEventsHandler() {
            public void onCameraError(String errorDescription) {
            }

            public void onCameraDisconnected() {
            }

            public void onCameraFreezed(String errorDescription) {
            }

            public void onCameraOpening(String cameraName) {
            }

            public void onFirstFrameAvailable() {
            }

            public void onCameraClosed() {
            }
         };
      }

      this.eventsHandler = eventsHandler;
      this.cameraEnumerator = cameraEnumerator;
      this.cameraName = cameraName;
      List<String> deviceNames = Arrays.asList(cameraEnumerator.getDeviceNames());
      this.uiThreadHandler = new Handler(Looper.getMainLooper());
      if (deviceNames.isEmpty()) {
         throw new RuntimeException("No cameras attached.");
      } else if (!deviceNames.contains(this.cameraName)) {
         throw new IllegalArgumentException("Camera name " + this.cameraName + " does not match any known camera device.");
      }
   }

   public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext, CapturerObserver capturerObserver) {
      this.applicationContext = applicationContext;
      this.capturerObserver = capturerObserver;
      this.surfaceHelper = surfaceTextureHelper;
      this.cameraThreadHandler = surfaceTextureHelper.getHandler();
   }

   public void startCapture(int width, int height, int framerate) {
      Logging.d("CameraCapturer", "startCapture: " + width + "x" + height + "@" + framerate);
      if (this.applicationContext == null) {
         throw new RuntimeException("CameraCapturer must be initialized before calling startCapture.");
      } else {
         synchronized(this.stateLock) {
            if (!this.sessionOpening && this.currentSession == null) {
               this.width = width;
               this.height = height;
               this.framerate = framerate;
               this.targetFramerate = framerate;
               this.sessionOpening = true;
               this.openAttemptsRemaining = 3;
               this.createSessionInternal(0);
            } else {
               Logging.w("CameraCapturer", "Session already open");
            }
         }
      }
   }

   private void createSessionInternal(int delayMs) {
      this.uiThreadHandler.postDelayed(this.openCameraTimeoutRunnable, (long)(delayMs + 10000));
      this.cameraThreadHandler.postDelayed(new Runnable() {
         public void run() {
            CameraCapturer.this.createCameraSession(CameraCapturer.this.createSessionCallback, CameraCapturer.this.cameraSessionEventsHandler, CameraCapturer.this.applicationContext, CameraCapturer.this.surfaceHelper, CameraCapturer.this.cameraName, CameraCapturer.this.width, CameraCapturer.this.height, CameraCapturer.this.framerate);
         }
      }, (long)delayMs);
   }

   public void stopCapture() {
      Logging.d("CameraCapturer", "Stop capture");
      synchronized(this.stateLock) {
         while(this.sessionOpening) {
            Logging.d("CameraCapturer", "Stop capture: Waiting for session to open");

            try {
               this.stateLock.wait();
            } catch (InterruptedException var4) {
               Logging.w("CameraCapturer", "Stop capture interrupted while waiting for the session to open.");
               Thread.currentThread().interrupt();
               return;
            }
         }

         if (this.currentSession != null) {
            Logging.d("CameraCapturer", "Stop capture: Nulling session");
            this.cameraStatistics.release();
            this.cameraStatistics = null;
            final CameraSession oldSession = this.currentSession;
            this.cameraThreadHandler.post(new Runnable() {
               public void run() {
                  oldSession.stop();
               }
            });
            this.currentSession = null;
            this.capturerObserver.onCapturerStopped();
         } else {
            Logging.d("CameraCapturer", "Stop capture: No session open");
         }
      }

      Logging.d("CameraCapturer", "Stop capture done");
   }

   public void changeCaptureFormat(int width, int height, int framerate) {
      Logging.d("CameraCapturer", "changeCaptureFormat: " + width + "x" + height + "@" + framerate);
      synchronized(this.stateLock) {
         this.stopCapture();
         this.startCapture(width, height, framerate);
      }
   }

   public void dispose() {
      Logging.d("CameraCapturer", "dispose");
      this.stopCapture();
   }

   public void switchCamera(final CameraVideoCapturer.CameraSwitchHandler switchEventsHandler) {
      Logging.d("CameraCapturer", "switchCamera");
      this.cameraThreadHandler.post(new Runnable() {
         public void run() {
            List<String> deviceNames = Arrays.asList(CameraCapturer.this.cameraEnumerator.getDeviceNames());
            if (deviceNames.size() < 2) {
               CameraCapturer.this.reportCameraSwitchError("No camera to switch to.", switchEventsHandler);
            } else {
               int cameraNameIndex = deviceNames.indexOf(CameraCapturer.this.cameraName);
               String cameraName = (String)deviceNames.get((cameraNameIndex + 1) % deviceNames.size());
               CameraCapturer.this.switchCameraInternal(switchEventsHandler, cameraName);
            }
         }
      });
   }

   public void switchCamera(final CameraVideoCapturer.CameraSwitchHandler switchEventsHandler, final String cameraName) {
      Logging.d("CameraCapturer", "switchCamera");
      this.cameraThreadHandler.post(new Runnable() {
         public void run() {
            CameraCapturer.this.switchCameraInternal(switchEventsHandler, cameraName);
         }
      });
   }

   public boolean isScreencast() {
      return false;
   }

   public void printStackTrace() {
      Thread cameraThread = null;
      if (this.cameraThreadHandler != null) {
         cameraThread = this.cameraThreadHandler.getLooper().getThread();
      }

      if (cameraThread != null) {
         StackTraceElement[] cameraStackTrace = cameraThread.getStackTrace();
         if (cameraStackTrace.length > 0) {
            Logging.d("CameraCapturer", "CameraCapturer stack trace:");
            StackTraceElement[] var3 = cameraStackTrace;
            int var4 = cameraStackTrace.length;

            for(int var5 = 0; var5 < var4; ++var5) {
               StackTraceElement traceElem = var3[var5];
               Logging.d("CameraCapturer", traceElem.toString());
            }
         }
      }

   }

   private void reportCameraSwitchError(String error, @Nullable CameraVideoCapturer.CameraSwitchHandler switchEventsHandler) {
      Logging.e("CameraCapturer", error);
      if (switchEventsHandler != null) {
         switchEventsHandler.onCameraSwitchError(error);
      }

   }

   private void switchCameraInternal(@Nullable CameraVideoCapturer.CameraSwitchHandler switchEventsHandler, String selectedCameraName) {
      Logging.d("CameraCapturer", "switchCamera internal");
      List<String> deviceNames = Arrays.asList(this.cameraEnumerator.getDeviceNames());
      if (!deviceNames.contains(selectedCameraName)) {
         this.reportCameraSwitchError("Attempted to switch to unknown camera device " + selectedCameraName, switchEventsHandler);
      } else {
         synchronized(this.stateLock) {
            if (this.switchState != CameraCapturer.SwitchState.IDLE) {
               this.reportCameraSwitchError("Camera switch already in progress.", switchEventsHandler);
               return;
            }

            if (!this.sessionOpening && this.currentSession == null) {
               this.reportCameraSwitchError("switchCamera: camera is not running.", switchEventsHandler);
               return;
            }

            this.switchEventsHandler = switchEventsHandler;
            if (this.sessionOpening) {
               this.switchState = CameraCapturer.SwitchState.PENDING;
               this.pendingCameraName = selectedCameraName;
               return;
            }

            this.switchState = CameraCapturer.SwitchState.IN_PROGRESS;
            Logging.d("CameraCapturer", "switchCamera: Stopping session");
            this.cameraStatistics.release();
            this.cameraStatistics = null;
            final CameraSession oldSession = this.currentSession;
            this.cameraThreadHandler.post(new Runnable() {
               public void run() {
                  oldSession.stop();
               }
            });
            this.currentSession = null;
            this.cameraName = selectedCameraName;
            this.sessionOpening = true;
            this.openAttemptsRemaining = 1;
            this.createSessionInternal(0);
         }

         Logging.d("CameraCapturer", "switchCamera done");
      }
   }

   public void setEncoder(HardwareVideoEncoder encoder) {
      this.encoder = encoder;
   }

   private void maybeAdaptFps() {
      if (this.encoder == null) {
         return;
      }

      long currentTimeMs = System.currentTimeMillis();
      if (currentTimeMs - this.lastFpsAdaptationTimeMs < FPS_ADAPTATION_INTERVAL_MS) {
         return;
      }
      this.lastFpsAdaptationTimeMs = currentTimeMs;

      int pendingFrames = this.encoder.getPendingInputFrames();
      int newFrameSkipRatio;

      if (pendingFrames >= 2) { // Start skipping when queue is half full
         // Encoder queue is getting full, increase frame skipping
         newFrameSkipRatio = Math.min(this.frameSkipRatio + 1, 4); // Max skip 3 out of 4 frames
      } else if (pendingFrames == 0) { // Only reduce skipping when queue is completely empty
         // Encoder queue is empty, reduce frame skipping
         newFrameSkipRatio = Math.max(this.frameSkipRatio - 1, 1); // Min skip ratio is 1 (no skipping)
      } else {
         // Encoder queue has 1 frame, maintain current skip ratio
         return;
      }

      if (newFrameSkipRatio != this.frameSkipRatio) {
         Logging.d("CameraCapturer", "Adapting frame skip ratio from " + this.frameSkipRatio + " to " + newFrameSkipRatio + " (pending frames: " + pendingFrames + ")");
         this.frameSkipRatio = newFrameSkipRatio;
         // Reset counter to avoid phase issues
         this.frameSkipCounter = 0;
      }
   }

   private void checkIsOnCameraThread() {
      if (Thread.currentThread() != this.cameraThreadHandler.getLooper().getThread()) {
         Logging.e("CameraCapturer", "Check is on camera thread failed.");
         throw new RuntimeException("Not on camera thread.");
      }
   }

   protected String getCameraName() {
      synchronized(this.stateLock) {
         return this.cameraName;
      }
   }

   protected abstract void createCameraSession(CameraSession.CreateSessionCallback var1, CameraSession.Events var2, Context var3, SurfaceTextureHelper var4, String var5, int var6, int var7, int var8);

   static enum SwitchState {
      IDLE,
      PENDING,
      IN_PROGRESS;

      // $FF: synthetic method
      private static CameraCapturer.SwitchState[] $values() {
         return new CameraCapturer.SwitchState[]{IDLE, PENDING, IN_PROGRESS};
      }
   }
}
