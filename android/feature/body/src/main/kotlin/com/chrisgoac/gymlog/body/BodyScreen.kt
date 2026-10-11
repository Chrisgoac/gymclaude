package com.chrisgoac.gymlog.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrisgoac.gymlog.designsystem.chart.ChartMath
import com.chrisgoac.gymlog.designsystem.chart.ChartPoint
import com.chrisgoac.gymlog.designsystem.chart.ChartText
import com.chrisgoac.gymlog.designsystem.chart.ChartTrend
import com.chrisgoac.gymlog.designsystem.chart.LineChart
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroErrorState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.math.WeightUnit
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.feature.body.R

/**
 * Body measurements: pick a metric, add / edit / delete readings, see the latest value, its changes,
 * an accessible chart (with a table alternative) and the history. Progress photos arrive in Plan 5.
 */
@Composable
fun BodyScreen(viewModel: BodyViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BodyContent(
        state = state,
        actions = BodyActions(
            onBack = onBack,
            onMetric = viewModel::onMetric,
            onPeriod = viewModel::onPeriod,
            onRetry = viewModel::retry,
            onAdd = viewModel::onAdd,
            onEdit = viewModel::onEdit,
            onDelete = viewModel::onDelete,
            onConfirmDelete = viewModel::onConfirmDelete,
            onCancelDelete = viewModel::onCancelDelete,
            onDismissFailure = viewModel::onDismissFailure,
            onEditorValue = viewModel::onEditorValue,
            onEditorDate = viewModel::onEditorDate,
            onEditorSave = viewModel::onSave,
            onEditorCancel = viewModel::onEditorCancel,
            onNewMetric = viewModel::onNewMetric,
            onCustomName = viewModel::onCustomName,
            onCustomUnit = viewModel::onCustomUnit,
            onCustomSave = viewModel::onCustomSave,
            onCustomCancel = viewModel::onCustomCancel,
        ),
        modifier = modifier,
    )
}

internal class BodyActions(
    val onBack: () -> Unit,
    val onMetric: (String) -> Unit,
    val onPeriod: (Period) -> Unit,
    val onRetry: () -> Unit,
    val onAdd: () -> Unit,
    val onEdit: (com.chrisgoac.gymlog.domain.body.BodyMeasurement) -> Unit,
    val onDelete: (com.chrisgoac.gymlog.domain.body.BodyMeasurement) -> Unit,
    val onConfirmDelete: () -> Unit,
    val onCancelDelete: () -> Unit,
    val onDismissFailure: () -> Unit,
    val onEditorValue: (String) -> Unit,
    val onEditorDate: (java.time.LocalDate) -> Unit,
    val onEditorSave: () -> Unit,
    val onEditorCancel: () -> Unit,
    val onNewMetric: () -> Unit,
    val onCustomName: (String) -> Unit,
    val onCustomUnit: (String) -> Unit,
    val onCustomSave: () -> Unit,
    val onCustomCancel: () -> Unit,
)

@Composable
internal fun BodyContent(state: BodyUiState, actions: BodyActions, modifier: Modifier = Modifier) {
    val editor = state.editor
    val clock = state.clock
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.body_title)) {
            HierroHeaderAction(stringResource(R.string.body_back), actions.onBack)
        }
        if (editor != null && clock != null) {
            BodyEntryEditor(state, editor, clock.today, actions.onEditorValue, actions.onEditorDate, actions.onEditorSave, actions.onEditorCancel)
        } else {
            BodyList(state, actions)
        }
    }
    state.customDialog?.let { CustomMetricDialog(it, actions.onCustomName, actions.onCustomUnit, actions.onCustomSave, actions.onCustomCancel) }
    val pending = state.pendingDelete
    if (pending != null && clock != null) DeleteDialog(pending, state.selected, state.weightUnit, clock.zone, actions.onConfirmDelete, actions.onCancelDelete)
}

@Composable
private fun BodyList(state: BodyUiState, actions: BodyActions) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "metric") { MetricRow(state.options, state.selected, actions.onMetric, actions.onNewMetric) }
        item(key = "period") { PeriodRow(state.period, actions.onPeriod) }
        item(key = "add") {
            HierroButton(stringResource(R.string.body_add), actions.onAdd, Modifier.fillMaxWidth(), enabled = state.status == BodyStatus.Ready || state.status == BodyStatus.Loading)
        }
        if (state.actionFailed) item(key = "failure") { FailureNotice(actions.onDismissFailure) }
        val clock = state.clock
        when {
            state.status == BodyStatus.Failed -> item(key = "failed") {
                HierroErrorState(
                    message = stringResource(R.string.body_error_message),
                    retryLabel = stringResource(R.string.body_retry),
                    onRetry = actions.onRetry,
                    title = stringResource(R.string.body_error_title),
                )
            }
            state.status == BodyStatus.Loading || clock == null -> item(key = "loading") { HierroLoadingState(stringResource(R.string.body_loading)) }
            state.history.isEmpty() -> item(key = "empty") {
                HierroEmptyState(
                    stringResource(R.string.body_empty_title, state.selected.label.lowercase()),
                    stringResource(R.string.body_empty_message),
                )
            }
            else -> {
                summarySection(state)
                chartSection(state, clock)
                historySection(state, clock, actions)
            }
        }
        photosSection()
    }
}

@Composable
private fun FailureNotice(onDismiss: () -> Unit) {
    HierroCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        HierroText(stringResource(R.string.body_action_failed), color = HierroTheme.colors.error)
        HierroButton(stringResource(R.string.body_action_dismiss), onDismiss, variant = HierroButtonVariant.Secondary)
    }
}

private fun LazyListScope.summarySection(state: BodyUiState) {
    val summary = state.summary ?: return
    item(key = "summary-heading") { SectionHeading(stringResource(R.string.body_section_summary)) }
    item(key = "summary") {
        val def = state.selected
        val unit: WeightUnit = state.weightUnit
        val zone = state.clock!!.zone
        val resources = LocalContext.current.resources
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatRow(
                label = stringResource(R.string.body_stat_latest),
                value = BodyFormat.valueWithUnit(def, summary.latest.value, unit),
                spokenValue = BodyFormat.spokenValue(def, summary.latest.value, unit, resources),
                supporting = BodyFormat.longDate(summary.latest.at, zone),
            )
            val (previous, previousSpoken) = deltaText(summary.previousDelta, def, unit)
            StatRow(stringResource(R.string.body_stat_previous), previous, previousSpoken)
            val (period, periodSpoken) = deltaText(summary.periodDelta, def, unit)
            StatRow(
                label = stringResource(R.string.body_stat_period),
                value = period,
                spokenValue = periodSpoken,
                supporting = summary.periodFirst?.takeIf { summary.periodDelta != null }?.let {
                    stringResource(R.string.body_stat_period_start) + ": " + BodyFormat.spokenValue(def, it, unit, resources)
                },
            )
        }
    }
}

private fun LazyListScope.chartSection(state: BodyUiState, clock: BodyClock) {
    item(key = "chart-heading") { SectionHeading(stringResource(R.string.body_section_chart)) }
    item(key = "chart") {
        val def = state.selected
        val unit = state.weightUnit
        val resources = LocalContext.current.resources
        val points = state.series.map { ChartPoint(BodyFormat.shortDate(it.at, clock.zone), BodyFormat.display(def, it.value, unit)) }
        val visible: (Double) -> String = { value -> BodyFormat.number(value).let { n -> BodyFormat.unitSymbol(def, unit).let { s -> if (s.isEmpty()) n else "$n $s" } } }
        val spoken: (Double) -> String = { value ->
            BodyFormat.number(value).let { n -> BodyFormat.spokenUnit(BodyFormat.unitSymbol(def, unit), resources).let { s -> if (s.isEmpty()) n else "$n $s" } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (points.size) {
                0 -> HierroText(stringResource(R.string.body_chart_period_empty), color = HierroTheme.colors.textMuted)
                1 -> HierroText(stringResource(R.string.body_chart_one_point), color = HierroTheme.colors.textMuted)
            }
            LineChart(
                points = points,
                contentDescription = chartSummary(stringResource(R.string.body_chart_title, def.label), points, spoken),
                formatValue = visible,
                text = ChartText(
                    showTable = stringResource(R.string.body_chart_show_table),
                    hideTable = stringResource(R.string.body_chart_hide_table),
                    xHeader = stringResource(R.string.body_chart_col_date),
                    yHeader = stringResource(R.string.body_chart_col_value),
                    empty = stringResource(R.string.body_chart_empty),
                ),
            )
        }
    }
}

/** Spoken summary (min / max / last / trend) from the same points the canvas and the table use. */
@Composable
private fun chartSummary(title: String, points: List<ChartPoint>, format: (Double) -> String): String {
    val stats = ChartMath.stats(points) ?: return title
    if (stats.count == 1) return stringResource(R.string.body_chart_summary_one, title, format(stats.last.value), stats.last.label)
    val trend = stringResource(
        when (stats.trend) {
            ChartTrend.Up -> R.string.body_trend_up
            ChartTrend.Down -> R.string.body_trend_down
            ChartTrend.Flat -> R.string.body_trend_flat
        },
    )
    return stringResource(
        R.string.body_chart_summary,
        title, stats.count,
        format(stats.min.value), stats.min.label,
        format(stats.max.value), stats.max.label,
        format(stats.last.value), stats.last.label,
        trend,
    )
}

private fun LazyListScope.historySection(state: BodyUiState, clock: BodyClock, actions: BodyActions) {
    item(key = "history-heading") { SectionHeading(stringResource(R.string.body_section_history)) }
    items(state.history, key = { "row-${it.id}" }) { measurement ->
        HistoryRow(
            measurement, state.selected, state.weightUnit, clock.zone,
            onEdit = { actions.onEdit(measurement) },
            onDelete = { actions.onDelete(measurement) },
        )
    }
}

/** Progress photos need the media capability (Plan 5): the action is visible but disabled, with the reason spoken. */
private fun LazyListScope.photosSection() {
    item(key = "photos-heading") { SectionHeading(stringResource(R.string.body_section_photos)) }
    item(key = "photos") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HierroButton(
                text = stringResource(R.string.body_photos_action),
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                variant = HierroButtonVariant.Secondary,
                enabled = false,
                contentDescription = stringResource(R.string.body_photos_description),
            )
            HierroText(stringResource(R.string.body_photos_soon), color = HierroTheme.colors.textMuted)
        }
    }
}
