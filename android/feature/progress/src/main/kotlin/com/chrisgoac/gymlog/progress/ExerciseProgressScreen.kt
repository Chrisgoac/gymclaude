package com.chrisgoac.gymlog.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrisgoac.gymlog.designsystem.chart.ChartPoint
import com.chrisgoac.gymlog.designsystem.chart.LineChart
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroErrorState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.feature.progress.R

/** Per-exercise drill-down: records, 1RM and a line series of the chosen metric with its table alternative. */
@Composable
fun ExerciseProgressScreen(
    viewModel: ExerciseProgressViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExerciseProgressContent(state, viewModel::onPeriod, viewModel::onGym, viewModel::onMetric, viewModel::retry, onBack, modifier)
}

@Composable
internal fun ExerciseProgressContent(
    state: ExerciseProgressUiState,
    onPeriod: (Period) -> Unit,
    onGym: (GymFilter) -> Unit,
    onMetric: (ProgressMetric) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(state.exerciseName ?: stringResource(R.string.progress_exercise_title)) {
            HierroHeaderAction(stringResource(R.string.progress_back), onBack)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "period") { PeriodRow(state.period, onPeriod) }
            item(key = "gym") { GymRow(state.gyms, state.gym, onGym) }
            item(key = "metric") {
                ProgressChoiceRow(
                    caption = stringResource(R.string.progress_filter_metric),
                    options = ProgressMetric.entries,
                    selected = state.metric,
                    labelOf = { metricLabel(it) },
                    onSelect = onMetric,
                )
            }
            when (state.status) {
                ProgressStatus.Loading -> item(key = "loading") { HierroLoadingState(stringResource(R.string.progress_loading)) }
                ProgressStatus.Failed -> item(key = "failed") {
                    HierroErrorState(
                        message = stringResource(R.string.progress_error_message),
                        retryLabel = stringResource(R.string.progress_retry),
                        onRetry = onRetry,
                        title = stringResource(R.string.progress_error_title),
                    )
                }
                ProgressStatus.Ready -> {
                    val records = state.records
                    if (records == null) {
                        item(key = "empty") { HierroEmptyState(stringResource(R.string.progress_exercise_title), stringResource(R.string.progress_exercise_empty)) }
                    } else {
                        item(key = "records-heading") { SectionHeading(stringResource(R.string.progress_exercise_records)) }
                        item(key = "records") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatRow(stringResource(R.string.progress_exercise_max_weight), stringResource(R.string.progress_exercise_kg, ProgressFormat.kg(records.maxWeight)))
                                StatRow(
                                    stringResource(R.string.progress_exercise_best_1rm),
                                    stringResource(R.string.progress_exercise_kg, ProgressFormat.kg(state.estimatedOneRepMax ?: records.bestOneRepMax)),
                                )
                            }
                        }
                        item(key = "chart-heading") { SectionHeading(metricTitle(state.metric)) }
                        item(key = "chart") { SeriesChart(state) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesChart(state: ExerciseProgressUiState) {
    val zone = state.clock?.zone ?: java.time.ZoneId.systemDefault()
    val points = state.series.map { p ->
        ChartPoint(
            label = ProgressFormat.shortDate(p.at, zone),
            value = when (state.metric) {
                ProgressMetric.ONE_REP_MAX -> p.bestOneRepMax
                ProgressMetric.MAX_WEIGHT -> p.maxWeight
                ProgressMetric.VOLUME -> p.volume
            },
        )
    }
    val format: (Double) -> String = if (state.metric == ProgressMetric.VOLUME) ProgressFormat::volume else { value -> ProgressFormat.kg(value) }
    if (points.size < 2) {
        HierroText(stringResource(R.string.progress_exercise_one_point), color = HierroTheme.colors.textMuted)
    }
    LineChart(
        points = points,
        contentDescription = chartSummary(metricTitle(state.metric), points, format),
        formatValue = format,
        text = chartText(),
    )
}

@Composable
private fun metricLabel(metric: ProgressMetric) = stringResource(
    when (metric) {
        ProgressMetric.ONE_REP_MAX -> R.string.progress_metric_1rm
        ProgressMetric.MAX_WEIGHT -> R.string.progress_metric_weight
        ProgressMetric.VOLUME -> R.string.progress_metric_volume
    },
)

@Composable
private fun metricTitle(metric: ProgressMetric) = stringResource(
    when (metric) {
        ProgressMetric.ONE_REP_MAX -> R.string.progress_metric_1rm_title
        ProgressMetric.MAX_WEIGHT -> R.string.progress_metric_weight_title
        ProgressMetric.VOLUME -> R.string.progress_metric_volume_title
    },
)
