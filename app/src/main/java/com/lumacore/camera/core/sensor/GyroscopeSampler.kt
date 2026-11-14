package com.lumacore.camera.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * High-frequency gyroscope sampler (SENSOR_DELAY_FASTEST) for camera burst alignment.
 * Records timestamped angular velocities in rad/s and integrates delta rotations (rotX, rotY, rotZ)
 * between frame exposure timestamps.
 */
class GyroscopeSampler(
    context: Context
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val gyroscope: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    data class Reading(
        val timestampNs: Long,
        val wx: Float,
        val wy: Float,
        val wz: Float
    )

    private val lock: Any = Any()
    private val maxCapacity: Int = 2048
    private val buffer: Array<Reading?> = arrayOfNulls(maxCapacity)
    private var writeIndex: Int = 0
    private var count: Int = 0

    @Volatile
    private var isSampling: Boolean = false

    /**
     * Starts high-frequency gyroscope sampling.
     * Returns true if sensor was successfully registered, false otherwise.
     */
    fun startSampling(): Boolean {
        val manager = sensorManager ?: return false
        val gyro = gyroscope ?: return false

        synchronized(lock) {
            writeIndex = 0
            count = 0
            for (i in buffer.indices) {
                buffer[i] = null
            }
        }

        val registered = manager.registerListener(
            this,
            gyro,
            SensorManager.SENSOR_DELAY_FASTEST
        )
        isSampling = registered
        Log.i(TAG, "GyroscopeSampler started (registered: $registered)")
        return registered
    }

    /**
     * Stops sampling and unregisters sensor listener.
     */
    fun stopSampling(): Unit {
        if (!isSampling) return
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering gyroscope listener", e)
        } finally {
            isSampling = false
            Log.i(TAG, "GyroscopeSampler stopped (samples recorded: $count)")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val ts = event.timestamp
        val wx = event.values[0]
        val wy = event.values[1]
        val wz = event.values[2]

        synchronized(lock) {
            buffer[writeIndex] = Reading(ts, wx, wy, wz)
            writeIndex = (writeIndex + 1) % maxCapacity
            if (count < maxCapacity) {
                count++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    /**
     * Integrates angular velocities over the time interval [t0Ns, t1Ns].
     * If t1Ns < t0Ns, integrates backwards yielding negative rotation.
     *
     * @param t0Ns Reference timestamp in nanoseconds.
     * @param t1Ns Target timestamp in nanoseconds.
     * @return FloatArray of size 3: [rotX, rotY, rotZ] in radians.
     */
    fun integrateRotationBetween(t0Ns: Long, t1Ns: Long): FloatArray {
        if (t0Ns == t1Ns) {
            return floatArrayOf(0.0f, 0.0f, 0.0f)
        }

        val readings = mutableListOf<Reading>()
        synchronized(lock) {
            val startIdx = if (count < maxCapacity) 0 else writeIndex
            for (i in 0 until count) {
                val idx = (startIdx + i) % maxCapacity
                val r = buffer[idx] ?: continue
                readings.add(r)
            }
        }

        if (readings.isEmpty()) {
            return floatArrayOf(0.0f, 0.0f, 0.0f)
        }

        val startNs = minOf(t0Ns, t1Ns)
        val endNs = maxOf(t0Ns, t1Ns)
        val sign = if (t1Ns >= t0Ns) 1.0f else -1.0f

        val inRange = readings.filter { it.timestampNs in startNs..endNs }

        var rotX = 0.0f
        var rotY = 0.0f
        var rotZ = 0.0f

        if (inRange.size >= 2) {
            for (i in 0 until inRange.size - 1) {
                val r0 = inRange[i]
                val r1 = inRange[i + 1]
                val dtSec = (r1.timestampNs - r0.timestampNs) * 1e-9f
                rotX += 0.5f * (r0.wx + r1.wx) * dtSec
                rotY += 0.5f * (r0.wy + r1.wy) * dtSec
                rotZ += 0.5f * (r0.wz + r1.wz) * dtSec
            }
        } else if (inRange.size == 1) {
            val dtSec = (endNs - startNs) * 1e-9f
            rotX = inRange[0].wx * dtSec
            rotY = inRange[0].wy * dtSec
            rotZ = inRange[0].wz * dtSec
        } else {
            val closest = readings.minByOrNull { Math.abs(it.timestampNs - startNs) }
            if (closest != null) {
                val dtSec = (endNs - startNs) * 1e-9f
                rotX = closest.wx * dtSec
                rotY = closest.wy * dtSec
                rotZ = closest.wz * dtSec
            }
        }

        return floatArrayOf(rotX * sign, rotY * sign, rotZ * sign)
    }

    companion object {
        private const val TAG: String = "GyroscopeSampler"
    }
}
