package com.chrisgoac.gymlog.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroShape
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.feature.history.R

/** A single-choice pill with selected semantics for TalkBack ("4 sem, seleccionado"). */
@Composable
internal fun HistoryChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HierroTheme.colors
    val selectedText = stringResource(R.string.history_selected)
    Box(
        modifier
            .heightIn(min = HierroShape.minTouchTarget)
            .widthIn(min = HierroShape.minTouchTarget)
            .background(if (selected) colors.accent else colors.surface)
            .border(HierroShape.border, colors.border)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = if (selected) "$label, $selectedText" else label }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        HierroText(label, style = HierroTheme.typography.label, color = if (selected) colors.onAccent else colors.ink)
    }
}

/** Horizontally scrolling single-choice row with a caption. */
@Composable
internal fun <T> HistoryChoiceRow(
    caption: String,
    options: List<T>,
    selected: T?,
    labelOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HierroText(caption.uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(options) { option -> HistoryChip(labelOf(option), option == selected, onClick = { onSelect(option) }) }
        }
    }
}

/** Small sync indicator: how many local changes still have to reach the server. */
@Composable
internal fun SyncIndicator(state: SyncUiState, modifier: Modifier = Modifier) {
    val attention = state is SyncUiState.NeedsAction || state is SyncUiState.SessionRequired || state is SyncUiState.SessionRejected
    val text = when (state) {
        SyncUiState.Synced -> stringResource(R.string.history_sync_synced)
        is SyncUiState.Pending -> pluralStringResource(R.plurals.history_sync_pending, state.count, state.count)
        is SyncUiState.Offline -> pluralStringResource(R.plurals.history_sync_offline, state.count, state.count)
        is SyncUiState.NeedsAction -> pluralStringResource(R.plurals.history_sync_needs_action, state.count, state.count)
        SyncUiState.SessionRequired -> stringResource(R.string.history_sync_session_required)
        SyncUiState.SessionRejected -> stringResource(R.string.history_sync_session_rejected)
    }
    HierroText(
        text,
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        HierroTheme.typography.label,
        if (attention) HierroTheme.colors.error else HierroTheme.colors.textMuted,
    )
}

/** "45 min" / "1 h 05 min". */
@Composable
internal fun durationText(seconds: Int): String {
    val minutes = seconds / 60
    return if (minutes < 60) stringResource(R.string.history_duration_minutes, minutes) else stringResource(R.string.history_duration_hours, minutes / 60, minutes % 60)
}

@Composable
internal fun exercisesText(count: Int): String = pluralStringResource(R.plurals.history_exercises, count, count)

@Composable
internal fun setsText(count: Int): String = pluralStringResource(R.plurals.history_sets, count, count)
