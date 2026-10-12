package com.chrisgoac.gymlog.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroErrorState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.workout.HistoryItem
import com.chrisgoac.gymlog.domain.workout.HistoryPeriod
import com.chrisgoac.gymlog.domain.workout.SetValidation
import com.chrisgoac.gymlog.feature.history.R
import java.text.DateFormat
import java.util.Date

/** Finished workouts, newest first, with period / gym / text filters and the sync state. */
@Composable
fun HistoryListScreen(
    viewModel: HistoryListViewModel,
    onOpen: (String) -> Unit,
    onSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    HistoryListContent(
        state = state,
        onQuery = viewModel::onQuery,
        onPeriod = viewModel::onPeriod,
        onGym = viewModel::onGym,
        onClearFilters = viewModel::clearFilters,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onOpen = onOpen,
        onSettings = onSettings,
        modifier = modifier,
    )
}

@Composable
internal fun HistoryListContent(
    state: HistoryListUiState,
    onQuery: (String) -> Unit,
    onPeriod: (HistoryPeriod) -> Unit,
    onGym: (String?) -> Unit,
    onClearFilters: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.history_title)) {
            if (onSettings != null) HierroHeaderAction(stringResource(R.string.history_settings), onSettings)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "sync") { SyncIndicator(state.sync) }
            item(key = "search") { HierroTextField(state.filter.query, onQuery, stringResource(R.string.history_search_label)) }
            item(key = "period") {
                HistoryChoiceRow(
                    caption = stringResource(R.string.history_filter_period),
                    options = HistoryPeriod.entries,
                    selected = state.filter.period,
                    labelOf = { periodLabel(it) },
                    onSelect = onPeriod,
                )
            }
            if (state.gyms.isNotEmpty()) {
                item(key = "gym") {
                    HistoryChoiceRow(
                        caption = stringResource(R.string.history_filter_gym),
                        options = listOf<String?>(null) + state.gyms.map { it.id },
                        selected = state.filter.gymId,
                        labelOf = { id -> if (id == null) stringResource(R.string.history_gym_all) else state.gyms.firstOrNull { it.id == id }?.name.orEmpty() },
                        onSelect = onGym,
                    )
                }
            }
            when (state.status) {
                HistoryLoadStatus.Loading -> item(key = "loading") { HierroLoadingState(stringResource(R.string.history_loading)) }
                HistoryLoadStatus.Failed -> item(key = "failed") {
                    HierroErrorState(
                        message = stringResource(R.string.history_error_message),
                        retryLabel = stringResource(R.string.history_retry),
                        onRetry = onRetry,
                        title = stringResource(R.string.history_error_title),
                    )
                }
                HistoryLoadStatus.Ready -> {
                    if (state.items.isEmpty()) {
                        item(key = "empty") {
                            if (state.filterActive) {
                                HierroEmptyState(
                                    stringResource(R.string.history_no_results_title),
                                    stringResource(R.string.history_no_results_message),
                                    actionLabel = stringResource(R.string.history_clear_filters),
                                    onAction = onClearFilters,
                                )
                            } else {
                                HierroEmptyState(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_message))
                            }
                        }
                    }
                    items(state.items, key = { it.sessionId }) { item -> HistoryRow(item, onOpen) }
                    if (state.hasMore) {
                        item(key = "more") {
                            HierroButton(stringResource(R.string.history_load_more), onLoadMore, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun periodLabel(period: HistoryPeriod): String = when (period) {
    HistoryPeriod.WEEKS_4 -> stringResource(R.string.history_period_4w)
    HistoryPeriod.MONTHS_3 -> stringResource(R.string.history_period_3m)
    HistoryPeriod.YEAR -> stringResource(R.string.history_period_year)
    HistoryPeriod.ALL -> stringResource(R.string.history_period_all)
}

@Composable
private fun HistoryRow(item: HistoryItem, onOpen: (String) -> Unit) {
    HierroCard(onClick = { onOpen(item.sessionId) }) {
        HierroText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.startedAt)), style = HierroTheme.typography.title)
        val duration = item.durationSeconds
        if (duration == null) UnfinishedBadge()
        HierroText(item.routineName ?: stringResource(R.string.history_free_workout))
        val counts = exercisesText(item.exerciseCount) + " · " + setsText(item.setCount)
        HierroText(
            if (duration == null) counts else stringResource(R.string.history_item_summary, counts, durationText(duration)),
            color = HierroTheme.colors.textMuted,
        )
        val gym = item.gymName ?: stringResource(if (item.gymDeleted) R.string.history_gym_deleted else R.string.history_gym_none)
        HierroText(
            gym + " · " + stringResource(R.string.history_volume, SetValidation.formatWeight(Math.round(item.volume * 10) / 10.0)),
            color = HierroTheme.colors.textMuted,
        )
    }
}
