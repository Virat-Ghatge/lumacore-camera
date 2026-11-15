package com.lumacore.camera.ui

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.view.Surface
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumacore.camera.core.engine.Camera2CaptureEngine
import com.lumacore.camera.core.hardware.HardwareProfile
import com.lumacore.camera.core.hardware.PhysicalLensInfo
import com.lumacore.camera.core.model.AspectRatioSetting
import com.lumacore.camera.core.model.CaptureMode
import com.lumacore.camera.core.model.FlashMode
import com.lumacore.camera.core.model.HardwareTier
import com.lumacore.camera.core.model.HdrMode
import com.lumacore.camera.core.model.LensFacing
import com.lumacore.camera.core.model.TimerSetting
import com.lumacore.camera.core.nativebridge.NativePipeline
import com.lumacore.camera.core.sensor.TripodDetector
import com.lumacore.camera.core.strategy.CaptureModeStrategy
import com.lumacore.camera.core.strategy.CaptureModeStrategyFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin

class CameraViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState: MutableStateFlow<CameraUiState> = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val engine: Camera2CaptureEngine = Camera2CaptureEngine(application.applicationContext)
    private val tripodDetector: TripodDetector = TripodDetector(application.applicationContext)

    private var attachedSurface: Surface? = null
    private var focusDismissJob: Job? = null
    private var videoRecordingJob: Job? = null
    private var histogramJob: Job? = null
    private var activeProfile: HardwareProfile? = null

    init {
        probeInitialNativeStatus()
        initTripodDetection()
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

    private fun initTripodDetection(): Unit {
        tripodDetector.startListening()
        viewModelScope.launch {
            tripodDetector.tripodFlow.collect { isMounted: Boolean ->
                _uiState.update { current: CameraUiState ->
                    current.copy(isTripodMounted = isMounted)
                }
            }
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
                        availableLenses = profile.availableLenses,
                        activeLensZoom = current.zoomRatio,
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
        if (_uiState.value.isRecordingVideo && mode != CaptureMode.VIDEO) {
            onStopVideoRecording()
        }

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
                            maxExposureCompensation = profile.supportedEvRange.endInclusive,
                            availableLenses = profile.availableLenses,
                            activeLensZoom = current.zoomRatio
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

    /**
     * Switches to selected physical or logical camera lens pill (0.6x, 1x, 2x, 3x).
     */
    fun onLensSelected(lens: PhysicalLensInfo): Unit {
        val zoom = lens.zoomFactor
        _uiState.update { current: CameraUiState ->
            current.copy(
                zoomRatio = zoom,
                activeLensZoom = zoom
            )
        }
        engine.setZoom(zoom)
    }

    fun onZoomChange(zoom: Float): Unit {
        val clampedZoom: Float = zoom.coerceIn(_uiState.value.minZoomRatio, _uiState.value.maxZoomRatio)
        _uiState.update { current: CameraUiState ->
            current.copy(
                zoomRatio = clampedZoom,
                activeLensZoom = clampedZoom
            )
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

    // --- Pro Mode Controls ---

    fun onIsoSelected(iso: Int?): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(manualIso = iso)
        }
        engine.setManualIso(iso)
    }

    fun onShutterSpeedSelected(ns: Long?): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(manualShutterSpeedNs = ns)
        }
        engine.setManualExposureTime(ns)
    }

    fun onFocusDistanceChanged(distance: Float?): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(manualFocusDistance = distance)
        }
        engine.setManualFocusDistance(distance)
    }

    fun onWhiteBalanceKelvinChanged(kelvin: Int?): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(manualWhiteBalanceKelvin = kelvin)
        }
        engine.setManualWhiteBalance(kelvin)
    }

    fun onToggleFocusPeaking(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(isFocusPeakingEnabled = !current.isFocusPeakingEnabled)
        }
    }

    fun onToggleHistogram(): Unit {
        val nextState = !_uiState.value.isHistogramEnabled
        _uiState.update { current: CameraUiState ->
            current.copy(isHistogramEnabled = nextState)
        }

        histogramJob?.cancel()
        if (nextState) {
            histogramJob = viewModelScope.launch {
                while (_uiState.value.isHistogramEnabled) {
                    val simBins = IntArray(256) { idx ->
                        val center = 100.0 + 20.0 * sin(System.currentTimeMillis() / 800.0)
                        val x = (idx - center) / 38.0
                        (Math.exp(-0.5 * x * x) * 1200).toInt().coerceAtLeast(0)
                    }
                    _uiState.update { current: CameraUiState ->
                        current.copy(liveHistogramLuma = simBins)
                    }
                    delay(120L)
                }
            }
        } else {
            _uiState.update { current: CameraUiState ->
                current.copy(liveHistogramLuma = null)
            }
        }
    }

    fun onToggleRawDng(): Unit {
        val nextVal = !_uiState.value.saveRawDng
        _uiState.update { current: CameraUiState ->
            current.copy(saveRawDng = nextVal)
        }
        engine.saveRawDng = nextVal
    }

    // --- Portrait Mode Controls ---

    fun onApertureChanged(fStop: Float): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(portraitApertureFStop = fStop)
        }
    }

    // --- Video Recording Controls ---

    fun onStartVideoRecording(): Unit {
        if (_uiState.value.isRecordingVideo) return
        _uiState.update { current: CameraUiState ->
            current.copy(
                isRecordingVideo = true,
                videoRecordingDurationSeconds = 0,
                audioVuAmplitude = 0.2f
            )
        }

        videoRecordingJob?.cancel()
        videoRecordingJob = viewModelScope.launch {
            var seconds = 0
            var tick = 0
            while (_uiState.value.isRecordingVideo) {
                delay(100L)
                tick++
                if (tick % 10 == 0) {
                    seconds++
                    _uiState.update { current: CameraUiState ->
                        current.copy(videoRecordingDurationSeconds = seconds)
                    }
                }
                val vu = (0.2f + 0.6f * abs(sin(tick * 0.45).toFloat())).coerceIn(0.0f, 1.0f)
                _uiState.update { current: CameraUiState ->
                    current.copy(audioVuAmplitude = vu)
                }
            }
        }
    }

    fun onStopVideoRecording(): Unit {
        videoRecordingJob?.cancel()
        videoRecordingJob = null
        _uiState.update { current: CameraUiState ->
            current.copy(
                isRecordingVideo = false,
                audioVuAmplitude = 0.0f
            )
        }
    }

    fun onVideoSnapshot(): Unit {
        onShutterClick()
    }

    fun onToggleVideoStabilization(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(isVideoStabilizationEnabled = !current.isVideoStabilizationEnabled)
        }
    }

    // --- Viewfinder Overlays & AE/AF Lock ---

    fun onLongPressAeAfLock(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(isAeAfLocked = true)
        }
        engine.lockAeAf(true)
    }

    fun onUnlockAeAf(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(isAeAfLocked = false)
        }
        engine.lockAeAf(false)
    }

    fun onToggleNeuralEngine(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(isNeuralEngineEnabled = !current.isNeuralEngineEnabled)
        }
    }

    fun onDismissScanner(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(detectedScannerText = null)
        }
    }

    // --- Shutter & Capture ---

    fun onShutterClick(): Unit {
        if (_uiState.value.captureMode == CaptureMode.VIDEO) {
            if (_uiState.value.isRecordingVideo) {
                onStopVideoRecording()
            } else {
                onStartVideoRecording()
            }
            return
        }

        if (_uiState.value.isCapturing) return

        val current: CameraUiState = _uiState.value
        val isHdrActive: Boolean = current.hdrMode != HdrMode.OFF
        val strategy: CaptureModeStrategy = CaptureModeStrategyFactory.getStrategy(
            mode = current.captureMode,
            isHdrActive = isHdrActive,
            activeArraySize = activeProfile?.activeArraySize,
            portraitApertureFStop = current.portraitApertureFStop,
            manualIso = current.manualIso,
            manualShutterSpeedNs = current.manualShutterSpeedNs,
            manualFocusDistance = current.manualFocusDistance,
            manualWhiteBalanceKelvin = current.manualWhiteBalanceKelvin,
            saveRawDng = current.saveRawDng,
            isVideoStabilizationEnabled = current.isVideoStabilizationEnabled
        )

        _uiState.update { state: CameraUiState ->
            state.copy(
                isCapturing = true,
                captureProgress = 0.0f
            )
        }

        engine.captureBurst(
            strategy = strategy,
            onBurstCaptured = {
                _uiState.update { state: CameraUiState ->
                    state.copy(
                        isCapturing = false,
                        isProcessingInBackground = true,
                        pendingJobsCount = state.pendingJobsCount + 1
                    )
                }
            },
            onProgress = { progress: Float ->
                _uiState.update { state: CameraUiState ->
                    state.copy(captureProgress = progress)
                }
            },
            onComplete = { savedUri: Uri? ->
                val telemetry = engine.latestTelemetrySummary ?: if (savedUri != null) {
                    "Mode: ${strategy.mode.name} | Burst ${strategy.burstCount} frames | Saved: ${savedUri.lastPathSegment}"
                } else {
                    "Capture sequence failed"
                }
                _uiState.update { state: CameraUiState ->
                    val newPendingCount = (state.pendingJobsCount - 1).coerceAtLeast(0)
                    state.copy(
                        pendingJobsCount = newPendingCount,
                        isProcessingInBackground = newPendingCount > 0,
                        captureProgress = 0.0f,
                        lastCapturedThumbnailUri = savedUri ?: state.lastCapturedThumbnailUri,
                        telemetrySummary = telemetry
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

    // --- In-App Filmstrip Gallery Controls ---

    fun onOpenGallery(): Unit {
        viewModelScope.launch(Dispatchers.IO) {
            val queriedUris = queryRecentGalleryUris()
            withContext(Dispatchers.Main) {
                _uiState.update { current: CameraUiState ->
                    val combinedUris = queriedUris.toMutableList()
                    val lastThumbnail = current.lastCapturedThumbnailUri
                    if (lastThumbnail != null && !combinedUris.contains(lastThumbnail)) {
                        combinedUris.add(0, lastThumbnail)
                    }
                    current.copy(
                        isGalleryOpen = true,
                        galleryUris = combinedUris,
                        selectedGalleryIndex = 0
                    )
                }
            }
        }
    }

    private fun queryRecentGalleryUris(): List<Uri> {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%DCIM/LumaCore%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            getApplication<Application>().contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && uris.size < 60) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    uris.add(contentUri)
                }
            }
        } catch (_: Exception) {
            try {
                getApplication<Application>().contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { fallbackCursor ->
                    val idCol = fallbackCursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (fallbackCursor.moveToNext() && uris.size < 60) {
                        val id = fallbackCursor.getLong(idCol)
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        uris.add(contentUri)
                    }
                }
            } catch (_: Exception) {}
        }
        return uris
    }

    fun onCloseGallery(): Unit {
        _uiState.update { current: CameraUiState ->
            current.copy(isGalleryOpen = false)
        }
    }

    fun onSelectGalleryPhoto(index: Int): Unit {
        _uiState.update { current: CameraUiState ->
            val count = current.galleryUris.size
            val validIndex = if (count > 0) index.coerceIn(0, count - 1) else 0
            current.copy(selectedGalleryIndex = validIndex)
        }
    }

    fun onDeleteCurrentGalleryPhoto(): Unit {
        val currentUris = _uiState.value.galleryUris
        val currentIndex = _uiState.value.selectedGalleryIndex
        if (currentUris.isEmpty() || currentIndex !in currentUris.indices) return

        val targetUri = currentUris[currentIndex]

        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.delete(targetUri, null, null)
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                _uiState.update { current: CameraUiState ->
                    val updatedList = current.galleryUris.toMutableList().apply {
                        removeAt(currentIndex)
                    }
                    val newIndex = if (updatedList.isEmpty()) 0 else currentIndex.coerceAtMost(updatedList.size - 1)
                    val nextThumbnail = updatedList.firstOrNull()
                    current.copy(
                        galleryUris = updatedList,
                        selectedGalleryIndex = newIndex,
                        isGalleryOpen = updatedList.isNotEmpty(),
                        lastCapturedThumbnailUri = if (current.lastCapturedThumbnailUri == targetUri) nextThumbnail else current.lastCapturedThumbnailUri
                    )
                }
            }
        }
    }

    override fun onCleared(): Unit {
        super.onCleared()
        focusDismissJob?.cancel()
        videoRecordingJob?.cancel()
        histogramJob?.cancel()
        tripodDetector.close()
        engine.close()
    }
}
