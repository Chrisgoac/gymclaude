package com.chrisgoac.gymlog.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.OutboxDiagnostic
import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.domain.billing.BillingRepository
import com.chrisgoac.gymlog.domain.toUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val sync: SyncUiState = SyncUiState.Synced,
    val diagnostics: List<OutboxDiagnostic> = emptyList(),
    /** The subscription for the Settings card; null when billing is not wired. */
    val plan: PlanStatus? = null,
)

/** Settings home: sync status, diagnostics and the subscription card. Sign-out and deletion live in [AccountViewModel]. */
class SettingsViewModel(
    private val sync: SyncUseCases,
    private val billing: BillingRepository? = null,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(
        sync.observeStatus().map { it.toUiState() },
        sync.observeDiagnostics(),
        billing?.entitlement?.map { PlanStatus.of(it) } ?: flowOf(null),
    ) { status, diagnostics, plan -> SettingsUiState(status, diagnostics, plan) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

    /** Play's subscription management page. */
    fun managementUri(): String? = billing?.managementUri()

    fun syncNow() {
        viewModelScope.launch { sync.requestSync() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
