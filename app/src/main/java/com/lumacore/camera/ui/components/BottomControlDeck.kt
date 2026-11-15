package com.lumacore.camera.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumacore.camera.core.model.CaptureMode
import com.lumacore.camera.ui.CameraUiState
import com.lumacore.camera.ui.theme.Black
import com.lumacore.camera.ui.theme.DarkSurfaceElevated
import com.lumacore.camera.ui.theme.GoldAccent
import com.lumacore.camera.ui.theme.ShutterRed
import com.lumacore.camera.ui.theme.TranslucentPillBackground
import com.lumacore.camera.ui.theme.White
import com.lumacore.camera.ui.theme.WhiteMuted
import java.util.Locale

@Composable
fun BottomControlDeck(
    uiState: CameraUiState,
    onModeSelected: (CaptureMode) -> Unit,
    onShutterClick: () -> Unit,
    onFlipCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onIsoSelected: (Int?) -> Unit,
    onShutterSpeedSelected: (Long?) -> Unit,
    onFocusDistanceChanged: (Float?) -> Unit,
    onWhiteBalanceKelvinChanged: (Int?) -> Unit,
    onToggleFocusPeaking: () -> Unit,
    onToggleHistogram: () -> Unit,
    onApertureChanged: (Float) -> Unit,
    onVideoSnapshot: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Black)
            .padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Mode-Specific Parameter Control Deck (Pro, Portrait, or Video HUD)
        when (uiState.captureMode) {
            CaptureMode.PRO -> {
                ProControlBar(
                    manualIso = uiState.manualIso,
                    manualShutterSpeedNs = uiState.manualShutterSpeedNs,
                    manualFocusDistance = uiState.manualFocusDistance,
                    manualWhiteBalanceKelvin = uiState.manualWhiteBalanceKelvin,
                    isFocusPeakingEnabled = uiState.isFocusPeakingEnabled,
                    isHistogramEnabled = uiState.isHistogramEnabled,
                    onIsoSelected = onIsoSelected,
                    onShutterSpeedSelected = onShutterSpeedSelected,
                    onFocusDistanceChanged = onFocusDistanceChanged,
                    onWhiteBalanceKelvinChanged = onWhiteBalanceKelvinChanged,
                    onToggleFocusPeaking = onToggleFocusPeaking,
                    onToggleHistogram = onToggleHistogram
                )
            }
            CaptureMode.PORTRAIT -> {
                PortraitControlBar(
                    currentFStop = uiState.portraitApertureFStop,
                    onFStopChanged = onApertureChanged
                )
            }
            CaptureMode.VIDEO -> {
                if (uiState.isRecordingVideo) {
                    VideoRecordingHud(
                        durationSeconds = uiState.videoRecordingDurationSeconds,
                        audioVuAmplitude = uiState.audioVuAmplitude
                    )
                }
            }
            else -> {
                // PHOTO, NIGHT, ACTION standard layout
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 2. Horizontal Mode Carousel
        val carouselScrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(carouselScrollState)
                .padding(vertical = 10.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CaptureMode.values().forEach { mode ->
                val isSelected = uiState.captureMode == mode
                Box(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onModeSelected(mode) }
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.label,
                        color = if (isSelected) GoldAccent else WhiteMuted,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Primary Controls Deck: Gallery | Shutter Button (or Record) | Lens Switch (or Snapshot)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Action: Still Snapshot during Video recording OR Gallery Button
            if (uiState.captureMode == CaptureMode.VIDEO && uiState.isRecordingVideo) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(TranslucentPillBackground)
                        .border(1.5.dp, Color(0x66FFFFFF), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = GoldAccent),
                            onClick = onVideoSnapshot
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoCamera,
                        contentDescription = "Video Still Snapshot",
                        tint = White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(TranslucentPillBackground)
                        .border(1.5.dp, Color(0x44FFFFFF), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = GoldAccent),
                            onClick = onGalleryClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = "Open Gallery",
                        tint = White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Tactile Shutter / Video Record Button
            ShutterButton(
                captureMode = uiState.captureMode,
                isCapturing = uiState.isCapturing,
                isRecordingVideo = uiState.isRecordingVideo,
                captureProgress = uiState.captureProgress,
                onClick = onShutterClick
            )

            // Right Action: Lens Flip Button
            val flipRotation by animateFloatAsState(
                targetValue = if (uiState.lensFacing == com.lumacore.camera.core.model.LensFacing.FRONT) 180f else 0f,
                animationSpec = tween(durationMillis = 350),
                label = "lens_flip_rotation"
            )
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(TranslucentPillBackground)
                    .border(1.5.dp, Color(0x44FFFFFF), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = GoldAccent),
                        onClick = onFlipCameraClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cameraswitch,
                    contentDescription = "Switch Camera Lens",
                    tint = White,
                    modifier = Modifier
                        .size(26.dp)
                        .rotate(flipRotation)
                )
            }
        }
    }
}

/**
 * Real-time video recording indicator with duration ticker and live audio VU meter.
 */
@Composable
private fun VideoRecordingHud(
    durationSeconds: Int,
    audioVuAmplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_dot_pulse")
    val alphaDot by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_dot_alpha"
    )

    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60
    val durationFormatted = String.format(Locale.US, "%02d:%02d", minutes, seconds)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xDD1B0B0B))
            .border(0.75.dp, ShutterRed.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pulsing Red Recording Dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(ShutterRed.copy(alpha = alphaDot))
        )

        // Time ticker
        Text(
            text = durationFormatted,
            color = White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )

        // Audio VU Meter Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = "Microphone VU",
                tint = ShutterRed,
                modifier = Modifier.size(14.dp)
            )

            // 6-segment VU meter
            val activeSegments = (audioVuAmplitude * 6).toInt().coerceIn(1, 6)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 1..6) {
                    val isSegmentActive = i <= activeSegments
                    val segColor = when {
                        i >= 5 -> ShutterRed
                        i >= 3 -> GoldAccent
                        else -> Color(0xFF4CAF50)
                    }
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height((6 + i * 2).dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (isSegmentActive) segColor else Color(0x33FFFFFF))
                    )
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(
    captureMode: CaptureMode,
    isCapturing: Boolean,
    isRecordingVideo: Boolean,
    captureProgress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = tween(durationMillis = 100),
        label = "shutter_scale"
    )

    val isVideo = captureMode == CaptureMode.VIDEO

    Box(
        modifier = modifier
            .size(80.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outer Track Ring
        Canvas(modifier = Modifier.size(80.dp)) {
            val ringColor = if (isVideo) ShutterRed else White
            drawCircle(
                color = ringColor,
                radius = size.minDimension / 2f - 2.dp.toPx(),
                style = Stroke(width = 3.dp.toPx())
            )

            if (isCapturing) {
                // Animated Multi-Frame Fusion Progress Indicator
                val sweep = captureProgress * 360f
                drawArc(
                    color = GoldAccent,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // Inner Core Target
        val innerColor = if (isVideo) ShutterRed else White
        val innerShape = if (isVideo && isRecordingVideo) {
            RoundedCornerShape(8.dp)
        } else {
            CircleShape
        }
        val innerSize = if (isVideo && isRecordingVideo) 32.dp else 64.dp

        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(innerShape)
                .background(innerColor)
        )
    }
}
