package com.lumacore.camera.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumacore.camera.ui.CameraUiState
import com.lumacore.camera.ui.theme.GoldAccent
import com.lumacore.camera.ui.theme.ReticleYellow
import com.lumacore.camera.ui.theme.TranslucentPillBackground
import com.lumacore.camera.ui.theme.White
import com.lumacore.camera.ui.theme.WhiteMuted
import kotlin.math.roundToInt

@Composable
fun ViewfinderControls(
    uiState: CameraUiState,
    onTapToFocus: (Offset) -> Unit,
    onZoomChange: (Float) -> Unit,
    onExposureCompensationChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val newZoom = (uiState.zoomRatio * zoom).coerceIn(
                        uiState.minZoomRatio,
                        uiState.maxZoomRatio
                    )
                    onZoomChange(newZoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onTapToFocus(offset)
                }
            }
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current

        // 1. Rule of Thirds Grid Overlay
        if (uiState.showGrid) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 1.dp.toPx()
                val gridColor = Color(0x55FFFFFF)

                // Vertical lines at 1/3 and 2/3
                val x1 = size.width / 3f
                val x2 = (size.width * 2f) / 3f
                drawLine(gridColor, Offset(x1, 0f), Offset(x1, size.height), strokeWidth)
                drawLine(gridColor, Offset(x2, 0f), Offset(x2, size.height), strokeWidth)

                // Horizontal lines at 1/3 and 2/3
                val y1 = size.height / 3f
                val y2 = (size.height * 2f) / 3f
                drawLine(gridColor, Offset(0f, y1), Offset(size.width, y1), strokeWidth)
                drawLine(gridColor, Offset(0f, y2), Offset(size.width, y2), strokeWidth)
            }
        }

        // 2. Real-time Telemetry HUD (Top Center)
        if (uiState.showTelemetry) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC101010))
                    .border(0.75.dp, Color(0x44FFC107), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(GoldAccent)
                    )
                    Text(
                        text = uiState.telemetrySummary,
                        color = White,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.3.sp
                    )
                }
            }
        }

        // 3. Focus Reticle with EV Sun Slider
        AnimatedVisibility(
            visible = uiState.showFocusReticle && uiState.focusPoint != null,
            enter = fadeIn(tween(150)) + scaleIn(tween(200, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(300)) + scaleOut(tween(250))
        ) {
            val point = uiState.focusPoint ?: Offset.Zero
            val reticleSizePx = with(density) { 68.dp.toPx() }
            val halfSize = reticleSizePx / 2f
            val xPos = (point.x - halfSize).roundToInt()
            val yPos = (point.y - halfSize).roundToInt()

            Box(
                modifier = Modifier
                    .offset { IntOffset(xPos, yPos) }
                    .size(68.dp)
            ) {
                // Focus square with brackets
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 2.dp.toPx())
                    val cornerLen = 14.dp.toPx()

                    // Top-Left corner
                    drawLine(ReticleYellow, Offset(0f, 0f), Offset(cornerLen, 0f), stroke.width)
                    drawLine(ReticleYellow, Offset(0f, 0f), Offset(0f, cornerLen), stroke.width)

                    // Top-Right corner
                    drawLine(ReticleYellow, Offset(size.width, 0f), Offset(size.width - cornerLen, 0f), stroke.width)
                    drawLine(ReticleYellow, Offset(size.width, 0f), Offset(size.width, cornerLen), stroke.width)

                    // Bottom-Left corner
                    drawLine(ReticleYellow, Offset(0f, size.height), Offset(cornerLen, size.height), stroke.width)
                    drawLine(ReticleYellow, Offset(0f, size.height), Offset(0f, size.height - cornerLen), stroke.width)

                    // Bottom-Right corner
                    drawLine(ReticleYellow, Offset(size.width, size.height), Offset(size.width - cornerLen, size.height), stroke.width)
                    drawLine(ReticleYellow, Offset(size.width, size.height), Offset(size.width, size.height - cornerLen), stroke.width)
                }

                // Vertical EV sun icon on right side
                Icon(
                    imageVector = Icons.Rounded.WbSunny,
                    contentDescription = "Exposure Value Slider",
                    tint = ReticleYellow,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = 28.dp)
                        .size(18.dp)
                )
            }
        }

        // 4. Quick Zoom Pills Bar (Centered above Bottom Controls)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(TranslucentPillBackground)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val zoomLevels = listOf(1.0f, 2.0f)
            zoomLevels.forEach { zoom ->
                val isSelected = (uiState.zoomRatio - zoom).let { kotlin.math.abs(it) < 0.15f }
                val animatedScale by animateFloatAsState(
                    targetValue = if (isSelected) 1.05f else 1.0f,
                    label = "zoom_scale"
                )

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) GoldAccent else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = GoldAccent),
                            onClick = { onZoomChange(zoom) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${zoom.toInt()}x",
                        color = if (isSelected) Color.Black else White,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
