package com.cloudwebrtc.webrtc.record;

import androidx.annotation.Nullable;
import android.util.Log;

import com.cloudwebrtc.webrtc.utils.EglUtils;

import java.io.File;

public class MediaRecorderImpl {

    private final Integer id;
    private final AudioSamplesInterceptor audioInterceptor;
    private VideoFileRenderer videoFileRenderer;
    private boolean isRunning = false;
    private File recordFile;

    public MediaRecorderImpl(Integer id, @Nullable AudioSamplesInterceptor audioInterceptor) {
        this.id = id;
        this.audioInterceptor = audioInterceptor;
    }

    public void startRecording(File file) throws Exception {
        recordFile = file;
        if (isRunning)
            return;
        isRunning = true;
        // noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();

        // Create VideoFileRenderer which will automatically register with
        // EncodedFrameMultiplexer
        videoFileRenderer = new VideoFileRenderer(
                file.getAbsolutePath(),
                audioInterceptor != null);

        if (audioInterceptor != null) {
            audioInterceptor.attachCallback(id, videoFileRenderer);
        }

        Log.d(TAG, "Recording started to file: " + file.getAbsolutePath());
    }

    public File getRecordFile() {
        return recordFile;
    }

    public void stopRecording() {
        isRunning = false;
        if (audioInterceptor != null)
            audioInterceptor.detachCallback(id);
        if (videoFileRenderer != null) {
            // VideoFileRenderer will automatically unregister from EncodedFrameMultiplexer
            videoFileRenderer.release();
            videoFileRenderer = null;
        }
        Log.d(TAG, "Recording stopped");
    }

    private static final String TAG = "MediaRecorderImpl";

}
