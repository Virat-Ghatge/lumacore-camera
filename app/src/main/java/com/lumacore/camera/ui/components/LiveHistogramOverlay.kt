package com.lumacore.camera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumacore.camera.ui.theme.GoldAccent
import com.lumacore.camera.ui.theme.WhiteMuted

/**
 * Real-time 256-bin luma histogram overlay for PRO mode viewfinder.
 * Draws an anti-aliased gradient curve representing luminance distribution.
 */
@Composable
fun LiveHistogramOverlay(
    histogramData: IntArray?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(136.dp)
            .height(68.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC0D0E11))
            .border(0.75.dp, Color(0x55FFC107), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "LUMA HISTOGRAM",
                color = GoldAccent,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 2.dp, bottom = 2.dp)
            ) {
                val w = size.width
                val h = size.height

                // Synthesize fallback smooth curve if live data not populated
                val bins = if (histogramData != null && histogramData.size == 256) {
                    histogramData
                } else {
                    IntArray(256) { idx ->
                        val x = (idx - 110) / 45.0
                        (Math.exp(-0.5 * x * x) * 1000).toInt()
                    }
                }

                var maxVal = 1
                for (v in bins) {
                    if (v > maxVal) maxVal = v
                }

                val strokePath = Path()
                val fillPath = Path()

                fillPath.moveTo(0f, h)

                val step = w / 255f
                for (i in 0 until 256) {
                    val px = i * step
                    val normalized = (bins[i].toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
                    val py = h - (normalized * (h - 2f))
                    if (i == 0) {
                        strokePath.moveTo(px, py)
                        fillPath.lineTo(px, py)
                    } else {
                        strokePath.lineTo(px, py)
                        fillPath.lineTo(px, py)
                    }
                }

                fillPath.lineTo(w, h)
                fillPath.close()

                // Draw gradient filled area
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            GoldAccent.copy(alpha = 0.45f),
                            Color(0x00FFC107)
                        ),
                        startY = 0f,
                        endY = h
                    )
                )

                // Draw curve border
                drawPath(
                    path = strokePath,
                    color = GoldAccent,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
}
