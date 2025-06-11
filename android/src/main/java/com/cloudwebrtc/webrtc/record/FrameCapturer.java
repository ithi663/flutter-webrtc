package com.cloudwebrtc.webrtc.record;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;
import org.webrtc.YuvHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.plugin.common.MethodChannel;

public class FrameCapturer implements VideoSink {
    private final VideoTrack videoTrack;
    private File file;
    private final MethodChannel.Result callback;
    private boolean gotFrame = false;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public FrameCapturer(VideoTrack track, File file, MethodChannel.Result callback) {
        videoTrack = track;
        this.file = file;
        this.callback = callback;
        track.addSink(this);
    }

    @Override
    public void onFrame(VideoFrame videoFrame) {

        if (gotFrame)
            return;
        gotFrame = true;
        videoFrame.retain();

        executor.submit(() -> {

            VideoFrame.Buffer buffer = videoFrame.getBuffer();
            VideoFrame.I420Buffer i420Buffer = buffer.toI420();
            ByteBuffer y = i420Buffer.getDataY();
            ByteBuffer u = i420Buffer.getDataU();
            ByteBuffer v = i420Buffer.getDataV();
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            int[] strides = new int[] {
                i420Buffer.getStrideY(),
                i420Buffer.getStrideU(),
                i420Buffer.getStrideV()
            };
            final int chromaWidth = (width + 1) / 2;
            final int chromaHeight = (height + 1) / 2;
            final int minSize = width * height + chromaWidth * chromaHeight * 2;

            // Debug logging to diagnose stride issues
            Log.d("FrameCapturer", String.format("Video frame: %dx%d, Y stride: %d, U stride: %d, V stride: %d, chromaWidth: %d", 
                width, height, strides[0], strides[1], strides[2], chromaWidth));

            ByteBuffer yuvBuffer = ByteBuffer.allocateDirect(minSize);
            // NV21 is the same as NV12, only that V and U are stored in the reverse order
            // NV21 (YYYYYYYYY:VUVU)
            // NV12 (YYYYYYYYY:UVUV)
            // Therefore we can use the NV12 helper, but swap the U and V input buffers
            
            // Fix: Handle stride properly to avoid green lines
            // When stride != width, we need to copy row by row to avoid color artifacts
            if (strides[0] == width && strides[1] == chromaWidth && strides[2] == chromaWidth) {
                // Fast path: stride equals width, can use direct conversion
                YuvHelper.I420ToNV12(y, strides[0], v, strides[2], u, strides[1], yuvBuffer, width, height);
            } else {
                // Slow path: stride != width, need to handle padding
                ByteBuffer compactY = ByteBuffer.allocateDirect(width * height);
                ByteBuffer compactU = ByteBuffer.allocateDirect(chromaWidth * chromaHeight);
                ByteBuffer compactV = ByteBuffer.allocateDirect(chromaWidth * chromaHeight);
                
                // Copy Y plane row by row, removing stride padding
                for (int row = 0; row < height; row++) {
                    y.position(row * strides[0]);
                    y.limit(y.position() + width);
                    compactY.put(y);
                }
                
                // Copy U plane row by row, removing stride padding
                for (int row = 0; row < chromaHeight; row++) {
                    u.position(row * strides[1]);
                    u.limit(u.position() + chromaWidth);
                    compactU.put(u);
                }
                
                // Copy V plane row by row, removing stride padding
                for (int row = 0; row < chromaHeight; row++) {
                    v.position(row * strides[2]);
                    v.limit(v.position() + chromaWidth);
                    compactV.put(v);
                }
                
                compactY.rewind();
                compactU.rewind();
                compactV.rewind();
                
                YuvHelper.I420ToNV12(compactY, width, compactV, chromaWidth, compactU, chromaWidth, yuvBuffer, width, height);
            }

            // For some reason the ByteBuffer may have leading 0. We remove them as
            // otherwise the image will be shifted
            byte[] cleanedArray = Arrays.copyOfRange(yuvBuffer.array(), yuvBuffer.arrayOffset(), minSize);

            YuvImage yuvImage = new YuvImage(
                cleanedArray,
                ImageFormat.NV21,
                width,
                height,
                // Now we can safely omit strides since we've handled the padding
                null);
            i420Buffer.release();
            videoFrame.release();

            // Remove sink on main thread
            new Handler(Looper.getMainLooper()).post(() -> {
                videoTrack.removeSink(FrameCapturer.this);
            });

            try {
                if (!file.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.getParentFile().mkdirs();
                    //noinspection ResultOfMethodCallIgnored
                    file.createNewFile();
                }
            } catch (IOException io) {
                postError("IOException", io.getLocalizedMessage(), io);
                return;
            }
            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                yuvImage.compressToJpeg(
                    new Rect(0, 0, width, height),
                    100,
                    outputStream
                );
                switch (videoFrame.getRotation()) {
                    case 0:
                        break;
                    case 90:
                    case 180:
                    case 270:
                        Bitmap original = BitmapFactory.decodeFile(file.toString());
                        Matrix matrix = new Matrix();
                        matrix.postRotate(videoFrame.getRotation());
                        Bitmap rotated = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
                        FileOutputStream rotatedOutputStream = new FileOutputStream(file);
                        rotated.compress(Bitmap.CompressFormat.JPEG, 100, rotatedOutputStream);
                        rotatedOutputStream.close();
                        break;
                    default:
                        postError("RuntimeException", "Invalid rotation", null);
                        return;
                }
                postSuccess();
            } catch (IOException io) {
                postError("IOException", io.getLocalizedMessage(), io);
            } catch (IllegalArgumentException iae) {
                postError("IllegalArgumentException", iae.getLocalizedMessage(), iae);
            } finally {
                file = null;
            }
        });
    }

    // Helper methods to post results to the main thread
    private void postSuccess() {
        new Handler(Looper.getMainLooper()).post(() -> callback.success(null));
    }

    private void postError(String code, String message, Exception e) {
        new Handler(Looper.getMainLooper()).post(() -> callback.error(code, message, e));
    }
}
