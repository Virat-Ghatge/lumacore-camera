package com.lumacore.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumacore.camera.ui.CameraUiState
import com.lumacore.camera.ui.theme.DarkSurface
import com.lumacore.camera.ui.theme.DarkSurfaceElevated
import com.lumacore.camera.ui.theme.GoldAccent
import com.lumacore.camera.ui.theme.White
import com.lumacore.camera.ui.theme.WhiteMuted
import com.lumacore.camera.ui.theme.WhiteSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    uiState: CameraUiState,
    onDismissRequest: () -> Unit,
    onToggleGrid: (Boolean) -> Unit,
    onToggleTelemetry: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = Color(0x99000000),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = "Camera Settings",
                color = White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Hardware Tier Status Card
            HardwareTierCard(uiState = uiState)

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color(0x22FFFFFF))
            Spacer(modifier = Modifier.height(16.dp))

            // 2. Framing Grid Switch
            SettingsSwitchRow(
                icon = Icons.Rounded.GridOn,
                title = "Rule of Thirds Grid",
                subtitle = "Assists with shot framing and horizon alignment",
                checked = uiState.showGrid,
                onCheckedChange = onToggleGrid
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Telemetry Overlay Switch
            SettingsSwitchRow(
                icon = Icons.Rounded.Speed,
                title = "Real-time Telemetry HUD",
                subtitle = "Display alignment latency, NNAPI inference time & memory",
                checked = uiState.showTelemetry,
                onCheckedChange = onToggleTelemetry
            )

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color(0x22FFFFFF))
            Spacer(modifier = Modifier.height(16.dp))

            // 4. About Neural Engine
            AboutNeuralEngineCard()
        }
    }
}

@Composable
private fun HardwareTierCard(
    uiState: CameraUiState
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceElevated)
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                tint = GoldAccent,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hardware Processing Tier",
                    color = WhiteMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = uiState.hardwareTier.displayName,
                    color = GoldAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = uiState.hardwareTier.description,
                    color = WhiteSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = WhiteSecondary,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    color = White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = WhiteMuted,
                    fontSize = 11.sp
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GoldAccent,
                checkedTrackColor = Color(0x66FFC107),
                uncheckedThumbColor = WhiteMuted,
                uncheckedTrackColor = DarkSurfaceElevated
            )
        )
    }
}

@Composable
private fun AboutNeuralEngineCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceElevated)
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = GoldAccent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "LumaCore Neural Engine v1.0",
                color = White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "High-performance computational photography engine running ONNX Runtime NNAPI bilateral grid slicing and native C++ multi-frame raw alignment.",
            color = WhiteSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}
