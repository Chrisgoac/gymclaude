package com.chrisgoac.gymlog.settings

import com.chrisgoac.gymlog.feature.settings.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme

/** Settings: sync status and account actions. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onSyncDetails: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null hides the entry (the destination is not wired). */
    onGyms: (() -> Unit)? = null,
    onTraining: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.settings_title)) {
            HierroHeaderAction(stringResource(R.string.settings_back), onBack)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onTraining != null) HierroButton(stringResource(R.string.settings_training), onTraining, variant = HierroButtonVariant.Secondary)
            if (onGyms != null) HierroButton(stringResource(R.string.settings_gyms), onGyms, variant = HierroButtonVariant.Secondary)
            SyncStatusCard(state.sync, onSyncNow = viewModel::syncNow, onDetails = onSyncDetails)
            AccountCard(state.signOut, viewModel)
        }
    }
}

@Composable
private fun AccountCard(phase: SignOutPhase, viewModel: SettingsViewModel) {
    HierroCard {
        HierroText(stringResource(R.string.settings_account_title).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        when (phase) {
            is SignOutPhase.Confirm -> {
                HierroText(stringResource(R.string.settings_sign_out_confirm_title).uppercase(), style = HierroTheme.typography.title, color = HierroTheme.colors.error)
                HierroText(pluralStringResource(R.plurals.settings_sign_out_confirm_message, phase.unsyncedChanges, phase.unsyncedChanges))
                HierroButton(stringResource(R.string.settings_sign_out_keep), viewModel::cancelSignOut)
                HierroButton(stringResource(R.string.settings_sign_out_discard), viewModel::confirmDiscard, variant = HierroButtonVariant.Secondary)
            }
            else -> {
                if (phase == SignOutPhase.Failed) HierroText(stringResource(R.string.settings_sign_out_failed), color = HierroTheme.colors.error)
                HierroButton(
                    stringResource(R.string.settings_sign_out),
                    viewModel::signOut,
                    variant = HierroButtonVariant.Secondary,
                    loading = phase == SignOutPhase.Working,
                )
            }
        }
    }
}
