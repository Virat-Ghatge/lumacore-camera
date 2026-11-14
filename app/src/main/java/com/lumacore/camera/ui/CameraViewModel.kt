package com.lumacore.camera.ui

import android.app.Application
import android.net.Uri
import android.view.Surface
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumacore.camera.core.engine.Camera2CaptureEngine
import com.lumacore.camera.core.hardware.HardwareProfile
import com.lumacore.camera.core.model.AspectRatioSetting
import com.lumacore.camera.core.model.CaptureMode
import com.lumacore.camera.core.model.FlashMode
import com.lumacore.camera.core.model.HardwareTier
import com.lumacore.camera.core.model.HdrMode
import com.lumacore.camera.core.model.LensFacing
import com.lumacore.camera.core.model.TimerSetting
import com.lumacore.camera.core.nativebridge.NativePipeline
import com.lumacore.camera.core.strategy.CaptureModeStrategy
import com.lumacore.camera.core.strategy.CaptureModeStrategyFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CameraViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState: MutableStateFlow<CameraUiState> = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val engine: Camera2CaptureEngine = Camera2CaptureEngine(application.applicationContext)
    private var attachedSurface: Surface? = null
    private var focusDismissJob: Job? = null
    private var activeProfile: HardwareProfile? = null

    init {
        probeInitialNativeStatus()
    }

    private fun probeInitialNativeStatus(): Unit {
        val isNativeAvailable: Boolean = NativePipeline.isNativeReady()
        val nativeVersion: String = if (isNativeAvailable) {
            NativePipeline.nativeInit()
            NativePipeline.nativeGetVersion() ?: "unknown"
        } else {
            "unavailable"
        }

        _uiState.update { current: CameraUiState ->
            current.copy(
                telemetrySummary = "Native: $nativeVersion | Initializing Camera2..."
            )
        }
    }

    /**
     * Attaches Viewfinder Surface from SurfaceView and starts camera preview session if permitted.
     */
    fun attachSurface(surface: Surface): Unit {
        this.attachedSurface = surface
        if (_uiState.value.hasCameraPermission) {
            startCameraPreview()
        }
    }

    /**
     * Detaches Viewfinder Surface when SurfaceView is destroyed.
     */
    fun detachSurface(): Unit {
        this.attachedSurface = null
        engine.close()
    }

    fun onCameraPermissionResult(isGranted: Boolean): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(hasCameraPermission = isGranted)
        }
        if (isGranted && attachedSurface != null) {
            startCameraPreview()
        }
    }

    private fun startCameraPreview(): Unit {
        val surface: Surface = attachedSurface ?: return

        engine.openCamera(
            lensFacing = _uiState.value.lensFacing,
            previewSurface = surface,
            onReady = { profile: HardwareProfile ->
                activeProfile = profile
                val isNativeAvailable: Boolean = NativePipeline.isNativeReady()
                val nativeVersion: String = if (isNativeAvailable) {
                    NativePipeline.nativeGetVersion() ?: "1.0.0"
                } else {
                    "unavailable"
                }

                _uiState.update { current: CameraUiState ->
                    current.copy(
                        hardwareTier = profile.tier,
                        minZoomRatio = profile.supportedZoomRange.start,
                        maxZoomRatio = profile.supportedZoomRange.endInclusive,
                        minExposureCompensation = profile.supportedEvRange.start,
                        maxExposureCompensation = profile.supportedEvRange.endInclusive,
                        telemetrySummary = "Tier: ${profile.tier.displayName} | ${profile.binnedCaptureSize.width}x${profile.binnedCaptureSize.height} | Native: $nativeVersion"
                    )
                }
            },
            onError = { errorMsg: String ->
                _uiState.update { current: CameraUiState ->
                    current.copy(telemetrySummary = "Camera Error: $errorMsg")
                }
            }
        )
    }

    fun onFlashToggle(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(flashMode = current.flashMode.next())
        }
    }

    fun onHdrToggle(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(hdrMode = current.hdrMode.next())
        }
    }

    fun onTimerToggle(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(timerSetting = current.timerSetting.next())
        }
    }

    fun onAspectRatioToggle(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(aspectRatio = current.aspectRatio.next())
        }
    }

    fun onCaptureModeSelected(mode: CaptureMode): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(captureMode = mode)
        }
    }

    fun onLensFlip(): Unit {
        val newFacing: LensFacing = _uiState.value.lensFacing.toggle()
        _uiState.update { current: CameraUiState ->
            current.copy(lensFacing = newFacing)
        }

        val surface: Surface? = attachedSurface
        if (surface != null && _uiState.value.hasCameraPermission) {
            engine.switchLens(
                newFacing = newFacing,
                previewSurface = surface,
                onReady = { profile: HardwareProfile ->
                    activeProfile = profile
                    _uiState.update { current: CameraUiState ->
                        current.copy(
                            hardwareTier = profile.tier,
                            minZoomRatio = profile.supportedZoomRange.start,
                            maxZoomRatio = profile.supportedZoomRange.endInclusive,
                            minExposureCompensation = profile.supportedEvRange.start,
                            maxExposureCompensation = profile.supportedEvRange.endInclusive
                        )
                    }
                },
                onError = { errorMsg: String ->
                    _uiState.update { current: CameraUiState ->
                        current.copy(telemetrySummary = "Lens Switch Error: $errorMsg")
                    }
                }
            )
        }
    }

    fun onZoomChange(zoom: Float): Unit {
        val clampedZoom: Float = zoom.coerceIn(_uiState.value.minZoomRatio, _uiState.value.maxZoomRatio)
        _uiState.update { current: CameraUiState ->
            current.copy(zoomRatio = clampedZoom)
        }
        engine.setZoom(clampedZoom)
    }

    fun onExposureCompensationChange(ev: Int): Unit {
        val clampedEv: Int = ev.coerceIn(_uiState.value.minExposureCompensation, _uiState.value.maxExposureCompensation)
        _uiState.update { current: CameraUiState ->
            current.copy(exposureCompensation = clampedEv)
        }
        engine.setExposureCompensation(clampedEv)
    }

    fun onTapToFocus(offset: Offset): Unit {
        focusDismissJob?.cancel()
        _uiState.update { current: CameraUiState ->
            current.copy(
                focusPoint = offset,
                showFocusReticle = true
            )
        }

        // Viewfinder normalized coordinate mapping approximation
        val xNorm: Float = (offset.x / 1080f).coerceIn(0.0f, 1.0f)
        val yNorm: Float = (offset.y / 1920f).coerceIn(0.0f, 1.0f)
        engine.setFocusPoint(xNorm, yNorm)

        focusDismissJob = viewModelScope.launch {
            delay(2800L)
            _uiState.update { current: CameraUiState ->
                current.copy(showFocusReticle = false)
            }
        }
    }

    fun onShutterClick(): Unit {
        if (_uiState.value.isCapturing) return

        val current: CameraUiState = _uiState.value
        val isHdrActive: Boolean = current.hdrMode != HdrMode.OFF
        val strategy: CaptureModeStrategy = CaptureModeStrategyFactory.getStrategy(
            mode = current.captureMode,
            isHdrActive = isHdrActive
        )

        _uiState.update { state: CameraUiState ->
            state.copy(
                isCapturing = true,
                captureProgress = 0.0f
            )
        }

        engine.captureBurst(
            strategy = strategy,
            onProgress = { progress: Float ->
                _uiState.update { state: CameraUiState ->
                    state.copy(captureProgress = progress)
                }
            },
            onComplete = { savedUri: Uri? ->
                _uiState.update { state: CameraUiState ->
                    state.copy(
                        isCapturing = false,
                        captureProgress = 0.0f,
                        lastCapturedThumbnailUri = savedUri ?: state.lastCapturedThumbnailUri,
                        telemetrySummary = if (savedUri != null) {
                            "Burst ${strategy.burstCount} frames | Saved: ${savedUri.lastPathSegment}"
                        } else {
                            "Capture sequence failed"
                        }
                    )
                }
            }
        )
    }

    fun onToggleGrid(show: Boolean): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(showGrid = show)
        }
    }

    fun onToggleTelemetry(show: Boolean): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(showTelemetry = show)
        }
    }

    fun onSettingsOpen(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(isSettingsOpen = true)
        }
    }

    fun onSettingsDismiss(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(isSettingsOpen = false)
        }
    }

    override fun onCleared(): Unit {
        super.onCleared()
        focusDismissJob?.cancel()
        engine.close()
    }
}
