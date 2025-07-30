package com.cloudwebrtc.webrtc.record;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.webrtc.EncodedFrameListener;
import org.webrtc.EncodedFrameMultiplexer;
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * VideoFileRenderer now acts as a muxer that receives already-encoded frames
 * from HardwareVideoEncoder and writes them to an MP4 file. This eliminates
 * the need for a second encoding operation.
 */
class VideoFileRenderer implements EncodedFrameListener, SamplesReadyCallback {
    private static final String TAG = "VideoFileRenderer";
    private final HandlerThread fileThread;
    private final Handler fileThreadHandler;
    private final HandlerThread audioThread;
    private final Handler audioThreadHandler;

    private final MediaMuxer mediaMuxer;
    private final String outputFilePath; // Store output file path for validation
    private MediaCodec.BufferInfo audioBufferInfo;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private volatile boolean muxerStarted = false;
    private volatile boolean isRunning = false;
    private volatile boolean isReleasing = false;
    private final Object muxerLock = new Object();
    private long lastWriteTime = 0;
    private static final long MIN_WRITE_INTERVAL_MS = 10; // Minimum 10ms between writes
    private long frameCount = 0;
    private static final long LOG_INTERVAL_FRAMES = 100; // Log every 100 frames

    // Audio encoding (kept separate as it's not part of the video multiplexing)
    private MediaCodec audioEncoder;
    private ByteBuffer[] audioInputBuffers;
    private ByteBuffer[] audioOutputBuffers;
    private long presTime = 0L;

    VideoFileRenderer(String outputFile, boolean withAudio) throws IOException {
        this.outputFilePath = outputFile; // Store the output file path

        fileThread = new HandlerThread(TAG + "FileThread");
        fileThread.start();
        fileThreadHandler = new Handler(fileThread.getLooper());

        if (withAudio) {
            audioThread = new HandlerThread(TAG + "AudioThread");
            audioThread.start();
            audioThreadHandler = new Handler(audioThread.getLooper());
        } else {
            audioThread = null;
            audioThreadHandler = null;
        }

        // Create a MediaMuxer for writing encoded frames to file
        mediaMuxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Initialize audio track index
        audioTrackIndex = withAudio ? -1 : 0;

        isRunning = true;

        // Register with the encoded frame multiplexer to receive encoded frames
        EncodedFrameMultiplexer.getInstance().addListener(this);
    }

    /**
     * Called by HardwareVideoEncoder when a frame has been encoded.
     * This method receives the encoded data and writes it to the MP4 file.
     */
    @Override
    public void onEncodedFrame(ByteBuffer encodedData, MediaCodec.BufferInfo bufferInfo) {
        if (isReleasing || !isRunning) {
            return;
        }

        fileThreadHandler.post(() -> {
            writeEncodedFrameToFile(encodedData, bufferInfo);
        });
    }

    private void writeEncodedFrameToFile(ByteBuffer encodedData, MediaCodec.BufferInfo bufferInfo) {
        // Validate input parameters
        if (encodedData == null || bufferInfo == null) {
            Log.w(TAG, "Invalid encoded data or buffer info");
            return;
        }

        // Check if buffer is still valid before accessing any properties
        try {
            // Test buffer validity by checking if we can access its capacity
            int capacity = encodedData.capacity();
            if (capacity <= 0) {
                Log.w(TAG, "Buffer has invalid capacity: " + capacity);
                return;
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "Buffer has been freed, cannot process frame: " + e.getMessage());
            return;
        }

        if (bufferInfo.size <= 0 || bufferInfo.offset < 0) {
            Log.w(TAG, "Invalid buffer info: size=" + bufferInfo.size + ", offset=" + bufferInfo.offset);
            return;
        }

        if (bufferInfo.presentationTimeUs < 0) {
            Log.w(TAG, "Invalid presentation time: " + bufferInfo.presentationTimeUs);
            return;
        }

        synchronized (muxerLock) {
            // Check if we're in a valid state to write
            if (isReleasing || !isRunning) {
                Log.w(TAG, "Cannot write frame: releasing=" + isReleasing + ", running=" + isRunning);
                return;
            }

            try {
                // Add video track to muxer if not already added
                if (videoTrackIndex == -1) {
                    // Create video format from the encoded data
                    MediaFormat videoFormat = createVideoFormat(bufferInfo);
                    videoTrackIndex = mediaMuxer.addTrack(videoFormat);
                    Log.d(TAG, "Video track added with index: " + videoTrackIndex);

                    // Start muxer if all tracks are ready
                    if (audioTrackIndex >= 0 || audioThread == null) {
                        mediaMuxer.start();
                        muxerStarted = true;
                        Log.d(TAG, "MediaMuxer started");
                    }
                }

                // Write the encoded frame to the muxer
                if (muxerStarted && videoTrackIndex >= 0) {
                    // Throttle writes to prevent overwhelming MediaMuxer
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastWriteTime < MIN_WRITE_INTERVAL_MS) {
                        return; // Skip frame silently
                    }

                    // Additional validation before writing
                    if (bufferInfo.offset + bufferInfo.size > encodedData.capacity()) {
                        Log.e(TAG, "Buffer overflow: offset=" + bufferInfo.offset + ", size=" + bufferInfo.size
                                + ", capacity=" + encodedData.capacity());
                        return;
                    }

                    // Increment frame counter
                    frameCount++;

                    // Log frame details only periodically to reduce spam
                    if (frameCount % LOG_INTERVAL_FRAMES == 0) {
                        Log.d(TAG, "Writing frame #" + frameCount + ": size=" + bufferInfo.size + ", pts="
                                + bufferInfo.presentationTimeUs + ", flags=" + bufferInfo.flags);
                    }

                    // Check if buffer is still valid before duplicating
                    ByteBuffer dataToWrite;
                    try {
                        // Create a duplicate buffer to avoid modifying the original
                        dataToWrite = encodedData.duplicate();
                        dataToWrite.position(bufferInfo.offset);
                        dataToWrite.limit(bufferInfo.offset + bufferInfo.size);
                    } catch (IllegalStateException e) {
                        Log.w(TAG, "Buffer has been freed, skipping frame: " + e.getMessage());
                        return;
                    }

                    // Additional validation of BufferInfo
                    if (bufferInfo.presentationTimeUs < 0) {
                        Log.w(TAG, "Adjusting negative presentation time to 0");
                        bufferInfo.presentationTimeUs = 0;
                    }

                    // Validate that we have actual data to write
                    if (dataToWrite.remaining() == 0) {
                        Log.w(TAG, "No data to write, skipping frame");
                        return;
                    }

                    // Additional validation before writing to MediaMuxer
                    if (bufferInfo.size != dataToWrite.remaining()) {
                        // Only log if this is a significant mismatch (not just minor differences)
                        if (Math.abs(bufferInfo.size - dataToWrite.remaining()) > 10) {
                            Log.w(TAG, "BufferInfo size mismatch: bufferInfo.size=" + bufferInfo.size
                                    + ", dataToWrite.remaining()=" + dataToWrite.remaining());
                        }
                        bufferInfo.size = dataToWrite.remaining();
                    }

                    // Ensure buffer is positioned correctly
                    if (dataToWrite.position() != 0) {
                        dataToWrite.rewind();
                        dataToWrite.limit(bufferInfo.size);
                    }

                    // Final validation before MediaMuxer write
                    if (!muxerStarted) {
                        Log.e(TAG, "MediaMuxer not started, cannot write frame");
                        return;
                    }

                    if (videoTrackIndex < 0) {
                        Log.e(TAG, "Invalid video track index: " + videoTrackIndex);
                        return;
                    }

                    // Final null check before writing
                    if (dataToWrite == null) {
                        Log.e(TAG, "dataToWrite is null, cannot write frame");
                        return;
                    }

                    try {
                        mediaMuxer.writeSampleData(videoTrackIndex, dataToWrite, bufferInfo);
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG, "MediaMuxer writeSampleData failed - invalid argument: " + e.getMessage());
                        return;
                    } catch (IllegalStateException e) {
                        Log.e(TAG,
                                "MediaMuxer writeSampleData failed - muxer may be in invalid state: " + e.getMessage());
                        // Try to recover by stopping and restarting if possible
                        muxerStarted = false;
                        return;
                    }
                    lastWriteTime = currentTime;

                    // Log success only periodically to reduce spam
                    if (frameCount % LOG_INTERVAL_FRAMES == 0) {
                        Log.i(TAG,
                                "Frames written successfully. Latest frame #" + frameCount + ": size=" + bufferInfo.size
                                        + ", pts="
                                        + bufferInfo.presentationTimeUs);
                    }
                } else {
                    Log.w(TAG, "Cannot write frame: muxerStarted=" + muxerStarted + ", videoTrackIndex="
                            + videoTrackIndex);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error writing encoded frame to file", e);
                // Don't rethrow to prevent crashes, just log the error
            }
        }
    }

    private MediaFormat createVideoFormat(MediaCodec.BufferInfo bufferInfo) {
        // Create a minimal H.264 video format for MediaMuxer
        // MediaMuxer is more forgiving with minimal format information
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", 640, 480);

        // Set only essential parameters to avoid compatibility issues
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000); // 1 Mbps - conservative
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);

        // Use baseline profile for maximum compatibility
        try {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3);
        } catch (Exception e) {
            Log.w(TAG, "Could not set profile/level, using defaults", e);
        }

        Log.d(TAG, "Created video format: " + format.toString());
        return format;
    }

    /**
     * Releases muxer and audio encoder resources.
     */
    void release() {
        isReleasing = true;
        isRunning = false;

        // Unregister from the encoded frame multiplexer
        EncodedFrameMultiplexer.getInstance().removeListener(this);

        if (fileThreadHandler != null) {
            fileThreadHandler.post(() -> {
                synchronized (muxerLock) {
                    if (muxerStarted && mediaMuxer != null) {
                        try {
                            Log.d(TAG, "Attempting to stop MediaMuxer");
                            mediaMuxer.stop();
                            muxerStarted = false;
                            Log.d(TAG, "MediaMuxer stopped successfully");
                        } catch (IllegalStateException e) {
                            Log.w(TAG, "MediaMuxer already stopped or in invalid state: " + e.getMessage());
                            muxerStarted = false;
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to stop MediaMuxer", e);
                            muxerStarted = false;
                        }
                    }

                    if (mediaMuxer != null) {
                        try {
                            Log.d(TAG, "Attempting to release MediaMuxer");
                            mediaMuxer.release();
                            Log.d(TAG, "MediaMuxer released successfully");
                        } catch (IllegalStateException e) {
                            Log.w(TAG, "MediaMuxer already released or in invalid state: " + e.getMessage());
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to release MediaMuxer", e);
                        }
                    }

                    // Validate the output video file after MediaMuxer is released
                    validateVideoFile();
                }
            });
        }

        if (audioThreadHandler != null) {
            audioThreadHandler.post(() -> {
                if (audioEncoder != null) {
                    try {
                        audioEncoder.stop();
                        audioEncoder.release();
                        audioEncoder = null;
                        Log.d(TAG, "Audio encoder released");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to release audio encoder", e);
                    }
                }
            });
        }

        if (fileThread != null) {
            fileThread.quitSafely();
        }
        if (audioThread != null) {
            audioThread.quitSafely();
        }
    }

    /**
     * Validates the recorded video file to ensure it's valid and properly written.
     * This method checks file existence, size, and basic format validity.
     */
    private void validateVideoFile() {
        if (outputFilePath == null || outputFilePath.isEmpty()) {
            Log.e(TAG, "Video file validation failed: Output file path is null or empty");
            return;
        }

        File videoFile = new File(outputFilePath);

        // Check if file exists
        if (!videoFile.exists()) {
            Log.e(TAG, "Video file validation failed: File does not exist at path: " + outputFilePath);
            return;
        }

        // Check if file is readable
        if (!videoFile.canRead()) {
            Log.e(TAG, "Video file validation failed: File is not readable at path: " + outputFilePath);
            return;
        }

        // Check file size
        long fileSize = videoFile.length();
        if (fileSize == 0) {
            Log.e(TAG, "Video file validation failed: File is empty (0 bytes) at path: " + outputFilePath);
            return;
        }

        // Check minimum file size (MP4 header should be at least a few KB)
        final long MIN_VALID_FILE_SIZE = 1024; // 1KB minimum
        if (fileSize < MIN_VALID_FILE_SIZE) {
            Log.w(TAG, "Video file validation warning: File size is very small (" + fileSize
                    + " bytes). This may indicate an incomplete recording at path: " + outputFilePath);
        }

        // Log validation success with file details
        Log.i(TAG, "Video file validation successful: " +
                "Path=" + outputFilePath +
                ", Size=" + fileSize + " bytes" +
                ", Frames=" + frameCount +
                ", VideoTrack=" + (videoTrackIndex >= 0 ? "present" : "missing") +
                ", AudioTrack=" + (audioTrackIndex >= 0 ? "present" : "missing"));

        // Additional format validation using MediaMetadataRetriever
        validateVideoFormat(videoFile);
    }

    /**
     * Performs additional format validation using MediaMetadataRetriever.
     * This checks if the file can be properly read as a video file.
     */
    private void validateVideoFormat(File videoFile) {
        android.media.MediaMetadataRetriever retriever = null;
        try {
            retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(videoFile.getAbsolutePath());

            // Try to extract basic metadata
            String duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String mimeType = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE);

            if (duration != null && width != null && height != null) {
                Log.i(TAG, "Video format validation successful: " +
                        "Duration=" + duration + "ms" +
                        ", Resolution=" + width + "x" + height +
                        ", MimeType=" + mimeType);
            } else {
                Log.w(TAG, "Video format validation warning: Some metadata could not be extracted. " +
                        "Duration=" + duration + ", Width=" + width + ", Height=" + height + ", MimeType=" + mimeType);
            }

        } catch (Exception e) {
            Log.e(TAG,
                    "Video format validation failed: Could not read video metadata from " + videoFile.getAbsolutePath(),
                    e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to release MediaMetadataRetriever", e);
                }
            }
        }
    }

    private void drainAudio() {
        if (audioBufferInfo == null)
            audioBufferInfo = new MediaCodec.BufferInfo();

        if (isReleasing)
            return;

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
                    if (videoTrackIndex != -1 && !muxerStarted) {
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
            if (isReleasing)
                return;

            if (audioEncoder == null)
                try {
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
