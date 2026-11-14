package com.lumacore.camera.core.model

/**
 * Flash modes supported by LumaCore camera.
 */
enum class FlashMode {
    AUTO,
    ON,
    OFF;

    fun next(): FlashMode = when (this) {
        AUTO -> ON
        ON -> OFF
        OFF -> AUTO
    }
}

/**
 * HDR capture modes.
 */
enum class HdrMode {
    AUTO,
    ON,
    OFF;

    fun next(): HdrMode = when (this) {
        AUTO -> ON
        ON -> OFF
        OFF -> AUTO
    }
}

/**
 * Countdown shutter timer settings in seconds.
 */
enum class TimerSetting(val seconds: Int) {
    OFF(0),
    SEC_3(3),
    SEC_10(10);

    fun next(): TimerSetting = when (this) {
        OFF -> SEC_3
        SEC_3 -> SEC_10
        SEC_10 -> OFF
    }
}

/**
 * Viewfinder and sensor aspect ratio configuration.
 */
enum class AspectRatioSetting(val ratioX: Int, val ratioY: Int, val displayName: String) {
    RATIO_4_3(4, 3, "4:3"),
    RATIO_16_9(16, 9, "16:9"),
    RATIO_1_1(1, 1, "1:1");

    fun next(): AspectRatioSetting = when (this) {
        RATIO_4_3 -> RATIO_16_9
        RATIO_16_9 -> RATIO_1_1
        RATIO_1_1 -> RATIO_4_3
    }
}

/**
 * Primary user-selectable capture modes.
 */
enum class CaptureMode(val label: String) {
    NIGHT("NIGHT"),
    PHOTO("PHOTO"),
    ACTION("ACTION"),
    SUPER_RES_ZOOM("ZOOM+"),
    VIDEO_V2("VIDEO (v2)")
}

/**
 * Hardware capability tiers detected on the device.
 */
enum class HardwareTier(val displayName: String, val description: String) {
    TIER_1_RAW_BURST(
        displayName = "Tier 1: RAW Burst",
        description = "Level 3 / Full hardware with RAW10 burst capture & multi-frame fusion"
    ),
    TIER_2_YUV_FAST(
        displayName = "Tier 2: YUV Fast",
        description = "Universal fast YUV_420_888 pipeline with HDRNet bilateral grid enhancement"
    ),
    LEGACY(
        displayName = "Legacy Fallback",
        description = "Limited hardware compatibility mode with single-frame ISP processing"
    )
}

/**
 * Physical lens direction.
 */
enum class LensFacing {
    BACK,
    FRONT;

    fun toggle(): LensFacing = when (this) {
        BACK -> FRONT
        FRONT -> BACK
    }
}
