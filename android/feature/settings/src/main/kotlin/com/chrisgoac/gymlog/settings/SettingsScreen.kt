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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader

/** Settings: sync status, subscription and the entry to account controls. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onSyncDetails: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null hides the entry (the destination is not wired). */
    onGyms: (() -> Unit)? = null,
    onTraining: (() -> Unit)? = null,
    onBackup: (() -> Unit)? = null,
    /** Null hides the subscription card (billing is not wired). */
    onOpenPro: (() -> Unit)? = null,
    onOpenUri: (String) -> Unit = {},
    /** Sign-out, privacy and account deletion; null hides the entry. */
    onAccount: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.settings_title)) {
            HierroHeaderAction(stringResource(R.string.settings_back), onBack)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onTraining != null) HierroButton(stringResource(R.string.settings_training), onTraining, variant = HierroButtonVariant.Secondary)
            if (onGyms != null) HierroButton(stringResource(R.string.settings_gyms), onGyms, variant = HierroButtonVariant.Secondary)
            if (onBackup != null) HierroButton(stringResource(R.string.settings_backup), onBackup, variant = HierroButtonVariant.Secondary)
            state.plan?.let { plan ->
                if (onOpenPro != null) {
                    SubscriptionCard(plan, onOpenPro, onManage = { viewModel.managementUri()?.let(onOpenUri) })
                }
            }
            SyncStatusCard(state.sync, onSyncNow = viewModel::syncNow, onDetails = onSyncDetails)
            if (onAccount != null) HierroButton(stringResource(R.string.settings_account_and_privacy), onAccount, variant = HierroButtonVariant.Secondary)
        }
    }
}

