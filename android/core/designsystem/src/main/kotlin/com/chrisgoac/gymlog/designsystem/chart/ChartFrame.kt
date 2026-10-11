package com.chrisgoac.gymlog.designsystem.chart

import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroColors
import com.chrisgoac.gymlog.designsystem.theme.HierroShape
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme

/** Localized strings of a chart (the design system ships no resources; the feature passes them in). */
@Immutable
data class ChartText(
    val showTable: String,
    val hideTable: String,
    val xHeader: String,
    val yHeader: String,
    val empty: String,
)

/** Past this many points the plot is no longer explorable point by point; the table still lists every one. */
const val MAX_POINT_NODES = 60

/**
 * Shared frame of [LineChart] and [BarChart]: bordered plot with bounded y labels, a polite-live
 * readout of the focused point, per-point semantic nodes (touch exploration, D-pad and keyboard focus
 * with a visible ring) and a toggleable table built from the SAME [points]. Colours come only from
 * the Hierro theme (ink on chalk / chalk on dark: high contrast); no information is colour-only
 * because the selected point is also enlarged and read out as text.
 */
@Composable
internal fun ChartFrame(
    points: List<ChartPoint>,
    domain: ChartDomain,
    contentDescription: String,
    formatValue: (Double) -> String,
    text: ChartText,
    modifier: Modifier,
    plotHeight: Dp,
    draw: DrawScope.(plot: Rect, selected: Int, colors: HierroColors) -> Unit,
) {
    val data = remember(points) { ChartMath.clean(points) }
    if (data.isEmpty()) {
        HierroText(text.empty, modifier, color = HierroTheme.colors.textMuted)
        return
    }
    val colors = HierroTheme.colors
    var selected by remember(data) { mutableIntStateOf(-1) }
    var tableShown by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.height(plotHeight).widthIn(min = 32.dp).clearAndSetSemantics {},
                verticalArrangement = if (domain.flat) Arrangement.Center else Arrangement.SpaceBetween,
            ) {
                if (domain.flat) {
                    HierroText(formatValue(data.first().value), style = HierroTheme.typography.label)
                } else {
                    HierroText(formatValue(domain.max), style = HierroTheme.typography.label)
                    HierroText(formatValue(domain.min), style = HierroTheme.typography.label)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(plotHeight)
                    .border(HierroShape.border, colors.border)
                    .semantics { this.contentDescription = contentDescription },
            ) {
                Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                    draw(Rect(0f, 0f, size.width, size.height), selected, colors)
                }
                if (data.size <= MAX_POINT_NODES) {
                    Row(Modifier.fillMaxSize()) {
                        data.forEachIndexed { index, point ->
                            PointNode(
                                description = point.spoken(formatValue),
                                isSelected = selected == index,
                                onSelect = { selected = index },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
            HierroText(data.first().label, Modifier.weight(1f), HierroTheme.typography.label, colors.textMuted)
            if (data.size > 1) HierroText(data.last().label, Modifier.weight(1f), HierroTheme.typography.label, colors.textMuted, textAlign = TextAlign.End)
        }
        val shown = data.getOrNull(selected)
        if (shown != null) {
            HierroText(
                shown.spoken(formatValue),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                HierroTheme.typography.title,
            )
        }
        HierroButton(
            if (tableShown) text.hideTable else text.showTable,
            onClick = { tableShown = !tableShown },
            variant = HierroButtonVariant.Secondary,
        )
        if (tableShown) ChartTable(data, text, formatValue)
    }
}

@Composable
private fun PointNode(description: String, isSelected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring = HierroTheme.colors.ink
    Box(
        modifier
            .onFocusChanged { if (it.isFocused) onSelect() }
            .then(if (focused) Modifier.border(3.dp, ring) else Modifier)
            .selectable(selected = isSelected, interactionSource = interaction, indication = null, onClick = onSelect)
            .semantics { contentDescription = description },
    )
}

/** The text alternative: every point of the chart as a row, readable row by row by TalkBack. */
@Composable
private fun ChartTable(points: List<ChartPoint>, text: ChartText, formatValue: (Double) -> String) {
    val colors = HierroTheme.colors
    Column(Modifier.fillMaxWidth().border(HierroShape.border, colors.border)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HierroText(text.xHeader.uppercase(), Modifier.weight(1f).semantics { heading() }, HierroTheme.typography.label, colors.textMuted)
            HierroText(text.yHeader.uppercase(), Modifier.weight(1f), HierroTheme.typography.label, colors.textMuted, textAlign = TextAlign.End)
        }
        points.forEach { point ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics(mergeDescendants = true) { contentDescription = point.spoken(formatValue) },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HierroText(point.label, Modifier.weight(1f))
                HierroText(
                    formatValue(point.value) + (point.detail?.let { " ($it)" } ?: ""),
                    Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
