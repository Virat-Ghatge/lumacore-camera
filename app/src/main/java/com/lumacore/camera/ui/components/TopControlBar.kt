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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Timer10
import androidx.compose.material.icons.rounded.Timer3
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color.Transparent)
            .padding(horizontal = 16.dp),
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
                .size(40.dp)
                .clip(CircleShape)
                .background(TranslucentPillBackground)
        ) {
            Icon(
                imageVector = flashIcon,
                contentDescription = "Flash Mode: ${uiState.flashMode.name}",
                tint = flashTint,
                modifier = Modifier.size(20.dp)
            )
        }

        // HDR Mode Toggle Badge
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
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = hdrText,
                color = hdrColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
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
                .size(40.dp)
                .clip(CircleShape)
                .background(TranslucentPillBackground)
        ) {
            Icon(
                imageVector = timerIcon,
                contentDescription = "Timer: ${uiState.timerSetting.seconds}s",
                tint = timerTint,
                modifier = Modifier.size(20.dp)
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
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = uiState.aspectRatio.displayName,
                color = White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Settings Gear Icon
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(TranslucentPillBackground)
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
