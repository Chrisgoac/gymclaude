package com.chrisgoac.gymlog.settings

import com.chrisgoac.gymlog.feature.settings.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.SyncUiState

/** Sync state with the action that fits it; "Ver detalles" opens the diagnostic list. */
@Composable
fun SyncStatusCard(
    state: SyncUiState,
    onSyncNow: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HierroTheme.colors
    val needsAttention = state is SyncUiState.NeedsAction || state is SyncUiState.SessionRequired || state is SyncUiState.SessionRejected
    HierroCard(modifier) {
        HierroText(stringResource(R.string.settings_sync_title).uppercase(), style = HierroTheme.typography.label, color = colors.textMuted)
        HierroText(
            text = message(state),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = HierroTheme.typography.body,
            color = if (needsAttention) colors.error else colors.ink,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                SyncUiState.SessionRequired, SyncUiState.Synced -> Unit
                SyncUiState.SessionRejected -> HierroButton(stringResource(R.string.settings_sync_retry), onSyncNow, variant = HierroButtonVariant.Secondary)
                else -> HierroButton(stringResource(R.string.settings_sync_now), onSyncNow, variant = HierroButtonVariant.Secondary)
            }
            HierroButton(stringResource(R.string.settings_sync_details), onDetails, variant = HierroButtonVariant.Secondary)
        }
    }
}

@Composable
private fun message(state: SyncUiState): String = when (state) {
    SyncUiState.Synced -> stringResource(R.string.settings_sync_synced)
    is SyncUiState.Pending -> pluralStringResource(R.plurals.settings_sync_pending, state.count, state.count)
    is SyncUiState.Offline -> pluralStringResource(R.plurals.settings_sync_offline, state.count, state.count)
    SyncUiState.SessionRequired -> stringResource(R.string.settings_sync_session_required)
    SyncUiState.SessionRejected -> stringResource(R.string.settings_sync_session_rejected)
    is SyncUiState.NeedsAction -> pluralStringResource(R.plurals.settings_sync_needs_action, state.count, state.count)
}
