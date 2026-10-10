package com.chrisgoac.gymlog.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.OutboxDiagnostic
import com.chrisgoac.gymlog.domain.SignOutOutcome
import com.chrisgoac.gymlog.domain.SignOutUseCase
import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.domain.toUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a sign-out attempt stands. */
sealed interface SignOutPhase {
    data object Idle : SignOutPhase
    data object Working : SignOutPhase

    /** [unsyncedChanges] would be lost: waiting for the user's explicit decision. */
    data class Confirm(val unsyncedChanges: Int) : SignOutPhase
    data object Failed : SignOutPhase
}

data class SettingsUiState(
    val sync: SyncUiState = SyncUiState.Synced,
    val diagnostics: List<OutboxDiagnostic> = emptyList(),
    val signOut: SignOutPhase = SignOutPhase.Idle,
)

class SettingsViewModel(
    private val sync: SyncUseCases,
    private val signOutUseCase: SignOutUseCase,
) : ViewModel() {
    private val signOutPhase = MutableStateFlow<SignOutPhase>(SignOutPhase.Idle)

    val state: StateFlow<SettingsUiState> = combine(
        sync.observeStatus().map { it.toUiState() },
        sync.observeDiagnostics(),
        signOutPhase,
    ) { status, diagnostics, phase -> SettingsUiState(status, diagnostics, phase) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

    fun syncNow() {
        viewModelScope.launch { sync.syncNow() }
    }

    fun signOut() = runSignOut { signOutUseCase.begin() }

    fun confirmDiscard() = runSignOut { signOutUseCase.confirmDiscard() }

    fun cancelSignOut() {
        viewModelScope.launch {
            signOutUseCase.cancel()
            signOutPhase.value = SignOutPhase.Idle
        }
    }

    private fun runSignOut(action: suspend () -> SignOutOutcome) {
        if (signOutPhase.value == SignOutPhase.Working) return
        signOutPhase.update { SignOutPhase.Working }
        viewModelScope.launch {
            signOutPhase.value = when (val outcome = action()) {
                SignOutOutcome.SignedOut -> SignOutPhase.Idle
                is SignOutOutcome.ConfirmationRequired -> SignOutPhase.Confirm(outcome.unsyncedChanges)
                SignOutOutcome.Failed -> SignOutPhase.Failed
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
