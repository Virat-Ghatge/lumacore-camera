package com.lumacore.camera.core.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.lumacore.camera.core.nativebridge.NativePipeline
import com.lumacore.camera.core.neural.BilateralGridResult
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Result produced by bilateral grid upsampling.
 *
 * @param enhancedBitmap Full-resolution enhanced output bitmap.
 * @param executionMs    Slicing execution latency in milliseconds.
 */
data class BilateralEnhanceResult(
    val enhancedBitmap: Bitmap,
    val executionMs: Long
)

/**
 * High-speed hardware-accelerated bilateral upsampler using headless OpenGL ES 3.0.
 * Uploads neural affine coefficients into 3D textures (`GL_TEXTURE_3D`) and computes
 * hardware trilinear interpolation in (X, Y, Luma) across full-resolution frames.
 *
 * Includes a multi-threaded C++ / native CPU fallback in case of headless or GPU-deprived environments.
 */
class GlesBilateralUpsampler(
    private val context: Context
) : AutoCloseable {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var isEglInitialized: Boolean = false

    private var programId: Int = 0
    private var uInputTextureLoc: Int = -1
    private var uGridTexture0Loc: Int = -1
    private var uGridTexture1Loc: Int = -1
    private var uGridTexture2Loc: Int = -1

    private var quadVboId: Int = 0
    private var quadVaoId: Int = 0

    init {
        initializeEgl()
    }

    private fun initializeEgl(): Boolean {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.w(TAG, "EGL14.eglGetDisplay returned NO_DISPLAY")
                return false
            }

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                Log.w(TAG, "EGL14.eglInitialize failed")
                return false
            }

            // Request OpenGL ES 3.0 support
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                Log.w(TAG, "eglChooseConfig failed to find matching GLES 3.0 config")
                return false
            }
            val config = configs[0] ?: return false

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.w(TAG, "eglCreateContext failed for GLES 3.0")
                return false
            }

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufferAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "eglCreatePbufferSurface failed")
                return false
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.w(TAG, "eglMakeCurrent failed")
                return false
            }

            initGlPipeline()
            isEglInitialized = true
            Log.i(TAG, "Headless EGL14 context initialized successfully for OpenGL ES 3.0.")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Exception during EGL initialization, falling back to CPU slicing: ${e.message}")
            teardownEgl()
            return false
        }
    }

    private fun initGlPipeline(): Unit {
        val vertCode = loadShaderSource(VERTEX_SHADER_ASSET, DEFAULT_VERT_SHADER)
        val fragCode = loadShaderSource(FRAGMENT_SHADER_ASSET, DEFAULT_FRAG_SHADER)

        val vertShader = compileShader(GLES30.GL_VERTEX_SHADER, vertCode)
        val fragShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragCode)

        programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vertShader)
        GLES30.glAttachShader(programId, fragShader)
        GLES30.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(programId)
            GLES30.glDeleteProgram(programId)
            programId = 0
            throw IllegalStateException("Failed to link bilateral slice shader program: $log")
        }

        GLES30.glDeleteShader(vertShader)
        GLES30.glDeleteShader(fragShader)

        uInputTextureLoc = GLES30.glGetUniformLocation(programId, "uInputTexture")
        uGridTexture0Loc = GLES30.glGetUniformLocation(programId, "uGridTexture0")
        uGridTexture1Loc = GLES30.glGetUniformLocation(programId, "uGridTexture1")
        uGridTexture2Loc = GLES30.glGetUniformLocation(programId, "uGridTexture2")

        setupQuadBuffers()
    }

    private fun setupQuadBuffers(): Unit {
        // Quad NDC coordinates and corresponding texture coordinates
        // Top-left: (-1, 1) -> (0, 1), Bottom-left: (-1, -1) -> (0, 0)
        // Top-right: (1, 1) -> (1, 1), Bottom-right: (1, -1) -> (1, 0)
        val quadVertices = floatArrayOf(
            -1.0f,  1.0f, 0.0f, 1.0f,
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f,  1.0f, 1.0f, 1.0f,
             1.0f, -1.0f, 1.0f, 0.0f
        )

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        quadVaoId = vaos[0]
        GLES30.glBindVertexArray(quadVaoId)

        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        quadVboId = vbos[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVboId)

        val buffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadVertices)
        buffer.rewind()

        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadVertices.size * 4, buffer, GLES30.GL_STATIC_DRAW)

        // Attribute 0: vec2 aPosition
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, 0)

        // Attribute 1: vec2 aTexCoord
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, 2 * 4)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun loadShaderSource(assetPath: String, fallback: String): String {
        return try {
            context.assets.open(assetPath).use { stream: InputStream ->
                stream.bufferedReader().use { reader -> reader.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read shader asset $assetPath, using fallback", e)
            fallback
        }
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, code)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Shader compile error: $log")
        }
        return shader
    }

    /**
     * Enhances a full-resolution [Bitmap] using the predicted [BilateralGridResult].
     * Uses OpenGL ES 3.0 trilinear hardware interpolation, or falls back to multi-threaded CPU acceleration.
     *
     * @param inputBitmap Full-resolution source image bitmap.
     * @param gridResult  3D bilateral grid affine coefficient tensor.
     * @return [BilateralEnhanceResult] with the enhanced bitmap and execution latency in ms.
     */
    fun enhanceImage(
        inputBitmap: Bitmap,
        gridResult: BilateralGridResult
    ): BilateralEnhanceResult {
        if (!isEglInitialized || programId == 0) {
            return enhanceImageCpuFallback(inputBitmap, gridResult)
        }

        return try {
            val startTime = System.currentTimeMillis()

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.w(TAG, "eglMakeCurrent failed in enhanceImage; delegating to CPU")
                return enhanceImageCpuFallback(inputBitmap, gridResult)
            }

            val width = inputBitmap.width
            val height = inputBitmap.height

            // 1. Upload 3D bilateral grid textures
            val gridTextures = uploadGridTextures(gridResult)

            // 2. Upload full-resolution input frame 2D texture
            val inTexIds = IntArray(1)
            GLES30.glGenTextures(1, inTexIds, 0)
            val inTexId = inTexIds[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inTexId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, inputBitmap, 0)

            // 3. Setup offscreen framebuffer target
            val outTexIds = IntArray(1)
            GLES30.glGenTextures(1, outTexIds, 0)
            val outTexId = outTexIds[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outTexId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA8,
                width,
                height,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                null
            )

            val fbos = IntArray(1)
            GLES30.glGenFramebuffers(1, fbos, 0)
            val fboId = fbos[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                outTexId,
                0
            )

            val fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (fboStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw IllegalStateException("Framebuffer incomplete with status: $fboStatus")
            }

            // 4. Render bilateral slice full-screen quad
            GLES30.glViewport(0, 0, width, height)
            GLES30.glUseProgram(programId)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inTexId)
            GLES30.glUniform1i(uInputTextureLoc, 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, gridTextures[0])
            GLES30.glUniform1i(uGridTexture0Loc, 1)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, gridTextures[1])
            GLES30.glUniform1i(uGridTexture1Loc, 2)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, gridTextures[2])
            GLES30.glUniform1i(uGridTexture2Loc, 3)

            GLES30.glBindVertexArray(quadVaoId)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)

            // 5. Read back enhanced pixels directly into Bitmap
            val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuffer)
            pixelBuffer.rewind()

            val enhancedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            enhancedBitmap.copyPixelsFromBuffer(pixelBuffer)

            // Cleanup textures and FBO
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbos, 0)
            GLES30.glDeleteTextures(1, outTexIds, 0)
            GLES30.glDeleteTextures(1, inTexIds, 0)
            GLES30.glDeleteTextures(3, gridTextures, 0)

            val executionMs = System.currentTimeMillis() - startTime
            BilateralEnhanceResult(enhancedBitmap, executionMs)
        } catch (e: Exception) {
            Log.e(TAG, "GLES upsampling error: ${e.message}; falling back to CPU slicing", e)
            enhanceImageCpuFallback(inputBitmap, gridResult)
        }
    }

    private fun uploadGridTextures(gridResult: BilateralGridResult): IntArray {
        val gridTextures = IntArray(3)
        GLES30.glGenTextures(3, gridTextures, 0)

        val depth = gridResult.depth
        val height = gridResult.height
        val width = gridResult.width

        val depthStride = height * width
        val channelStride = depth * depthStride
        val floatsPerTex = depth * height * width * 4

        for (k in 0..2) {
            val baseChannel = k * 4
            val floatBuf: FloatBuffer = ByteBuffer.allocateDirect(floatsPerTex * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            for (d in 0 until depth) {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val cellIdx = d * depthStride + y * width + x
                        floatBuf.put(gridResult.gridData[(baseChannel + 0) * channelStride + cellIdx])
                        floatBuf.put(gridResult.gridData[(baseChannel + 1) * channelStride + cellIdx])
                        floatBuf.put(gridResult.gridData[(baseChannel + 2) * channelStride + cellIdx])
                        floatBuf.put(gridResult.gridData[(baseChannel + 3) * channelStride + cellIdx])
                    }
                }
            }
            floatBuf.rewind()

            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, gridTextures[k])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D,
                0,
                GLES30.GL_RGBA16F,
                width,
                height,
                depth,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_FLOAT,
                floatBuf
            )
        }

        return gridTextures
    }

    /**
     * High-speed CPU bilateral slicing fallback utilizing native C++20 SIMD/thread acceleration.
     */
    fun enhanceImageCpuFallback(
        bitmap: Bitmap,
        gridResult: BilateralGridResult
    ): BilateralEnhanceResult {
        val width = bitmap.width
        val height = bitmap.height
        val totalBytes = width * height * 4

        val inBuffer = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.nativeOrder())
        val outBuffer = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(inBuffer)
        inBuffer.rewind()

        val startTime = System.currentTimeMillis()

        val success = if (NativePipeline.isNativeReady()) {
            NativePipeline.nativeSliceBilateralGrid(
                inBuffer = inBuffer,
                width = width,
                height = height,
                pixelStride = 4,
                gridData = gridResult.gridData,
                gridDepth = gridResult.depth,
                gridHeight = gridResult.height,
                gridWidth = gridResult.width,
                outBuffer = outBuffer
            )
        } else {
            false
        }

        val executionMs = System.currentTimeMillis() - startTime

        return if (success) {
            outBuffer.rewind()
            val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            outBitmap.copyPixelsFromBuffer(outBuffer)
            BilateralEnhanceResult(outBitmap, executionMs)
        } else {
            // Passthrough copy if native slicing unavailable
            BilateralEnhanceResult(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false), executionMs)
        }
    }

    private fun teardownEgl(): Unit {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        isEglInitialized = false
    }

    override fun close(): Unit {
        if (isEglInitialized) {
            try {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                if (programId != 0) {
                    GLES30.glDeleteProgram(programId)
                    programId = 0
                }
                if (quadVaoId != 0) {
                    GLES30.glDeleteVertexArrays(1, intArrayOf(quadVaoId), 0)
                    quadVaoId = 0
                }
                if (quadVboId != 0) {
                    GLES30.glDeleteBuffers(1, intArrayOf(quadVboId), 0)
                    quadVboId = 0
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up GL resources", e)
            } finally {
                teardownEgl()
            }
        }
    }

    companion object {
        private const val TAG: String = "GlesBilateralUpsampler"
        private const val VERTEX_SHADER_ASSET: String = "shaders/bilateral_slice.vert"
        private const val FRAGMENT_SHADER_ASSET: String = "shaders/bilateral_slice.frag"

        private const val EGL_OPENGL_ES3_BIT: Int = 0x00000040

        private const val DEFAULT_VERT_SHADER: String = """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}"""

        private const val DEFAULT_FRAG_SHADER: String = """#version 300 es
precision highp float;
precision highp sampler3D;
in vec2 vTexCoord;
out vec4 FragColor;
uniform sampler2D uInputTexture;
uniform sampler3D uGridTexture0;
uniform sampler3D uGridTexture1;
uniform sampler3D uGridTexture2;
void main() {
    vec4 color = texture(uInputTexture, vTexCoord);
    float luma = clamp(dot(color.rgb, vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
    vec3 sliceCoord = vec3(vTexCoord.x, vTexCoord.y, luma);
    vec4 affR = texture(uGridTexture0, sliceCoord);
    vec4 affG = texture(uGridTexture1, sliceCoord);
    vec4 affB = texture(uGridTexture2, sliceCoord);
    vec3 enhanced;
    enhanced.r = dot(affR.rgb, color.rgb) + affR.a;
    enhanced.g = dot(affG.rgb, color.rgb) + affG.a;
    enhanced.b = dot(affB.rgb, color.rgb) + affB.a;
    FragColor = vec4(clamp(enhanced, 0.0, 1.0), color.a);
}"""
    }
}
