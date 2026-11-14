package com.lumacore.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumacore.camera.core.model.AspectRatioSetting
import com.lumacore.camera.ui.components.BottomControlDeck
import com.lumacore.camera.ui.components.SettingsBottomSheet
import com.lumacore.camera.ui.components.TopControlBar
import com.lumacore.camera.ui.components.ViewfinderControls
import com.lumacore.camera.ui.theme.Black
import com.lumacore.camera.ui.theme.DarkSurface
import com.lumacore.camera.ui.theme.GoldAccent
import com.lumacore.camera.ui.theme.WhiteMuted

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Black)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. Top Control Bar (Padded for Status Bar / Cutout)
            TopControlBar(
                uiState = uiState,
                onFlashToggle = viewModel::onFlashToggle,
                onHdrToggle = viewModel::onHdrToggle,
                onTimerToggle = viewModel::onTimerToggle,
                onAspectRatioToggle = viewModel::onAspectRatioToggle,
                onSettingsClick = viewModel::onSettingsOpen,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 4.dp)
            )

            // 2. Viewfinder Area (Clamped by Aspect Ratio)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val ratioModifier = when (uiState.aspectRatio) {
                    AspectRatioSetting.RATIO_4_3 -> Modifier.aspectRatio(3f / 4f)
                    AspectRatioSetting.RATIO_16_9 -> Modifier.aspectRatio(9f / 16f)
                    AspectRatioSetting.RATIO_1_1 -> Modifier.aspectRatio(1f)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(ratioModifier)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF1E2124),
                                    Color(0xFF141619),
                                    Color(0xFF0F1012)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Viewfinder Surface Placeholder (Ready for Camera2 SurfaceView in Milestone 2)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "LumaCore RAW Sensor Viewfinder",
                            color = WhiteMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Zoom: ${String.format("%.1fx", uiState.zoomRatio)} | EV: ${uiState.exposureCompensation}",
                            color = GoldAccent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Viewfinder Gesture & Reticle Controls Overlay
                    ViewfinderControls(
                        uiState = uiState,
                        onTapToFocus = viewModel::onTapToFocus,
                        onZoomChange = viewModel::onZoomChange,
                        onExposureCompensationChange = viewModel::onExposureCompensationChange
                    )
                }
            }

            // 3. Bottom Control Deck (Padded for Navigation Bar)
            BottomControlDeck(
                uiState = uiState,
                onModeSelected = viewModel::onCaptureModeSelected,
                onShutterClick = viewModel::onShutterClick,
                onFlipCameraClick = viewModel::onLensFlip,
                onGalleryClick = { /* Handled via Intent/PhotoPicker in M2 */ },
                modifier = Modifier.navigationBarsPadding()
            )
        }

        // 4. Modal Settings Bottom Sheet
        if (uiState.isSettingsOpen) {
            SettingsBottomSheet(
                uiState = uiState,
                onDismissRequest = viewModel::onSettingsDismiss,
                onToggleGrid = viewModel::onToggleGrid,
                onToggleTelemetry = viewModel::onToggleTelemetry
            )
        }
    }
}
