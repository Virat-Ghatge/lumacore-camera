package com.lumacore.camera.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumacore.camera.ui.theme.DarkSurface
import com.lumacore.camera.ui.theme.DarkSurfaceElevated
import com.lumacore.camera.ui.theme.GoldAccent
import com.lumacore.camera.ui.theme.TranslucentPillBackground
import com.lumacore.camera.ui.theme.White
import com.lumacore.camera.ui.theme.WhiteMuted
import kotlin.math.abs

enum class ProControlCategory(val label: String) {
    ISO("ISO"),
    SHUTTER("SS"),
    FOCUS("FOCUS"),
    WB("WB"),
    PEAK("PEAK"),
    HIST("HIST")
}

private data class ShutterOption(val label: String, val ns: Long?)
private data class FocusOption(val label: String, val distance: Float?)
private data class WbOption(val label: String, val kelvin: Int?)

/**
 * Stock-camera style manual Pro controls deck.
 * Features an upper category bar and a lower granular parameter selection reel.
 */
@Composable
fun ProControlBar(
    manualIso: Int?,
    manualShutterSpeedNs: Long?,
    manualFocusDistance: Float?,
    manualWhiteBalanceKelvin: Int?,
    isFocusPeakingEnabled: Boolean,
    isHistogramEnabled: Boolean,
    onIsoSelected: (Int?) -> Unit,
    onShutterSpeedSelected: (Long?) -> Unit,
    onFocusDistanceChanged: (Float?) -> Unit,
    onWhiteBalanceKelvinChanged: (Int?) -> Unit,
    onToggleFocusPeaking: () -> Unit,
    onToggleHistogram: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(ProControlCategory.ISO) }
    val valueScrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Parameter Value Selector Reel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.Center
        ) {
            when (selectedCategory) {
                ProControlCategory.ISO -> {
                    val isoOptions: List<Int?> = listOf(null, 50, 100, 200, 400, 800, 1600, 3200)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(valueScrollState)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        isoOptions.forEach { iso ->
                            val isSelected = manualIso == iso
                            val label = iso?.toString() ?: "AUTO"
                            ProValuePill(
                                label = label,
                                isSelected = isSelected,
                                onClick = { onIsoSelected(iso) }
                            )
                        }
                    }
                }

                ProControlCategory.SHUTTER -> {
                    val shutterOptions = listOf(
                        ShutterOption("AUTO", null),
                        ShutterOption("1/4000", 250_000L),
                        ShutterOption("1/2000", 500_000L),
                        ShutterOption("1/1000", 1_000_000L),
                        ShutterOption("1/500", 2_000_000L),
                        ShutterOption("1/250", 4_000_000L),
                        ShutterOption("1/125", 8_000_000L),
                        ShutterOption("1/60", 16_666_666L),
                        ShutterOption("1/30", 33_333_333L),
                        ShutterOption("1/15", 66_666_666L),
                        ShutterOption("1/4", 250_000_000L),
                        ShutterOption("1s", 1_000_000_000L),
                        ShutterOption("2s", 2_000_000_000L)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(valueScrollState)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        shutterOptions.forEach { opt ->
                            val isSelected = manualShutterSpeedNs == opt.ns
                            ProValuePill(
                                label = opt.label,
                                isSelected = isSelected,
                                onClick = { onShutterSpeedSelected(opt.ns) }
                            )
                        }
                    }
                }

                ProControlCategory.FOCUS -> {
                    val focusOptions = listOf(
                        FocusOption("AUTO", null),
                        FocusOption("INF", 0.0f),
                        FocusOption("0.2", 0.2f),
                        FocusOption("0.4", 0.4f),
                        FocusOption("0.6", 0.6f),
                        FocusOption("0.8", 0.8f),
                        FocusOption("MACRO", 1.0f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(valueScrollState)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        focusOptions.forEach { opt ->
                            val isSelected = if (opt.distance == null) {
                                manualFocusDistance == null
                            } else {
                                manualFocusDistance != null && abs(manualFocusDistance - opt.distance) < 0.05f
                            }
                            ProValuePill(
                                label = opt.label,
                                isSelected = isSelected,
                                onClick = { onFocusDistanceChanged(opt.distance) }
                            )
                        }
                    }
                }

                ProControlCategory.WB -> {
                    val wbOptions = listOf(
                        WbOption("AUTO", null),
                        WbOption("2800K", 2800),
                        WbOption("3200K", 3200),
                        WbOption("4000K", 4000),
                        WbOption("5000K", 5000),
                        WbOption("5500K", 5500),
                        WbOption("6500K", 6500),
                        WbOption("8000K", 8000)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(valueScrollState)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        wbOptions.forEach { opt ->
                            val isSelected = manualWhiteBalanceKelvin == opt.kelvin
                            ProValuePill(
                                label = opt.label,
                                isSelected = isSelected,
                                onClick = { onWhiteBalanceKelvinChanged(opt.kelvin) }
                            )
                        }
                    }
                }

                ProControlCategory.PEAK -> {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProValuePill(
                            label = if (isFocusPeakingEnabled) "PEAKING: ON" else "PEAKING: OFF",
                            isSelected = isFocusPeakingEnabled,
                            onClick = onToggleFocusPeaking
                        )
                    }
                }

                ProControlCategory.HIST -> {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProValuePill(
                            label = if (isHistogramEnabled) "HISTOGRAM: ON" else "HISTOGRAM: OFF",
                            isSelected = isHistogramEnabled,
                            onClick = onToggleHistogram
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 2. Primary Category Selector Tabs Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProControlCategory.values().forEach { cat ->
                val isSelected = selectedCategory == cat
                val subText = when (cat) {
                    ProControlCategory.ISO -> manualIso?.toString() ?: "AUTO"
                    ProControlCategory.SHUTTER -> if (manualShutterSpeedNs != null) {
                        val denom = (1_000_000_000L / manualShutterSpeedNs).toInt()
                        if (denom > 0) "1/$denom" else "1s"
                    } else "AUTO"
                    ProControlCategory.FOCUS -> if (manualFocusDistance != null) "${(manualFocusDistance * 10).toInt() / 10f}" else "AUTO"
                    ProControlCategory.WB -> if (manualWhiteBalanceKelvin != null) "${manualWhiteBalanceKelvin}K" else "AUTO"
                    ProControlCategory.PEAK -> if (isFocusPeakingEnabled) "ON" else "OFF"
                    ProControlCategory.HIST -> if (isHistogramEnabled) "ON" else "OFF"
                }

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = GoldAccent),
                            onClick = { selectedCategory = cat }
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = cat.label,
                        color = if (isSelected) GoldAccent else WhiteMuted,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        text = subText,
                        color = if (isSelected) White else Color(0x88FFFFFF),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun ProValuePill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (isSelected) GoldAccent else DarkSurfaceElevated)
            .border(
                width = 0.75.dp,
                color = if (isSelected) GoldAccent else Color(0x33FFFFFF),
                shape = RoundedCornerShape(15.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = GoldAccent),
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.Black else White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
