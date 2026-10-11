package com.chrisgoac.gymlog.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.auth.AuthFailure
import com.chrisgoac.gymlog.feature.settings.R

/** Account: sign-out (resolving unsynced changes), privacy links and account deletion. */
@Composable
fun AccountScreen(
    viewModel: AccountViewModel,
    links: AccountLinks,
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.settings_account_title)) {
            HierroHeaderAction(stringResource(R.string.settings_back), onBack)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SignOutCard(state, viewModel)
            HierroButton(stringResource(R.string.settings_privacy_title), onPrivacy, variant = HierroButtonVariant.Secondary)
            DeleteAccountCard(state, viewModel, links, onOpenUri)
        }
    }
}

@Composable
private fun SignOutCard(state: AccountUiState, viewModel: AccountViewModel) {
    HierroCard {
        when (val phase = state.signOut) {
            is SignOutPhase.Confirm -> {
                HierroText(stringResource(R.string.settings_sign_out_confirm_title).uppercase(), style = HierroTheme.typography.title, color = HierroTheme.colors.error)
                HierroText(pluralStringResource(R.plurals.settings_sign_out_confirm_message, phase.unsyncedChanges, phase.unsyncedChanges))
                if (phase.syncFailed) HierroText(stringResource(R.string.settings_sign_out_sync_failed), color = HierroTheme.colors.error)
                if (!state.online) HierroText(stringResource(R.string.settings_sign_out_offline), color = HierroTheme.colors.textMuted)
                HierroButton(stringResource(R.string.settings_sign_out_sync_now), viewModel::syncNowThenSignOut, Modifier.fillMaxWidth(), enabled = state.online)
                HierroButton(stringResource(R.string.settings_sign_out_keep), viewModel::staySignedIn, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
                HierroButton(stringResource(R.string.settings_sign_out_discard), viewModel::confirmDiscard, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Destructive)
            }
            SignOutPhase.Syncing -> {
                HierroText(stringResource(R.string.settings_sign_out_syncing))
                HierroButton(stringResource(R.string.settings_sign_out), {}, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary, loading = true)
            }
            else -> {
                if (phase == SignOutPhase.Failed) HierroText(stringResource(R.string.settings_sign_out_failed), color = HierroTheme.colors.error)
                HierroButton(
                    stringResource(R.string.settings_sign_out),
                    viewModel::signOut,
                    Modifier.fillMaxWidth(),
                    variant = HierroButtonVariant.Secondary,
                    loading = phase == SignOutPhase.Working,
                )
            }
        }
    }
}

@Composable
private fun DeleteAccountCard(state: AccountUiState, viewModel: AccountViewModel, links: AccountLinks, onOpenUri: (String) -> Unit) {
    HierroCard {
        HierroText(stringResource(R.string.settings_delete_title).uppercase(), style = HierroTheme.typography.title, color = HierroTheme.colors.error)
        HierroText(stringResource(R.string.settings_delete_explanation))
        HierroText(
            stringResource(
                when (state.subscription) {
                    SubscriptionNotice.RENEWING -> R.string.settings_delete_subscription_renewing
                    SubscriptionNotice.NOT_RENEWING -> R.string.settings_delete_subscription_not_renewing
                    SubscriptionNotice.NONE -> R.string.settings_delete_subscription_general
                },
            ),
            color = if (state.subscription == SubscriptionNotice.RENEWING) HierroTheme.colors.error else HierroTheme.colors.ink,
        )
        HierroButton(
            stringResource(R.string.settings_delete_manage),
            { onOpenUri(viewModel.managementUri() ?: links.subscriptions()) },
            Modifier.fillMaxWidth(),
            variant = HierroButtonVariant.Secondary,
        )
        when (val phase = state.deletion) {
            is DeletionPhase.Reauth -> ReauthForm(phase, viewModel)
            DeletionPhase.ReauthUnavailable -> {
                HierroText(stringResource(R.string.settings_delete_reauth_unavailable), color = HierroTheme.colors.error)
                HierroButton(stringResource(R.string.settings_delete_web), { onOpenUri(links.accountDeletion) }, Modifier.fillMaxWidth())
                HierroButton(stringResource(R.string.settings_delete_reauth_cancel), viewModel::cancelDeletion, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
            }
            else -> ConfirmationForm(state, viewModel, links, onOpenUri)
        }
    }
}

@Composable
private fun ConfirmationForm(state: AccountUiState, viewModel: AccountViewModel, links: AccountLinks, onOpenUri: (String) -> Unit) {
    val working = state.deletion == DeletionPhase.Working || state.deletion == DeletionPhase.Started
    HierroTextField(
        value = state.confirmation,
        onValueChange = viewModel::onConfirmationChanged,
        label = stringResource(R.string.settings_delete_confirm_label),
        enabled = !working,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
    )
    if (!state.online) HierroText(stringResource(R.string.settings_delete_offline), color = HierroTheme.colors.textMuted)
    (state.deletion as? DeletionPhase.Failed)?.let { failed ->
        HierroText(
            stringResource(
                when (failed.reason) {
                    DeletionError.OFFLINE -> R.string.settings_delete_error_offline
                    DeletionError.SESSION_ENDED -> R.string.settings_delete_error_session
                    DeletionError.RETRYABLE -> R.string.settings_delete_error_retryable
                    DeletionError.PERMANENT -> R.string.settings_delete_error_permanent
                },
            ),
            color = HierroTheme.colors.error,
        )
        if (failed.reason == DeletionError.PERMANENT) {
            HierroButton(stringResource(R.string.settings_delete_web), { onOpenUri(links.accountDeletion) }, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
        }
    }
    HierroButton(
        stringResource(R.string.settings_delete_button),
        viewModel::deleteAccount,
        Modifier.fillMaxWidth(),
        variant = HierroButtonVariant.Destructive,
        enabled = state.canDelete || working,
        loading = working,
    )
}

@Composable
private fun ReauthForm(phase: DeletionPhase.Reauth, viewModel: AccountViewModel) {
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    HierroText(stringResource(R.string.settings_delete_reauth_title).uppercase(), style = HierroTheme.typography.label)
    HierroText(stringResource(R.string.settings_delete_reauth_message))
    phase.failure?.let { HierroText(stringResource(it.reauthMessage()), color = HierroTheme.colors.error) }
    if (phase.password) {
        HierroTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.settings_delete_reauth_password),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = PasswordVisualTransformation(),
        )
        HierroButton(
            stringResource(R.string.settings_delete_reauth_verify_password),
            { viewModel.verifyReauthPassword(password) },
            Modifier.fillMaxWidth(),
            enabled = password.isNotEmpty(),
        )
    }
    if (phase.codeSent) {
        HierroText(
            phase.emailCodeTo?.let { stringResource(R.string.settings_delete_reauth_code_sent, it) }
                ?: stringResource(R.string.settings_delete_reauth_code_sent_generic),
        )
        HierroTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(MAX_CODE) },
            label = stringResource(R.string.settings_delete_reauth_code),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        HierroButton(stringResource(R.string.settings_delete_reauth_verify_code), { viewModel.verifyReauthCode(code) }, Modifier.fillMaxWidth(), enabled = code.isNotEmpty())
    } else if (phase.emailCodeTo != null) {
        HierroButton(
            stringResource(R.string.settings_delete_reauth_send_code, phase.emailCodeTo),
            viewModel::sendReauthCode,
            Modifier.fillMaxWidth(),
            variant = if (phase.password) HierroButtonVariant.Secondary else HierroButtonVariant.Primary,
        )
    }
    HierroButton(stringResource(R.string.settings_delete_reauth_cancel), viewModel::cancelDeletion, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
}

private fun AuthFailure.reauthMessage(): Int = when (this) {
    AuthFailure.Network -> R.string.settings_delete_reauth_error_network
    AuthFailure.InvalidCode, AuthFailure.ExpiredCode -> R.string.settings_delete_reauth_error_code
    AuthFailure.InvalidCredentials, AuthFailure.WeakPassword -> R.string.settings_delete_reauth_error_password
    AuthFailure.RateLimited -> R.string.settings_delete_reauth_error_rate
    else -> R.string.settings_delete_reauth_error_unknown
}

private const val MAX_CODE = 8
