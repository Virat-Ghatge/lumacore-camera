package com.lumacore.camera.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * Real-time tripod detection engine for Night mode stabilization.
 * Continuously evaluates sensor variance across a 1.0-second sliding window
 * of accelerometer and gyroscope readings. If acceleration variance drops
 * below 0.05 m/s², the device is classified as tripod-mounted.
 */
class TripodDetector(
    context: Context
) : SensorEventListener, AutoCloseable {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gyroscope: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private data class TimestampedMagnitude(
        val timestampNs: Long,
        val magnitude: Float
    )

    private val lock: Any = Any()
    private val accelWindow: ArrayDeque<TimestampedMagnitude> = ArrayDeque(128)
    private val gyroWindow: ArrayDeque<TimestampedMagnitude> = ArrayDeque(128)

    private val _tripodFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val tripodFlow: StateFlow<Boolean> = _tripodFlow.asStateFlow()

    @Volatile
    var isTripodMounted: Boolean = false
        private set

    private val listeners: CopyOnWriteArrayList<(Boolean) -> Unit> = CopyOnWriteArrayList()

    @Volatile
    private var isListening: Boolean = false

    fun addListener(listener: (Boolean) -> Unit): Unit {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit): Unit {
        listeners.remove(listener)
    }

    /**
     * Starts listening to accelerometer and gyroscope sensor streams.
     * Returns true if both sensors (or at least accelerometer) were registered.
     */
    fun startListening(): Boolean {
        val manager = sensorManager ?: return false
        val accel = accelerometer ?: return false

        synchronized(lock) {
            accelWindow.clear()
            gyroWindow.clear()
            isTripodMounted = false
            _tripodFlow.value = false
        }

        val accelRegistered = manager.registerListener(
            this,
            accel,
            SensorManager.SENSOR_DELAY_GAME
        )

        gyroscope?.let { gyro ->
            manager.registerListener(
                this,
                gyro,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        isListening = accelRegistered
        Log.i(TAG, "TripodDetector started (registered: $accelRegistered)")
        return accelRegistered
    }

    /**
     * Stops sensor listeners and clears historical sample buffers.
     */
    fun stopListening(): Unit {
        if (!isListening) return
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering tripod sensor listeners", e)
        } finally {
            isListening = false
            synchronized(lock) {
                accelWindow.clear()
                gyroWindow.clear()
            }
            Log.i(TAG, "TripodDetector stopped")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val currentTimestamp = event.timestamp
        val cutoffTimestamp = currentTimestamp - WINDOW_DURATION_NS

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val mag = sqrt(ax * ax + ay * ay + az * az)

                var currentMountedState = false
                var stateChanged = false

                synchronized(lock) {
                    accelWindow.addLast(TimestampedMagnitude(currentTimestamp, mag))

                    // Prune elements outside the 1.0s sliding window
                    while (accelWindow.isNotEmpty() && accelWindow.first().timestampNs < cutoffTimestamp) {
                        accelWindow.removeFirst()
                    }

                    // Variance computation requires sufficient sample density
                    if (accelWindow.size >= MIN_SAMPLES) {
                        val duration = accelWindow.last().timestampNs - accelWindow.first().timestampNs
                        if (duration >= MIN_WINDOW_SPAN_NS) {
                            var sum = 0.0
                            for (s in accelWindow) {
                                sum += s.magnitude
                            }
                            val mean = sum / accelWindow.size

                            var sumSqDiff = 0.0
                            for (s in accelWindow) {
                                val diff = s.magnitude - mean
                                sumSqDiff += diff * diff
                            }
                            val variance = sumSqDiff / accelWindow.size

                            // Also verify gyro angular stability if gyroscope is available
                            var gyroStable = true
                            if (gyroWindow.size >= 5) {
                                var gyroSum = 0.0
                                for (g in gyroWindow) {
                                    gyroSum += g.magnitude
                                }
                                val gyroMean = gyroSum / gyroWindow.size
                                if (gyroMean > 0.06f) { // Angular rate > 0.06 rad/s indicates camera rotation
                                    gyroStable = false
                                }
                            }

                            currentMountedState = (variance < VARIANCE_THRESHOLD) && gyroStable
                            if (currentMountedState != isTripodMounted) {
                                isTripodMounted = currentMountedState
                                stateChanged = true
                            }
                        }
                    }
                }

                if (stateChanged) {
                    _tripodFlow.value = currentMountedState
                    for (listener in listeners) {
                        try {
                            listener(currentMountedState)
                        } catch (e: Exception) {
                            Log.w(TAG, "Exception invoking tripod listener", e)
                        }
                    }
                    Log.i(TAG, "Tripod mounted state updated: $currentMountedState")
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                val wx = event.values[0]
                val wy = event.values[1]
                val wz = event.values[2]
                val mag = sqrt(wx * wx + wy * wy + wz * wz)

                synchronized(lock) {
                    gyroWindow.addLast(TimestampedMagnitude(currentTimestamp, mag))
                    while (gyroWindow.isNotEmpty() && gyroWindow.first().timestampNs < cutoffTimestamp) {
                        gyroWindow.removeFirst()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    override fun close(): Unit {
        stopListening()
        listeners.clear()
    }

    companion object {
        private const val TAG: String = "TripodDetector"
        private const val WINDOW_DURATION_NS: Long = 1_000_000_000L // 1.0 second sliding window
        private const val MIN_WINDOW_SPAN_NS: Long = 500_000_000L   // At least 0.5s span
        private const val MIN_SAMPLES: Int = 15                     // Minimum readings
        private const val VARIANCE_THRESHOLD: Double = 0.05         // Threshold 0.05 m/s²
    }
}
