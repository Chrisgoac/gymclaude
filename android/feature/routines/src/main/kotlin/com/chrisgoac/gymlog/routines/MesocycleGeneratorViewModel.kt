package com.chrisgoac.gymlog.routines

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.ai.MesocycleDraft
import com.chrisgoac.gymlog.domain.ai.MesocycleDraftDay
import com.chrisgoac.gymlog.domain.ai.MesocycleDraftExercise
import com.chrisgoac.gymlog.domain.ai.MesocycleGoal
import com.chrisgoac.gymlog.domain.ai.MesocycleParams
import com.chrisgoac.gymlog.domain.ai.MesocycleRepository
import com.chrisgoac.gymlog.domain.coach.CoachException
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val KEY_GOAL = "meso.goal"
private const val KEY_DAYS = "meso.days"
private const val KEY_WEEKS = "meso.weeks"
private const val KEY_MINUTES = "meso.minutes"
private const val KEY_REQUEST_ID = "meso.requestId"
private const val KEY_REQUEST_PARAMS = "meso.requestParams"

/** Presentation only: the server decides Pro for every request. */
enum class MesocycleAccess { CHECKING, LOCKED, AVAILABLE }

enum class MesocyclePhase {
    /** Choosing the parameters (also after a failed generation, with [MesocycleGeneratorUiState.failure]). */
    FORM,
    GENERATING,

    /** The draft is shown for review and editing; nothing is stored yet. */
    REVIEW,
    SAVING,

    /** Stored locally (outbox); the screen leaves. */
    SAVED,
}

data class MesocycleGeneratorUiState(
    val access: MesocycleAccess = MesocycleAccess.CHECKING,
    val online: Boolean = true,
    val params: MesocycleParams = MesocycleParams(),
    val phase: MesocyclePhase = MesocyclePhase.FORM,
    val draft: MesocycleDraft? = null,
    /** Why the last generation failed (retry keeps the same request id, so it is never paid twice). */
    val failure: CoachFailure? = null,
    /** Generation was asked for without a connection: nothing was sent. */
    val offlineNotice: Boolean = false,
    val saveFailed: Boolean = false,
    val savedMesocycleId: String? = null,
) {
    val canGenerate: Boolean get() = access == MesocycleAccess.AVAILABLE && phase == MesocyclePhase.FORM && params.isValid
    val canSave: Boolean get() = phase == MesocyclePhase.REVIEW && draft?.canSave == true
}

/**
 * Generator + review of an AI mesocycle. The request id of the pending generation and the parameters survive
 * process death (SavedStateHandle): asking again with the same parameters reuses the id, so the server returns
 * the draft it already made instead of generating (and charging) a second one. The draft itself lives here and
 * is edited in memory; only "Guardar" writes, as one local mutation.
 */
class MesocycleGeneratorViewModel(
    private val repository: MesocycleRepository,
    entitlements: EntitlementReader,
    online: Flow<Boolean>,
    private val savedState: SavedStateHandle,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(MesocycleGeneratorUiState(params = restoredParams()))
    val state: StateFlow<MesocycleGeneratorUiState> = _state.asStateFlow()

    private var devicePro: Boolean? = null
    private var lockedByServer = false
    private var job: Job? = null

    init {
        viewModelScope.launch {
            entitlements.observeIsPro().catch { emit(false) }.distinctUntilChanged().collect { pro ->
                if (pro != devicePro) lockedByServer = false
                devicePro = pro
                _state.update { it.copy(access = access()) }
            }
        }
        viewModelScope.launch {
            online.distinctUntilChanged().collect { up -> _state.update { it.copy(online = up, offlineNotice = it.offlineNotice && !up) } }
        }
    }

    private fun access() = when {
        devicePro == null -> MesocycleAccess.CHECKING
        devicePro == true && !lockedByServer -> MesocycleAccess.AVAILABLE
        else -> MesocycleAccess.LOCKED
    }

    private fun restoredParams(): MesocycleParams {
        val defaults = MesocycleParams()
        return MesocycleParams(
            goal = savedState.get<String>(KEY_GOAL)?.let { wire -> MesocycleGoal.entries.firstOrNull { it.wire == wire } } ?: defaults.goal,
            daysPerWeek = savedState.get<Int>(KEY_DAYS) ?: defaults.daysPerWeek,
            weeks = savedState.get<Int>(KEY_WEEKS) ?: defaults.weeks,
            minutesPerSession = savedState.get<Int>(KEY_MINUTES) ?: defaults.minutesPerSession,
        )
    }

    // ---- Form ----

    fun onGoal(goal: MesocycleGoal) = editParams { it.copy(goal = goal) }

    fun onDays(delta: Int) = editParams { it.copy(daysPerWeek = (it.daysPerWeek + delta).coerceIn(MesocycleParams.DAYS)) }

    fun onWeeks(delta: Int) = editParams { it.copy(weeks = (it.weeks + delta).coerceIn(MesocycleParams.WEEKS)) }

    fun onMinutes(delta: Int) =
        editParams { it.copy(minutesPerSession = (it.minutesPerSession + delta * MesocycleParams.MINUTES_STEP).coerceIn(MesocycleParams.MINUTES)) }

    private fun editParams(change: (MesocycleParams) -> MesocycleParams) {
        if (_state.value.phase != MesocyclePhase.FORM) return
        val next = change(_state.value.params)
        savedState[KEY_GOAL] = next.goal.wire
        savedState[KEY_DAYS] = next.daysPerWeek
        savedState[KEY_WEEKS] = next.weeks
        savedState[KEY_MINUTES] = next.minutesPerSession
        _state.update { it.copy(params = next, failure = null) }
    }

    /** Generates with the current parameters; a retry (same parameters, no draft accepted yet) reuses the request id. */
    fun generate() {
        val s = _state.value
        if (!s.canGenerate) return
        if (!s.online) {
            _state.update { it.copy(offlineNotice = true) }
            return
        }
        val params = s.params
        val key = params.key()
        val requestId = savedState.get<String>(KEY_REQUEST_ID)?.takeIf { savedState.get<String>(KEY_REQUEST_PARAMS) == key } ?: newRequestId()
        savedState[KEY_REQUEST_ID] = requestId
        savedState[KEY_REQUEST_PARAMS] = key
        _state.update { it.copy(phase = MesocyclePhase.GENERATING, failure = null, offlineNotice = false) }
        job = viewModelScope.launch {
            try {
                val draft = repository.generate(requestId, params)
                _state.update { it.copy(phase = MesocyclePhase.REVIEW, draft = draft) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = (e as? CoachException)?.failure ?: CoachFailure.INTERRUPTED
                if (failure == CoachFailure.PRO_REQUIRED) lockedByServer = true
                _state.update { it.copy(phase = MesocyclePhase.FORM, failure = failure, access = access()) }
            }
        }
    }

    /** Drops the draft under review and asks for a new one (a new request id: a new generation). */
    fun regenerate() {
        if (_state.value.phase != MesocyclePhase.REVIEW) return
        forgetRequest()
        _state.update { it.copy(phase = MesocyclePhase.FORM, draft = null, saveFailed = false) }
        generate()
    }

    /** Back to the form without saving; the draft is discarded. */
    fun discard() {
        if (_state.value.phase != MesocyclePhase.REVIEW) return
        forgetRequest()
        _state.update { it.copy(phase = MesocyclePhase.FORM, draft = null, saveFailed = false) }
    }

    private fun forgetRequest() {
        savedState.remove<String>(KEY_REQUEST_ID)
        savedState.remove<String>(KEY_REQUEST_PARAMS)
    }

    // ---- Review edits (in memory) ----

    fun onRename(name: String) = editDraft { it.copy(name = name.take(MesocycleDraft.MAX_NAME_CHARS)) }

    fun onRemoveExercise(dayKey: String, exerciseKey: String) =
        editExercises(dayKey) { list -> list.filterNot { it.key == exerciseKey } }

    fun onSets(dayKey: String, exerciseKey: String, delta: Int) = editExercise(dayKey, exerciseKey) {
        it.copy(sets = (it.sets + delta).coerceIn(MesocycleDraft.SETS))
    }

    fun onReps(dayKey: String, exerciseKey: String, delta: Int) = editExercise(dayKey, exerciseKey) {
        it.copy(reps = (it.reps + delta).coerceIn(MesocycleDraft.REPS))
    }

    private fun editExercise(dayKey: String, exerciseKey: String, change: (MesocycleDraftExercise) -> MesocycleDraftExercise) =
        editExercises(dayKey) { list -> list.map { if (it.key == exerciseKey) change(it) else it } }

    private fun editExercises(dayKey: String, change: (List<MesocycleDraftExercise>) -> List<MesocycleDraftExercise>) = editDraft { draft ->
        draft.copy(days = draft.days.map { day: MesocycleDraftDay -> if (day.key == dayKey) day.copy(exercises = change(day.exercises)) else day })
    }

    private fun editDraft(change: (MesocycleDraft) -> MesocycleDraft) {
        _state.update { s ->
            if (s.phase != MesocyclePhase.REVIEW || s.draft == null) s else s.copy(draft = change(s.draft), saveFailed = false)
        }
    }

    // ---- Save ----

    /** Saves the reviewed draft as one local mutation; works offline (it syncs later). */
    fun save() {
        val s = _state.value
        val draft = s.draft ?: return
        if (!s.canSave) return
        _state.update { it.copy(phase = MesocyclePhase.SAVING, saveFailed = false) }
        viewModelScope.launch {
            try {
                val id = repository.save(draft)
                forgetRequest()
                _state.update { it.copy(phase = MesocyclePhase.SAVED, savedMesocycleId = id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = MesocyclePhase.REVIEW, saveFailed = true) }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(offlineNotice = false, failure = null, saveFailed = false) }

    private fun MesocycleParams.key() = "${goal.wire}|$daysPerWeek|$weeks|$minutesPerSession"
}
