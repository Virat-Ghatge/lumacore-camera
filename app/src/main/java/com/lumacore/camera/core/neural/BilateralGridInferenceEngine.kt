package com.lumacore.camera.core.neural

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

/**
 * Result container for neural bilateral grid prediction.
 * Dimensions: [batch=1, channels=12, depth=8, height=16, width=16] -> 24,576 float elements.
 */
data class BilateralGridResult(
    val gridData: FloatArray,
    val channels: Int = 12,
    val depth: Int = 8,
    val height: Int = 16,
    val width: Int = 16,
    val latencyMs: Long = 0L,
    val safetyBlend: Float = 1.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BilateralGridResult
        if (!gridData.contentEquals(other.gridData)) return false
        if (channels != other.channels) return false
        if (depth != other.depth) return false
        if (height != other.height) return false
        if (width != other.width) return false
        if (latencyMs != other.latencyMs) return false
        if (safetyBlend != other.safetyBlend) return false
        return true
    }

    override fun hashCode(): Int {
        var result = gridData.contentHashCode()
        result = 31 * result + channels
        result = 31 * result + depth
        result = 31 * result + height
        result = 31 * result + width
        result = 31 * result + latencyMs.hashCode()
        result = 31 * result + safetyBlend.hashCode()
        return result
    }
}

/**
 * High-performance mobile neural inference engine for HDRNet bilateral grid prediction.
 * Configures ONNX Runtime with hardware-accelerated NNAPI Execution Provider and provides
 * robust multi-threaded CPU / XNNPACK fallback.
 */
class BilateralGridInferenceEngine(
    private val context: Context
) : AutoCloseable {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    var isNnapiActive: Boolean = false
        private set

    init {
        initializeSession()
    }

    private fun initializeSession(): Unit {
        try {
            val modelBytes: ByteArray = context.assets.open(MODEL_ASSET_PATH).use { input ->
                input.readBytes()
            }

            var sessionCreated = false

            // Attempt 1: NNAPI execution provider
            try {
                val nnapiOptions = OrtSession.SessionOptions().apply {
                    addNnapi()
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                ortSession = ortEnv.createSession(modelBytes, nnapiOptions)
                isNnapiActive = true
                sessionCreated = true
                Log.i(TAG, "ONNX Runtime initialized with NNAPI Execution Provider.")
            } catch (nnapiEx: Throwable) {
                Log.w(TAG, "NNAPI provider unavailable or failed to initialize: ${nnapiEx.message}")
            }

            // Attempt 2: High-performance 4-thread CPU / XNNPACK fallback
            if (!sessionCreated) {
                val cpuOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                ortSession = ortEnv.createSession(modelBytes, cpuOptions)
                isNnapiActive = false
                Log.i(TAG, "ONNX Runtime initialized with multi-threaded CPU / XNNPACK fallback.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model from assets ($MODEL_ASSET_PATH): ${e.message}", e)
            ortSession = null
            isNnapiActive = false
        }
    }

    /**
     * Executes bilateral grid prediction on the given frame.
     * Preprocesses image to NCHW [1, 3, 384, 512] float array normalized in [0.0f, 1.0f].
     *
     * @param bitmap Input bitmap (any resolution). Will be downsampled internally to 384x512.
     * @return Structured [BilateralGridResult] containing the 24,576 affine grid coefficients.
     */
    fun predictBilateralGrid(bitmap: Bitmap): BilateralGridResult {
        val session = ortSession
        if (session == null) {
            Log.w(TAG, "ONNX session uninitialized; returning identity bilateral grid.")
            return createIdentityBilateralGrid()
        }

        val scaledBitmap = if (bitmap.width == MODEL_INPUT_WIDTH && bitmap.height == MODEL_INPUT_HEIGHT) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT, true)
        }

        val planeSize = MODEL_INPUT_WIDTH * MODEL_INPUT_HEIGHT
        val pixels = IntArray(planeSize)
        scaledBitmap.getPixels(pixels, 0, MODEL_INPUT_WIDTH, 0, 0, MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT)

        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }

        val floatBuffer = ByteBuffer.allocateDirect(1 * 3 * planeSize * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val rPlane = FloatArray(planeSize)
        val gPlane = FloatArray(planeSize)
        val bPlane = FloatArray(planeSize)

        for (i in 0 until planeSize) {
            val px = pixels[i]
            rPlane[i] = ((px shr 16) and 0xFF) * (1.0f / 255.0f)
            gPlane[i] = ((px shr 8) and 0xFF) * (1.0f / 255.0f)
            bPlane[i] = (px and 0xFF) * (1.0f / 255.0f)
        }

        floatBuffer.put(rPlane)
        floatBuffer.put(gPlane)
        floatBuffer.put(bPlane)
        floatBuffer.rewind()

        val inputShape = longArrayOf(1L, 3L, MODEL_INPUT_HEIGHT.toLong(), MODEL_INPUT_WIDTH.toLong())

        return try {
            val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, inputShape)
            val startTime = System.currentTimeMillis()
            val results = session.run(Collections.singletonMap(INPUT_TENSOR_NAME, inputTensor))
            val latencyMs = System.currentTimeMillis() - startTime

            val outputTensor: OnnxTensor? = if (results.size() > 0) {
                results.get(0) as? OnnxTensor
            } else {
                null
            }

            val gridData = if (outputTensor != null) {
                val outBuffer = outputTensor.floatBuffer
                val data = FloatArray(outBuffer.remaining())
                outBuffer.get(data)
                data
            } else {
                createIdentityGridArray()
            }

            results.close()
            inputTensor.close()

            // 1. Inspect for NaN or Infinite values
            var hasNanOrInf = false
            for (v in gridData) {
                if (v.isNaN() || v.isInfinite()) {
                    hasNanOrInf = true
                    break
                }
            }
            if (hasNanOrInf) {
                Log.w(TAG, "Anomaly: Bilateral grid contains NaN or Infinite values; replacing with identity grid.")
                return createIdentityBilateralGrid()
            }

            // 2. Compute dynamic range and variance across coefficients
            var minVal = Float.MAX_VALUE
            var maxVal = -Float.MAX_VALUE
            var sum = 0.0
            for (v in gridData) {
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
                sum += v.toDouble()
            }
            val mean = (sum / gridData.size).toFloat()
            var sumSqDiff = 0.0
            for (v in gridData) {
                val diff = v - mean
                sumSqDiff += (diff * diff).toDouble()
            }
            val variance = (sumSqDiff / gridData.size).toFloat()

            // 3. Detect abnormalities, sanitize bounds, and compute calibrated safety blend factor
            var safetyBlend = 1.0f
            val isRangeAbnormal = maxVal > 3.0f || minVal < -1.0f
            val isVarianceAbnormal = variance > 2.0f

            if (isRangeAbnormal || isVarianceAbnormal) {
                Log.w(
                    TAG,
                    "Coefficient anomaly detected (min=$minVal, max=$maxVal, variance=$variance). " +
                    "Sanitizing grid coefficients and applying safety blend factor."
                )
                for (i in gridData.indices) {
                    gridData[i] = gridData[i].coerceIn(-1.0f, 3.0f)
                }
                safetyBlend = when {
                    maxVal > 5.0f || minVal < -2.5f || variance > 4.0f -> 0.50f
                    maxVal > 4.0f || minVal < -1.8f || variance > 2.5f -> 0.70f
                    else -> 0.85f
                }
            }

            BilateralGridResult(
                gridData = gridData,
                channels = GRID_CHANNELS,
                depth = GRID_DEPTH,
                height = GRID_HEIGHT,
                width = GRID_WIDTH,
                latencyMs = latencyMs,
                safetyBlend = safetyBlend
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inference error during predictBilateralGrid: ${e.message}", e)
            createIdentityBilateralGrid()
        }
    }

    /**
     * Constructs a neutral identity bilateral grid providing a 1:1 pass-through transformation.
     */
    fun createIdentityBilateralGrid(): BilateralGridResult {
        return BilateralGridResult(
            gridData = createIdentityGridArray(),
            channels = GRID_CHANNELS,
            depth = GRID_DEPTH,
            height = GRID_HEIGHT,
            width = GRID_WIDTH,
            latencyMs = 0L,
            safetyBlend = 1.0f
        )
    }

    private fun createIdentityGridArray(): FloatArray {
        val totalElements = GRID_CHANNELS * GRID_DEPTH * GRID_HEIGHT * GRID_WIDTH
        val array = FloatArray(totalElements)
        val depthStride = GRID_HEIGHT * GRID_WIDTH
        val channelStride = GRID_DEPTH * depthStride

        for (d in 0 until GRID_DEPTH) {
            for (y in 0 until GRID_HEIGHT) {
                for (x in 0 until GRID_WIDTH) {
                    val cellIdx = d * depthStride + y * GRID_WIDTH + x
                    // Identity affine color transform:
                    // R_out = 1.0 * R_in (channel 0)
                    // G_out = 1.0 * G_in (channel 5)
                    // B_out = 1.0 * B_in (channel 10)
                    array[0 * channelStride + cellIdx] = 1.0f
                    array[5 * channelStride + cellIdx] = 1.0f
                    array[10 * channelStride + cellIdx] = 1.0f
                }
            }
        }
        return array
    }

    override fun close(): Unit {
        try {
            ortSession?.close()
            ortSession = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ORT session", e)
        }
        try {
            ortEnv.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ORT environment", e)
        }
    }

    companion object {
        private const val TAG: String = "BilateralGridInference"
        private const val MODEL_ASSET_PATH: String = "models/hdrnet_bilateral_grid_384x512.onnx"
        private const val INPUT_TENSOR_NAME: String = "input_preview"
        private const val OUTPUT_TENSOR_NAME: String = "bilateral_grid"

        const val MODEL_INPUT_WIDTH: Int = 512
        const val MODEL_INPUT_HEIGHT: Int = 384
        const val GRID_CHANNELS: Int = 12
        const val GRID_DEPTH: Int = 8
        const val GRID_HEIGHT: Int = 16
        const val GRID_WIDTH: Int = 16
    }
}
