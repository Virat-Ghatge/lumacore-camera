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
 * Low-light burst strategy with multi-frame exposure bracketing (6-8 frames).
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

        // Auto-exposure bracketing offsets: highlight preservation -> neutral midtones -> shadow boost
        val evOffset = when {
            frameIndex < totalFrames / 3 -> -2
            frameIndex < (totalFrames * 2) / 3 -> 0
            else -> 1
        }
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evOffset)
    }
}

/**
 * High-speed anti-blur strategy (4-6 frames) prioritizing short shutter durations.
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
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
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

        // Bias towards fast shutter speed (<= 1/100s) to freeze fast motion
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -1)
    }
}

/**
 * Super-resolution multi-frame zoom strategy (4 frames burst with center sampling).
 */
class SuperResZoomModeStrategy(
    override val burstCount: Int = 4
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
        isHdrActive: Boolean = false
    ): CaptureModeStrategy = when (mode) {
        CaptureMode.PHOTO -> PhotoStandardModeStrategy(isHdrActive = isHdrActive)
        CaptureMode.NIGHT -> NightBurstModeStrategy()
        CaptureMode.ACTION -> ActionAntiBlurModeStrategy()
        CaptureMode.SUPER_RES_ZOOM -> SuperResZoomModeStrategy()
        CaptureMode.VIDEO_V2 -> VideoModePlaceholderStrategy()
    }
}
