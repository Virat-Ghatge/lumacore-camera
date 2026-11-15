package com.lumacore.camera.core.sensor

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/**
 * Monitors device thermal status via PowerManager (API 29+) and computes dynamic
 * burst scaling factors to prevent thermal throttling and hardware degradation.
 */
class ThermalManager(
    context: Context
) : Closeable {

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _thermalStatus: MutableStateFlow<Int> = MutableStateFlow(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            powerManager.currentThermalStatus
        } else {
            0
        }
    )
    val thermalStatus: StateFlow<Int> = _thermalStatus.asStateFlow()

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    init {
        registerThermalListener(context)
    }

    private fun registerThermalListener(context: Context): Unit {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                val listener = PowerManager.OnThermalStatusChangedListener { status: Int ->
                    Log.i(TAG, "Thermal status changed: $status (${getThermalStatusLabel(status)})")
                    _thermalStatus.value = status
                }
                powerManager.addThermalStatusListener(
                    ContextCompat.getMainExecutor(context),
                    listener
                )
                thermalListener = listener
                _thermalStatus.value = powerManager.currentThermalStatus
                Log.i(TAG, "Thermal listener registered, initial status: ${_thermalStatus.value} (${getThermalStatusLabel(_thermalStatus.value)})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register thermal status listener", e)
            }
        }
    }

    /**
     * Computes the burst frame scaling factor based on current thermal state.
     *  - THERMAL_STATUS_NONE / LIGHT: 1.0f (full burst count)
     *  - THERMAL_STATUS_MODERATE: 0.65f (e.g. scales 8 -> 5 frames)
     *  - THERMAL_STATUS_SEVERE / CRITICAL: 0.40f (e.g. scales 8 -> 3 frames)
     *  - THERMAL_STATUS_EMERGENCY / SHUTDOWN: 0.25f (minimal capture)
     */
    fun getBurstScalingFactor(): Float {
        val status = _thermalStatus.value
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (status) {
                PowerManager.THERMAL_STATUS_NONE,
                PowerManager.THERMAL_STATUS_LIGHT -> 1.0f
                PowerManager.THERMAL_STATUS_MODERATE -> 0.65f
                PowerManager.THERMAL_STATUS_SEVERE,
                PowerManager.THERMAL_STATUS_CRITICAL -> 0.40f
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> 0.25f
                else -> 1.0f
            }
        } else {
            1.0f
        }
    }

    /**
     * Returns true if thermal throttling is actively degrading capture burst depth.
     */
    fun isThrottling(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val status = _thermalStatus.value
        return status >= PowerManager.THERMAL_STATUS_MODERATE
    }

    /**
     * Converts integer thermal status code to human-readable telemetry label.
     */
    fun getThermalStatusLabel(status: Int = _thermalStatus.value): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "NORMAL"
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NORMAL"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN ($status)"
        }
    }

    override fun close(): Unit {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            thermalListener?.let { listener ->
                try {
                    powerManager.removeThermalStatusListener(listener)
                    Log.i(TAG, "Thermal listener unregistered successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister thermal status listener", e)
                }
            }
            thermalListener = null
        }
    }

    companion object {
        private const val TAG: String = "ThermalManager"
    }
}
