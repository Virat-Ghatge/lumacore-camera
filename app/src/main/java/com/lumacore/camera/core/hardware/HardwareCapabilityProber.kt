package com.lumacore.camera.core.hardware

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.SurfaceHolder
import com.lumacore.camera.core.model.HardwareTier
import com.lumacore.camera.core.model.LensFacing
import kotlin.math.abs

/**
 * Encapsulates probed camera hardware capabilities and optimal pipeline parameters.
 */
data class HardwareProfile(
    val tier: HardwareTier,
    val cameraId: String,
    val binnedCaptureSize: Size,
    val previewSize: Size,
    val supportedEvRange: ClosedRange<Int>,
    val evStepRational: Rational,
    val supportedZoomRange: ClosedFloatingPointRange<Float>,
    val rawSensorSize: Size? = null,
    val activeArraySize: Rect? = null,
    val sensorOrientation: Int = 90
)

/**
 * Probes Camera2 hardware characteristics to assign an execution tier and configure optimal stream buffers.
 */
object HardwareCapabilityProber {

    private const val TAG: String = "HardwareCapabilityProber"
    private const val TARGET_BINNED_PIXELS: Int = 12_000_000 // Target ~12MP binned sensor output

    /**
     * Probes the camera device matching the preferred lens direction and constructs its HardwareProfile.
     */
    fun probeCamera(
        cameraManager: CameraManager,
        preferredFacing: LensFacing
    ): HardwareProfile {
        val targetFacingConstant: Int = when (preferredFacing) {
            LensFacing.BACK -> CameraCharacteristics.LENS_FACING_BACK
            LensFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
        }

        val cameraIds: Array<String> = cameraManager.cameraIdList
        var matchedCameraId: String? = null
        var matchedCharacteristics: CameraCharacteristics? = null

        for (id in cameraIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacingConstant) {
                    matchedCameraId = id
                    matchedCharacteristics = chars
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read CameraCharacteristics for cameraId: $id", e)
            }
        }

        // Fallback to first available camera if preferred facing not found
        if (matchedCameraId == null && cameraIds.isNotEmpty()) {
            matchedCameraId = cameraIds[0]
            matchedCharacteristics = cameraManager.getCameraCharacteristics(matchedCameraId)
        }

        val cameraId: String = matchedCameraId ?: "0"
        val characteristics: CameraCharacteristics = matchedCharacteristics
            ?: cameraManager.getCameraCharacteristics(cameraId)

        return buildProfile(cameraId = cameraId, characteristics = characteristics)
    }

    /**
     * Probes all available cameras on the device.
     */
    fun probeAll(cameraManager: CameraManager): Map<LensFacing, HardwareProfile> {
        val resultMap = mutableMapOf<LensFacing, HardwareProfile>()
        val cameraIds = cameraManager.cameraIdList

        for (id in cameraIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val lensFacing = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
                    else -> LensFacing.BACK
                }
                if (!resultMap.containsKey(lensFacing)) {
                    resultMap[lensFacing] = buildProfile(cameraId = id, characteristics = chars)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error probing camera id $id", e)
            }
        }

        return resultMap
    }

    /**
     * Determines the HardwareTier for a given CameraCharacteristics instance.
     */
    fun determineTier(characteristics: CameraCharacteristics): HardwareTier {
        val hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()

        val hasRawCapability = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

        val isLevel3OrFull = hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 ||
                hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL

        return when {
            isLevel3OrFull && hasRawCapability -> HardwareTier.TIER_1_RAW_BURST
            isLevel3OrFull || hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareTier.TIER_2_YUV_FAST
            else -> HardwareTier.LEGACY
        }
    }

    /**
     * Selects optimal binned ~12MP resolution from the stream configuration map to prevent memory exhaustion.
     */
    fun findOptimalBinnedSize(
        map: StreamConfigurationMap?,
        format: Int,
        targetPixels: Int = TARGET_BINNED_PIXELS
    ): Size {
        if (map == null) return Size(4000, 3000)

        val availableSizes = map.getOutputSizes(format)
        val candidateSizes: Array<Size> = if (availableSizes.isNullOrEmpty()) {
            map.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(4000, 3000))
        } else {
            availableSizes
        }

        // Target aspect ratio is 4:3 (1.333)
        val targetAspect = 4.0 / 3.0

        // Find size closest to 12MP prioritizing 4:3 aspect ratio
        var bestSize: Size = candidateSizes[0]
        var smallestPixelDelta = Long.MAX_VALUE
        var bestAspectDelta = Double.MAX_VALUE

        for (size in candidateSizes) {
            val pixels = size.width.toLong() * size.height.toLong()
            val aspect = size.width.toDouble() / size.height.toDouble()
            val pixelDelta = abs(pixels - targetPixels.toLong())
            val aspectDelta = abs(aspect - targetAspect)

            // Prefer sizes with close aspect ratio (within 5% of 4:3) and closer to 12MP
            if (aspectDelta < 0.05) {
                if (pixelDelta < smallestPixelDelta) {
                    smallestPixelDelta = pixelDelta
                    bestSize = size
                    bestAspectDelta = aspectDelta
                }
            } else if (bestAspectDelta >= 0.05 && pixelDelta < smallestPixelDelta) {
                smallestPixelDelta = pixelDelta
                bestSize = size
            }
        }

        Log.i(TAG, "Selected binned capture size: ${bestSize.width}x${bestSize.height} (${bestSize.width * bestSize.height / 1_000_000}MP)")
        return bestSize
    }

    /**
     * Selects optimal preview resolution for SurfaceView matching the capture aspect ratio.
     */
    fun findOptimalPreviewSize(
        map: StreamConfigurationMap?,
        captureAspect: Double
    ): Size {
        if (map == null) return Size(1440, 1080)

        val previewSizes = map.getOutputSizes(SurfaceHolder::class.java)
            ?: arrayOf(Size(1440, 1080), Size(1920, 1080), Size(1280, 720))

        var bestPreviewSize = Size(1440, 1080)
        var minAspectDelta = Double.MAX_VALUE
        var bestPixelCount = 0

        for (size in previewSizes) {
            // Keep preview size bounded to <= 1920 on longest edge to avoid GPU thermal/frame drops
            if (size.width > 1920 || size.height > 1920) continue

            val aspect = size.width.toDouble() / size.height.toDouble()
            val aspectDelta = abs(aspect - captureAspect)
            val pixels = size.width * size.height

            if (aspectDelta < minAspectDelta || (abs(aspectDelta - minAspectDelta) < 0.02 && pixels > bestPixelCount)) {
                minAspectDelta = aspectDelta
                bestPreviewSize = size
                bestPixelCount = pixels
            }
        }

        Log.i(TAG, "Selected preview size: ${bestPreviewSize.width}x${bestPreviewSize.height}")
        return bestPreviewSize
    }

    private fun buildProfile(
        cameraId: String,
        characteristics: CameraCharacteristics
    ): HardwareProfile {
        val tier = determineTier(characteristics)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val captureFormat = if (tier == HardwareTier.TIER_1_RAW_BURST) {
            ImageFormat.RAW_SENSOR
        } else {
            ImageFormat.YUV_420_888
        }

        val binnedSize = findOptimalBinnedSize(streamMap, captureFormat, TARGET_BINNED_PIXELS)
        val captureAspect = binnedSize.width.toDouble() / binnedSize.height.toDouble()
        val previewSize = findOptimalPreviewSize(streamMap, captureAspect)

        val evRangeObj: Range<Int>? = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val supportedEvRange: ClosedRange<Int> = if (evRangeObj != null) {
            evRangeObj.lower..evRangeObj.upper
        } else {
            -4..4
        }

        val evStepRational: Rational = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?: Rational(1, 3)

        val supportedZoomRange: ClosedFloatingPointRange<Float> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoomRangeObj = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRangeObj != null) {
                zoomRangeObj.lower..zoomRangeObj.upper
            } else {
                val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10.0f
                1.0f..maxOf(1.0f, maxZoom)
            }
        } else {
            val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10.0f
            1.0f..maxOf(1.0f, maxZoom)
        }

        val rawSensorSize: Size? = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val activeArray: Rect? = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val orientation: Int = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        return HardwareProfile(
            tier = tier,
            cameraId = cameraId,
            binnedCaptureSize = binnedSize,
            previewSize = previewSize,
            supportedEvRange = supportedEvRange,
            evStepRational = evStepRational,
            supportedZoomRange = supportedZoomRange,
            rawSensorSize = rawSensorSize,
            activeArraySize = activeArray,
            sensorOrientation = orientation
        )
    }
}
