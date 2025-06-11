package org.webrtc.video;

import android.util.Log;
import androidx.annotation.Nullable;

import com.cloudwebrtc.webrtc.SimulcastVideoEncoderFactoryWrapper;

import org.webrtc.EglBase;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.VideoCodecInfo;
import org.webrtc.VideoEncoder;
import org.webrtc.VideoEncoderFactory;

import java.util.ArrayList;
import java.util.List;

public class CustomVideoEncoderFactory implements VideoEncoderFactory {
    private static final String TAG = "CustomVideoEncoderFactory";
    private SoftwareVideoEncoderFactory softwareVideoEncoderFactory = new SoftwareVideoEncoderFactory();
    private SimulcastVideoEncoderFactoryWrapper simulcastVideoEncoderFactoryWrapper;

    private boolean forceSWCodec = false; // <--- SET TO TRUE FOR TESTING TEMPORARILY

    private List<String> forceSWCodecs = new ArrayList<>();

    public CustomVideoEncoderFactory(EglBase.Context sharedContext,
                                     boolean enableIntelVp8Encoder,
                                     boolean enableH264HighProfile) {
        this.simulcastVideoEncoderFactoryWrapper = new SimulcastVideoEncoderFactoryWrapper(sharedContext, enableIntelVp8Encoder, enableH264HighProfile);
    }

    public void setForceSWCodec(boolean forceSWCodec) {
        Log.d(TAG, "setForceSWCodec: " + forceSWCodec);
        this.forceSWCodec = forceSWCodec;
    }

    public void setForceSWCodecList(List<String> forceSWCodecs) {
        this.forceSWCodecs = forceSWCodecs;
    }

    @Nullable
    @Override
    public VideoEncoder createEncoder(VideoCodecInfo videoCodecInfo) {
        Log.d(TAG, "createEncoder called for: " + videoCodecInfo.name + ", forceSWCodec: " + forceSWCodec);
        
        if(forceSWCodec) {
            Log.d(TAG, "Forcing SW encoder for: " + videoCodecInfo.name);
            return softwareVideoEncoderFactory.createEncoder(videoCodecInfo);
        }

        if(!forceSWCodecs.isEmpty()) {
            if(forceSWCodecs.contains(videoCodecInfo.name)) {
                Log.d(TAG, "Forcing SW encoder for specific codec: " + videoCodecInfo.name);
                return softwareVideoEncoderFactory.createEncoder(videoCodecInfo);
            }
        }

        Log.d(TAG, "Using hardware encoder for: " + videoCodecInfo.name);
        return simulcastVideoEncoderFactoryWrapper.createEncoder(videoCodecInfo);
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
        if(forceSWCodec && forceSWCodecs.isEmpty()) {
            return softwareVideoEncoderFactory.getSupportedCodecs();
        }
        return simulcastVideoEncoderFactoryWrapper.getSupportedCodecs();
    }
}
