package com.lumacore.camera.ui

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.lumacore.camera.core.model.AspectRatioSetting
import com.lumacore.camera.core.model.CaptureMode
import com.lumacore.camera.core.model.FlashMode
import com.lumacore.camera.core.model.HardwareTier
import com.lumacore.camera.core.model.HdrMode
import com.lumacore.camera.core.model.LensFacing
import com.lumacore.camera.core.model.TimerSetting

/**
 * Immutable UI State representation for LumaCore camera screen.
 */
data class CameraUiState(
    val flashMode: FlashMode = FlashMode.AUTO,
    val hdrMode: HdrMode = HdrMode.AUTO,
    val timerSetting: TimerSetting = TimerSetting.OFF,
    val aspectRatio: AspectRatioSetting = AspectRatioSetting.RATIO_4_3,
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val lensFacing: LensFacing = LensFacing.BACK,
    val zoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 0.5f,
    val maxZoomRatio: Float = 10.0f,
    val exposureCompensation: Int = 0,
    val minExposureCompensation: Int = -4,
    val maxExposureCompensation: Int = 4,
    val showGrid: Boolean = false,
    val showTelemetry: Boolean = true,
    val isCapturing: Boolean = false,
    val captureProgress: Float = 0.0f,
    val lastCapturedThumbnailUri: Uri? = null,
    val hardwareTier: HardwareTier = HardwareTier.TIER_1_RAW_BURST,
    val telemetrySummary: String = "Tier: RAW_BURST | Align: 18.2ms | NNAPI: 24.1ms | RAM: 142MB",
    val isSettingsOpen: Boolean = false,
    val focusPoint: Offset? = null,
    val showFocusReticle: Boolean = false,
    val hasCameraPermission: Boolean = false
)
