package com.lumacore.camera.core.strategy

import android.graphics.Rect
import com.lumacore.camera.core.model.CaptureMode

/**
 * Factory providing corresponding [CaptureModeStrategy] implementation for all capture modes.
 */
object CaptureModeStrategyFactory {

    fun getStrategy(
        mode: CaptureMode,
        isHdrActive: Boolean = false,
        activeArraySize: Rect? = null,
        portraitApertureFStop: Float = 2.8f,
        manualIso: Int? = null,
        manualShutterSpeedNs: Long? = null,
        manualFocusDistance: Float? = null,
        manualWhiteBalanceKelvin: Int? = null,
        saveRawDng: Boolean = false,
        isVideoStabilizationEnabled: Boolean = true
    ): CaptureModeStrategy = when (mode) {
        CaptureMode.PHOTO -> PhotoStandardModeStrategy(isHdrActive = isHdrActive)
        CaptureMode.NIGHT -> NightBurstModeStrategy()
        CaptureMode.PORTRAIT -> PortraitModeStrategy(apertureFStop = portraitApertureFStop)
        CaptureMode.PRO -> ProManualModeStrategy(
            iso = manualIso,
            shutterSpeedNs = manualShutterSpeedNs,
            focusDistance = manualFocusDistance,
            whiteBalanceKelvin = manualWhiteBalanceKelvin,
            saveRawDng = saveRawDng
        )
        CaptureMode.ACTION -> ActionAntiBlurModeStrategy()
        CaptureMode.VIDEO -> VideoModeStrategy(isStabilizationEnabled = isVideoStabilizationEnabled)
    }
}
