package com.lumacore.camera.core.strategy

import android.graphics.ImageFormat
import android.hardware.camera2.CaptureRequest
import android.util.Range
import com.lumacore.camera.core.model.CaptureMode
import com.lumacore.camera.core.model.HardwareTier

/**
 * Strategy contract defining camera parameters, target buffer formats,
 * burst count, and OEM post-processing bypass controls.
 */
interface CaptureModeStrategy {
    val mode: CaptureMode
    val burstCount: Int

    /**
     * Resolves the target image buffer format given device hardware capability tier.
     */
    fun getTargetFormat(tier: HardwareTier): Int

    /**
     * Configures repeating preview request.
     */
    fun configureRepeating(builder: CaptureRequest.Builder, tier: HardwareTier): Unit

    /**
     * Configures still/burst capture request for an individual frame in a burst sequence.
     */
    fun configureCapture(
        builder: CaptureRequest.Builder,
        tier: HardwareTier,
        frameIndex: Int,
        totalFrames: Int
    ): Unit
}

/**
 * Standard single-frame or 3-frame HDR capture strategy.
 * In Tier 2 YUV, disables edge enhancement and aggressive noise reduction to preserve raw texture.
 */
class PhotoStandardModeStrategy(
    val isHdrActive: Boolean = false
) : CaptureModeStrategy {

    override val mode: CaptureMode = CaptureMode.PHOTO
    override val burstCount: Int = if (isHdrActive) 3 else 1

    override fun getTargetFormat(tier: HardwareTier): Int = when (tier) {
        HardwareTier.TIER_1_RAW_BURST -> ImageFormat.RAW_SENSOR
        HardwareTier.TIER_2_YUV_FAST, HardwareTier.LEGACY -> ImageFormat.YUV_420_888
    }

    override fun configureRepeating(builder: CaptureRequest.Builder, tier: HardwareTier): Unit {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
    }

    override fun configureCapture(
        builder: CaptureRequest.Builder,
        tier: HardwareTier,
        frameIndex: Int,
        totalFrames: Int
    ): Unit {
        configureRepeating(builder, tier)

        // Bypass OEM over-sharpening and smeared de-noising on Tier 2 YUV pipeline
        if (tier == HardwareTier.TIER_2_YUV_FAST) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        }

        // Bracket exposure when HDR mode is active
        if (isHdrActive && totalFrames >= 3) {
            val evOffset = when (frameIndex) {
                0 -> -1
                1 -> 0
                else -> 1
            }
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evOffset)
        }
    }
}

/**
 * Low-light burst strategy with multi-frame exposure bracketing (8 frames).
 * Brackets exposure from -1.5 EV to +1.5 EV and requests high sensitivity for deep shadow recovery.
 */
class NightBurstModeStrategy(
    override val burstCount: Int = 8
) : CaptureModeStrategy {

    override val mode: CaptureMode = CaptureMode.NIGHT

    override fun getTargetFormat(tier: HardwareTier): Int = when (tier) {
        HardwareTier.TIER_1_RAW_BURST -> ImageFormat.RAW_SENSOR
        HardwareTier.TIER_2_YUV_FAST, HardwareTier.LEGACY -> ImageFormat.YUV_420_888
    }

    override fun configureRepeating(builder: CaptureRequest.Builder, tier: HardwareTier): Unit {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
    }

    override fun configureCapture(
        builder: CaptureRequest.Builder,
        tier: HardwareTier,
        frameIndex: Int,
        totalFrames: Int
    ): Unit {
        configureRepeating(builder, tier)

        if (tier == HardwareTier.TIER_2_YUV_FAST) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        }

        // Auto-exposure bracketing sequence from -1.5 EV to +1.5 EV:
        // Frames 0-1: -2 (highlight preservation)
        // Frame 2: -1
        // Frames 3-4: 0 (neutral midtones)
        // Frame 5: +1
        // Frames 6-7: +2 (deep shadow lifting)
        val evOffset = when (frameIndex) {
            0, 1 -> -2
            2 -> -1
            3, 4 -> 0
            5 -> 1
            else -> 2
        }
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evOffset)
    }
}

/**
 * High-speed anti-blur strategy (6 frames) prioritizing short shutter durations (<= 1/100s).
 */
class ActionAntiBlurModeStrategy(
    override val burstCount: Int = 6
) : CaptureModeStrategy {

    override val mode: CaptureMode = CaptureMode.ACTION

    override fun getTargetFormat(tier: HardwareTier): Int = when (tier) {
        HardwareTier.TIER_1_RAW_BURST -> ImageFormat.RAW_SENSOR
        HardwareTier.TIER_2_YUV_FAST, HardwareTier.LEGACY -> ImageFormat.YUV_420_888
    }

    override fun configureRepeating(builder: CaptureRequest.Builder, tier: HardwareTier): Unit {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
    }

    override fun configureCapture(
        builder: CaptureRequest.Builder,
        tier: HardwareTier,
        frameIndex: Int,
        totalFrames: Int
    ): Unit {
        configureRepeating(builder, tier)

        if (tier == HardwareTier.TIER_2_YUV_FAST) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        }

        // Bias towards fast shutter speed (<= 1/100s = 10,000,000 ns) with ISO boost to freeze fast motion
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -1)
        try {
            // Restrict sensor exposure duration <= 10ms (1/100s)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, 800)
        } catch (_: Exception) {
            // Safely ignored on devices that lock manual sensor settings while AE is ON
        }
    }
}

/**
 * Super-resolution multi-frame zoom strategy (4-6 frames burst with center crop sampling).
 */
class SuperResZoomModeStrategy(
    override val burstCount: Int = 6,
    private val activeArraySize: android.graphics.Rect? = null
) : CaptureModeStrategy {

    override val mode: CaptureMode = CaptureMode.SUPER_RES_ZOOM

    override fun getTargetFormat(tier: HardwareTier): Int = when (tier) {
        HardwareTier.TIER_1_RAW_BURST -> ImageFormat.RAW_SENSOR
        HardwareTier.TIER_2_YUV_FAST, HardwareTier.LEGACY -> ImageFormat.YUV_420_888
    }

    override fun configureRepeating(builder: CaptureRequest.Builder, tier: HardwareTier): Unit {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
    }

    override fun configureCapture(
        builder: CaptureRequest.Builder,
        tier: HardwareTier,
        frameIndex: Int,
        totalFrames: Int
    ): Unit {
        configureRepeating(builder, tier)

        if (tier == HardwareTier.TIER_2_YUV_FAST) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        }

        // Configure 2x center crop region if active array size is available
        if (activeArraySize != null) {
            val cropW = activeArraySize.width() / 2
            val cropH = activeArraySize.height() / 2
            val cropLeft = (activeArraySize.width() - cropW) / 2
            val cropTop = (activeArraySize.height() - cropH) / 2
            val cropRect = android.graphics.Rect(cropLeft, cropTop, cropLeft + cropW, cropTop + cropH)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        }
    }
}

/**
 * Placeholder strategy stubbing future video recording without breaking capture session.
 */
class VideoModePlaceholderStrategy : CaptureModeStrategy {

    override val mode: CaptureMode = CaptureMode.VIDEO_V2
    override val burstCount: Int = 1

    override fun getTargetFormat(tier: HardwareTier): Int = ImageFormat.YUV_420_888

    override fun configureRepeating(builder: CaptureRequest.Builder, tier: HardwareTier): Unit {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
    }

    override fun configureCapture(
        builder: CaptureRequest.Builder,
        tier: HardwareTier,
        frameIndex: Int,
        totalFrames: Int
    ): Unit {
        configureRepeating(builder, tier)
    }
}

/**
 * Factory providing corresponding [CaptureModeStrategy] implementation.
 */
object CaptureModeStrategyFactory {

    fun getStrategy(
        mode: CaptureMode,
        isHdrActive: Boolean = false,
        activeArraySize: android.graphics.Rect? = null
    ): CaptureModeStrategy = when (mode) {
        CaptureMode.PHOTO -> PhotoStandardModeStrategy(isHdrActive = isHdrActive)
        CaptureMode.NIGHT -> NightBurstModeStrategy()
        CaptureMode.ACTION -> ActionAntiBlurModeStrategy()
        CaptureMode.SUPER_RES_ZOOM -> SuperResZoomModeStrategy(activeArraySize = activeArraySize)
        CaptureMode.VIDEO_V2 -> VideoModePlaceholderStrategy()
    }
}
