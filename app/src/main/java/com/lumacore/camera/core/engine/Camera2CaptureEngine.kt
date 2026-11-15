package com.lumacore.camera.core.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.core.content.ContextCompat
import com.lumacore.camera.core.buffer.BoundedImageRingBuffer
import com.lumacore.camera.core.gl.GlesBilateralUpsampler
import com.lumacore.camera.core.hardware.HardwareCapabilityProber
import com.lumacore.camera.core.hardware.HardwareProfile
import com.lumacore.camera.core.model.HardwareTier
import com.lumacore.camera.core.model.LensFacing
import com.lumacore.camera.core.nativebridge.NativePipeline
import com.lumacore.camera.core.neural.BilateralGridInferenceEngine
import com.lumacore.camera.core.sensor.GyroscopeSampler
import com.lumacore.camera.core.sensor.ThermalManager
import com.lumacore.camera.core.storage.MediaSaver
import com.lumacore.camera.core.strategy.CaptureModeStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Extracted per-frame sensor color science metadata from TotalCaptureResult.
 */
data class FrameColorMetadata(
    val blackLevels: FloatArray = floatArrayOf(16f, 16f, 16f, 16f),
    val whiteLevel: Float = 255.0f,
    val colorGains: FloatArray = floatArrayOf(1.8f, 1.0f, 1.0f, 1.5f),
    val colorTransform: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    ),
    val iso: Int = 100,
    val exposureTimeNs: Long = 10_000_000L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FrameColorMetadata
        if (!blackLevels.contentEquals(other.blackLevels)) return false
        if (whiteLevel != other.whiteLevel) return false
        if (!colorGains.contentEquals(other.colorGains)) return false
        if (!colorTransform.contentEquals(other.colorTransform)) return false
        if (iso != other.iso) return false
        if (exposureTimeNs != other.exposureTimeNs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = blackLevels.contentHashCode()
        result = 31 * result + whiteLevel.hashCode()
        result = 31 * result + colorGains.contentHashCode()
        result = 31 * result + colorTransform.contentHashCode()
        result = 31 * result + iso
        result = 31 * result + exposureTimeNs.hashCode()
        return result
    }
}

/**
 * Reaped direct memory plane data decoupled from Camera2 Image lifecycle.
 */
class CapturedPlaneData(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int
)

/**
 * Reaped frame carrier decoupled from bounded ring buffer and ImageReader,
 * allowing immediate shutter release while background processing executes asynchronously.
 */
class CapturedFrameCarrier(
    val width: Int,
    val height: Int,
    val format: Int,
    val timestamp: Long,
    val planes: List<CapturedPlaneData>
)

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
    private val processingScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val processingMutex: Mutex = Mutex()
    val ringBuffer: BoundedImageRingBuffer = BoundedImageRingBuffer(maxCapacity = 10)
    private val gyroSampler: GyroscopeSampler = GyroscopeSampler(context)
    val thermalManager: ThermalManager = ThermalManager(context)
    private val onnxEngine: BilateralGridInferenceEngine = BilateralGridInferenceEngine(context)
    private val glUpsampler: GlesBilateralUpsampler = GlesBilateralUpsampler(context)
    var latestTelemetrySummary: String? = null
    var latestPreviewResult: TotalCaptureResult? = null
        private set

    private val previewCaptureCallback: CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                latestPreviewResult = result
            }
        }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureImageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var hardwareProfile: HardwareProfile? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var currentLensFacing: LensFacing = LensFacing.BACK
    private var currentZoomRatio: Float = 1.0f
    private var currentExposureCompensation: Int = 0

    var saveRawDng: Boolean = false
    private var isAeAfLocked: Boolean = false
    private var manualIso: Int? = null
    private var manualExposureTimeNs: Long? = null
    private var manualFocusDistance: Float? = null
    private var manualWhiteBalanceKelvin: Int? = null

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
            this.cameraCharacteristics = cameraManager.getCameraCharacteristics(profile.cameraId)

            val captureFormat = if (profile.tier == HardwareTier.TIER_1_RAW_BURST) {
                ImageFormat.RAW_SENSOR
            } else {
                ImageFormat.YUV_420_888
            }

            val binnedSize = profile.binnedCaptureSize
            val imageReader = ImageReader.newInstance(binnedSize.width, binnedSize.height, captureFormat, 10)
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
            applyManualControls(builder)

            previewRequestBuilder = builder
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)

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
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
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
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
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
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
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
     * Sets manual ISO sensitivity. Pass null to revert to automatic AE.
     */
    fun setManualIso(iso: Int?): Unit {
        this.manualIso = iso
        updatePreviewRepeatingRequest()
    }

    /**
     * Sets manual exposure time in nanoseconds. Pass null to revert to automatic AE.
     */
    fun setManualExposureTime(exposureTimeNs: Long?): Unit {
        this.manualExposureTimeNs = exposureTimeNs
        updatePreviewRepeatingRequest()
    }

    /**
     * Sets manual focus distance in diopters (0.0f = infinity, larger = closer macro).
     * Pass null to revert to continuous autofocus.
     */
    fun setManualFocusDistance(diopters: Float?): Unit {
        this.manualFocusDistance = diopters
        updatePreviewRepeatingRequest()
    }

    /**
     * Sets manual white balance color temperature in Kelvin (2000K to 10000K).
     * Pass null to revert to automatic white balance (AWB).
     */
    fun setManualWhiteBalance(kelvin: Int?): Unit {
        this.manualWhiteBalanceKelvin = kelvin
        updatePreviewRepeatingRequest()
    }

    /**
     * Locks or unlocks Auto Exposure (AE) and Auto Focus (AF).
     */
    fun lockAeAf(lock: Boolean): Unit {
        this.isAeAfLocked = lock
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, lock)
            if (lock) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            }
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to toggle AE/AF lock", e)
        }
    }

    private fun updatePreviewRepeatingRequest(): Unit {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            applyManualControls(builder)
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply manual controls to repeating request", e)
        }
    }

    private fun applyManualControls(builder: CaptureRequest.Builder): Unit {
        // Manual ISO and Exposure Time
        if (manualIso != null || manualExposureTimeNs != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            manualIso?.let { iso ->
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            }
            manualExposureTimeNs?.let { expNs ->
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        // Manual Focus Distance
        if (manualFocusDistance != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        // Manual White Balance (Kelvin)
        if (manualWhiteBalanceKelvin != null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            val kelvin = manualWhiteBalanceKelvin!!.toFloat()
            // Planckian locus approximation for Kelvin -> R & B channel gains
            val rGain: Float = if (kelvin <= 5500f) {
                1.0f + ((kelvin - 2000f) / 3500f) * 0.8f
            } else {
                1.8f + ((kelvin - 5500f) / 4500f) * 1.2f
            }.coerceIn(0.5f, 4.0f)

            val bGain: Float = if (kelvin <= 5500f) {
                3.0f - ((kelvin - 2000f) / 3500f) * 1.5f
            } else {
                1.5f - ((kelvin - 5500f) / 4500f) * 0.7f
            }.coerceIn(0.5f, 4.0f)

            val gains = RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
    }

    /**
     * Executes atomic burst capture sequence according to strategy rules,
     * immediately reaps frame planes and releases the shutter lock,
     * and asynchronously dispatches alignment, color science, and neural enhancement
     * to a background queue.
     */
    fun captureBurst(
        strategy: CaptureModeStrategy,
        onBurstCaptured: () -> Unit = {},
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
            onBurstCaptured()
            onComplete(null)
            return
        }

        ringBuffer.clear()
        gyroSampler.startSampling()
        val burstScalingFactor = thermalManager.getBurstScalingFactor()
        val totalFrames = if (strategy.burstCount > 1) {
            maxOf(2, kotlin.math.round(strategy.burstCount * burstScalingFactor).toInt())
        } else {
            1
        }

        // Dynamic Metering: Inspect latest preview result to detect low light
        val previewResult = latestPreviewResult
        val previewIso: Int = previewResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val previewExpTimeNs: Long = previewResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
        val isLowLight: Boolean = previewIso > 400 || previewExpTimeNs > 30_000_000L

        try {
            val requests = mutableListOf<CaptureRequest>()
            for (i in 0 until totalFrames) {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(imageReader.surface)
                strategy.configureCapture(builder, profile.tier, i, totalFrames)
                applyZoom(builder, currentZoomRatio)
                applyExposureCompensation(builder, currentExposureCompensation)
                applyManualControls(builder)

                if (isLowLight) {
                    // Shutter Ceiling: Clamp shutter duration <= 33ms (1/30s) to prevent handshake blur
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
                    try {
                        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, minOf(previewExpTimeNs, 33_333_333L))
                    } catch (_: Exception) {}
                    // Enforce underexposure bracketing ceiling (never positive EV in low light)
                    val currentEv = builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) ?: 0
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, minOf(currentEv, 0))
                }

                requests.add(builder.build())
            }

            var framesDelivered = 0
            val burstMetadataList = mutableListOf<FrameColorMetadata>()

            session.captureBurst(
                requests,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        framesDelivered++
                        val progress = (framesDelivered.toFloat() / totalFrames.toFloat()) * 0.4f
                        onProgress(progress)

                        // Per-frame Metadata Extraction
                        val rggb = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                        val colorGains = if (rggb != null) {
                            floatArrayOf(rggb.red, rggb.greenEven, rggb.greenOdd, rggb.blue)
                        } else {
                            floatArrayOf(1.8f, 1.0f, 1.0f, 1.5f)
                        }

                        val cst = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                        val colorTransform = FloatArray(9)
                        if (cst != null) {
                            for (r in 0 until 3) {
                                for (c in 0 until 3) {
                                    colorTransform[r * 3 + c] = cst.getElement(r, c).toFloat()
                                }
                            }
                        } else {
                            colorTransform[0] = 1.0f
                            colorTransform[4] = 1.0f
                            colorTransform[8] = 1.0f
                        }

                        val dynBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                        val blackLevels = if (dynBlack != null && dynBlack.size >= 4) {
                            floatArrayOf(dynBlack[0], dynBlack[1], dynBlack[2], dynBlack[3])
                        } else {
                            floatArrayOf(16f, 16f, 16f, 16f)
                        }

                        val dynWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
                        val whiteLevel = dynWhite?.toFloat() ?: 255.0f

                        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: previewIso
                        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: previewExpTimeNs

                        burstMetadataList.add(
                            FrameColorMetadata(
                                blackLevels = blackLevels,
                                whiteLevel = whiteLevel,
                                colorGains = colorGains,
                                colorTransform = colorTransform,
                                iso = iso,
                                exposureTimeNs = exposureTimeNs
                            )
                        )
                    }

                    override fun onCaptureSequenceCompleted(
                        session: CameraCaptureSession,
                        sequenceId: Int,
                        frameNumber: Long
                    ) {
                        engineScope.launch {
                            drainReapAndDispatchBackground(
                                totalFrames = totalFrames,
                                strategy = strategy,
                                burstMetadataList = burstMetadataList.toList(),
                                onBurstCaptured = onBurstCaptured,
                                onProgress = onProgress,
                                onComplete = onComplete
                            )
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.e(TAG, "Capture failed with reason: ${failure.reason}")
                        gyroSampler.stopSampling()
                        ringBuffer.releaseShutterLock()
                        onBurstCaptured()
                        onComplete(null)
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating burst capture", e)
            gyroSampler.stopSampling()
            ringBuffer.releaseShutterLock()
            onBurstCaptured()
            onComplete(null)
        }
    }

    private suspend fun drainReapAndDispatchBackground(
        totalFrames: Int,
        strategy: CaptureModeStrategy,
        burstMetadataList: List<FrameColorMetadata>,
        onBurstCaptured: () -> Unit,
        onProgress: (Float) -> Unit,
        onComplete: (Uri?) -> Unit
    ): Unit {
        try {
            // Await frames arrival into ring buffer from ImageReader callback
            var frames: List<Image> = ringBuffer.drainFrames(totalFrames)
            var attempts = 0
            while (frames.size < totalFrames && attempts < 15) {
                delay(30L)
                val more = ringBuffer.drainFrames(totalFrames - frames.size)
                if (more.isNotEmpty()) {
                    frames = frames + more
                }
                attempts++
            }

            if (frames.isEmpty()) {
                Log.e(TAG, "No frames drained from ring buffer")
                gyroSampler.stopSampling()
                ringBuffer.releaseShutterLock()
                onBurstCaptured()
                onComplete(null)
                return
            }

            val frameCount = frames.size
            val refTimestampNs = frames[0].timestamp
            val gyroRotations = FloatArray((frameCount - 1) * 3)

            for (k in 1 until frameCount) {
                val targetTimestampNs = frames[k].timestamp
                val rot = gyroSampler.integrateRotationBetween(refTimestampNs, targetTimestampNs)
                val baseIdx = (k - 1) * 3
                gyroRotations[baseIdx + 0] = rot[0]
                gyroRotations[baseIdx + 1] = rot[1]
                gyroRotations[baseIdx + 2] = rot[2]
            }
            gyroSampler.stopSampling()

            // Save RAW DNG alongside JPEG if requested and format is RAW_SENSOR
            if (saveRawDng && frames.isNotEmpty() && frames[0].format == ImageFormat.RAW_SENSOR) {
                val chars = cameraCharacteristics
                val result = latestPreviewResult
                val orientation = hardwareProfile?.sensorOrientation ?: 90
                if (chars != null && result != null) {
                    try {
                        MediaSaver.saveDng(
                            context = context,
                            characteristics = chars,
                            captureResult = result,
                            rawImage = frames[0],
                            orientationDegrees = orientation
                        )
                    } catch (dngEx: Exception) {
                        Log.e(TAG, "Error saving RAW DNG alongside JPEG to MediaStore", dngEx)
                    }
                }
            }

            // 1. Decouple from ImageReader by copying planes into direct memory carriers & closing frames
            val carriers = reapPlanesAndCloseImages(frames)

            // 2. Release shutter lock IMMEDIATELY so camera is unblocked for next photo!
            ringBuffer.releaseShutterLock()

            // 3. Notify ViewModel that burst is ingested (shutter button springs back ready)
            onBurstCaptured()

            // 4. Launch heavy processing on background processingScope (Dispatchers.Default)
            processingScope.launch {
                executeBackgroundProcessingQueue(
                    carriers = carriers,
                    strategy = strategy,
                    gyroRotations = gyroRotations,
                    metadataList = burstMetadataList,
                    onProgress = onProgress,
                    onComplete = onComplete
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in drainReapAndDispatchBackground", e)
            gyroSampler.stopSampling()
            ringBuffer.releaseShutterLock()
            onBurstCaptured()
            onComplete(null)
        }
    }

    private fun reapPlanesAndCloseImages(frames: List<Image>): List<CapturedFrameCarrier> {
        val carriers = ArrayList<CapturedFrameCarrier>(frames.size)
        for (frame in frames) {
            try {
                val planeList = ArrayList<CapturedPlaneData>(frame.planes.size)
                for (plane in frame.planes) {
                    val srcBuf = plane.buffer
                    srcBuf.rewind()
                    val directBuf = ByteBuffer.allocateDirect(srcBuf.remaining())
                    directBuf.put(srcBuf)
                    directBuf.rewind()
                    srcBuf.rewind()
                    planeList.add(CapturedPlaneData(directBuf, plane.rowStride, plane.pixelStride))
                }
                carriers.add(
                    CapturedFrameCarrier(
                        width = frame.width,
                        height = frame.height,
                        format = frame.format,
                        timestamp = frame.timestamp,
                        planes = planeList
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reap planes from frame", e)
            } finally {
                try {
                    frame.close()
                } catch (closeEx: Exception) {
                    Log.w(TAG, "Error closing drained frame", closeEx)
                }
            }
        }
        return carriers
    }

    private suspend fun executeBackgroundProcessingQueue(
        carriers: List<CapturedFrameCarrier>,
        strategy: CaptureModeStrategy,
        gyroRotations: FloatArray,
        metadataList: List<FrameColorMetadata>,
        onProgress: (Float) -> Unit,
        onComplete: (Uri?) -> Unit
    ): Unit {
        processingMutex.withLock {
            try {
                onProgress(0.6f)
                val orientation = hardwareProfile?.sensorOrientation ?: 90
                val savedUri: Uri?

                if (carriers.size == 1 || !NativePipeline.isNativeReady() || carriers[0].format != ImageFormat.YUV_420_888) {
                    val selectedCarrier = carriers[carriers.size / 2]
                    savedUri = processSingleCarrierAndSave(
                        carrier = selectedCarrier,
                        strategy = strategy,
                        metadata = metadataList.firstOrNull(),
                        orientationDegrees = orientation
                    )
                } else {
                    savedUri = mergeBurstCarriersAndSave(
                        carriers = carriers,
                        strategy = strategy,
                        gyroRotations = gyroRotations,
                        metadataList = metadataList,
                        orientationDegrees = orientation
                    )
                }

                onProgress(1.0f)
                onComplete(savedUri)
            } catch (e: Exception) {
                Log.e(TAG, "Error in executeBackgroundProcessingQueue", e)
                onComplete(null)
            }
        }
    }

    private suspend fun processSingleCarrierAndSave(
        carrier: CapturedFrameCarrier,
        strategy: CaptureModeStrategy,
        metadata: FrameColorMetadata?,
        orientationDegrees: Int
    ): Uri? {
        val primaryMeta = metadata ?: FrameColorMetadata()
        val width = carrier.width
        val height = carrier.height

        val bitmap: Bitmap? = if (carrier.format == ImageFormat.YUV_420_888 && carrier.planes.size >= 3 && NativePipeline.isNativeReady()) {
            val yPlane = carrier.planes[0]
            val uPlane = carrier.planes[1]
            val vPlane = carrier.planes[2]
            val outRgba = ByteBuffer.allocateDirect(width * height * 4)

            val success = NativePipeline.nativeProcessColorPipeline(
                yPlane = yPlane.buffer,
                uPlane = uPlane.buffer,
                vPlane = vPlane.buffer,
                width = width,
                height = height,
                yRowStride = yPlane.rowStride,
                uvRowStride = uPlane.rowStride,
                uvPixelStride = uPlane.pixelStride,
                blackLevel = primaryMeta.blackLevels,
                whiteLevel = primaryMeta.whiteLevel,
                colorGains = primaryMeta.colorGains,
                colorTransform = primaryMeta.colorTransform,
                iso = primaryMeta.iso,
                exposureTimeNs = primaryMeta.exposureTimeNs,
                radialVignetteGain = 0.15f,
                outRgba = outRgba
            )
            if (success) {
                outRgba.rewind()
                val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                b.copyPixelsFromBuffer(outRgba)
                b
            } else {
                null
            }
        } else {
            null
        }

        val decodedBitmap: Bitmap = bitmap ?: run {
            val buf = carrier.planes[0].buffer
            buf.rewind()
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            buf.rewind()
            val decodeOptions = BitmapFactory.Options().apply { inMutable = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?: return MediaSaver.saveJpeg(
                    context = context,
                    jpegBytes = bytes,
                    orientationDegrees = orientationDegrees,
                    iso = primaryMeta.iso,
                    exposureTimeSeconds = primaryMeta.exposureTimeNs / 1_000_000_000.0
                )
        }

        val gridResult = onnxEngine.predictBilateralGrid(decodedBitmap)
        val onnxMs = gridResult.latencyMs

        val enhanceResult = glUpsampler.enhanceImage(decodedBitmap, gridResult, gridResult.safetyBlend)
        val enhancedBitmap = enhanceResult.enhancedBitmap
        val glMs = enhanceResult.executionMs

        val tier = hardwareProfile?.tier?.name ?: "TIER_2_YUV_FAST"
        val totalMem = Runtime.getRuntime().totalMemory()
        val freeMem = Runtime.getRuntime().freeMemory()
        val usedRamMb = (totalMem - freeMem) / (1024 * 1024)
        val thermalLabel = thermalManager.getThermalStatusLabel()
        val scalingPct = (thermalManager.getBurstScalingFactor() * 100).toInt()
        val telemetry = "Mode: ${strategy.mode.name} | Tier: $tier | Align: 0.0ms | Merge: 0.0ms | ONNX: ${String.format(Locale.US, "%.1f", onnxMs)}ms | GL: ${String.format(Locale.US, "%.1f", glMs)}ms | Thermal: $thermalLabel | Scaling: $scalingPct% | RAM: ${usedRamMb}MB"
        Log.i(TAG, telemetry)
        latestTelemetrySummary = telemetry

        val savedUri = MediaSaver.saveBitmap(
            context = context,
            bitmap = enhancedBitmap,
            orientationDegrees = orientationDegrees,
            iso = primaryMeta.iso,
            exposureTimeSeconds = primaryMeta.exposureTimeNs / 1_000_000_000.0
        )

        decodedBitmap.recycle()
        if (enhancedBitmap !== decodedBitmap) {
            enhancedBitmap.recycle()
        }

        return savedUri
    }

    private suspend fun mergeBurstCarriersAndSave(
        carriers: List<CapturedFrameCarrier>,
        strategy: CaptureModeStrategy,
        gyroRotations: FloatArray,
        metadataList: List<FrameColorMetadata>,
        orientationDegrees: Int
    ): Uri? {
        val frameCount = carriers.size
        val width = carriers[0].width
        val height = carriers[0].height

        val yRowStride = carriers[0].planes[0].rowStride
        val uvRowStride = carriers[0].planes[1].rowStride
        val uvPixelStride = carriers[0].planes[1].pixelStride

        // Action Anti-Blur Mode: Select sharpest frame as reference
        val orderedCarriers: List<CapturedFrameCarrier> = if (strategy.mode == com.lumacore.camera.core.model.CaptureMode.ACTION && frameCount > 1) {
            val yBuffers = Array(frameCount) { i ->
                carriers[i].planes[0].buffer.rewind()
                carriers[i].planes[0].buffer
            }
            val sharpestIndex = NativePipeline.nativeSelectSharpestFrame(
                lumaPlanes = yBuffers,
                width = width,
                height = height,
                stride = yRowStride
            )
            Log.i(TAG, "Action Anti-Blur: Frame $sharpestIndex selected as sharpest reference")
            if (sharpestIndex > 0 && sharpestIndex < frameCount) {
                listOf(carriers[sharpestIndex]) + carriers.filterIndexed { index, _ -> index != sharpestIndex }
            } else {
                carriers
            }
        } else {
            carriers
        }

        val outY: ByteBuffer
        val outU: ByteBuffer
        val outV: ByteBuffer
        var alignMs = 0.0f
        var mergeMs = 0.0f
        var ghostRatio = 0.0f

        if (currentZoomRatio >= 1.9f) {
            // 2x Super-Resolution sub-pixel burst merge
            val cropW = (width / 2) and 1.inv()
            val cropH = (height / 2) and 1.inv()
            val cropX = (width - cropW) / 2
            val cropY = (height - cropH) / 2

            val cropYBuffers = Array(frameCount) { i ->
                val buf = ByteBuffer.allocateDirect(cropW * cropH)
                val srcBuf = orderedCarriers[i].planes[0].buffer
                srcBuf.rewind()
                for (r in 0 until cropH) {
                    srcBuf.position((cropY + r) * yRowStride + cropX)
                    val row = ByteArray(cropW)
                    srcBuf.get(row)
                    buf.put(row)
                }
                buf.rewind()
                buf
            }

            outY = ByteBuffer.allocateDirect(width * height)
            val superResStart = System.currentTimeMillis()
            val success = NativePipeline.nativeMergeSuperRes2x(
                yPlanes = cropYBuffers,
                inWidth = cropW,
                inHeight = cropH,
                inStride = cropW,
                out2xY = outY,
                outWidth = width,
                outHeight = height,
                outStride = width
            )
            mergeMs = (System.currentTimeMillis() - superResStart).toFloat()
            if (!success) {
                Log.w(TAG, "nativeMergeSuperRes2x returned false, falling back to direct copy")
            }

            // Upsample Chroma (U and V) center crop 2x to fill (width/2) x (height/2)
            val halfW = width / 2
            val halfH = height / 2
            val cropHalfW = cropW / 2
            val cropHalfH = cropH / 2
            val cropHalfX = cropX / 2
            val cropHalfY = cropY / 2

            outU = ByteBuffer.allocateDirect(halfW * halfH)
            outV = ByteBuffer.allocateDirect(halfW * halfH)

            val refU = orderedCarriers[0].planes[1].buffer
            val refV = orderedCarriers[0].planes[2].buffer
            refU.rewind()
            refV.rewind()

            for (r in 0 until halfH) {
                val srcR = (r * cropHalfH) / halfH
                for (c in 0 until halfW) {
                    val srcC = (c * cropHalfW) / halfW
                    val idx = (cropHalfY + srcR) * uvRowStride + (cropHalfX + srcC) * uvPixelStride
                    outU.put(refU.get(idx))
                    outV.put(refV.get(idx))
                }
            }
            outU.rewind()
            outV.rewind()
            outY.rewind()
        } else {
            // Standard / Night / Action Gyro-Assisted Pyramid Burst Fusion
            val yPlanes = Array(frameCount) { i ->
                orderedCarriers[i].planes[0].buffer.rewind()
                orderedCarriers[i].planes[0].buffer
            }
            val uPlanes = Array(frameCount) { i ->
                orderedCarriers[i].planes[1].buffer.rewind()
                orderedCarriers[i].planes[1].buffer
            }
            val vPlanes = Array(frameCount) { i ->
                orderedCarriers[i].planes[2].buffer.rewind()
                orderedCarriers[i].planes[2].buffer
            }

            outY = ByteBuffer.allocateDirect(width * height)
            outU = ByteBuffer.allocateDirect((width / 2) * (height / 2))
            outV = ByteBuffer.allocateDirect((width / 2) * (height / 2))

            val stats = NativePipeline.nativeAlignAndMergeBurst(
                yPlanes = yPlanes,
                uPlanes = uPlanes,
                vPlanes = vPlanes,
                width = width,
                height = height,
                yRowStride = yRowStride,
                uvRowStride = uvRowStride,
                uvPixelStride = uvPixelStride,
                gyroRotations = gyroRotations,
                outY = outY,
                outU = outU,
                outV = outV
            )

            alignMs = stats.getOrNull(0) ?: 0.0f
            mergeMs = stats.getOrNull(1) ?: 0.0f
            ghostRatio = (stats.getOrNull(2) ?: 0.0f) * 100.0f

            // Night Mode: Deep shadow lifting tone curve on planar luma
            if (strategy.mode == com.lumacore.camera.core.model.CaptureMode.NIGHT) {
                outY.rewind()
                val yBytes = ByteArray(width * height)
                outY.get(yBytes)
                for (i in yBytes.indices) {
                    val luma = yBytes[i].toInt() and 0xFF
                    val norm = luma / 255.0f
                    val lifted = (Math.pow(norm.toDouble(), 0.82) * 255.0).toInt().coerceIn(0, 255)
                    yBytes[i] = lifted.toByte()
                }
                outY.rewind()
                outY.put(yBytes)
                outY.rewind()
            }
        }

        // Direct YUV -> RGBA Sensor Color Pipeline (Zero Double JPEG)
        val outRgba = ByteBuffer.allocateDirect(width * height * 4)
        outY.rewind()
        outU.rewind()
        outV.rewind()

        val primaryMeta = metadataList.firstOrNull() ?: FrameColorMetadata()

        val colorStart = System.currentTimeMillis()
        val colorSuccess = NativePipeline.nativeProcessColorPipeline(
            yPlane = outY,
            uPlane = outU,
            vPlane = outV,
            width = width,
            height = height,
            yRowStride = width,
            uvRowStride = width / 2,
            uvPixelStride = 1,
            blackLevel = primaryMeta.blackLevels,
            whiteLevel = primaryMeta.whiteLevel,
            colorGains = primaryMeta.colorGains,
            colorTransform = primaryMeta.colorTransform,
            iso = primaryMeta.iso,
            exposureTimeNs = primaryMeta.exposureTimeNs,
            radialVignetteGain = 0.15f,
            outRgba = outRgba
        )
        val colorMs = (System.currentTimeMillis() - colorStart).toFloat()

        val mergedBitmap: Bitmap
        if (colorSuccess) {
            outRgba.rewind()
            mergedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            mergedBitmap.copyPixelsFromBuffer(outRgba)
        } else {
            // Fallback: Planar YUV to JPEG bytes then decode
            val jpegBytes = MediaSaver.convertPlanarYuvToJpegBytes(
                outY = outY,
                outU = outU,
                outV = outV,
                width = width,
                height = height
            )
            val decodeOptions = BitmapFactory.Options().apply { inMutable = true }
            mergedBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)
                ?: return MediaSaver.saveJpeg(
                    context = context,
                    jpegBytes = jpegBytes,
                    orientationDegrees = orientationDegrees,
                    iso = primaryMeta.iso,
                    exposureTimeSeconds = primaryMeta.exposureTimeNs / 1_000_000_000.0
                )
        }

        // Run neural bilateral grid inference on merged frame
        val gridResult = onnxEngine.predictBilateralGrid(mergedBitmap)
        val onnxMs = gridResult.latencyMs

        // Run full-resolution OpenGL ES 3.0 bilateral slicing
        val enhanceResult = glUpsampler.enhanceImage(mergedBitmap, gridResult, gridResult.safetyBlend)
        val enhancedBitmap = enhanceResult.enhancedBitmap
        val glMs = enhanceResult.executionMs

        val tier = hardwareProfile?.tier?.name ?: "TIER_1_RAW_BURST"
        val totalMem = Runtime.getRuntime().totalMemory()
        val freeMem = Runtime.getRuntime().freeMemory()
        val usedRamMb = (totalMem - freeMem) / (1024 * 1024)
        val thermalLabel = thermalManager.getThermalStatusLabel()
        val scalingPct = (thermalManager.getBurstScalingFactor() * 100).toInt()
        val telemetry = "Mode: ${strategy.mode.name} | Tier: $tier | Color: ${String.format(Locale.US, "%.1f", colorMs)}ms | Align: ${String.format(Locale.US, "%.1f", alignMs)}ms | Merge: ${String.format(Locale.US, "%.1f", mergeMs)}ms | ONNX: ${String.format(Locale.US, "%.1f", onnxMs)}ms | GL: ${String.format(Locale.US, "%.1f", glMs)}ms | Safety: ${String.format(Locale.US, "%.2f", gridResult.safetyBlend)} | Ghost: ${String.format(Locale.US, "%.1f", ghostRatio)}% | Thermal: $thermalLabel | Scaling: $scalingPct% | RAM: ${usedRamMb}MB"
        Log.i(TAG, telemetry)
        latestTelemetrySummary = telemetry

        val savedUri = MediaSaver.saveBitmap(
            context = context,
            bitmap = enhancedBitmap,
            orientationDegrees = orientationDegrees,
            iso = primaryMeta.iso,
            exposureTimeSeconds = primaryMeta.exposureTimeNs / 1_000_000_000.0
        )

        mergedBitmap.recycle()
        if (enhancedBitmap !== mergedBitmap) {
            enhancedBitmap.recycle()
        }

        return savedUri
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

        gyroSampler.stopSampling()
        ringBuffer.clear()
    }

    override fun close(): Unit {
        closeCamera()
        gyroSampler.stopSampling()
        thermalManager.close()
        ringBuffer.close()
        onnxEngine.close()
        glUpsampler.close()
        cameraThread.quitSafely()
    }

    companion object {
        private const val TAG: String = "Camera2CaptureEngine"
    }
}
