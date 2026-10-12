package com.chrisgoac.gymlog.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.domain.workout.ActiveWorkoutPolicy
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.HistoryDetail
import com.chrisgoac.gymlog.domain.workout.HistoryFilter
import com.chrisgoac.gymlog.domain.workout.HistoryItem
import com.chrisgoac.gymlog.domain.workout.HistoryPeriod
import com.chrisgoac.gymlog.domain.workout.HistoryRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MILLIS = 5_000L

enum class HistoryLoadStatus { Loading, Ready, Failed }

// ---- List ----

data class HistoryListUiState(
    val filter: HistoryFilter = HistoryFilter(),
    val status: HistoryLoadStatus = HistoryLoadStatus.Loading,
    val items: List<HistoryItem> = emptyList(),
    val hasMore: Boolean = false,
    /** Gyms to filter by. */
    val gyms: List<Gym> = emptyList(),
    val sync: SyncUiState = SyncUiState.Synced,
) {
    val filterActive: Boolean get() = filter.period != HistoryPeriod.ALL || filter.gymId != null || filter.query.isNotBlank()
}

/**
 * The finished-workout list with period / gym / text filters. Only the newest [pageSize] matches
 * are read; [loadMore] widens the window. Any filter change returns to the first page.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HistoryListViewModel(
    history: HistoryRepository,
    workouts: WorkoutRepository,
    private val syncState: Flow<SyncUiState> = emptyFlow(),
    private val pageSize: Int = PAGE_SIZE,
) : ViewModel() {
    private data class Window(val filter: HistoryFilter = HistoryFilter(), val limit: Int)

    private val window = MutableStateFlow(Window(limit = pageSize))
    private val attempt = MutableStateFlow(0)

    /** The query last handed to the repository: only a changed, non-blank query waits [QUERY_DEBOUNCE_MILLIS] (clearing applies at once). */
    private var appliedQuery = window.value.filter.query

    /** What is actually queried: typing is debounced, other filter changes and paging apply at once. */
    private val applied: Flow<Window> = window
        .debounce { w -> if (w.filter.query == appliedQuery || w.filter.query.isBlank()) 0L else QUERY_DEBOUNCE_MILLIS }
        .onEach { appliedQuery = it.filter.query }

    val state: StateFlow<HistoryListUiState> = combine(applied, attempt) { w, _ -> w }
        .flatMapLatest { w ->
            combine(
                history.observeHistory(w.filter, w.limit),
                workouts.observeGyms().catch { emit(emptyList()) },
                syncState.onStart { emit(SyncUiState.Synced) }.catch { emit(SyncUiState.Synced) },
            ) { page, gyms, sync ->
                HistoryListUiState(w.filter, HistoryLoadStatus.Ready, page.items, page.hasMore, gyms, sync)
            }
                .onStart { emit(HistoryListUiState(w.filter, HistoryLoadStatus.Loading)) }
                .catch { emit(HistoryListUiState(w.filter, HistoryLoadStatus.Failed)) }
        }
        // The search field always shows what was typed, even while its results are still debounced.
        .combine(window) { content, typed -> content.copy(filter = typed.filter) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HistoryListUiState())

    fun onQuery(query: String) = changeFilter { it.copy(query = query) }

    fun onPeriod(period: HistoryPeriod) = changeFilter { it.copy(period = period) }

    /** Selecting the active gym again shows every gym. */
    fun onGym(gymId: String?) = changeFilter { it.copy(gymId = if (it.gymId == gymId) null else gymId) }

    fun clearFilters() = changeFilter { HistoryFilter() }

    fun loadMore() = window.update { it.copy(limit = it.limit + pageSize) }

    fun retry() = attempt.update { it + 1 }

    private fun changeFilter(transform: (HistoryFilter) -> HistoryFilter) =
        window.update { Window(transform(it.filter), pageSize) }

    companion object {
        const val PAGE_SIZE = 30

        /** Wait after the last keystroke before querying (one query per pause, not per character). */
        const val QUERY_DEBOUNCE_MILLIS = 250L
    }
}

// ---- Detail ----

sealed interface HistoryDetailUiState {
    data object Loading : HistoryDetailUiState
    data object NotFound : HistoryDetailUiState
    data object Failed : HistoryDetailUiState
    data class Found(
        val detail: HistoryDetail,
        val gyms: List<Gym>,
        val choosingGym: Boolean = false,
        val gymFailed: Boolean = false,
        val confirmingDelete: Boolean = false,
        val deleting: Boolean = false,
        val deleteFailed: Boolean = false,
        /** Typed minutes for finishing an abandoned (unfinished) session; prefilled with the default. */
        val finishMinutes: String = ActiveWorkoutPolicy.DEFAULT_FINISH_MINUTES.toString(),
        val finishInvalid: Boolean = false,
        val finishing: Boolean = false,
        val finishFailed: Boolean = false,
    ) : HistoryDetailUiState {
        /** An abandoned workout: offer "Terminar" with a typed duration instead of editing. */
        val unfinished: Boolean get() = detail.workout.session.durationSeconds == null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryDetailViewModel(
    private val history: HistoryRepository,
    workouts: WorkoutRepository,
    private val sessionId: String,
) : ViewModel() {
    private data class Local(
        val choosingGym: Boolean = false,
        val gymFailed: Boolean = false,
        val confirmingDelete: Boolean = false,
        val deleting: Boolean = false,
        val deleteFailed: Boolean = false,
        val finishMinutes: String = ActiveWorkoutPolicy.DEFAULT_FINISH_MINUTES.toString(),
        val finishInvalid: Boolean = false,
        val finishing: Boolean = false,
        val finishFailed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val attempt = MutableStateFlow(0)
    private val deletedFlag = MutableStateFlow(false)

    /** True once this screen deleted the session: navigate back. */
    val deleted: StateFlow<Boolean> = deletedFlag

    val state: StateFlow<HistoryDetailUiState> = attempt
        .flatMapLatest {
            combine(history.observeDetail(sessionId), workouts.observeGyms().catch { emit(emptyList()) }, local) { detail, gyms, l ->
                if (detail == null) {
                    HistoryDetailUiState.NotFound
                } else {
                    HistoryDetailUiState.Found(
                        detail, gyms, l.choosingGym, l.gymFailed, l.confirmingDelete, l.deleting, l.deleteFailed,
                        l.finishMinutes, l.finishInvalid, l.finishing, l.finishFailed,
                    )
                }
            }.onStart<HistoryDetailUiState> { emit(HistoryDetailUiState.Loading) }
                .catch { emit(HistoryDetailUiState.Failed) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HistoryDetailUiState.Loading)

    fun retry() = attempt.update { it + 1 }

    // ---- Change gym ----

    fun openGymPicker() = local.update { it.copy(choosingGym = true, gymFailed = false) }

    fun closeGymPicker() = local.update { it.copy(choosingGym = false) }

    /** One mutation updating the session's gym; the picker closes once it is stored. */
    fun changeGym(gymId: String?) {
        viewModelScope.launch {
            try {
                history.changeGym(sessionId, gymId)
                local.update { it.copy(choosingGym = false, gymFailed = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(gymFailed = true) }
            }
        }
    }

    // ---- Finish an abandoned session ----

    fun onFinishMinutes(text: String) = local.update { it.copy(finishMinutes = text, finishInvalid = false, finishFailed = false) }

    /**
     * Stores the typed duration (whole minutes, 1..[ActiveWorkoutPolicy.MAX_FINISH_MINUTES]) as the
     * session's duration in one mutation; never `now - start`. Invalid input writes nothing.
     */
    fun finishWithDuration() {
        val current = local.value
        if (current.finishing) return
        val minutes = parseFinishMinutes(current.finishMinutes)
        if (minutes == null) {
            local.update { it.copy(finishInvalid = true) }
            return
        }
        local.update { it.copy(finishing = true, finishInvalid = false, finishFailed = false) }
        viewModelScope.launch {
            try {
                history.finishWithDuration(sessionId, minutes * 60)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(finishFailed = true) }
            } finally {
                local.update { it.copy(finishing = false) }
            }
        }
    }

    // ---- Delete ----

    fun requestDelete() = local.update { it.copy(confirmingDelete = true, deleteFailed = false) }

    fun cancelDelete() = local.update { if (it.deleting) it else it.copy(confirmingDelete = false) }

    /** Deletes only after [requestDelete]; repeated taps while deleting are ignored. */
    fun confirmDelete() {
        val current = local.value
        if (!current.confirmingDelete || current.deleting) return
        local.update { it.copy(deleting = true, deleteFailed = false) }
        viewModelScope.launch {
            try {
                history.deleteSession(sessionId)
                deletedFlag.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(deleteFailed = true) }
            } finally {
                local.update { it.copy(deleting = false, confirmingDelete = false) }
            }
        }
    }
}

/** Whole minutes in 1..[ActiveWorkoutPolicy.MAX_FINISH_MINUTES], or null. */
internal fun parseFinishMinutes(text: String): Int? =
    text.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 4 }?.toInt()
        ?.takeIf { it in 1..ActiveWorkoutPolicy.MAX_FINISH_MINUTES }
