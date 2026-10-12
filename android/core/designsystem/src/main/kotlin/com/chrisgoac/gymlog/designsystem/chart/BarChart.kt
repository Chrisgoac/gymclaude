package com.chrisgoac.gymlog.designsystem.chart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bar chart on a Compose Canvas, anchored at zero (an all-zero series spans 0..1 so nothing divides
 * by zero). Same semantics contract as [LineChart]: summary [contentDescription], one focusable
 * node per bar, and a table toggle listing the same points.
 */
@Composable
fun BarChart(
    points: List<ChartPoint>,
    contentDescription: String,
    formatValue: (Double) -> String,
    text: ChartText,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 180.dp,
) {
    val domain = remember(points) { ChartMath.barDomain(ChartMath.clean(points).map { it.value }) }
    ChartFrame(points, domain, contentDescription, formatValue, text, modifier, plotHeight) { plot, selected, colors ->
        val data = ChartMath.clean(points)
        val inset = 4.dp.toPx()
        val column = plot.width / data.size
        val barWidth = (column * 0.6f).coerceAtLeast(minOf(4.dp.toPx(), column))
        val baseline = plot.top + inset + (1f - ChartMath.fraction(0.0, domain)) * (plot.height - 2 * inset)
        data.forEachIndexed { i, p ->
            val top = plot.top + inset + (1f - ChartMath.fraction(p.value, domain)) * (plot.height - 2 * inset)
            val left = plot.left + column * i + (column - barWidth) / 2
            val topLeft = Offset(left, minOf(top, baseline))
            val size = Size(barWidth, kotlin.math.abs(baseline - top).coerceAtLeast(2.dp.toPx()))
            val isSelected = i == selected
            drawRect(if (isSelected) colors.ink else colors.accent, topLeft, size)
            drawRect(if (isSelected) colors.accent else colors.ink, topLeft, size, style = Stroke(width = (if (isSelected) 4.dp else 2.dp).toPx()))
        }
        drawLine(colors.ink, Offset(plot.left, baseline), Offset(plot.right, baseline), strokeWidth = 2.dp.toPx())
    }
}
