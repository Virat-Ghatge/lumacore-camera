package com.lumacore.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Timer10
import androidx.compose.material.icons.rounded.Timer3
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumacore.camera.core.model.AspectRatioSetting
import com.lumacore.camera.core.model.CaptureMode
import com.lumacore.camera.core.model.FlashMode
import com.lumacore.camera.core.model.HdrMode
import com.lumacore.camera.core.model.TimerSetting
import com.lumacore.camera.ui.CameraUiState
import com.lumacore.camera.ui.theme.GoldAccent
import com.lumacore.camera.ui.theme.TranslucentPillBackground
import com.lumacore.camera.ui.theme.White
import com.lumacore.camera.ui.theme.WhiteMuted

@Composable
fun TopControlBar(
    uiState: CameraUiState,
    onFlashToggle: () -> Unit,
    onHdrToggle: () -> Unit,
    onTimerToggle: () -> Unit,
    onAspectRatioToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    onToggleRawDng: () -> Unit,
    onToggleNeuralEngine: () -> Unit,
    onToggleVideoStabilization: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color.Transparent)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flash Mode Toggle
        val (flashIcon: ImageVector, flashTint: Color) = when (uiState.flashMode) {
            FlashMode.AUTO -> Icons.Rounded.FlashAuto to GoldAccent
            FlashMode.ON -> Icons.Rounded.FlashOn to GoldAccent
            FlashMode.OFF -> Icons.Rounded.FlashOff to WhiteMuted
        }
        IconButton(
            onClick = onFlashToggle,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(TranslucentPillBackground)
        ) {
            Icon(
                imageVector = flashIcon,
                contentDescription = "Flash Mode: ${uiState.flashMode.name}",
                tint = flashTint,
                modifier = Modifier.size(19.dp)
            )
        }

        // HDR Mode Toggle Badge OR Video Stabilization in Video mode
        if (uiState.captureMode == CaptureMode.VIDEO) {
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(TranslucentPillBackground)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = GoldAccent),
                        onClick = onToggleVideoStabilization
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MotionPhotosOn,
                        contentDescription = "Stabilization",
                        tint = if (uiState.isVideoStabilizationEnabled) GoldAccent else WhiteMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (uiState.isVideoStabilizationEnabled) "EIS" else "OFF",
                        color = if (uiState.isVideoStabilizationEnabled) GoldAccent else WhiteMuted,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            val (hdrText: String, hdrColor: Color) = when (uiState.hdrMode) {
                HdrMode.AUTO -> "HDR-A" to GoldAccent
                HdrMode.ON -> "HDR" to GoldAccent
                HdrMode.OFF -> "HDR" to WhiteMuted
            }
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(TranslucentPillBackground)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = GoldAccent),
                        onClick = onHdrToggle
                    )
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = hdrText,
                    color = hdrColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // RAW DNG Toggle Pill (In PRO mode) OR Neural Engine status pill
        if (uiState.captureMode == CaptureMode.PRO) {
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(TranslucentPillBackground)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = GoldAccent),
                        onClick = onToggleRawDng
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (uiState.saveRawDng) "RAW" else "JPEG",
                    color = if (uiState.saveRawDng) GoldAccent else WhiteMuted,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Neural Engine quick indicator
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(TranslucentPillBackground)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = GoldAccent),
                        onClick = onToggleNeuralEngine
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Psychology,
                        contentDescription = "Neural Engine",
                        tint = if (uiState.isNeuralEngineEnabled) GoldAccent else WhiteMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (uiState.isNeuralEngineEnabled) "AI" else "ISP",
                        color = if (uiState.isNeuralEngineEnabled) GoldAccent else WhiteMuted,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Timer Toggle
        val (timerIcon: ImageVector, timerTint: Color) = when (uiState.timerSetting) {
            TimerSetting.OFF -> Icons.Rounded.Timer to WhiteMuted
            TimerSetting.SEC_3 -> Icons.Rounded.Timer3 to GoldAccent
            TimerSetting.SEC_10 -> Icons.Rounded.Timer10 to GoldAccent
        }
        IconButton(
            onClick = onTimerToggle,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(TranslucentPillBackground)
        ) {
            Icon(
                imageVector = timerIcon,
                contentDescription = "Timer: ${uiState.timerSetting.seconds}s",
                tint = timerTint,
                modifier = Modifier.size(19.dp)
            )
        }

        // Aspect Ratio Toggle Capsule
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(TranslucentPillBackground)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = GoldAccent),
                    onClick = onAspectRatioToggle
                )
                .padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = uiState.aspectRatio.displayName,
                color = White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Settings Gear Icon
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(TranslucentPillBackground)
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = White,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}
