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
import com.lumacore.camera.core.gl.GlesBilateralUpsampler
import com.lumacore.camera.core.hardware.HardwareCapabilityProber
import com.lumacore.camera.core.hardware.HardwareProfile
import com.lumacore.camera.core.model.HardwareTier
import com.lumacore.camera.core.model.LensFacing
import com.lumacore.camera.core.nativebridge.NativePipeline
import com.lumacore.camera.core.neural.BilateralGridInferenceEngine
import com.lumacore.camera.core.sensor.GyroscopeSampler
import com.lumacore.camera.core.storage.MediaSaver
import com.lumacore.camera.core.strategy.CaptureModeStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.Locale

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
    val ringBuffer: BoundedImageRingBuffer = BoundedImageRingBuffer(maxCapacity = 10)
    private val gyroSampler: GyroscopeSampler = GyroscopeSampler(context)
    private val onnxEngine: BilateralGridInferenceEngine = BilateralGridInferenceEngine(context)
    private val glUpsampler: GlesBilateralUpsampler = GlesBilateralUpsampler(context)
    var latestTelemetrySummary: String? = null

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
        gyroSampler.startSampling()
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
                        gyroSampler.stopSampling()
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating burst capture", e)
            gyroSampler.stopSampling()
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
                gyroSampler.stopSampling()
                onComplete(null)
                return
            }

            val orientation = hardwareProfile?.sensorOrientation ?: 90
            val savedUri: Uri?

            if (frames.size == 1 || !NativePipeline.isNativeReady() || frames[0].format != ImageFormat.YUV_420_888) {
                // Select primary frame for direct single frame enhance & save
                val selectedFrame: Image = frames[frames.size / 2]
                savedUri = processSingleFrameAndSave(selectedFrame, strategy, orientation)
            } else {
                // Multi-frame burst fusion with specialized mode pipeline
                savedUri = mergeBurstFramesAndSave(frames, strategy, orientation)
            }

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
            gyroSampler.stopSampling()
            ringBuffer.releaseShutterLock()
        }
    }

    private suspend fun processSingleFrameAndSave(
        frame: Image,
        strategy: CaptureModeStrategy,
        orientationDegrees: Int
    ): Uri? {
        val jpegBytes = when (frame.format) {
            ImageFormat.JPEG -> {
                val buf = frame.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                bytes
            }
            ImageFormat.YUV_420_888 -> MediaSaver.convertYuv420ToJpegBytes(frame)
            else -> null
        }

        if (jpegBytes == null) {
            return MediaSaver.saveImage(context, frame, orientationDegrees)
        }

        val decodeOptions = BitmapFactory.Options().apply { inMutable = true }
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)
            ?: return MediaSaver.saveJpeg(context, jpegBytes, orientationDegrees)

        val gridResult = onnxEngine.predictBilateralGrid(bitmap)
        val onnxMs = gridResult.latencyMs

        val enhanceResult = glUpsampler.enhanceImage(bitmap, gridResult)
        val enhancedBitmap = enhanceResult.enhancedBitmap
        val glMs = enhanceResult.executionMs

        val tier = hardwareProfile?.tier?.name ?: "TIER_2_YUV_FAST"
        val totalMem = Runtime.getRuntime().totalMemory()
        val freeMem = Runtime.getRuntime().freeMemory()
        val usedRamMb = (totalMem - freeMem) / (1024 * 1024)
        val telemetry = "Mode: ${strategy.mode.name} | Tier: $tier | Align: 0.0ms | Merge: 0.0ms | ONNX: ${String.format(Locale.US, "%.1f", onnxMs)}ms | GL: ${String.format(Locale.US, "%.1f", glMs)}ms | Ghost: 0.0% | RAM: ${usedRamMb}MB"
        Log.i(TAG, telemetry)
        latestTelemetrySummary = telemetry

        val savedUri = MediaSaver.saveBitmap(
            context = context,
            bitmap = enhancedBitmap,
            orientationDegrees = orientationDegrees
        )

        bitmap.recycle()
        if (enhancedBitmap !== bitmap) {
            enhancedBitmap.recycle()
        }

        return savedUri
    }

    private suspend fun mergeBurstFramesAndSave(
        rawFrames: List<Image>,
        strategy: CaptureModeStrategy,
        orientationDegrees: Int
    ): Uri? {
        val frameCount = rawFrames.size
        val width = rawFrames[0].width
        val height = rawFrames[0].height

        val yRowStride = rawFrames[0].planes[0].rowStride
        val uvRowStride = rawFrames[0].planes[1].rowStride
        val uvPixelStride = rawFrames[0].planes[1].pixelStride

        // Action Anti-Blur Mode: Select sharpest frame as reference
        val frames: List<Image> = if (strategy.mode == com.lumacore.camera.core.model.CaptureMode.ACTION && frameCount > 1) {
            val yBuffers = Array(frameCount) { i ->
                rawFrames[i].planes[0].buffer.rewind()
                rawFrames[i].planes[0].buffer
            }
            val sharpestIndex = NativePipeline.nativeSelectSharpestFrame(
                lumaPlanes = yBuffers,
                width = width,
                height = height,
                stride = yRowStride
            )
            Log.i(TAG, "Action Anti-Blur: Frame $sharpestIndex selected as sharpest reference")
            if (sharpestIndex > 0 && sharpestIndex < frameCount) {
                listOf(rawFrames[sharpestIndex]) + rawFrames.filterIndexed { index, _ -> index != sharpestIndex }
            } else {
                rawFrames
            }
        } else {
            rawFrames
        }

        val outY: ByteBuffer
        val outU: ByteBuffer
        val outV: ByteBuffer
        var alignMs = 0.0f
        var mergeMs = 0.0f
        var ghostRatio = 0.0f

        if (strategy.mode == com.lumacore.camera.core.model.CaptureMode.SUPER_RES_ZOOM || currentZoomRatio >= 1.9f) {
            // 2x Super-Resolution sub-pixel burst merge
            val cropW = (width / 2) and 1.inv()
            val cropH = (height / 2) and 1.inv()
            val cropX = (width - cropW) / 2
            val cropY = (height - cropH) / 2

            val cropYBuffers = Array(frameCount) { i ->
                val buf = ByteBuffer.allocateDirect(cropW * cropH)
                val srcBuf = frames[i].planes[0].buffer
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

            val refU = frames[0].planes[1].buffer
            val refV = frames[0].planes[2].buffer
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
                frames[i].planes[0].buffer.rewind()
                frames[i].planes[0].buffer
            }
            val uPlanes = Array(frameCount) { i ->
                frames[i].planes[1].buffer.rewind()
                frames[i].planes[1].buffer
            }
            val vPlanes = Array(frameCount) { i ->
                frames[i].planes[2].buffer.rewind()
                frames[i].planes[2].buffer
            }

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

        val jpegBytes = MediaSaver.convertPlanarYuvToJpegBytes(
            outY = outY,
            outU = outU,
            outV = outV,
            width = width,
            height = height
        )

        val totalMem = Runtime.getRuntime().totalMemory()
        val freeMem = Runtime.getRuntime().freeMemory()
        val usedRamMb = (totalMem - freeMem) / (1024 * 1024)
        val tier = hardwareProfile?.tier?.name ?: "TIER_1_RAW_BURST"

        val decodeOptions = BitmapFactory.Options().apply { inMutable = true }
        val mergedBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)

        if (mergedBitmap == null) {
            val fallbackTelemetry = "Mode: ${strategy.mode.name} | Tier: $tier | Align: ${String.format(Locale.US, "%.1f", alignMs)}ms | Merge: ${String.format(Locale.US, "%.1f", mergeMs)}ms | ONNX: 0.0ms | GL: 0.0ms | Ghost: ${String.format(Locale.US, "%.1f", ghostRatio)}% | RAM: ${usedRamMb}MB"
            Log.i(TAG, fallbackTelemetry)
            latestTelemetrySummary = fallbackTelemetry
            return MediaSaver.saveJpeg(
                context = context,
                jpegBytes = jpegBytes,
                orientationDegrees = orientationDegrees
            )
        }

        // Run neural bilateral grid inference on merged frame
        val gridResult = onnxEngine.predictBilateralGrid(mergedBitmap)
        val onnxMs = gridResult.latencyMs

        // Run full-resolution OpenGL ES 3.0 bilateral slicing
        val enhanceResult = glUpsampler.enhanceImage(mergedBitmap, gridResult)
        val enhancedBitmap = enhanceResult.enhancedBitmap
        val glMs = enhanceResult.executionMs

        val telemetry = "Mode: ${strategy.mode.name} | Tier: $tier | Align: ${String.format(Locale.US, "%.1f", alignMs)}ms | Merge: ${String.format(Locale.US, "%.1f", mergeMs)}ms | ONNX: ${String.format(Locale.US, "%.1f", onnxMs)}ms | GL: ${String.format(Locale.US, "%.1f", glMs)}ms | Ghost: ${String.format(Locale.US, "%.1f", ghostRatio)}% | RAM: ${usedRamMb}MB"
        Log.i(TAG, telemetry)
        latestTelemetrySummary = telemetry

        val savedUri = MediaSaver.saveBitmap(
            context = context,
            bitmap = enhancedBitmap,
            orientationDegrees = orientationDegrees
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
        ringBuffer.close()
        onnxEngine.close()
        glUpsampler.close()
        cameraThread.quitSafely()
    }

    companion object {
        private const val TAG: String = "Camera2CaptureEngine"
    }
}
