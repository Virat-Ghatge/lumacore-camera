package com.lumacore.camera.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.ripple
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumacore.camera.core.model.CaptureMode
import com.lumacore.camera.ui.CameraUiState
import com.lumacore.camera.ui.theme.Black
import com.lumacore.camera.ui.theme.CaptureIndicatorTrack
import com.lumacore.camera.ui.theme.GoldAccent
import com.lumacore.camera.ui.theme.ShutterRed
import com.lumacore.camera.ui.theme.TranslucentPillBackground
import com.lumacore.camera.ui.theme.White
import com.lumacore.camera.ui.theme.WhiteMuted

@Composable
fun BottomControlDeck(
    uiState: CameraUiState,
    onModeSelected: (CaptureMode) -> Unit,
    onShutterClick: () -> Unit,
    onFlipCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Black)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Horizontal Mode Carousel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
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
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.label,
                        color = if (isSelected) GoldAccent else WhiteMuted,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Primary Controls Deck: Gallery | Shutter Button | Lens Switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gallery Thumbnail Button
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

            // Tactile Shutter Button with Multi-Frame Fusion Ring
            ShutterButton(
                captureMode = uiState.captureMode,
                isCapturing = uiState.isCapturing,
                captureProgress = uiState.captureProgress,
                onClick = onShutterClick
            )

            // Lens Flip Button
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

@Composable
private fun ShutterButton(
    captureMode: CaptureMode,
    isCapturing: Boolean,
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
            drawCircle(
                color = White,
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

        // Inner Core Shutter Target
        val isVideo = captureMode == CaptureMode.VIDEO_V2
        val innerColor = if (isVideo) ShutterRed else White
        val innerShape = if (isVideo && isCapturing) {
            RoundedCornerShape(8.dp)
        } else {
            CircleShape
        }
        val innerSize = if (isVideo && isCapturing) 32.dp else 64.dp

        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(innerShape)
                .background(innerColor)
        )
    }
}
