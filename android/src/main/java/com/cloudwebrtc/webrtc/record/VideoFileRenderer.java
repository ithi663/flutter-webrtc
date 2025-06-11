package com.cloudwebrtc.webrtc.record;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import org.webrtc.EglBase;
import org.webrtc.GlRectDrawer;
import org.webrtc.JavaI420Buffer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrameDrawer;
import org.webrtc.VideoSink;
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback;

import java.io.IOException;
import java.nio.ByteBuffer;

class VideoFileRenderer implements VideoSink, SamplesReadyCallback {
    private static final String TAG = "VideoFileRenderer";
    private final HandlerThread renderThread;
    private final Handler renderThreadHandler;
    private final HandlerThread audioThread;
    private final Handler audioThreadHandler;
    private int outputFileWidth = -1;
    private int outputFileHeight = -1;
    private ByteBuffer[] encoderOutputBuffers;
    private ByteBuffer[] audioInputBuffers;
    private ByteBuffer[] audioOutputBuffers;
    private EglBase eglBase;
    private final EglBase.Context sharedContext;
    private VideoFrameDrawer frameDrawer;

    // TODO: these ought to be configurable as well
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames

    private final MediaMuxer mediaMuxer;
    private MediaCodec encoder;
    private final MediaCodec.BufferInfo bufferInfo;
    private MediaCodec.BufferInfo audioBufferInfo;
    private int trackIndex = -1;
    private int audioTrackIndex;
    private boolean isRunning = true;
    private GlRectDrawer drawer;
    private Surface surface;
    private MediaCodec audioEncoder;

    private boolean encoderStarted = false;
    private volatile boolean muxerStarted = false;
    private long videoFrameStart = 0;
    private volatile boolean isReleasing = false;
    
    // Performance optimization: Buffer pool for frame alignment
    private ByteBuffer cachedAlignedY = null;
    private ByteBuffer cachedAlignedU = null;
    private ByteBuffer cachedAlignedV = null;
    private int cachedWidth = -1;
    private int cachedHeight = -1;

    VideoFileRenderer(String outputFile, final EglBase.Context sharedContext, boolean withAudio) throws IOException {
        renderThread = new HandlerThread(TAG + "RenderThread");
        renderThread.start();
        renderThreadHandler = new Handler(renderThread.getLooper());
        if (withAudio) {
            audioThread = new HandlerThread(TAG + "AudioThread");
            audioThread.start();
            audioThreadHandler = new Handler(audioThread.getLooper());
        } else {
            audioThread = null;
            audioThreadHandler = null;
        }
        bufferInfo = new MediaCodec.BufferInfo();
        this.sharedContext = sharedContext;

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        mediaMuxer = new MediaMuxer(outputFile,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        audioTrackIndex = withAudio ? -1 : 0;
    }

    private void initVideoEncoder() {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, outputFileWidth, outputFileHeight);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        try {
            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            renderThreadHandler.post(() -> {
                eglBase = EglBase.create(sharedContext, EglBase.CONFIG_RECORDABLE);
                surface = encoder.createInputSurface();
                eglBase.createSurface(surface);
                eglBase.makeCurrent();
                drawer = new GlRectDrawer();
            });
        } catch (Exception e) {
            Log.wtf(TAG, e);
        }
    }

    @Override
    public void onFrame(VideoFrame frame) {
        frame.retain();
        if (outputFileWidth == -1) {
            outputFileWidth = frame.getRotatedWidth();
            outputFileHeight = frame.getRotatedHeight();
            initVideoEncoder();
        }
        renderThreadHandler.post(() -> renderFrameOnRenderThread(frame));
    }

    private void renderFrameOnRenderThread(VideoFrame frame) {
        if (frameDrawer == null) {
            frameDrawer = new VideoFrameDrawer();
        }
        
        // Add stride validation and logging for debugging
        VideoFrame.Buffer buffer = frame.getBuffer();
        if (buffer instanceof VideoFrame.I420Buffer) {
            VideoFrame.I420Buffer i420Buffer = (VideoFrame.I420Buffer) buffer;
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            int strideY = i420Buffer.getStrideY();
            int strideU = i420Buffer.getStrideU();
            int strideV = i420Buffer.getStrideV();
            
            // Log stride information for debugging
            Log.d(TAG, String.format("Recording frame: %dx%d, Y stride: %d, U stride: %d, V stride: %d", 
                width, height, strideY, strideU, strideV));
            
            // Check for stride alignment issues that cause green lines
            int expectedChromaWidth = (width + 1) / 2;
            if (strideY != width || strideU != expectedChromaWidth || strideV != expectedChromaWidth) {
                Log.w(TAG, "Stride mismatch detected - this may cause green lines in recording");
                Log.w(TAG, String.format("Expected: Y=%d, U=%d, V=%d | Actual: Y=%d, U=%d, V=%d", 
                    width, expectedChromaWidth, expectedChromaWidth, strideY, strideU, strideV));
                
                // Create a properly aligned frame to avoid green lines
                VideoFrame alignedFrame = createAlignedFrame(frame);
                if (alignedFrame != null) {
                    frameDrawer.drawFrame(alignedFrame, drawer, null, 0, 0, outputFileWidth, outputFileHeight);
                    alignedFrame.release();
                    frame.release();
                    drainEncoder();
                    eglBase.swapBuffers();
                    return;
                }
            }
        }
        
        frameDrawer.drawFrame(frame, drawer, null, 0, 0, outputFileWidth, outputFileHeight);
        frame.release();
        drainEncoder();
        eglBase.swapBuffers();
    }
    
    /**
     * Creates a properly aligned video frame to prevent green lines caused by stride misalignment
     */
    private VideoFrame createAlignedFrame(VideoFrame originalFrame) {
        long startTime = System.nanoTime();
        try {
            VideoFrame.Buffer buffer = originalFrame.getBuffer();
            VideoFrame.I420Buffer i420Buffer = buffer.toI420();
            
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            int chromaWidth = (width + 1) / 2;
            int chromaHeight = (height + 1) / 2;
            
            // Performance optimization: Reuse buffers if dimensions match
            ByteBuffer alignedY, alignedU, alignedV;
            if (cachedWidth == width && cachedHeight == height && 
                cachedAlignedY != null && cachedAlignedU != null && cachedAlignedV != null) {
                // Reuse existing buffers
                alignedY = cachedAlignedY;
                alignedU = cachedAlignedU;
                alignedV = cachedAlignedV;
                alignedY.clear();
                alignedU.clear();
                alignedV.clear();
            } else {
                // Create new buffers and cache them
                alignedY = ByteBuffer.allocateDirect(width * height);
                alignedU = ByteBuffer.allocateDirect(chromaWidth * chromaHeight);
                alignedV = ByteBuffer.allocateDirect(chromaWidth * chromaHeight);
                cachedAlignedY = alignedY;
                cachedAlignedU = alignedU;
                cachedAlignedV = alignedV;
                cachedWidth = width;
                cachedHeight = height;
            }
            
            // Copy Y plane row by row to remove stride padding
            ByteBuffer srcY = i420Buffer.getDataY();
            int srcStrideY = i420Buffer.getStrideY();
            for (int row = 0; row < height; row++) {
                srcY.position(row * srcStrideY);
                srcY.limit(srcY.position() + width);
                alignedY.put(srcY);
            }
            
            // Copy U plane row by row to remove stride padding
            ByteBuffer srcU = i420Buffer.getDataU();
            int srcStrideU = i420Buffer.getStrideU();
            for (int row = 0; row < chromaHeight; row++) {
                srcU.position(row * srcStrideU);
                srcU.limit(srcU.position() + chromaWidth);
                alignedU.put(srcU);
            }
            
            // Copy V plane row by row to remove stride padding
            ByteBuffer srcV = i420Buffer.getDataV();
            int srcStrideV = i420Buffer.getStrideV();
            for (int row = 0; row < chromaHeight; row++) {
                srcV.position(row * srcStrideV);
                srcV.limit(srcV.position() + chromaWidth);
                alignedV.put(srcV);
            }
            
            alignedY.rewind();
            alignedU.rewind();
            alignedV.rewind();
            
            // Create new I420Buffer with aligned data
            VideoFrame.I420Buffer alignedI420Buffer = JavaI420Buffer.wrap(
                width, height,
                alignedY, width,
                alignedU, chromaWidth,
                alignedV, chromaWidth,
                null // release callback
            );
            
            i420Buffer.release();
            
            long endTime = System.nanoTime();
            long durationMicros = (endTime - startTime) / 1000;
            Log.d(TAG, String.format("Frame alignment took %d μs for %dx%d frame", durationMicros, width, height));
            
            return new VideoFrame(alignedI420Buffer, originalFrame.getRotation(), originalFrame.getTimestampNs());
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create aligned frame", e);
            return null;
        }
    }

    /**
     * Release all resources. All already posted frames will be rendered first.
     */
    void release() {
        isRunning = false;
        isReleasing = true;
        
        // First stop audio processing on the audio thread
        if (audioThreadHandler != null) {
            audioThreadHandler.post(() -> {
                if (audioEncoder != null) {
                    try {
                        audioEncoder.stop();
                        audioEncoder.release();
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping audio encoder", e);
                    }
                }
            });
            
            try {
                // Give audio thread time to complete current processing
                audioThread.quitSafely();
                audioThread.join(500); // Wait for audio thread to complete
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for audio thread to quit", e);
            }
        }
        
        // Then handle the video and muxer on the render thread
        renderThreadHandler.post(() -> {
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
                
                if (eglBase != null) {
                    eglBase.release();
                }
                
                if (muxerStarted) {
                    mediaMuxer.stop();
                    mediaMuxer.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error releasing resources", e);
            } finally {
                renderThread.quitSafely();
            }
        });
        
        try {
            // Wait for render thread to complete before returning
            renderThread.join(1000);
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for render thread to quit", e);
        }
    }

    private void drainEncoder() {
        if (!encoderStarted) {
            encoder.start();
            encoderOutputBuffers = encoder.getOutputBuffers();
            encoderStarted = true;
            return;
        }
        while (true) {
            int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = encoder.getOutputBuffers();
                Log.e(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // not expected for an encoder
                MediaFormat newFormat = encoder.getOutputFormat();

                Log.e(TAG, "encoder output format changed: " + newFormat);
                trackIndex = mediaMuxer.addTrack(newFormat);
                if (audioTrackIndex != -1 && !muxerStarted) {
                    mediaMuxer.start();
                    muxerStarted = true;
                }
                if (!muxerStarted)
                    break;
            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result fr om encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                try {
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                        break;
                    }
                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    if (videoFrameStart == 0 && bufferInfo.presentationTimeUs != 0) {
                        videoFrameStart = bufferInfo.presentationTimeUs;
                    }
                    bufferInfo.presentationTimeUs -= videoFrameStart;
                    if (muxerStarted)
                        mediaMuxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                    isRunning = isRunning && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    encoder.releaseOutputBuffer(encoderStatus, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                    break;
                }
            }
        }
    }

    private long presTime = 0L;

    private void drainAudio() {
        if (audioBufferInfo == null)
            audioBufferInfo = new MediaCodec.BufferInfo();
            
        if (isReleasing) return;
            
        while (true) {
            try {
                int encoderStatus = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 10000);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    audioOutputBuffers = audioEncoder.getOutputBuffers();
                    Log.w(TAG, "encoder output buffers changed");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // not expected for an encoder
                    MediaFormat newFormat = audioEncoder.getOutputFormat();

                    Log.w(TAG, "encoder output format changed: " + newFormat);
                    audioTrackIndex = mediaMuxer.addTrack(newFormat);
                    if (trackIndex != -1 && !muxerStarted) {
                        mediaMuxer.start();
                        muxerStarted = true;
                    }
                    if (!muxerStarted)
                        break;
                } else if (encoderStatus < 0) {
                    Log.e(TAG, "unexpected result fr om encoder.dequeueOutputBuffer: " + encoderStatus);
                } else { // encoderStatus >= 0
                    try {
                        ByteBuffer encodedData = audioOutputBuffers[encoderStatus];
                        if (encodedData == null) {
                            Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                            break;
                        }
                        // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                        encodedData.position(audioBufferInfo.offset);
                        encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size);
                        if (muxerStarted && !isReleasing) {
                            mediaMuxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo);
                        }
                        isRunning = isRunning && (audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                        audioEncoder.releaseOutputBuffer(encoderStatus, false);
                        if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break;
                        }
                    } catch (IllegalStateException e) {
                        // This can happen if the muxer was released while we were trying to write
                        if (!isReleasing) {
                            Log.e(TAG, "Failed to write audio sample", e);
                        }
                        break;
                    } catch (Exception e) {
                        if (!isReleasing) {
                            Log.e(TAG, "Error in drainAudio", e);
                        }
                        break;
                    }
                }
            } catch (IllegalStateException e) {
                // This can happen if the muxer was released while we were trying to write
                if (!isReleasing) {
                    Log.e(TAG, "Failed to write audio sample", e);
                }
                break;
            } catch (Exception e) {
                if (!isReleasing) {
                    Log.e(TAG, "Error in drainAudio", e);
                }
                break;
            }
        }
    }

    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples audioSamples) {
        if (!isRunning || isReleasing)
            return;
        audioThreadHandler.post(() -> {
            if (isReleasing) return;
            
            if (audioEncoder == null) try {
                audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
                MediaFormat format = new MediaFormat();
                format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, audioSamples.getChannelCount());
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, audioSamples.getSampleRate());
                format.setInteger(MediaFormat.KEY_BIT_RATE, 64 * 1024);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                audioEncoder.start();
                audioInputBuffers = audioEncoder.getInputBuffers();
                audioOutputBuffers = audioEncoder.getOutputBuffers();
            } catch (IOException exception) {
                Log.wtf(TAG, exception);
            }
            
            try {
                int bufferIndex = audioEncoder.dequeueInputBuffer(0);
                if (bufferIndex >= 0) {
                    ByteBuffer buffer = audioInputBuffers[bufferIndex];
                    buffer.clear();
                    byte[] data = audioSamples.getData();
                    buffer.put(data);
                    audioEncoder.queueInputBuffer(bufferIndex, 0, data.length, presTime, 0);
                    presTime += data.length * 125 / 12; // 1000000 microseconds / 48000hz / 2 bytes
                }
                drainAudio();
            } catch (Exception e) {
                if (!isReleasing) {
                    Log.e(TAG, "Error processing audio", e);
                }
            }
        });
    }

}
