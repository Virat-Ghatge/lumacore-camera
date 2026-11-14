package com.lumacore.camera.core.nativebridge

import android.util.Log

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
}
