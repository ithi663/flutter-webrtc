package com.cloudwebrtc.webrtc.record;

import android.annotation.SuppressLint;

import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback;
import org.webrtc.audio.JavaAudioDeviceModule.AudioSamples;

import java.util.concurrent.ConcurrentHashMap;

/** JavaAudioDeviceModule allows attaching samples callback only on building
 *  We don't want to instantiate VideoFileRenderer and codecs at this step
 *  It's simple dummy class, it does nothing until samples are necessary */
@SuppressWarnings("WeakerAccess")
public class AudioSamplesInterceptor implements SamplesReadyCallback {

    @SuppressLint("UseSparseArrays")
    protected final ConcurrentHashMap<Integer, SamplesReadyCallback> callbacks = new ConcurrentHashMap<>();

    @Override
    public void onWebRtcAudioRecordSamplesReady(AudioSamples audioSamples) {
        // Iterate over a stable snapshot to avoid concurrent modification issues
        final SamplesReadyCallback[] snapshot = callbacks.values().toArray(new SamplesReadyCallback[0]);
        for (SamplesReadyCallback callback : snapshot) {
            try {
                if (callback != null) {
                    callback.onWebRtcAudioRecordSamplesReady(audioSamples);
                }
            } catch (Throwable t) {
                // Make callback invocation robust; swallow per-listener errors to avoid breaking others
                // Optionally: log using your logging facility if available
            }
        }
    }

    public void attachCallback(Integer id, SamplesReadyCallback callback) throws Exception {
        callbacks.put(id, callback);
    }

    public void detachCallback(Integer id) {
        callbacks.remove(id);
    }

    public void clear() {
        callbacks.clear();
    }

}
