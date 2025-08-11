package com.cloudwebrtc.webrtc.video;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import org.webrtc.GlShader;
import org.webrtc.GlUtil;
import org.webrtc.RendererCommon;
import org.webrtc.VideoFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Night Vision Video Renderer
 *
 * GPU-accelerated OpenGL ES shader implementation for real-time night vision
 * processing.
 *
 * Changes in this version:
 * - Shadow-only enhancement: gamma/contrast/tint are applied only to darker
 * pixels, leaving bright
 * areas largely unchanged. This prevents bright rooms from getting brighter and
 * focuses the
 * effect on dark regions.
 * - Tint moderation: tint strength scales with local luminance within the dark
 * region to avoid
 * turning near-black noise into green patches. Stronger green spill
 * suppression.
 * - Retains bilateral filtering and adaptive gain, but both are driven by a
 * smooth shadow mask.
 */
public class NightVisionRenderer implements RendererCommon.GlDrawer {
    private static final String TAG = "NightVisionRenderer";

    // Shader programs for different input types
    private GlShader rgbShader;
    private GlShader yuvShader;
    private GlShader oesShader;

    // Vertex buffer for screen quad
    private FloatBuffer vertexBuffer;

    // Night vision configuration (defaults tuned for subtle, shadow-focused lift)
    private float intensity = 0.6f;
    private float gamma = 0.5f;
    private float brightnessThreshold = 0.3f;
    private float contrast = 2.0f;
    private float noiseReduction = 0.3f;
    private float tintStrength = 0.0f;

    // Performance statistics
    private long frameCount = 0;
    private long lastStatsTime = 0;
    private long totalProcessingTime = 0;

    // Common vertex shader for all input types
    private static final String VERTEX_SHADER = "attribute vec4 in_pos;\n" +
            "attribute vec2 in_tc;\n" +
            "varying vec2 tc;\n" +
            "uniform mat4 texMatrix;\n" +
            "void main() {\n" +
            "  gl_Position = in_pos;\n" +
            "  tc = (texMatrix * vec4(in_tc, 0.0, 1.0)).xy;\n" +
            "}\n";

    // Night vision fragment shader for RGB textures (shadow-only enhancement)
    private static final String RGB_FRAGMENT_SHADER = "precision mediump float;\n" +
            "varying vec2 tc;\n" +
            "uniform sampler2D tex;\n" +
            "uniform float u_intensity;\n" +
            "uniform float u_gamma;\n" +
            "uniform float u_brightnessThreshold;\n" +
            "uniform float u_contrast;\n" +
            "uniform float u_noiseReduction;\n" +
            "uniform float u_tintStrength;\n" +
            "uniform vec2 u_texSize;\n" +
            "\n" +
            "// Bilateral filter approximation for noise reduction\n" +
            "vec3 bilateralFilter(vec2 coord){\n" +
            "  vec2 texelSize=1.0/u_texSize;\n" +
            "  vec3 center=texture2D(tex,coord).rgb;\n" +
            "  vec3 result=center;\n" +
            "  float totalWeight=1.0;\n" +
            "  const float expFactor=10.0;\n" +
            "  for(int x=-1;x<=1;x++){\n" +
            "    for(int y=-1;y<=1;y++){\n" +
            "      if(x==0&&y==0) continue;\n" +
            "      vec2 offset=vec2(float(x),float(y))*texelSize*u_noiseReduction;\n" +
            "      vec3 neighbor=texture2D(tex,coord+offset).rgb;\n" +
            "      float colorDiff=length(neighbor-center);\n" +
            "      float weight=exp(-colorDiff*expFactor);\n" +
            "      result+=neighbor*weight;\n" +
            "      totalWeight+=weight;\n" +
            "    }\n" +
            "  }\n" +
            "  return result/totalWeight;\n" +
            "}\n" +
            "\n" +
            "void main(){\n" +
            "  vec3 color=bilateralFilter(tc);\n" +
            "  float luma=dot(color,vec3(0.299,0.587,0.114));\n" +
            "  float shadowMask=1.0-smoothstep(u_brightnessThreshold-0.05,u_brightnessThreshold+0.15,luma);\n" +
            "  float liftedLuma=pow(luma,u_gamma);\n" +
            "  float gain=clamp(1.0+(u_brightnessThreshold-luma)*2.5,1.0,3.0);\n" +
            "  float targetLuma=clamp(liftedLuma*gain,0.0,1.0);\n" +
            "  vec3 chroma=color/max(luma,0.0001);\n" +
            "  vec3 boosted=chroma*targetLuma;\n" +
            "  float desatAmt=0.2*shadowMask;\n" +
            "  vec3 gray=vec3(dot(boosted,vec3(0.299,0.587,0.114)));\n" +
            "  boosted=mix(boosted,gray,desatAmt);\n" +
            "  vec2 t=1.0/u_texSize;\n" +
            "  float lyL=dot(texture2D(tex,tc+vec2(-t.x,0.0)).rgb,vec3(0.299,0.587,0.114));\n" +
            "  float lyR=dot(texture2D(tex,tc+vec2( t.x,0.0)).rgb,vec3(0.299,0.587,0.114));\n" +
            "  float lyT=dot(texture2D(tex,tc+vec2(0.0,-t.y)).rgb,vec3(0.299,0.587,0.114));\n" +
            "  float lyB=dot(texture2D(tex,tc+vec2(0.0, t.y)).rgb,vec3(0.299,0.587,0.114));\n" +
            "  float grad=abs(lyR-lyL)+abs(lyB-lyT);\n" +
            "  float edgeMask=1.0-smoothstep(0.05,0.25,grad);\n" +
            "  float localContrast=mix(1.0,u_contrast,shadowMask);\n" +
            "  boosted=(boosted-0.5)*localContrast+0.5;\n" +
            "  vec3 tinted=mix(boosted,boosted*vec3(0.9,1.0,0.9),clamp(u_tintStrength,0.0,1.0)*shadowMask);\n" +
            "  float effect=shadowMask*edgeMask;\n" +
            "  vec3 outColor=mix(color,tinted,u_intensity*effect);\n" +
            "  outColor=clamp(outColor,0.0,1.0);\n" +
            "  gl_FragColor=vec4(outColor,1.0);\n" +
            "}\n";

    // Night vision fragment shader for YUV textures (shadow-only enhancement)
    private static final String YUV_FRAGMENT_SHADER = "precision mediump float;\n" +
            "varying vec2 tc;\n" +
            "uniform sampler2D y_tex;\n" +
            "uniform sampler2D u_tex;\n" +
            "uniform sampler2D v_tex;\n" +
            "uniform float u_intensity;\n" +
            "uniform float u_gamma;\n" +
            "uniform float u_brightnessThreshold;\n" +
            "uniform float u_contrast;\n" +
            "uniform float u_noiseReduction;\n" +
            "uniform float u_tintStrength;\n" +
            "uniform vec2 u_texSize;\n" +
            "\n" +
            "// YUV to RGB conversion\n" +
            "vec3 yuvToRgb(vec3 yuv){\n" +
            "  yuv.y-=0.5;\n" +
            "  yuv.z-=0.5;\n" +
            "  return mat3(\n" +
            "    1.0,1.0,1.0,\n" +
            "    0.0,-0.344,1.772,\n" +
            "    1.402,-0.714,0.0\n" +
            "  )*yuv;\n" +
            "}\n" +
            "\n" +
            "void main(){\n" +
            "  float y=texture2D(y_tex,tc).r;\n" +
            "  float u=texture2D(u_tex,tc).r;\n" +
            "  float v=texture2D(v_tex,tc).r;\n" +
            "  float shadowMask=1.0-smoothstep(u_brightnessThreshold-0.05,u_brightnessThreshold+0.15,y);\n" +
            "  float liftedY=pow(y,u_gamma);\n" +
            "  float gain=clamp(1.0+(u_brightnessThreshold-y)*2.5,1.0,3.0);\n" +
            "  float yEnhanced=clamp(liftedY*gain,0.0,1.0);\n" +
            "  vec2 t=1.0/u_texSize;\n" +
            "  float yL=texture2D(y_tex,tc+vec2(-t.x,0.0)).r;\n" +
            "  float yR=texture2D(y_tex,tc+vec2( t.x,0.0)).r;\n" +
            "  float yT=texture2D(y_tex,tc+vec2(0.0,-t.y)).r;\n" +
            "  float yB=texture2D(y_tex,tc+vec2(0.0, t.y)).r;\n" +
            "  float grad=abs(yR-yL)+abs(yB-yT);\n" +
            "  float edgeMask=1.0-smoothstep(0.05,0.25,grad);\n" +
            "  float localContrast=mix(1.0,u_contrast,shadowMask);\n" +
            "  yEnhanced=(yEnhanced-0.5)*localContrast+0.5;\n" +
            "  float effect=shadowMask*edgeMask;\n" +
            "  float yOut=mix(y,yEnhanced,u_intensity*effect);\n" +
            "  vec3 outColor=yuvToRgb(vec3(yOut,u,v));\n" +
            "  gl_FragColor=vec4(clamp(outColor,0.0,1.0),1.0);\n" +
            "}\n";

    // Night vision fragment shader for OES textures (shadow-only enhancement)
    private static final String OES_FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 tc;\n" +
            "uniform samplerExternalOES tex;\n" +
            "uniform float u_intensity;\n" +
            "uniform float u_gamma;\n" +
            "uniform float u_brightnessThreshold;\n" +
            "uniform float u_contrast;\n" +
            "uniform float u_noiseReduction;\n" +
            "uniform float u_tintStrength;\n" +
            "uniform vec2 u_texSize;\n" +
            "\n" +
            "vec3 bilateralFilter(vec2 coord){\n" +
            "  vec2 texelSize=1.0/u_texSize;\n" +
            "  vec3 center=texture2D(tex,coord).rgb;\n" +
            "  vec3 result=center;\n" +
            "  float totalWeight=1.0;\n" +
            "  const float expFactor=10.0;\n" +
            "  for(int x=-1;x<=1;x++){\n" +
            "    for(int y=-1;y<=1;y++){\n" +
            "      if(x==0&&y==0) continue;\n" +
            "      vec2 offset=vec2(float(x),float(y))*texelSize*u_noiseReduction;\n" +
            "      vec3 neighbor=texture2D(tex,coord+offset).rgb;\n" +
            "      float colorDiff=length(neighbor-center);\n" +
            "      float weight=exp(-colorDiff*expFactor);\n" +
            "      result+=neighbor*weight;\n" +
            "      totalWeight+=weight;\n" +
            "    }\n" +
            "  }\n" +
            "  return result/totalWeight;\n" +
            "}\n" +
            "\n" +
            "void main(){\n" +
            "  vec3 color=bilateralFilter(tc);\n" +
            "  float luma=dot(color,vec3(0.299,0.587,0.114));\n" +
            "  float shadowMask=1.0-smoothstep(u_brightnessThreshold-0.05,u_brightnessThreshold+0.15,luma);\n" +
            "  float liftedLuma=pow(luma,u_gamma);\n" +
            "  float gain=clamp(1.0+(u_brightnessThreshold-luma)*2.5,1.0,3.0);\n" +
            "  float targetLuma=clamp(liftedLuma*gain,0.0,1.0);\n" +
            "  vec3 chroma=color/max(luma,0.0001);\n" +
            "  vec3 boosted=chroma*targetLuma;\n" +
            "  float localContrast=mix(1.0,u_contrast,shadowMask);\n" +
            "  boosted=(boosted-0.5)*localContrast+0.5;\n" +
            "  vec3 tinted=mix(boosted,boosted*vec3(0.9,1.0,0.9),clamp(u_tintStrength,0.0,1.0)*shadowMask);\n" +
            "  float effect=shadowMask;\n" +
            "  vec3 outColor=mix(color,tinted,u_intensity*effect);\n" +
            "  gl_FragColor=vec4(clamp(outColor,0.0,1.0),1.0);\n" +
            "}\n";

    // Vertex coordinates for screen quad
    private static final float[] VERTEX_COORDINATES = new float[] {
            -1.0f, -1.0f, // Bottom left
            1.0f, -1.0f, // Bottom right
            -1.0f, 1.0f, // Top left
            1.0f, 1.0f // Top right
    };

    // Texture coordinates
    private static final float[] TEXTURE_COORDINATES = new float[] {
            0.0f, 0.0f, // Bottom left
            1.0f, 0.0f, // Bottom right
            0.0f, 1.0f, // Top left
            1.0f, 1.0f // Top right
    };

    public NightVisionRenderer() {
        // Initialize vertex buffer
        vertexBuffer = ByteBuffer.allocateDirect(VERTEX_COORDINATES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(VERTEX_COORDINATES);
        vertexBuffer.position(0);
    }

    /**
     * Initialize shaders and GL resources
     */
    private void initializeShaders() {
        if (rgbShader == null) {
            try {
                rgbShader = new GlShader(VERTEX_SHADER, RGB_FRAGMENT_SHADER);
                yuvShader = new GlShader(VERTEX_SHADER, YUV_FRAGMENT_SHADER);
                oesShader = new GlShader(VERTEX_SHADER, OES_FRAGMENT_SHADER);
                Log.d(TAG, "Night vision shaders initialized successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize night vision shaders", e);
                throw new RuntimeException("Failed to initialize night vision shaders", e);
            }
        }
    }

    /**
     * Set night vision configuration parameters
     */
    public void setNightVisionConfig(float intensity, float gamma, float brightnessThreshold,
            float contrast, float noiseReduction, float tintStrength) {
        this.intensity = Math.max(0.0f, Math.min(1.0f, intensity));
        this.gamma = Math.max(0.1f, Math.min(2.0f, gamma));
        this.brightnessThreshold = Math.max(0.0f, Math.min(1.0f, brightnessThreshold));
        this.contrast = Math.max(0.5f, Math.min(3.0f, contrast));
        this.noiseReduction = Math.max(0.0f, Math.min(1.0f, noiseReduction));
        this.tintStrength = Math.max(0.0f, Math.min(1.0f, tintStrength));

        Log.d(TAG, String.format(
                "Night vision config updated: intensity=%.2f, gamma=%.2f, threshold=%.2f, contrast=%.2f, noise=%.2f, tintStrength=%.2f",
                this.intensity, this.gamma, this.brightnessThreshold, this.contrast, this.noiseReduction,
                this.tintStrength));
    }

    /**
     * Apply shader uniforms
     */
    private void applyShaderUniforms(GlShader shader, int frameWidth, int frameHeight) {
        GLES20.glUniform1f(shader.getUniformLocation("u_intensity"), intensity);
        GLES20.glUniform1f(shader.getUniformLocation("u_gamma"), gamma);
        GLES20.glUniform1f(shader.getUniformLocation("u_brightnessThreshold"), brightnessThreshold);
        GLES20.glUniform1f(shader.getUniformLocation("u_contrast"), contrast);
        GLES20.glUniform1f(shader.getUniformLocation("u_noiseReduction"), noiseReduction);
        GLES20.glUniform1f(shader.getUniformLocation("u_tintStrength"), tintStrength);
        GLES20.glUniform2f(shader.getUniformLocation("u_texSize"), frameWidth, frameHeight);
    }

    /**
     * Set up vertex attributes
     */
    private void setupVertexAttributes(GlShader shader, float[] texMatrix) {
        int posLocation = shader.getAttribLocation("in_pos");
        int tcLocation = shader.getAttribLocation("in_tc");
        int matrixLocation = shader.getUniformLocation("texMatrix");

        // Enable vertex attributes
        GLES20.glEnableVertexAttribArray(posLocation);
        GLES20.glEnableVertexAttribArray(tcLocation);

        // Set vertex data
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(posLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        // Set texture coordinates
        FloatBuffer texBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDINATES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        texBuffer.put(TEXTURE_COORDINATES);
        texBuffer.position(0);
        GLES20.glVertexAttribPointer(tcLocation, 2, GLES20.GL_FLOAT, false, 0, texBuffer);

        // Set texture matrix
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, texMatrix, 0);
    }

    /**
     * Update performance statistics
     */
    private void updateStats(long processingTimeNs) {
        frameCount++;
        totalProcessingTime += processingTimeNs;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastStatsTime >= 5000) { // Log every 5 seconds
            double avgFrameTime = (double) totalProcessingTime / frameCount / 1_000_000.0; // ms
            double fps = 1000.0 / avgFrameTime;

            Log.d(TAG, String.format("Night vision stats: %.1f FPS, %.2f ms avg frame time, %d frames processed",
                    fps, avgFrameTime, frameCount));

            frameCount = 0;
            totalProcessingTime = 0;
            lastStatsTime = currentTime;
        }
    }

    @Override
    public void drawOes(int oesTextureId, float[] texMatrix, int frameWidth, int frameHeight,
            int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
        long startTime = System.nanoTime();

        try {
            initializeShaders();

            // Use OES shader
            oesShader.useProgram();

            // Set viewport
            GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);

            // Bind texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES20.glUniform1i(oesShader.getUniformLocation("tex"), 0);

            // Apply night vision uniforms
            applyShaderUniforms(oesShader, frameWidth, frameHeight);

            // Setup vertex attributes and draw
            setupVertexAttributes(oesShader, texMatrix);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // Check for GL errors
            GlUtil.checkNoGLES2Error("NightVisionRenderer.drawOes");

        } catch (Exception e) {
            Log.e(TAG, "Error in drawOes", e);
        } finally {
            updateStats(System.nanoTime() - startTime);
        }
    }

    @Override
    public void drawRgb(int textureId, float[] texMatrix, int frameWidth, int frameHeight,
            int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
        long startTime = System.nanoTime();

        try {
            initializeShaders();

            // Use RGB shader
            rgbShader.useProgram();

            // Set viewport
            GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);

            // Bind texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(rgbShader.getUniformLocation("tex"), 0);

            // Apply night vision uniforms
            applyShaderUniforms(rgbShader, frameWidth, frameHeight);

            // Setup vertex attributes and draw
            setupVertexAttributes(rgbShader, texMatrix);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // Check for GL errors
            GlUtil.checkNoGLES2Error("NightVisionRenderer.drawRgb");

        } catch (Exception e) {
            Log.e(TAG, "Error in drawRgb", e);
        } finally {
            updateStats(System.nanoTime() - startTime);
        }
    }

    @Override
    public void drawYuv(int[] yuvTextures, float[] texMatrix, int frameWidth, int frameHeight,
            int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
        long startTime = System.nanoTime();

        try {
            initializeShaders();

            // Use YUV shader
            yuvShader.useProgram();

            // Set viewport
            GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);

            // Bind Y, U, V textures
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures[0]);
            GLES20.glUniform1i(yuvShader.getUniformLocation("y_tex"), 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures[1]);
            GLES20.glUniform1i(yuvShader.getUniformLocation("u_tex"), 1);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures[2]);
            GLES20.glUniform1i(yuvShader.getUniformLocation("v_tex"), 2);

            // Apply night vision uniforms
            applyShaderUniforms(yuvShader, frameWidth, frameHeight);

            // Setup vertex attributes and draw
            setupVertexAttributes(yuvShader, texMatrix);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // Check for GL errors
            GlUtil.checkNoGLES2Error("NightVisionRenderer.drawYuv");

        } catch (Exception e) {
            Log.e(TAG, "Error in drawYuv", e);
        } finally {
            updateStats(System.nanoTime() - startTime);
        }
    }

    @Override
    public void release() {
        Log.d(TAG, "Releasing night vision renderer resources");

        try {
            if (rgbShader != null) {
                rgbShader.release();
                rgbShader = null;
            }
            if (yuvShader != null) {
                yuvShader.release();
                yuvShader = null;
            }
            if (oesShader != null) {
                oesShader.release();
                oesShader = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing night vision renderer", e);
        }

        Log.d(TAG, "Night vision renderer resources released successfully");
    }

    /**
     * Get performance statistics
     */
    public String getPerformanceStats() {
        if (frameCount > 0) {
            double avgFrameTime = (double) totalProcessingTime / frameCount / 1_000_000.0;
            double fps = 1000.0 / avgFrameTime;
            return String.format("NightVision: %.1f FPS, %.2f ms avg", fps, avgFrameTime);
        }
        return "NightVision: No stats available";
    }
}