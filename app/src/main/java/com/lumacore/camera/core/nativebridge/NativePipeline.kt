package com.lumacore.camera.core.nativebridge

import android.util.Log
import java.nio.ByteBuffer

/**
 * JNI Bridge to lumacore_native C++ engine.
 */
object NativePipeline {
    private const val TAG: String = "NativePipeline"

    private var isLoaded: Boolean = false

    init {
        try {
            System.loadLibrary("lumacore_native")
            isLoaded = true
            Log.i(TAG, "lumacore_native library loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load lumacore_native shared library", e)
            isLoaded = false
        }
    }

    fun isNativeReady(): Boolean = isLoaded

    external fun nativeInit(): Boolean

    external fun nativeGetVersion(): String?

    external fun nativeComputeGyroHomography(
        rotX: Float,
        rotY: Float,
        rotZ: Float,
        focalLength: Float,
        cx: Float,
        cy: Float
    ): FloatArray

    external fun nativeAlignAndMergeBurst(
        yPlanes: Array<ByteBuffer>,
        uPlanes: Array<ByteBuffer>,
        vPlanes: Array<ByteBuffer>,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        gyroRotations: FloatArray,
        outY: ByteBuffer,
        outU: ByteBuffer,
        outV: ByteBuffer
    ): FloatArray

    external fun nativeSliceBilateralGrid(
        inBuffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        gridData: FloatArray,
        gridDepth: Int,
        gridHeight: Int,
        gridWidth: Int,
        outBuffer: ByteBuffer
    ): Boolean

    external fun nativeComputeLaplacianVariance(
        lumaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int
    ): Float

    external fun nativeSelectSharpestFrame(
        lumaPlanes: Array<ByteBuffer>,
        width: Int,
        height: Int,
        stride: Int
    ): Int

    external fun nativeMergeSuperRes2x(
        yPlanes: Array<ByteBuffer>,
        inWidth: Int,
        inHeight: Int,
        inStride: Int,
        out2xY: ByteBuffer,
        outWidth: Int,
        outHeight: Int,
        outStride: Int
    ): Boolean

    external fun nativeConvertYuvToRgba(
        yPlane: ByteBuffer,
        uPlane: ByteBuffer,
        vPlane: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        outRgba: ByteBuffer
    ): Boolean

    external fun nativeProcessColorPipeline(
        yPlane: ByteBuffer,
        uPlane: ByteBuffer,
        vPlane: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        blackLevel: FloatArray,
        whiteLevel: Float,
        colorGains: FloatArray,
        colorTransform: FloatArray,
        iso: Int,
        exposureTimeNs: Long,
        radialVignetteGain: Float,
        outRgba: ByteBuffer
    ): Boolean

    external fun nativeComputeHistogram(
        rgbaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        outR: IntArray,
        outG: IntArray,
        outB: IntArray,
        outLuma: IntArray
    ): Boolean
}
