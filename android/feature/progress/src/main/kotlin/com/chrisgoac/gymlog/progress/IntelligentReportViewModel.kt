package com.chrisgoac.gymlog.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.ai.IntelligentReport
import com.chrisgoac.gymlog.domain.ai.IntelligentReportRepository
import com.chrisgoac.gymlog.domain.coach.CoachException
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Presentation only: the server decides Pro for every request. */
enum class ReportAccess { CHECKING, LOCKED, AVAILABLE }

data class IntelligentReportUiState(
    val access: ReportAccess = ReportAccess.CHECKING,
    val online: Boolean = true,
    val loading: Boolean = false,
    /** The report shown: the last one fetched (possibly while offline) or the new one. */
    val report: IntelligentReport? = null,
    val failure: CoachFailure? = null,
) {
    val canRefresh: Boolean get() = access == ReportAccess.AVAILABLE && online && !loading
}

/**
 * The intelligent report behind Progress's Pro action. It is fetched when the screen opens (Pro + online) and on
 * "Actualizar": the server answers from its cache when the data did not change. Offline, the last report fetched
 * in this session is shown with its date and nothing is requested.
 */
class IntelligentReportViewModel(
    private val repository: IntelligentReportRepository,
    entitlements: EntitlementReader,
    online: Flow<Boolean>,
) : ViewModel() {
    private val _state = MutableStateFlow(IntelligentReportUiState(report = repository.lastReport()))
    val state: StateFlow<IntelligentReportUiState> = _state.asStateFlow()

    private var lockedByServer = false
    private var requestedOnOpen = false

    init {
        viewModelScope.launch {
            combine(
                entitlements.observeIsPro().catch { emit(false) }.distinctUntilChanged(),
                online.distinctUntilChanged(),
            ) { pro, up -> pro to up }.collect { (pro, up) ->
                _state.update {
                    it.copy(
                        access = if (pro && !lockedByServer) ReportAccess.AVAILABLE else ReportAccess.LOCKED,
                        online = up,
                    )
                }
                // Once per screen: the first time it can be fetched.
                if (!requestedOnOpen && _state.value.canRefresh) {
                    requestedOnOpen = true
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        if (!_state.value.canRefresh) return
        _state.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            try {
                val report = repository.generate()
                _state.update { it.copy(loading = false, report = report) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = (e as? CoachException)?.failure ?: CoachFailure.INTERRUPTED
                if (failure == CoachFailure.PRO_REQUIRED) lockedByServer = true
                _state.update {
                    it.copy(
                        loading = false,
                        failure = failure,
                        access = if (lockedByServer) ReportAccess.LOCKED else it.access,
                    )
                }
            }
        }
    }
}
