package com.chrisgoac.gymlog.settings

import com.chrisgoac.gymlog.feature.settings.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.OutboxDiagnostic

/** Unsynced mutations: id prefix, tables, attempts and last error code. Never payload values or tokens. */
@Composable
fun SyncDiagnosticsScreen(diagnostics: List<OutboxDiagnostic>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.diag_title)) {
            HierroHeaderAction(stringResource(R.string.settings_back), onBack)
        }
        if (diagnostics.isEmpty()) {
            HierroEmptyState(
                title = stringResource(R.string.diag_empty_title),
                message = stringResource(R.string.diag_empty_message),
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(diagnostics, key = { it.mutationIdPrefix + it.tables.joinToString() }) { entry -> DiagnosticRow(entry) }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(entry: OutboxDiagnostic) {
    val colors = HierroTheme.colors
    HierroCard {
        HierroText(stringResource(R.string.diag_change, entry.mutationIdPrefix), style = HierroTheme.typography.title)
        HierroText(
            stringResource(if (entry.rejected) R.string.diag_status_rejected else R.string.diag_status_queued),
            style = HierroTheme.typography.label,
            color = if (entry.rejected) colors.error else colors.textMuted,
        )
        HierroText(
            if (entry.tables.isEmpty()) stringResource(R.string.diag_tables_unknown)
            else stringResource(R.string.diag_tables, entry.tables.joinToString(", ")),
        )
        HierroText(stringResource(R.string.diag_attempts, entry.attemptCount))
        entry.lastErrorCode?.let { HierroText(stringResource(R.string.diag_last_error, it)) }
    }
}
