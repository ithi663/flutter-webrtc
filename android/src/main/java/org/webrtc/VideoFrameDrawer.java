package org.webrtc;

import android.graphics.Matrix;
import android.graphics.Point;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import java.nio.ByteBuffer;

public class VideoFrameDrawer {
   public static final String TAG = "VideoFrameDrawer";
   static final float[] srcPoints = new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F};
   private final float[] dstPoints = new float[6];
   private final Point renderSize = new Point();
   private int renderWidth;
   private int renderHeight;
   private final VideoFrameDrawer.YuvUploader yuvUploader = new VideoFrameDrawer.YuvUploader();
   @Nullable
   private VideoFrame lastI420Frame;
   private final Matrix renderMatrix = new Matrix();

   public static void drawTexture(RendererCommon.GlDrawer drawer, VideoFrame.TextureBuffer buffer, Matrix renderMatrix, int frameWidth, int frameHeight, int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
      Matrix finalMatrix = new Matrix(buffer.getTransformMatrix());
      finalMatrix.preConcat(renderMatrix);
      float[] finalGlMatrix = RendererCommon.convertMatrixFromAndroidGraphicsMatrix(finalMatrix);
      switch(buffer.getType()) {
      case OES:
         drawer.drawOes(buffer.getTextureId(), finalGlMatrix, frameWidth, frameHeight, viewportX, viewportY, viewportWidth, viewportHeight);
         break;
      case RGB:
         drawer.drawRgb(buffer.getTextureId(), finalGlMatrix, frameWidth, frameHeight, viewportX, viewportY, viewportWidth, viewportHeight);
         break;
      default:
         throw new RuntimeException("Unknown texture type.");
      }

   }

   private static int distance(float x0, float y0, float x1, float y1) {
      return (int)Math.round(Math.hypot((double)(x1 - x0), (double)(y1 - y0)));
   }

   private void calculateTransformedRenderSize(int frameWidth, int frameHeight, @Nullable Matrix renderMatrix) {
      if (renderMatrix == null) {
         this.renderWidth = frameWidth;
         this.renderHeight = frameHeight;
      } else {
         renderMatrix.mapPoints(this.dstPoints, srcPoints);

         for(int i = 0; i < 3; ++i) {
            float[] var10000 = this.dstPoints;
            var10000[i * 2 + 0] *= (float)frameWidth;
            var10000 = this.dstPoints;
            var10000[i * 2 + 1] *= (float)frameHeight;
         }

         this.renderWidth = distance(this.dstPoints[0], this.dstPoints[1], this.dstPoints[2], this.dstPoints[3]);
         this.renderHeight = distance(this.dstPoints[0], this.dstPoints[1], this.dstPoints[4], this.dstPoints[5]);
      }
   }

   public void drawFrame(VideoFrame frame, RendererCommon.GlDrawer drawer) {
      this.drawFrame(frame, drawer, (Matrix)null);
   }

   public void drawFrame(VideoFrame frame, RendererCommon.GlDrawer drawer, Matrix additionalRenderMatrix) {
      this.drawFrame(frame, drawer, additionalRenderMatrix, 0, 0, frame.getRotatedWidth(), frame.getRotatedHeight());
   }

   public void drawFrame(VideoFrame frame, RendererCommon.GlDrawer drawer, @Nullable Matrix additionalRenderMatrix, int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
      int width = frame.getRotatedWidth();
      int height = frame.getRotatedHeight();
      this.calculateTransformedRenderSize(width, height, additionalRenderMatrix);
      if (this.renderWidth > 0 && this.renderHeight > 0) {
         boolean isTextureFrame = frame.getBuffer() instanceof VideoFrame.TextureBuffer;
         this.renderMatrix.reset();
         this.renderMatrix.preTranslate(0.5F, 0.5F);
         if (!isTextureFrame) {
            this.renderMatrix.preScale(1.0F, -1.0F);
         }

         this.renderMatrix.preRotate((float)frame.getRotation());
         this.renderMatrix.preTranslate(-0.5F, -0.5F);
         if (additionalRenderMatrix != null) {
            this.renderMatrix.preConcat(additionalRenderMatrix);
         }

         if (isTextureFrame) {
            this.lastI420Frame = null;
            drawTexture(drawer, (VideoFrame.TextureBuffer)frame.getBuffer(), this.renderMatrix, this.renderWidth, this.renderHeight, viewportX, viewportY, viewportWidth, viewportHeight);
         } else {
            if (frame != this.lastI420Frame) {
               this.lastI420Frame = frame;
               VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();
               this.yuvUploader.uploadFromBuffer(i420Buffer);
               i420Buffer.release();
            }

            drawer.drawYuv(this.yuvUploader.getYuvTextures(), RendererCommon.convertMatrixFromAndroidGraphicsMatrix(this.renderMatrix), this.renderWidth, this.renderHeight, viewportX, viewportY, viewportWidth, viewportHeight);
         }

      } else {
         Logging.w("VideoFrameDrawer", "Illegal frame size: " + this.renderWidth + "x" + this.renderHeight);
      }
   }

   public VideoFrame.Buffer prepareBufferForViewportSize(VideoFrame.Buffer buffer, int width, int height) {
      buffer.retain();
      return buffer;
   }

   public void release() {
      this.yuvUploader.release();
      this.lastI420Frame = null;
   }

   private static class YuvUploader {
      @Nullable
      private ByteBuffer copyBuffer;
      @Nullable
      private int[] yuvTextures;

      @Nullable
      public int[] uploadYuvData(int width, int height, int[] strides, ByteBuffer[] planes) {
         int[] planeWidths = new int[]{width, width / 2, width / 2};
         int[] planeHeights = new int[]{height, height / 2, height / 2};
         int copyCapacityNeeded = 0;

         int i;
         for(i = 0; i < 3; ++i) {
            if (strides[i] > planeWidths[i]) {
               copyCapacityNeeded = Math.max(copyCapacityNeeded, planeWidths[i] * planeHeights[i]);
            }
         }

         if (copyCapacityNeeded > 0 && (this.copyBuffer == null || this.copyBuffer.capacity() < copyCapacityNeeded)) {
            this.copyBuffer = ByteBuffer.allocateDirect(copyCapacityNeeded);
         }

         if (this.yuvTextures == null) {
            this.yuvTextures = new int[3];

            for(i = 0; i < 3; ++i) {
               this.yuvTextures[i] = GlUtil.generateTexture(3553);
            }
         }

         for(i = 0; i < 3; ++i) {
            GLES20.glActiveTexture('蓀' + i);
            GLES20.glBindTexture(3553, this.yuvTextures[i]);
            ByteBuffer packedByteBuffer;
            if (strides[i] == planeWidths[i]) {
               packedByteBuffer = planes[i];
            } else {
               YuvHelper.copyPlane(planes[i], strides[i], this.copyBuffer, planeWidths[i], planeWidths[i], planeHeights[i]);
               packedByteBuffer = this.copyBuffer;
            }

            GLES20.glTexImage2D(3553, 0, 6409, planeWidths[i], planeHeights[i], 0, 6409, 5121, packedByteBuffer);
         }

         return this.yuvTextures;
      }

      @Nullable
      public int[] uploadFromBuffer(VideoFrame.I420Buffer buffer) {
         int[] strides = new int[]{buffer.getStrideY(), buffer.getStrideU(), buffer.getStrideV()};
         ByteBuffer[] planes = new ByteBuffer[]{buffer.getDataY(), buffer.getDataU(), buffer.getDataV()};
         return this.uploadYuvData(buffer.getWidth(), buffer.getHeight(), strides, planes);
      }

      @Nullable
      public int[] getYuvTextures() {
         return this.yuvTextures;
      }

      public void release() {
         this.copyBuffer = null;
         if (this.yuvTextures != null) {
            GLES20.glDeleteTextures(3, this.yuvTextures, 0);
            this.yuvTextures = null;
         }

      }
   }
}
