package com.chrisgoac.gymlog.designsystem.chart

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line chart on a Compose Canvas. The y axis is bounded by the data (min..max, labelled); equal
 * values draw a flat line in the middle, one point only its marker. [contentDescription] is the
 * spoken summary (min / max / last / trend); each point is a focusable semantic node and the same
 * points are listed by the table toggle. See [ChartFrame].
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    contentDescription: String,
    formatValue: (Double) -> String,
    text: ChartText,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 180.dp,
) {
    val domain = remember(points) { ChartMath.lineDomain(ChartMath.clean(points).map { it.value }) }
    ChartFrame(points, domain, contentDescription, formatValue, text, modifier, plotHeight) { plot, selected, colors ->
        val data = ChartMath.clean(points)
        val inset = 12.dp.toPx()
        fun x(i: Int) = plot.left + plot.width * ChartMath.xFraction(i, data.size)
        fun y(value: Double) = plot.top + inset + (1f - ChartMath.fraction(value, domain)) * (plot.height - 2 * inset)

        // Reference line at the middle of the range.
        drawLine(colors.textMuted, Offset(plot.left, plot.center.y), Offset(plot.right, plot.center.y), strokeWidth = 1.dp.toPx())
        if (data.size > 1) {
            val path = Path()
            data.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(i), y(p.value)) else path.lineTo(x(i), y(p.value)) }
            drawPath(path, colors.ink, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter))
        }
        if (data.size <= MAX_POINT_NODES) {
            val marker = 8.dp.toPx()
            data.forEachIndexed { i, p ->
                if (i != selected) drawRect(colors.ink, Offset(x(i) - marker / 2, y(p.value) - marker / 2), Size(marker, marker))
            }
        }
        data.getOrNull(selected)?.let { p ->
            // Selected: guide line, big accent square with an ink border (shape + size, not colour alone).
            drawLine(colors.ink, Offset(x(selected), plot.top), Offset(x(selected), plot.bottom), strokeWidth = 2.dp.toPx())
            val big = 16.dp.toPx()
            val topLeft = Offset(x(selected) - big / 2, y(p.value) - big / 2)
            drawRect(colors.accent, topLeft, Size(big, big))
            drawRect(colors.ink, topLeft, Size(big, big), style = Stroke(width = 3.dp.toPx()))
        }
    }
}
