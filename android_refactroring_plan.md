### Detailed Analysis

#### Evidence from Logs:

1.  **System Overload and Frame Drops:**
    *   `W/ImageReader_JNI(10658): Unable to acquire a buffer item, very likely client tried to acquire more than maxImages buffers`: This is the most telling log. The camera is producing frames faster than your application can process them. The buffer queue is full, and new frames are being dropped before they even reach your WebRTC or recording logic.
    *   `I/org.webrtc.Logging(10658): CameraStatistics: Camera fps: 24.` dropping to `Camera fps: 15.`: The camera's frame rate is plummeting, a clear sign of system stress.

2.  **Recording Failure:**
    *   `[INFO: WebRtcVideoRecorderImpl] 📹 Starting recording with path: ...`: The recording process is correctly initiated.
    *   `D/MPEG4Writer(10658): Video skip non-key frame`: This log confirms that the `MPEG4Writer` is not receiving a keyframe (I-frame) at the beginning of the recording, which is essential to start a video file.
    *   `I/MPEG4Writer(10658): The mp4 file will not be streamable.`: This indicates that the `MPEG4Writer` failed to properly finalize the MP4 container, often because no valid video data was written.
    *   `[INFO: DetectMotionUseCase] ⏱️ [_handleRecording] Recording timeout - resetting recording flag`: The recording is not stopping cleanly but is being terminated by a timeout, which is another symptom of the underlying process being stuck or failing.

#### Evidence from Source Code:

1.  **First Encoding Path (WebRTC Streaming):**
    *   `MethodCallHandlerImpl.java` and `PeerConnectionObserver.java` set up the standard WebRTC pipeline.
    *   `HardwareVideoEncoder.java` is used to encode the video frames from the `VideoCapturer` for the real-time stream. It takes raw `VideoFrame` objects and outputs `EncodedImage` objects. This is the **first** encoding session.

2.  **Second Encoding Path (File Recording):**
    *   `GetUserMediaImpl.java` in `startRecordingToFile()` creates a `MediaRecorderImpl`.
    *   `MediaRecorderImpl.java` creates a `VideoFileRenderer`.
    *   `VideoFileRenderer.java` is the critical file. It implements `VideoSink`, meaning it receives **raw** `VideoFrame` objects. Inside its `initVideoEncoder()` method, it creates a brand new `MediaCodec` encoder: `encoder = MediaCodec.createEncoderByType(MIME_TYPE);`. This is the **second** encoding session.

Both the WebRTC stream's `HardwareVideoEncoder` and the `VideoFileRenderer`'s `MediaCodec` are being fed the same raw frames from the camera, causing the system to perform the computationally expensive task of video encoding twice.

### The Solution: Encode Once, Use Twice (Multiplexing)

The correct approach is to encode the video frames only once and then distribute the encoded data to both the WebRTC stream and the file recorder.

Here’s how to implement this solution:

#### Step 1: Create a Callback for Encoded Frames

First, create an interface that allows other components to listen for encoded frames coming out of the `HardwareVideoEncoder`.

```java
// In a new file or inside HardwareVideoEncoder.java
public interface EncodedFrameListener {
    void onEncodedFrame(ByteBuffer buffer, MediaCodec.BufferInfo info);
}
```

#### Step 2: Modify `HardwareVideoEncoder` to Distribute Encoded Frames

Modify `HardwareVideoEncoder.java` to accept listeners and forward the encoded data.

```java
// In HardwareVideoEncoder.java

// ... add a list for listeners
private final List<EncodedFrameListener> listeners = new ArrayList<>();

public void addEncodedFrameListener(EncodedFrameListener listener) {
    synchronized (listeners) {
        listeners.add(listener);
    }
}

public void removeEncodedFrameListener(EncodedFrameListener listener) {
    synchronized (listeners) {
        listeners.remove(listener);
    }
}


// ... modify deliverEncodedImage() method
protected void deliverEncodedImage() {
    this.outputThreadChecker.checkIsOnValidThread();
    try {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int index = this.codec.dequeueOutputBuffer(info, 100000L);

        // ... (existing code for handling index < 0)

        if (index >= 0) {
            ByteBuffer outputBuffer = this.codec.getOutputBuffer(index);
            outputBuffer.position(info.offset);
            outputBuffer.limit(info.offset + info.size);

            // *** NEW: Forward encoded data to listeners ***
            // Create a sliced buffer so each listener gets its own view
            synchronized (listeners) {
                for (EncodedFrameListener listener : listeners) {
                    ByteBuffer slicedBuffer = outputBuffer.slice();
                    listener.onEncodedFrame(slicedBuffer, info);
                }
            }
            // *** END NEW CODE ***

            // ... (rest of the original deliverEncodedImage logic for WebRTC)
            if ((info.flags & 2) != 0) {
                // ... handle config buffer
                this.codec.releaseOutputBuffer(index, false);
                return;
            }

            // ... the rest of the method continues as before
            // to handle the WebRTC streaming part.
        }
    } catch (IllegalStateException e) {
        Logging.e(TAG, "deliverOutput failed", e);
    }
}
```

#### Step 3: Refactor `VideoFileRenderer` to be a Muxer, Not an Encoder

This is the most significant change. `VideoFileRenderer` should no longer encode. It will now receive already-encoded frames and just write them to the MP4 container.

```java
// In src/main/java/com/cloudwebrtc/webrtc/record/VideoFileRenderer.java

// Change the class signature. It no longer needs to be a VideoSink.
// It will now listen for encoded frames.
public class VideoFileRenderer implements HardwareVideoEncoder.EncodedFrameListener {
    private static final String TAG = "VideoFileRenderer";
    private final HandlerThread fileThread;
    private final Handler fileThreadHandler;
    private final MediaMuxer mediaMuxer;

    private int videoTrackIndex = -1;
    private boolean isRunning = false;

    // Remove all EGL, Surface, and MediaCodec encoder members.

    public VideoFileRenderer(String outputFile, boolean withAudio) throws IOException {
        fileThread = new HandlerThread(TAG + "FileThread");
        fileThread.start();
        fileThreadHandler = new Handler(fileThread.getLooper());

        mediaMuxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Audio handling would need a similar listener for encoded audio,
        // or it can be added separately. For now, focus on video.
    }

    // This method is called by HardwareVideoEncoder
    @Override
    public void onEncodedFrame(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (!isRunning) return;

        fileThreadHandler.post(() -> {
            if (videoTrackIndex == -1 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // This is the SPS/PPS data. Use it to add the video track to the muxer.
                MediaFormat format = MediaFormat.createVideoFormat(
                    "video/avc", // Assuming H.264
                    /* width, height */ 1280, 720); // You might need to pass these in
                format.setByteBuffer("csd-0", buffer);
                videoTrackIndex = mediaMuxer.addTrack(format);
                mediaMuxer.start();
                Log.d(TAG, "MediaMuxer started with video track.");
            } else if (videoTrackIndex != -1 && info.size != 0) {
                // This is a media frame. Write it to the muxer.
                mediaMuxer.writeSampleData(videoTrackIndex, buffer, info);
            }
        });
    }

    public void start() {
        isRunning = true;
    }

    public void release() {
        isRunning = false;
        fileThreadHandler.post(() -> {
            try {
                if (videoTrackIndex != -1) {
                    mediaMuxer.stop();
                }
                mediaMuxer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing muxer", e);
            } finally {
                fileThread.quitSafely();
            }
        });
    }
}
```

#### Step 4: Update `MediaRecorderImpl` and Integration

Finally, update `MediaRecorderImpl` to use the new `VideoFileRenderer` and hook it up to the `HardwareVideoEncoder`.

```java
// In src/main/java/com/cloudwebrtc/webrtc/record/MediaRecorderImpl.java

public class MediaRecorderImpl {
    private final Integer id;
    private final VideoTrack videoTrack;
    private final HardwareVideoEncoder videoEncoder; // You'll need to get a reference to this
    private VideoFileRenderer videoFileRenderer;

    // The constructor needs to get a reference to the active encoder for the track
    public MediaRecorderImpl(Integer id, @Nullable VideoTrack videoTrack, @Nullable HardwareVideoEncoder videoEncoder, @Nullable AudioSamplesInterceptor audioInterceptor) {
        this.id = id;
        this.videoTrack = videoTrack;
        this.videoEncoder = videoEncoder;
        // ... audio part remains similar ...
    }

    public void startRecording(File file) throws Exception {
        if (isRunning) return;
        isRunning = true;
        file.getParentFile().mkdirs();

        if (videoEncoder != null) {
            videoFileRenderer = new VideoFileRenderer(file.getAbsolutePath(), audioInterceptor != null);
            videoEncoder.addEncodedFrameListener(videoFileRenderer);
            videoFileRenderer.start();
        } else {
            // Handle audio-only or error case
        }
    }

    public void stopRecording() {
        isRunning = false;
        if (videoEncoder != null && videoFileRenderer != null) {
            videoEncoder.removeEncodedFrameListener(videoFileRenderer);
            videoFileRenderer.release();
            videoFileRenderer = null;
        }
        // ... audio part ...
    }
}
```

This refactoring ensures that the heavy lifting of video encoding happens only once. The encoded result is then efficiently routed to both the network for streaming and the file system for recording, solving the performance bottleneck and ensuring your recordings are valid.