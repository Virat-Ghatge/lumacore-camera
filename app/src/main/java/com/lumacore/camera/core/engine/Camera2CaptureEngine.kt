package com.lumacore.camera.core.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.lumacore.camera.core.buffer.BoundedImageRingBuffer
import com.lumacore.camera.core.hardware.HardwareCapabilityProber
import com.lumacore.camera.core.hardware.HardwareProfile
import com.lumacore.camera.core.model.HardwareTier
import com.lumacore.camera.core.model.LensFacing
import com.lumacore.camera.core.storage.MediaSaver
import com.lumacore.camera.core.strategy.CaptureModeStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Core Camera2 Capture Engine orchestrating camera lifecycle, real-time preview,
 * metering algorithms, and atomic burst captures with bounded native ring buffers.
 */
class Camera2CaptureEngine(
    private val context: Context
) : AutoCloseable {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val cameraThread: HandlerThread = HandlerThread("Camera2EngineThread").apply { start() }
    private val cameraHandler: Handler = Handler(cameraThread.looper)

    private val engineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val ringBuffer: BoundedImageRingBuffer = BoundedImageRingBuffer(maxCapacity = 3)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureImageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var hardwareProfile: HardwareProfile? = null
    private var currentLensFacing: LensFacing = LensFacing.BACK
    private var currentZoomRatio: Float = 1.0f
    private var currentExposureCompensation: Int = 0

    /**
     * Probes hardware and initializes Camera2 session for the specified lens facing.
     */
    fun openCamera(
        lensFacing: LensFacing,
        previewSurface: Surface,
        onReady: (HardwareProfile) -> Unit,
        onError: (String) -> Unit
    ): Unit {
        closeCamera()

        this.currentLensFacing = lensFacing
        this.previewSurface = previewSurface

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("CAMERA permission not granted")
            return
        }

        try {
            val profile = HardwareCapabilityProber.probeCamera(cameraManager, lensFacing)
            this.hardwareProfile = profile

            val captureFormat = if (profile.tier == HardwareTier.TIER_1_RAW_BURST) {
                ImageFormat.RAW_SENSOR
            } else {
                ImageFormat.YUV_420_888
            }

            val binnedSize = profile.binnedCaptureSize
            val imageReader = ImageReader.newInstance(binnedSize.width, binnedSize.height, captureFormat, 4)
            imageReader.setOnImageAvailableListener({ reader: ImageReader ->
                val image: Image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                ringBuffer.addImage(image)
            }, cameraHandler)
            this.captureImageReader = imageReader

            cameraManager.openCamera(
                profile.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        createCaptureSession(device, previewSurface, imageReader.surface, onReady, onError)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        Log.w(TAG, "Camera disconnected: ${device.id}")
                        closeCamera()
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        Log.e(TAG, "CameraDevice error: $error on cameraId: ${device.id}")
                        closeCamera()
                        onError("Camera error code: $error")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            onError(e.message ?: "Failed to open camera")
        }
    }

    private fun createCaptureSession(
        device: CameraDevice,
        previewSurface: Surface,
        captureSurface: Surface,
        onReady: (HardwareProfile) -> Unit,
        onError: (String) -> Unit
    ): Unit {
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(previewSurface, captureSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        startPreview(session, onReady)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "CameraCaptureSession configuration failed")
                        onError("Session configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating capture session", e)
            onError(e.message ?: "Failed to create session")
        }
    }

    private fun startPreview(
        session: CameraCaptureSession,
        onReady: (HardwareProfile) -> Unit
    ): Unit {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            applyZoom(builder, currentZoomRatio)
            applyExposureCompensation(builder, currentExposureCompensation)

            previewRequestBuilder = builder
            session.setRepeatingRequest(builder.build(), null, cameraHandler)

            hardwareProfile?.let { profile -> onReady(profile) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview repeating request", e)
        }
    }

    /**
     * Executes tap-to-focus by computing metering region in active sensor array coordinates.
     */
    fun setFocusPoint(xNormalized: Float, yNormalized: Float): Unit {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val profile = hardwareProfile ?: return
        val activeArray = profile.activeArraySize ?: Rect(0, 0, 4000, 3000)

        val clampedX = (xNormalized * activeArray.width()).toInt().coerceIn(0, activeArray.width())
        val clampedY = (yNormalized * activeArray.height()).toInt().coerceIn(0, activeArray.height())
        val boxRadius = minOf(activeArray.width(), activeArray.height()) / 12

        val left = (clampedX - boxRadius).coerceIn(0, activeArray.width() - 1)
        val top = (clampedY - boxRadius).coerceIn(0, activeArray.height() - 1)
        val right = (clampedX + boxRadius).coerceIn(left + 1, activeArray.width())
        val bottom = (clampedY + boxRadius).coerceIn(top + 1, activeArray.height())

        val meteringRect = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)

        try {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

            // Trigger AF convergence
            session.capture(builder.build(), null, cameraHandler)

            // Resume continuous AF
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply tap to focus", e)
        }
    }

    /**
     * Sets exposure compensation within the hardware supported EV range.
     */
    fun setExposureCompensation(ev: Int): Unit {
        val profile = hardwareProfile ?: return
        val clampedEv = ev.coerceIn(profile.supportedEvRange)
        currentExposureCompensation = clampedEv

        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            applyExposureCompensation(builder, clampedEv)
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update exposure compensation", e)
        }
    }

    /**
     * Applies optical / digital zoom ratio using Android 11+ CONTROL_ZOOM_RATIO or SCALER_CROP_REGION fallback.
     */
    fun setZoom(zoomRatio: Float): Unit {
        val profile = hardwareProfile ?: return
        val clampedZoom = zoomRatio.coerceIn(profile.supportedZoomRange)
        currentZoomRatio = clampedZoom

        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            applyZoom(builder, clampedZoom)
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update zoom ratio", e)
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder, zoomRatio: Float): Unit {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            val activeArray = hardwareProfile?.activeArraySize ?: return
            val cropWidth = (activeArray.width() / zoomRatio).toInt()
            val cropHeight = (activeArray.height() / zoomRatio).toInt()
            val cropLeft = (activeArray.width() - cropWidth) / 2
            val cropTop = (activeArray.height() - cropHeight) / 2
            val cropRect = Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        }
    }

    private fun applyExposureCompensation(builder: CaptureRequest.Builder, ev: Int): Unit {
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
    }

    /**
     * Executes atomic burst capture sequence according to strategy rules,
     * drains frames safely from bounded ring buffer, and persists output to MediaStore.
     */
    fun captureBurst(
        strategy: CaptureModeStrategy,
        onProgress: (Float) -> Unit,
        onComplete: (Uri?) -> Unit
    ): Unit {
        if (!ringBuffer.tryAcquireShutterLock()) {
            Log.w(TAG, "Shutter lock active: burst already underway")
            return
        }

        val device = cameraDevice
        val session = captureSession
        val imageReader = captureImageReader
        val profile = hardwareProfile

        if (device == null || session == null || imageReader == null || profile == null) {
            Log.e(TAG, "Camera not ready for capture")
            ringBuffer.releaseShutterLock()
            onComplete(null)
            return
        }

        ringBuffer.clear()
        val totalFrames = strategy.burstCount

        try {
            val requests = mutableListOf<CaptureRequest>()
            for (i in 0 until totalFrames) {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(imageReader.surface)
                strategy.configureCapture(builder, profile.tier, i, totalFrames)
                applyZoom(builder, currentZoomRatio)
                applyExposureCompensation(builder, currentExposureCompensation)
                requests.add(builder.build())
            }

            var framesDelivered = 0
            session.captureBurst(
                requests,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        framesDelivered++
                        val progress = (framesDelivered.toFloat() / totalFrames.toFloat()) * 0.7f
                        onProgress(progress)
                    }

                    override fun onCaptureSequenceCompleted(
                        session: CameraCaptureSession,
                        sequenceId: Int,
                        frameNumber: Long
                    ) {
                        engineScope.launch {
                            processAndSaveDrainedFrames(strategy, onProgress, onComplete)
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.e(TAG, "Capture failed with reason: ${failure.reason}")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating burst capture", e)
            ringBuffer.releaseShutterLock()
            onComplete(null)
        }
    }

    private suspend fun processAndSaveDrainedFrames(
        strategy: CaptureModeStrategy,
        onProgress: (Float) -> Unit,
        onComplete: (Uri?) -> Unit
    ): Unit {
        try {
            // Await frames arrival into ring buffer from ImageReader callback
            var frames: List<Image> = ringBuffer.drainFrames(strategy.burstCount)
            var attempts = 0
            while (frames.size < strategy.burstCount && attempts < 15) {
                delay(40L)
                val more = ringBuffer.drainFrames(strategy.burstCount - frames.size)
                if (more.isNotEmpty()) {
                    frames = frames + more
                }
                attempts++
            }

            onProgress(0.85f)

            if (frames.isEmpty()) {
                Log.e(TAG, "No frames drained from ring buffer")
                onComplete(null)
                return
            }

            // Select primary frame for final save
            val selectedFrame: Image = frames[frames.size / 2]
            val orientation = hardwareProfile?.sensorOrientation ?: 90

            val savedUri = MediaSaver.saveImage(
                context = context,
                image = selectedFrame,
                orientationDegrees = orientation
            )

            // Safely close every drained frame
            for (frame in frames) {
                try {
                    frame.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing frame in drain cleanup", e)
                }
            }

            onProgress(1.0f)
            onComplete(savedUri)
        } catch (e: Exception) {
            Log.e(TAG, "Error in processAndSaveDrainedFrames", e)
            onComplete(null)
        } finally {
            ringBuffer.releaseShutterLock()
        }
    }

    /**
     * Switches between front and back lenses.
     */
    fun switchLens(
        newFacing: LensFacing,
        previewSurface: Surface,
        onReady: (HardwareProfile) -> Unit,
        onError: (String) -> Unit
    ): Unit {
        openCamera(newFacing, previewSurface, onReady, onError)
    }

    private fun closeCamera(): Unit {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing capture session", e)
        }

        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera device", e)
        }

        try {
            captureImageReader?.close()
            captureImageReader = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ImageReader", e)
        }

        ringBuffer.clear()
    }

    override fun close(): Unit {
        closeCamera()
        ringBuffer.close()
        cameraThread.quitSafely()
    }

    companion object {
        private const val TAG: String = "Camera2CaptureEngine"
    }
}
