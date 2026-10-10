package com.chrisgoac.gymlog.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.auth.AuthFailure
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.auth.AuthSessionState
import com.chrisgoac.gymlog.domain.auth.AuthStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { SignIn, SignUp }

/** Credentials = email (+ password); Code = waiting for the emailed one-time code. */
enum class AuthStage { Credentials, Code }

sealed interface AuthMessage {
    data object InvalidEmail : AuthMessage
    data object MissingPassword : AuthMessage
    data object MissingCode : AuthMessage
    data class Gateway(val failure: AuthFailure) : AuthMessage
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.SignIn,
    val stage: AuthStage = AuthStage.Credentials,
    /** Sign-in only: password instead of an emailed code. */
    val usePassword: Boolean = false,
    val email: String = "",
    val password: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val message: AuthMessage? = null,
    /** The identity service can't be reached / rejects the app: shown as a banner above the form. */
    val serviceUnavailable: Boolean = false,
) {
    /** A clean form after a flow finishes: only the chosen mode and typed email survive. */
    fun reset() = AuthUiState(mode = mode, email = email, serviceUnavailable = serviceUnavailable)
}

/**
 * Drives the native email/code, password and Google flows. It never decides navigation: once the
 * gateway reports a session, the app's root state machine replaces this screen.
 */
class AuthViewModel(private val gateway: AuthGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            var previous: AuthSessionState? = null
            gateway.state.collect { session ->
                when {
                    // Drop secrets and the finished flow, so a later sign-out starts from a clean form.
                    session is AuthSessionState.SignedIn -> mutableState.update { it.reset() }
                    // An account just signed out: its email must not prefill the form for the next person.
                    session is AuthSessionState.SignedOut &&
                        (previous is AuthSessionState.SignedIn || previous is AuthSessionState.SessionExpired) ->
                        mutableState.update { it.reset().copy(email = "") }
                }
                previous = session
            }
        }
        viewModelScope.launch {
            gateway.serviceUnavailable.collect { unavailable -> mutableState.update { it.copy(serviceUnavailable = unavailable) } }
        }
    }

    fun onEmailChange(value: String) = mutableState.update { it.copy(email = value, message = null) }
    fun onPasswordChange(value: String) = mutableState.update { it.copy(password = value, message = null) }
    fun onCodeChange(value: String) = mutableState.update { it.copy(code = value.filter(Char::isDigit).take(CODE_MAX), message = null) }

    fun setMode(mode: AuthMode) = mutableState.update {
        if (it.busy) it else it.copy(mode = mode).reset()
    }

    fun togglePasswordSignIn() = mutableState.update {
        if (it.busy) it else it.copy(usePassword = !it.usePassword, password = "", message = null)
    }

    fun back() = mutableState.update {
        if (it.busy) it else it.copy(stage = AuthStage.Credentials, code = "", message = null)
    }

    fun submit() {
        val current = state.value
        if (current.busy) return
        val email = current.email.trim()
        when (current.stage) {
            AuthStage.Credentials -> {
                if (!looksLikeEmail(email)) return fail(AuthMessage.InvalidEmail)
                val password = current.password
                when {
                    current.mode == AuthMode.SignUp -> perform { gateway.startSignUp(email, password.ifBlank { null }) }
                    current.usePassword ->
                        if (password.isBlank()) fail(AuthMessage.MissingPassword)
                        else perform { gateway.signInWithPassword(email, password) }
                    else -> perform { gateway.startEmailCodeSignIn(email) }
                }
            }
            AuthStage.Code -> {
                val code = current.code
                if (code.isBlank()) return fail(AuthMessage.MissingCode)
                when (current.mode) {
                    AuthMode.SignIn -> perform { gateway.verifyEmailCodeSignIn(code) }
                    AuthMode.SignUp -> perform { gateway.verifySignUp(code) }
                }
            }
        }
    }

    /** Sends a fresh code: a new email-code sign-in, or a re-sent code for the pending sign-up. */
    fun resendCode() {
        val current = state.value
        if (current.busy || current.stage != AuthStage.Code) return
        val email = current.email.trim()
        when (current.mode) {
            AuthMode.SignIn -> perform { gateway.startEmailCodeSignIn(email) }
            AuthMode.SignUp -> perform { gateway.resendSignUpCode() }
        }
    }

    fun signInWithGoogle() {
        if (state.value.busy) return
        perform { gateway.signInWithGoogle() }
    }

    private fun fail(message: AuthMessage) = mutableState.update { it.copy(message = message) }

    private fun perform(step: suspend () -> AuthStep) {
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = step()
            mutableState.update { previous ->
                val idle = previous.copy(busy = false)
                when (result) {
                    AuthStep.CodeSent -> idle.copy(stage = AuthStage.Code, code = "")
                    AuthStep.Completed -> idle.reset()
                    AuthStep.Cancelled -> idle
                    is AuthStep.Failed -> idle.copy(message = AuthMessage.Gateway(result.reason))
                }
            }
        }
    }

    private companion object {
        const val CODE_MAX = 8
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun looksLikeEmail(value: String) = EMAIL.matches(value)
    }
}
