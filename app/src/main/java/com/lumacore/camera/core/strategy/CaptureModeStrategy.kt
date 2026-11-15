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

        // Pixel-style underexposure schedule when HDR mode is active (never positive EV to prevent motion blur)
        if (isHdrActive && totalFrames >= 3) {
            val evOffset = when (frameIndex) {
                0 -> -2
                1 -> -1
                else -> 0
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

        // Pixel-style underexposure bracketing schedule:
        // Handheld night burst MUST NOT use positive overexposure (+1/+2 EV) which causes motion blur.
        // Frames 0-2: -2 EV (extreme highlight preservation & motion freeze)
        // Frames 3-5: -1 EV (fast midtones for temporal SNR accumulation)
        // Frames 6-7:  0 EV (neutral base ceiling; shadows are lifted by bilateral grid & temporal fusion)
        val evOffset = when (frameIndex) {
            0, 1, 2 -> -2
            3, 4, 5 -> -1
            else -> 0
        }
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evOffset)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
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
 * Portrait capture strategy simulating shallow depth-of-field with adjustable synthetic bokeh aperture.
 */
class PortraitModeStrategy(
    val apertureFStop: Float = 2.8f
) : CaptureModeStrategy {

    override val mode: CaptureMode = CaptureMode.PORTRAIT
    override val burstCount: Int = 1

    override fun getTargetFormat(tier: HardwareTier): Int = when (tier) {
        HardwareTier.TIER_1_RAW_BURST -> ImageFormat.RAW_SENSOR
        HardwareTier.TIER_2_YUV_FAST, HardwareTier.LEGACY -> ImageFormat.YUV_420_888
    }

    override fun configureRepeating(builder: CaptureRequest.Builder, tier: HardwareTier): Unit {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        // Enable face detection priority for portrait depth estimation
        try {
            builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
        } catch (_: Exception) {}
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
 * Professional manual capture strategy allowing explicit ISO, shutter speed,
 * manual focus distance, Kelvin white balance, and optional RAW DNG capture.
 */
class ProManualModeStrategy(
    val iso: Int? = null,
    val shutterSpeedNs: Long? = null,
    val focusDistance: Float? = null,
    val whiteBalanceKelvin: Int? = null,
    val saveRawDng: Boolean = false
) : CaptureModeStrategy {

    override val mode: CaptureMode = CaptureMode.PRO
    override val burstCount: Int = 1

    override fun getTargetFormat(tier: HardwareTier): Int = if (saveRawDng) {
        ImageFormat.RAW_SENSOR
    } else {
        when (tier) {
            HardwareTier.TIER_1_RAW_BURST -> ImageFormat.RAW_SENSOR
            HardwareTier.TIER_2_YUV_FAST, HardwareTier.LEGACY -> ImageFormat.YUV_420_888
        }
    }

    override fun configureRepeating(builder: CaptureRequest.Builder, tier: HardwareTier): Unit {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        // Manual or Auto AE
        if (iso != null || shutterSpeedNs != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            shutterSpeedNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        // Manual or Auto Focus
        if (focusDistance != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        // Manual or Auto White Balance
        if (whiteBalanceKelvin != null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            val kelvin = whiteBalanceKelvin.toFloat()
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

            val gains = android.hardware.camera2.params.RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
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
 * Video recording strategy with continuous video autofocus and optional electronic video stabilization.
 */
class VideoModeStrategy(
    val isStabilizationEnabled: Boolean = true
) : CaptureModeStrategy {

    override val mode: CaptureMode = CaptureMode.VIDEO
    override val burstCount: Int = 1

    override fun getTargetFormat(tier: HardwareTier): Int = ImageFormat.YUV_420_888

    override fun configureRepeating(builder: CaptureRequest.Builder, tier: HardwareTier): Unit {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        if (isStabilizationEnabled) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        } else {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }
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
