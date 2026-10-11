package com.chrisgoac.gymlog.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrisgoac.gymlog.designsystem.chart.BarChart
import com.chrisgoac.gymlog.designsystem.chart.ChartPoint
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroErrorState
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroShape
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.stats.BalanceRow
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.stats.RecordKind
import com.chrisgoac.gymlog.domain.stats.Stall
import com.chrisgoac.gymlog.domain.stats.StatsFilter
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.domain.time.Periods
import com.chrisgoac.gymlog.feature.progress.R
import java.time.Instant

/**
 * Progress home: filters, this week, selected period, weekly volume chart, stalls with suggestions,
 * per-exercise drill-down entry, muscle balance and the Pro-gated intelligent report action.
 * [onOpenReport] null = no report destination yet (Plan 5): the action then stays disabled.
 */
@Composable
fun ProgressScreen(
    viewModel: ProgressViewModel,
    onOpenExercise: (exerciseId: String, filter: StatsFilter) -> Unit,
    onOpenReport: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProgressContent(
        state = state,
        onPeriod = viewModel::onPeriod,
        onGym = viewModel::onGym,
        onQuery = viewModel::onExerciseQuery,
        onRetry = viewModel::retry,
        onOpenExercise = { id -> onOpenExercise(id, StatsFilter(state.period, state.gym)) },
        onOpenReport = onOpenReport,
        modifier = modifier,
    )
}

@Composable
internal fun ProgressContent(
    state: ProgressUiState,
    onPeriod: (Period) -> Unit,
    onGym: (GymFilter) -> Unit,
    onQuery: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onOpenReport: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.progress_title))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "period") { PeriodRow(state.period, onPeriod) }
            item(key = "gym") { GymRow(state.gyms, state.gym, onGym) }
            val data = state.data
            val clock = state.clock
            when {
                state.status == ProgressStatus.Failed -> item(key = "failed") {
                    HierroErrorState(
                        message = stringResource(R.string.progress_error_message),
                        retryLabel = stringResource(R.string.progress_retry),
                        onRetry = onRetry,
                        title = stringResource(R.string.progress_error_title),
                    )
                }
                data == null || clock == null -> item(key = "loading") { HierroLoadingState(stringResource(R.string.progress_loading)) }
                else -> {
                    if (data.isEmpty) {
                        item(key = "empty") { HierroEmptyState(stringResource(R.string.progress_empty_title), stringResource(R.string.progress_empty_message)) }
                    } else {
                        weekSection(data, onOpenExercise)
                        periodSection(data, clock)
                        stallsSection(data, clock, onOpenExercise)
                        balanceSection(data.balance)
                    }
                }
            }
            exerciseSearchSection(state, onQuery, onOpenExercise)
            item(key = "report") { ReportAction(state.isPro, onOpenReport) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.weekSection(data: ProgressData, onOpenExercise: (String) -> Unit) {
    item(key = "week-heading") { SectionHeading(stringResource(R.string.progress_section_week)) }
    item(key = "week-cards") {
        val week = data.weekly.summary
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatRow(
                stringResource(R.string.progress_stat_sessions_goal),
                stringResource(R.string.progress_stat_sessions_goal_value, week.sessions, data.streak.weeklyTarget),
            )
            StatRow(stringResource(R.string.progress_stat_records), data.weekly.records.size.toString())
            val delta = week.deltaPct
            StatRow(
                stringResource(R.string.progress_stat_volume_change),
                when {
                    delta == null -> stringResource(R.string.progress_delta_none)
                    delta >= 0 -> stringResource(R.string.progress_delta_up, delta)
                    else -> stringResource(R.string.progress_delta_down, -delta)
                },
                spokenValue = when {
                    delta == null -> stringResource(R.string.progress_delta_none_spoken)
                    delta >= 0 -> stringResource(R.string.progress_delta_up_spoken, delta)
                    else -> stringResource(R.string.progress_delta_down_spoken, -delta)
                },
            )
        }
    }
    if (data.weekly.records.isNotEmpty()) {
        item(key = "records-heading") { SectionHeading(stringResource(R.string.progress_section_records)) }
        items(data.weekly.records, key = { "record-${it.exerciseId}-${it.kind.wire}" }) { record ->
            val kind = stringResource(if (record.kind == RecordKind.WEIGHT) R.string.progress_record_weight else R.string.progress_record_1rm)
            val open = stringResource(R.string.progress_open_exercise, record.name)
            HierroCard(onClick = { onOpenExercise(record.exerciseId) }, contentDescription = "${record.name}. $kind. $open") {
                HierroText(record.name, style = HierroTheme.typography.title)
                HierroText(kind, color = HierroTheme.colors.textMuted)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.periodSection(data: ProgressData, clock: ProgressClock) {
    item(key = "period-heading") { SectionHeading(stringResource(R.string.progress_section_period)) }
    item(key = "period-cards") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatRow(stringResource(R.string.progress_stat_streak_days), data.streak.days.toString())
            StatRow(
                stringResource(R.string.progress_stat_weeks),
                data.streak.weeks.current.toString(),
                supporting = stringResource(R.string.progress_stat_best_weeks, data.streak.weeks.best),
            )
            StatRow(stringResource(R.string.progress_stat_sessions), data.volume.summary.sessions.toString())
            StatRow(
                stringResource(R.string.progress_stat_volume),
                ProgressFormat.volume(data.volume.summary.volume),
                spokenValue = ProgressFormat.volume(data.volume.summary.volume) + " " + stringResource(R.string.progress_volume_unit),
            )
        }
    }
    item(key = "volume-heading") { SectionHeading(stringResource(R.string.progress_section_volume)) }
    item(key = "volume-chart") {
        if (data.volume.weekly.isEmpty()) {
            HierroText(stringResource(R.string.progress_volume_empty), color = HierroTheme.colors.textMuted)
        } else {
            val points = data.volume.weekly.map { week ->
                ChartPoint(
                    label = ProgressFormat.shortDate(week.weekStart, clock.zone),
                    value = week.volume,
                    detail = week.deltaPct?.let { pct ->
                        if (pct >= 0) stringResource(R.string.progress_week_delta_up, pct) else stringResource(R.string.progress_week_delta_down, -pct)
                    },
                )
            }
            val title = stringResource(R.string.progress_volume_chart_title) + " (" + stringResource(R.string.progress_volume_unit) + ")"
            BarChart(
                points = points,
                contentDescription = chartSummary(title, points, ProgressFormat::volume),
                formatValue = ProgressFormat::volume,
                text = chartText(xHeader = stringResource(R.string.progress_chart_col_week)),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.stallsSection(data: ProgressData, clock: ProgressClock, onOpenExercise: (String) -> Unit) {
    item(key = "stalls-heading") { SectionHeading(stringResource(R.string.progress_section_stalls)) }
    if (data.stalls.isEmpty()) {
        item(key = "stalls-empty") { HierroText(stringResource(R.string.progress_stalls_empty), color = HierroTheme.colors.textMuted) }
    } else {
        val advice = data.suggestions.associateBy { it.exerciseId }
        items(data.stalls, key = { "stall-${it.exerciseId}" }) { stall -> StallCard(stall, advice[stall.exerciseId]?.advice, clock, onOpenExercise) }
    }
}

@Composable
private fun StallCard(stall: Stall, advice: String?, clock: ProgressClock, onOpenExercise: (String) -> Unit) {
    val sessions = pluralStringResource(R.plurals.progress_stall_sessions, stall.sessionsWithoutImprovement, stall.sessionsWithoutImprovement)
    val last = stall.lastImprovementAt?.let {
        stringResource(R.string.progress_stall_last_improvement, Periods.formatDaysAgo(Instant.ofEpochMilli(it), Instant.ofEpochMilli(clock.nowMillis), clock.zone))
    } ?: stringResource(R.string.progress_stall_never_improved)
    val suggestion = advice?.let { stringResource(R.string.progress_suggestion, it) }
    val open = stringResource(R.string.progress_open_exercise, stall.name)
    HierroCard(
        onClick = { onOpenExercise(stall.exerciseId) },
        contentDescription = listOfNotNull(stall.name, sessions, last, suggestion, open).joinToString(". "),
    ) {
        HierroText(stall.name, style = HierroTheme.typography.title)
        HierroText(sessions, color = HierroTheme.colors.error)
        HierroText(last, color = HierroTheme.colors.textMuted)
        if (suggestion != null) HierroText(suggestion)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.balanceSection(rows: List<BalanceRow>) {
    item(key = "balance-heading") { SectionHeading(stringResource(R.string.progress_section_balance)) }
    val shown = rows.filter { it.volume > 0.0 || it.daysSinceTrained != null }
    if (shown.isEmpty()) {
        item(key = "balance-empty") { HierroText(stringResource(R.string.progress_balance_empty), color = HierroTheme.colors.textMuted) }
    } else {
        val max = maxOf(shown.maxOf { it.volume }, 1.0)
        items(shown, key = { "balance-${it.group.wire}" }) { row -> BalanceRowItem(row, max) }
    }
}

@Composable
private fun BalanceRowItem(row: BalanceRow, max: Double) {
    val colors = HierroTheme.colors
    val parts = listOfNotNull(
        stringResource(R.string.progress_balance_row, row.group.label, ProgressFormat.number(Math.round(row.volume).toDouble(), 0)),
        row.goalPct?.let { stringResource(R.string.progress_balance_goal, it) },
        row.daysSinceTrained?.let { stringResource(R.string.progress_balance_days_ago, it) } ?: stringResource(R.string.progress_balance_never),
        if (row.neglected) stringResource(R.string.progress_balance_neglected) else null,
    )
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = parts.joinToString(", ") },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HierroText(parts.joinToString(" · "), style = HierroTheme.typography.label, color = if (row.neglected) colors.error else colors.ink)
        Box(Modifier.fillMaxWidth().height(16.dp).border(HierroShape.border, colors.border)) {
            Box(Modifier.fillMaxWidth((row.volume / max).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(colors.accent))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.exerciseSearchSection(
    state: ProgressUiState,
    onQuery: (String) -> Unit,
    onOpenExercise: (String) -> Unit,
) {
    item(key = "exercise-heading") { SectionHeading(stringResource(R.string.progress_section_exercise)) }
    item(key = "exercise-search") { HierroTextField(state.exerciseQuery, onQuery, stringResource(R.string.progress_exercise_search)) }
    if (state.exerciseQuery.isNotBlank() && state.exerciseResults.isEmpty()) {
        item(key = "exercise-none") { HierroText(stringResource(R.string.progress_exercise_no_results), color = HierroTheme.colors.textMuted) }
    }
    items(state.exerciseResults, key = { "exercise-${it.id}" }) { exercise -> ExerciseResult(exercise, onOpenExercise) }
}

@Composable
private fun ExerciseResult(exercise: Exercise, onOpenExercise: (String) -> Unit) {
    HierroCard(onClick = { onOpenExercise(exercise.id) }, contentDescription = "${exercise.name}. ${exercise.muscleGroup.label}") {
        HierroText(exercise.name, style = HierroTheme.typography.title)
        HierroText(exercise.muscleGroup.label, color = HierroTheme.colors.textMuted)
    }
}

/** Intelligent report: visible always; enabled only with Pro and a destination (wired in Plan 5). */
@Composable
internal fun ReportAction(isPro: Boolean, onOpenReport: (() -> Unit)?) {
    val enabled = isPro && onOpenReport != null
    val label = stringResource(if (isPro) R.string.progress_report_action else R.string.progress_report_action_locked)
    val reason = when {
        !isPro -> stringResource(R.string.progress_report_locked_state)
        onOpenReport == null -> stringResource(R.string.progress_report_unavailable_state)
        else -> null
    }
    HierroButton(
        text = label,
        onClick = { onOpenReport?.invoke() },
        modifier = Modifier.fillMaxWidth(),
        variant = HierroButtonVariant.Secondary,
        enabled = enabled,
        contentDescription = if (reason == null) label else "${stringResource(R.string.progress_report_action)}. $reason",
    )
}
