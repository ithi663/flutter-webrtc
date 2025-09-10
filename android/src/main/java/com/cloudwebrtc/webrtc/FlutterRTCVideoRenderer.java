package com.cloudwebrtc.webrtc;

import android.util.Log;
import android.graphics.SurfaceTexture;
import android.view.Surface;

import com.cloudwebrtc.webrtc.utils.AnyThreadSink;
import com.cloudwebrtc.webrtc.utils.ConstraintsMap;
import com.cloudwebrtc.webrtc.utils.EglUtils;
import com.cloudwebrtc.webrtc.video.NightVisionRenderer;

import java.util.List;

import org.webrtc.EglBase;
import org.webrtc.MediaStream;
import org.webrtc.RendererCommon;
import org.webrtc.RendererCommon.RendererEvents;
import org.webrtc.VideoTrack;

import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;

public class FlutterRTCVideoRenderer implements EventChannel.StreamHandler {

    private static final String TAG = FlutterWebRTCPlugin.TAG;
    private final TextureRegistry.SurfaceProducer producer;
    private int id = -1;
    private MediaStream mediaStream;

    private String ownerTag;

    // Default intensity for night-vision effect
    public static final float DEFAULT_NIGHT_VISION_INTENSITY = 0.7f;

    // Night-vision state for drawer-based GPU processing (Android only)
    private boolean nightVisionEnabled = false;
    private NightVisionRenderer nightVisionDrawer = null;
    private float nightVisionIntensity = DEFAULT_NIGHT_VISION_INTENSITY;

    // Guard to avoid operations after disposal
    private volatile boolean disposed = false;

    /**
     * The {@code RendererEvents} which listens to rendering events reported by
     * {@link #surfaceTextureRenderer}.
     */
    private RendererEvents rendererEvents;

    private void listenRendererEvents() {
        rendererEvents = new RendererEvents() {
            private int _rotation = -1;
            private int _width = 0, _height = 0;

            @Override
            public void onFirstFrameRendered() {
                ConstraintsMap params = new ConstraintsMap();
                params.putString("event", "didFirstFrameRendered");
                params.putInt("id", id);
                if (eventSink != null) {
                    eventSink.success(params.toMap());
                }
            }

            @Override
            public void onFrameResolutionChanged(
                    int videoWidth, int videoHeight,
                    int rotation) {

                if (eventSink != null) {
                    if (_width != videoWidth || _height != videoHeight) {
                        ConstraintsMap params = new ConstraintsMap();
                        params.putString("event", "didTextureChangeVideoSize");
                        params.putInt("id", id);
                        params.putDouble("width", (double) videoWidth);
                        params.putDouble("height", (double) videoHeight);
                        _width = videoWidth;
                        _height = videoHeight;
                        eventSink.success(params.toMap());
                    }

                    if (_rotation != rotation) {
                        ConstraintsMap params2 = new ConstraintsMap();
                        params2.putString("event", "didTextureChangeRotation");
                        params2.putInt("id", id);
                        params2.putInt("rotation", rotation);
                        _rotation = rotation;
                        eventSink.success(params2.toMap());
                    }
                }
            }
        };
    }

    protected final SurfaceTextureRenderer surfaceTextureRenderer;

    /**
     * The {@code VideoTrack}, if any, rendered by this
     * {@code FlutterRTCVideoRenderer}.
     */
    protected VideoTrack videoTrack;

    EventChannel eventChannel;
    EventChannel.EventSink eventSink;

    public FlutterRTCVideoRenderer(TextureRegistry.SurfaceProducer producer) {
        this.surfaceTextureRenderer = new SurfaceTextureRenderer("");
        listenRendererEvents();
        surfaceTextureRenderer.init(EglUtils.getRootEglBaseContext(), rendererEvents);
        surfaceTextureRenderer.surfaceCreated(producer);

        this.eventSink = null;
        this.producer = producer;
        this.ownerTag = null;
    }

    public void setEventChannel(EventChannel eventChannel) {
        this.eventChannel = eventChannel;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public void onListen(Object o, EventChannel.EventSink sink) {
        if (disposed) return;
        eventSink = new AnyThreadSink(sink);
    }

    @Override
    public void onCancel(Object o) {
        eventSink = null;
    }

    /**
     * Stops rendering {@link #videoTrack} and releases the associated acquired
     * resources (if rendering is in progress).
     */
    private void removeRendererFromVideoTrack() {
        videoTrack.removeSink(surfaceTextureRenderer);
    }

    /**
     * Sets the {@code MediaStream} to be rendered by this
     * {@code FlutterRTCVideoRenderer}.
     * The implementation renders the first {@link VideoTrack}, if any, of the
     * specified {@code mediaStream}.
     *
     * @param mediaStream The {@code MediaStream} to be rendered by this
     *                    {@code FlutterRTCVideoRenderer} or {@code null}.
     */
    public void setStream(MediaStream mediaStream, String ownerTag) {
        if (disposed) return;
        VideoTrack videoTrack;
        this.mediaStream = mediaStream;
        this.ownerTag = ownerTag;
        if (mediaStream == null) {
            videoTrack = null;
        } else {
            List<VideoTrack> videoTracks = mediaStream.videoTracks;

            videoTrack = videoTracks.isEmpty() ? null : videoTracks.get(0);
        }

        setVideoTrack(videoTrack);
    }

    /**
     * Sets the {@code MediaStream} to be rendered by this
     * {@code FlutterRTCVideoRenderer}.
     * The implementation renders the first {@link VideoTrack}, if any, of the
     * specified trackId
     *
     * @param mediaStream The {@code MediaStream} to be rendered by this
     *                    {@code FlutterRTCVideoRenderer} or {@code null}.
     * @param trackId     The {@code trackId} to be rendered by this
     *                    {@code FlutterRTCVideoRenderer} or {@code null}.
     */
    public void setStream(MediaStream mediaStream, String trackId, String ownerTag) {
        if (disposed) return;
        VideoTrack videoTrack;
        this.mediaStream = mediaStream;
        this.ownerTag = ownerTag;
        if (mediaStream == null) {
            videoTrack = null;
        } else {
            List<VideoTrack> videoTracks = mediaStream.videoTracks;

            videoTrack = videoTracks.isEmpty() ? null : videoTracks.get(0);

            for (VideoTrack track : videoTracks) {
                if (track.id().equals(trackId)) {
                    videoTrack = track;
                }
            }
        }

        setVideoTrack(videoTrack);
    }

    /**
     * Sets the {@code VideoTrack} to be rendered by this
     * {@code FlutterRTCVideoRenderer}.
     *
     * @param videoTrack The {@code VideoTrack} to be rendered by this
     *                   {@code FlutterRTCVideoRenderer} or {@code null}.
     */
    public void setVideoTrack(VideoTrack videoTrack) {
        if (disposed) return;
        VideoTrack oldValue = this.videoTrack;

        if (oldValue != videoTrack) {
            if (oldValue != null) {
                removeRendererFromVideoTrack();
            }

            this.videoTrack = videoTrack;

            if (videoTrack != null) {
                try {
                    Log.w(TAG, "FlutterRTCVideoRenderer.setVideoTrack, set video track to " + videoTrack.id());
                    tryAddRendererToVideoTrack();
                } catch (Exception e) {
                    Log.e(TAG, "tryAddRendererToVideoTrack " + e);
                }
            } else {
                Log.w(TAG, "FlutterRTCVideoRenderer.setVideoTrack, set video track to null - will clean up renderer");
                if (surfaceTextureRenderer != null) {
                    surfaceTextureRenderer.release();
                }
            }
        }
    }

    /**
     * Starts rendering {@link #videoTrack} if rendering is not in progress and
     * all preconditions for the start of rendering are met.
     */
    private void tryAddRendererToVideoTrack() throws Exception {
        if (disposed) return;
        if (videoTrack != null) {
            EglBase.Context sharedContext = EglUtils.getRootEglBaseContext();

            if (sharedContext == null) {
                // If SurfaceViewRenderer#init() is invoked, it will throw a
                // RuntimeException which will very likely kill the application.
                Log.e(TAG, "Failed to render a VideoTrack!");
                return;
            }

            surfaceTextureRenderer.release();
            listenRendererEvents();
            surfaceTextureRenderer.init(sharedContext, rendererEvents);
            surfaceTextureRenderer.surfaceCreated(producer);

            videoTrack.addSink(surfaceTextureRenderer);
        } else {
            // When videoTrack is null, we should properly clean up the renderer
            // to stop EglRenderer statistics logging and free resources
            surfaceTextureRenderer.release();
        }
    }

    public boolean checkMediaStream(String id, String ownerTag) {
        if (null == id || null == mediaStream || ownerTag == null || !ownerTag.equals(this.ownerTag)) {
            return false;
        }
        return id.equals(mediaStream.getId());
    }

    public boolean checkVideoTrack(String id, String ownerTag) {
        if (null == id || null == videoTrack || ownerTag == null || !ownerTag.equals(this.ownerTag)) {
            return false;
        }
        return id.equals(videoTrack.id());
    }

    public String getMediaStreamId() {
        return mediaStream != null ? mediaStream.getId() : null;
    }

    public String getOwnerTag() {
        return ownerTag;
    }

    /**
     * Re-initialise {@link #surfaceTextureRenderer} with a different
     * {@link RendererCommon.GlDrawer}.
     * Must be called on UI thread just like the other init helpers in this class.
     */
    private void reinitDrawer(RendererCommon.GlDrawer newDrawer) {
        // Release current GL resources & stop stats logging.
        surfaceTextureRenderer.release();

        // Re-create with the supplied drawer and the same producer / event callbacks.
        listenRendererEvents();
        surfaceTextureRenderer.init(
                EglUtils.getRootEglBaseContext(),
                rendererEvents,
                org.webrtc.EglBase.CONFIG_PLAIN,
                newDrawer);
        // Re-bind the Surface so frames start flowing again.
        surfaceTextureRenderer.surfaceCreated(producer);
    }

    /** Enable GPU night-vision processing for this renderer. */
    public void enableNightVision(float intensity) {
        float clamped = Math.max(0.0f, Math.min(1.0f, intensity));
        if (nightVisionEnabled && Math.abs(clamped - nightVisionIntensity) < 0.01f) {
            return; // already active with same intensity
        }

        nightVisionIntensity = clamped;

        if (nightVisionDrawer == null) {
            nightVisionDrawer = new NightVisionRenderer();
        }
        nightVisionDrawer.setNightVisionConfig(
                nightVisionIntensity,
                0.3f + (nightVisionIntensity * 0.5f), // gamma
                0.3f, // brightness threshold
                1.0f + (nightVisionIntensity * 0.8f), // contrast
                nightVisionIntensity * 0.3f, // noiseReduction
                nightVisionIntensity // tintStrength
        );

        reinitDrawer(nightVisionDrawer);
        nightVisionEnabled = true;
    }

    /** Disable GPU night-vision processing and restore the default drawer. */
    public void disableNightVision() {
        if (!nightVisionEnabled)
            return;

        nightVisionEnabled = false;

        // Restore original GL drawer
        reinitDrawer(new org.webrtc.GlRectDrawer());

        if (nightVisionDrawer != null) {
            nightVisionDrawer.release();
            nightVisionDrawer = null;
        }
    }

    /**
     * Release night-vision resources without re-initializing the GL drawer.
     * Use this during disposal to avoid unnecessary init/release churn.
     */
    private void releaseNightVisionResources() {
        nightVisionEnabled = false;
        if (nightVisionDrawer != null) {
            nightVisionDrawer.release();
            nightVisionDrawer = null;
        }
    }

    /** Update intensity while night-vision is enabled. */
    public void setNightVisionIntensity(float intensity) {
        float clamped = Math.max(0.0f, Math.min(1.0f, intensity));
        nightVisionIntensity = clamped;
        if (nightVisionDrawer != null) {
            nightVisionDrawer.setNightVisionConfig(
                    nightVisionIntensity,
                    0.3f + (nightVisionIntensity * 0.5f),
                    0.3f,
                    1.0f + (nightVisionIntensity * 0.8f),
                    nightVisionIntensity * 0.3f,
                    nightVisionIntensity);
        }
    }

    public boolean isNightVisionEnabled() {
        return nightVisionEnabled;
    }

    /**
     * Dispose and clean up GL resources for this renderer.
     */
    public void Dispose() {
        Log.d(TAG, "FlutterRTCVideoRenderer.Dispose() - cleaning up renderer resources");
        disposed = true;

        // Remove sink from the current video track BEFORE releasing GL resources to stop callbacks
        try {
            if (videoTrack != null) {
                removeRendererFromVideoTrack();
            }
        } catch (Exception e) {
            Log.w(TAG, "Dispose(): error removing sink from videoTrack: " + e.getMessage());
        }

        // Ensure night-vision resources are released without re-initializing GL drawer.
        if (nightVisionEnabled || nightVisionDrawer != null) {
            releaseNightVisionResources();
        }

        if (surfaceTextureRenderer != null) {
            surfaceTextureRenderer.disposeAndStop();
        }

        if (eventChannel != null) {
            eventChannel.setStreamHandler(null);
        }

        eventSink = null;
        producer.release();

        // Clear references
        videoTrack = null;
        mediaStream = null;
        ownerTag = null;
    }
}
