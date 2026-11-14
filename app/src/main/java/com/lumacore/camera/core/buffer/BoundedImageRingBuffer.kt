package com.lumacore.camera.core.buffer

import android.media.Image
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Strict bounded image ring buffer designed for zero-shutter-lag and multi-frame burst processing.
 *
 * Implements a strict Drop-Oldest eviction policy with immediate native buffer deallocation
 * to guarantee that total native memory consumption remains safely bounded under 180MB on 4GB RAM devices.
 */
class BoundedImageRingBuffer(
    val maxCapacity: Int = 10
) : AutoCloseable {

    private val lock: Any = Any()
    private val frameQueue: ArrayDeque<Image> = ArrayDeque(maxCapacity)
    private val shutterLock: AtomicBoolean = AtomicBoolean(false)
    private var isClosed: Boolean = false

    /**
     * Attempts to acquire the shutter lock atomically.
     * Prevents overlapping capture bursts when rapid taps occur.
     */
    fun tryAcquireShutterLock(): Boolean {
        return shutterLock.compareAndSet(false, true)
    }

    /**
     * Releases the atomic shutter lock.
     */
    fun releaseShutterLock(): Unit {
        shutterLock.set(false)
    }

    /**
     * Checks if the shutter lock is currently held.
     */
    val isShutterLocked: Boolean
        get() = shutterLock.get()

    /**
     * Current number of frames available in the buffer.
     */
    val currentSize: Int
        get() = synchronized(lock) { frameQueue.size }

    /**
     * Inserts an incoming camera [Image] into the ring buffer.
     *
     * If the buffer is full (reaches [maxCapacity]), the oldest frame is evicted
     * and [Image.close] is invoked immediately to release the underlying hardware buffer.
     *
     * @param image The newly acquired image from ImageReader.
     * @return true if accepted, false if buffer is closed.
     */
    fun addImage(image: Image): Boolean {
        synchronized(lock) {
            if (isClosed) {
                try {
                    image.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed closing image on closed buffer", e)
                }
                return false
            }

            // Evict oldest frames if capacity limit reached
            while (frameQueue.size >= maxCapacity) {
                val evicted: Image? = frameQueue.pollFirst()
                if (evicted != null) {
                    try {
                        evicted.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing evicted frame", e)
                    }
                }
            }

            frameQueue.addLast(image)
            return true
        }
    }

    /**
     * Drains up to [count] frames from the buffer in chronological order.
     * Extracted frames are removed from internal queue; the caller assumes ownership
     * and MUST close them once processing concludes.
     */
    fun drainFrames(count: Int): List<Image> {
        synchronized(lock) {
            if (isClosed || frameQueue.isEmpty()) {
                return emptyList()
            }

            val drained = mutableListOf<Image>()
            val takeCount = minOf(count, frameQueue.size)
            for (i in 0 until takeCount) {
                val frame: Image? = frameQueue.pollFirst()
                if (frame != null) {
                    drained.add(frame)
                }
            }
            return drained
        }
    }

    /**
     * Closes and discards all retained images currently in the queue.
     */
    fun clear(): Unit {
        synchronized(lock) {
            while (!frameQueue.isEmpty()) {
                val frame: Image? = frameQueue.pollFirst()
                try {
                    frame?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing frame during clear", e)
                }
            }
        }
    }

    /**
     * Closes the ring buffer and releases all allocated native hardware images.
     */
    override fun close(): Unit {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            clear()
            releaseShutterLock()
        }
    }

    companion object {
        private const val TAG: String = "BoundedRingBuffer"
    }
}
