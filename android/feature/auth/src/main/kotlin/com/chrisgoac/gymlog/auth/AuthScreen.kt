package com.chrisgoac.gymlog.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.auth.AuthFailure
import com.chrisgoac.gymlog.feature.auth.R

/**
 * Native sign-in / sign-up screen. [notice] is an optional banner set by the host (e.g. "your
 * session expired, sign in again").
 */
@Composable
fun AuthScreen(viewModel: AuthViewModel, modifier: Modifier = Modifier, notice: String? = null) {
    val state by viewModel.state.collectAsState()
    BackHandler(enabled = state.stage == AuthStage.Code && !state.busy) { viewModel.back() }
    AuthContent(
        state = state,
        notice = notice,
        actions = AuthActions(
            onEmailChange = viewModel::onEmailChange,
            onPasswordChange = viewModel::onPasswordChange,
            onCodeChange = viewModel::onCodeChange,
            onModeChange = viewModel::setMode,
            onTogglePassword = viewModel::togglePasswordSignIn,
            onSubmit = viewModel::submit,
            onResend = viewModel::resendCode,
            onBack = viewModel::back,
            onGoogle = viewModel::signInWithGoogle,
        ),
        modifier = modifier,
    )
}

class AuthActions(
    val onEmailChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onCodeChange: (String) -> Unit = {},
    val onModeChange: (AuthMode) -> Unit = {},
    val onTogglePassword: () -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onResend: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onGoogle: () -> Unit = {},
)

/** Stateless body of [AuthScreen]. */
@Composable
fun AuthContent(state: AuthUiState, actions: AuthActions, modifier: Modifier = Modifier, notice: String? = null) {
    val colors = HierroTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HierroText(
                stringResource(R.string.auth_title).uppercase(),
                Modifier.semantics { heading() },
                HierroTheme.typography.display,
            )
            HierroText(
                stringResource(if (state.mode == AuthMode.SignIn) R.string.auth_subtitle_sign_in else R.string.auth_subtitle_sign_up),
                color = colors.textMuted,
            )
            if (state.serviceUnavailable) {
                HierroCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    HierroText(stringResource(R.string.auth_service_unavailable), color = colors.error)
                }
            }
            if (notice != null) {
                HierroCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { HierroText(notice) }
            }
            when (state.stage) {
                AuthStage.Credentials -> CredentialsForm(state, actions)
                AuthStage.Code -> CodeForm(state, actions)
            }
            state.message?.let { message ->
                HierroText(
                    messageText(message),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.error,
                )
            }
        }
    }
}

@Composable
private fun CredentialsForm(state: AuthUiState, actions: AuthActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeButton(stringResource(R.string.auth_mode_sign_in), state.mode == AuthMode.SignIn, !state.busy, Modifier.weight(1f)) {
            actions.onModeChange(AuthMode.SignIn)
        }
        ModeButton(stringResource(R.string.auth_mode_sign_up), state.mode == AuthMode.SignUp, !state.busy, Modifier.weight(1f)) {
            actions.onModeChange(AuthMode.SignUp)
        }
    }
    val showPassword = state.mode == AuthMode.SignUp || state.usePassword
    HierroTextField(
        value = state.email,
        onValueChange = actions.onEmailChange,
        label = stringResource(R.string.auth_email),
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = if (showPassword) ImeAction.Next else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.onSubmit() }),
    )
    if (showPassword) {
        HierroTextField(
            value = state.password,
            onValueChange = actions.onPasswordChange,
            label = stringResource(if (state.mode == AuthMode.SignUp) R.string.auth_password_optional else R.string.auth_password),
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onSubmit() }),
            visualTransformation = PasswordVisualTransformation(),
        )
    }
    val submitLabel = when {
        state.mode == AuthMode.SignUp -> R.string.auth_create_account
        state.usePassword -> R.string.auth_sign_in
        else -> R.string.auth_send_code
    }
    HierroButton(
        stringResource(submitLabel),
        actions.onSubmit,
        Modifier.fillMaxWidth(),
        loading = state.busy,
        loadingLabel = stringResource(R.string.auth_working),
    )
    if (state.mode == AuthMode.SignIn) {
        HierroButton(
            stringResource(if (state.usePassword) R.string.auth_use_code else R.string.auth_use_password),
            actions.onTogglePassword,
            Modifier.fillMaxWidth(),
            variant = HierroButtonVariant.Secondary,
            enabled = !state.busy,
        )
    }
    HierroText(
        stringResource(R.string.auth_or).uppercase(),
        Modifier.fillMaxWidth(),
        HierroTheme.typography.label,
        HierroTheme.colors.textMuted,
        textAlign = TextAlign.Center,
    )
    HierroButton(
        stringResource(R.string.auth_google),
        actions.onGoogle,
        Modifier.fillMaxWidth(),
        variant = HierroButtonVariant.Secondary,
        enabled = !state.busy,
    )
}

@Composable
private fun CodeForm(state: AuthUiState, actions: AuthActions) {
    HierroText(stringResource(R.string.auth_code_sent, state.email.trim()))
    HierroTextField(
        value = state.code,
        onValueChange = actions.onCodeChange,
        label = stringResource(R.string.auth_code),
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.onSubmit() }),
    )
    HierroButton(
        stringResource(R.string.auth_verify),
        actions.onSubmit,
        Modifier.fillMaxWidth(),
        loading = state.busy,
        loadingLabel = stringResource(R.string.auth_working),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HierroButton(
            stringResource(R.string.auth_resend),
            actions.onResend,
            Modifier.weight(1f),
            variant = HierroButtonVariant.Secondary,
            enabled = !state.busy,
        )
        HierroButton(
            stringResource(R.string.auth_change_email),
            actions.onBack,
            Modifier.weight(1f),
            variant = HierroButtonVariant.Secondary,
            enabled = !state.busy,
        )
    }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    HierroButton(
        text,
        onClick,
        modifier.semantics { this.selected = selected },
        variant = if (selected) HierroButtonVariant.Primary else HierroButtonVariant.Secondary,
        enabled = enabled,
    )
}

@Composable
private fun messageText(message: AuthMessage): String = when (message) {
    AuthMessage.InvalidEmail -> stringResource(R.string.auth_error_invalid_email)
    AuthMessage.MissingPassword -> stringResource(R.string.auth_error_missing_password)
    AuthMessage.MissingCode -> stringResource(R.string.auth_error_missing_code)
    is AuthMessage.Gateway -> when (message.failure) {
        AuthFailure.Network -> stringResource(R.string.auth_error_network)
        AuthFailure.InvalidCode -> stringResource(R.string.auth_error_invalid_code)
        AuthFailure.ExpiredCode -> stringResource(R.string.auth_error_expired_code)
        AuthFailure.InvalidCredentials -> stringResource(R.string.auth_error_invalid_credentials)
        AuthFailure.AccountNotFound -> stringResource(R.string.auth_error_account_not_found)
        AuthFailure.AccountExists -> stringResource(R.string.auth_error_account_exists)
        AuthFailure.WeakPassword -> stringResource(R.string.auth_error_weak_password)
        AuthFailure.RateLimited -> stringResource(R.string.auth_error_rate_limited)
        AuthFailure.Unsupported -> stringResource(R.string.auth_error_unsupported)
        is AuthFailure.Unknown -> stringResource(R.string.auth_error_unknown)
    }
}
