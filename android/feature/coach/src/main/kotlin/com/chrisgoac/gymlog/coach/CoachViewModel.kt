package com.chrisgoac.gymlog.coach

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.coach.CoachException
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.coach.CoachLimits
import com.chrisgoac.gymlog.domain.coach.CoachMessage
import com.chrisgoac.gymlog.domain.coach.CoachReportCategory
import com.chrisgoac.gymlog.domain.coach.CoachRepository
import com.chrisgoac.gymlog.domain.coach.CoachRole
import com.chrisgoac.gymlog.domain.coach.CoachStreamUpdate
import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
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
import java.util.UUID

private const val KEY_DRAFT = "coach.draft"

/** Whether the Coach can be used here. Presentation only: the server decides Pro for every request. */
enum class CoachAccess { CHECKING, LOCKED, AVAILABLE }

/** Short-lived messages shown above the composer. */
enum class CoachNotice { OFFLINE, REPORT_SENT }

enum class TurnPhase {
    /** Request sent, nothing back yet (includes waiting out another attempt of the same message). */
    SENDING,

    /** The answer is arriving. */
    STREAMING,

    /** Complete and stored; shown until the stored message reaches the thread. */
    COMPLETING,

    /** Stopped before completion: what arrived is shown as incomplete and is never stored. */
    INCOMPLETE,
}

/**
 * The turn in flight (in memory only: the question is already stored, the answer is stored only when
 * complete). [text] is the answer received so far.
 */
data class CoachTurn(
    val clientMessageId: String,
    val responseId: String?,
    val text: String,
    val phase: TurnPhase,
    val failure: CoachFailure? = null,
) {
    val canRetry: Boolean get() = phase == TurnPhase.INCOMPLETE && failure?.retryable == true
    val inFlight: Boolean get() = phase == TurnPhase.SENDING || phase == TurnPhase.STREAMING
}

data class CoachReportDraft(
    val responseId: String,
    val category: CoachReportCategory? = null,
    val sending: Boolean = false,
    val failure: CoachFailure? = null,
) {
    val canSubmit: Boolean get() = category != null && !sending
}

data class CoachUiState(
    val access: CoachAccess = CoachAccess.CHECKING,
    val threadLoaded: Boolean = false,
    val messages: List<CoachMessage> = emptyList(),
    val draft: String = "",
    val online: Boolean = true,
    val turn: CoachTurn? = null,
    val notice: CoachNotice? = null,
    val report: CoachReportDraft? = null,
) {
    val canSend: Boolean
        get() = access == CoachAccess.AVAILABLE && online && turn?.inFlight != true && draft.isNotBlank()
}

/**
 * The Coach screen. The state lives here, not in the composition: a turn keeps streaming through a
 * rotation (nothing observes the screen meanwhile) and the typed question survives process death.
 * Nothing is queued: without a connection the screen says so and sends nothing.
 */
class CoachViewModel(
    private val repository: CoachRepository,
    entitlements: EntitlementReader,
    online: Flow<Boolean>,
    private val savedState: SavedStateHandle,
    private val newMessageId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(CoachUiState(draft = savedState.get<String>(KEY_DRAFT).orEmpty()))
    val state: StateFlow<CoachUiState> = _state.asStateFlow()

    /** The server answered PRO_REQUIRED: locked until the entitlement changes. */
    private var lockedByServer = false
    private var devicePro: Boolean? = null
    private var turnJob: Job? = null

    /** Unanswered questions the user dismissed: not offered again while this screen lives. */
    private val dismissed = HashSet<String>()

    init {
        viewModelScope.launch {
            entitlements.observeIsPro().catch { emit(false) }.distinctUntilChanged().collect { pro ->
                if (pro != devicePro) lockedByServer = false
                devicePro = pro
                _state.update { it.copy(access = access()) }
            }
        }
        viewModelScope.launch {
            online.distinctUntilChanged().collect { up ->
                _state.update { s -> s.copy(online = up, notice = if (up && s.notice == CoachNotice.OFFLINE) null else s.notice) }
            }
        }
        viewModelScope.launch {
            repository.observeThread().catch { emit(emptyList()) }.collect { messages ->
                _state.update { s ->
                    val turn = s.turn?.takeUnless { t -> t.phase == TurnPhase.COMPLETING && messages.any { it.id == t.responseId } }
                    s.copy(threadLoaded = true, messages = messages, turn = turn ?: unanswered(messages))
                }
            }
        }
    }

    /**
     * The thread ends with a question and nothing is in flight here: its answer was cut (the screen was left
     * mid-answer, the process died) or is still coming from another device. Offered as an interrupted turn,
     * so retrying reuses its id: the server replays a finished answer or regenerates it, never duplicating.
     */
    private fun unanswered(messages: List<CoachMessage>): CoachTurn? {
        val last = messages.lastOrNull()?.takeIf { it.role == CoachRole.USER } ?: return null
        if (last.id in dismissed) return null
        return CoachTurn(last.id, null, "", TurnPhase.INCOMPLETE, CoachFailure.INTERRUPTED)
    }

    private fun access(): CoachAccess = when (devicePro) {
        null -> CoachAccess.CHECKING
        false -> CoachAccess.LOCKED
        true -> if (lockedByServer) CoachAccess.LOCKED else CoachAccess.AVAILABLE
    }

    fun onDraftChange(text: String) {
        val clipped = text.take(CoachLimits.MAX_TEXT_CHARS)
        savedState[KEY_DRAFT] = clipped
        _state.update { it.copy(draft = clipped) }
    }

    fun send() {
        val question = _state.value.draft.trim()
        if (question.isEmpty() || !ready()) return
        onDraftChange("")
        start(newMessageId(), question)
    }

    fun sendSuggestion(prompt: String) {
        if (!ready()) return
        start(newMessageId(), prompt)
    }

    /** Resends the interrupted message with the SAME id: the server replays or regenerates, never duplicates. */
    fun retry() {
        val turn = _state.value.turn ?: return
        if (!turn.canRetry || !ready()) return
        val question = _state.value.messages.firstOrNull { it.id == turn.clientMessageId }?.text.orEmpty()
        start(turn.clientMessageId, question)
    }

    fun dismissTurn() {
        val turn = _state.value.turn ?: return
        if (turn.inFlight) return
        dismissed += turn.clientMessageId
        _state.update { it.copy(turn = null) }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** Available, no turn in flight and online; offline only explains itself. */
    private fun ready(): Boolean {
        val s = _state.value
        if (s.access != CoachAccess.AVAILABLE || s.turn?.inFlight == true) return false
        if (!s.online) {
            _state.update { it.copy(notice = CoachNotice.OFFLINE) }
            return false
        }
        return true
    }

    private fun start(clientMessageId: String, question: String) {
        _state.update { it.copy(turn = CoachTurn(clientMessageId, null, "", TurnPhase.SENDING), notice = null) }
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            try {
                repository.send(clientMessageId, question).collect { update -> apply(clientMessageId, update) }
                // The flow ending without Completed cannot happen (the repository throws), but never leave a spinner.
                updateTurn(clientMessageId) { t -> if (t.phase == TurnPhase.COMPLETING) t else t.copy(phase = TurnPhase.INCOMPLETE, failure = CoachFailure.INTERRUPTED) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoachException) {
                fail(clientMessageId, e.failure)
            } catch (e: Exception) {
                fail(clientMessageId, CoachFailure.INTERRUPTED)
            }
        }
    }

    private fun apply(clientMessageId: String, update: CoachStreamUpdate) = when (update) {
        is CoachStreamUpdate.Started -> updateTurn(clientMessageId) { it.copy(responseId = update.responseId, text = "", phase = TurnPhase.STREAMING) }
        is CoachStreamUpdate.Delta -> updateTurn(clientMessageId) { it.copy(text = it.text + update.text, phase = TurnPhase.STREAMING) }
        is CoachStreamUpdate.Completed -> _state.update { s ->
            val turn = s.turn?.takeIf { it.clientMessageId == clientMessageId } ?: return@update s
            // Already in the thread (stored before Completed): drop the ephemeral copy now.
            if (s.messages.any { it.id == update.responseId }) s.copy(turn = null)
            else s.copy(turn = turn.copy(responseId = update.responseId, phase = TurnPhase.COMPLETING))
        }
    }

    private fun fail(clientMessageId: String, failure: CoachFailure) {
        if (failure == CoachFailure.PRO_REQUIRED) {
            lockedByServer = true
            _state.update { s -> s.copy(access = access(), turn = s.turn?.takeUnless { it.clientMessageId == clientMessageId }) }
            return
        }
        updateTurn(clientMessageId) { it.copy(phase = TurnPhase.INCOMPLETE, failure = failure) }
    }

    private fun updateTurn(clientMessageId: String, change: (CoachTurn) -> CoachTurn) = _state.update { s ->
        val turn = s.turn?.takeIf { it.clientMessageId == clientMessageId } ?: return@update s
        s.copy(turn = change(turn))
    }

    // ---- Report ----

    fun openReport(responseId: String) = _state.update { it.copy(report = CoachReportDraft(responseId)) }

    fun selectReportCategory(category: CoachReportCategory) = _state.update { s -> s.copy(report = s.report?.copy(category = category, failure = null)) }

    fun closeReport() = _state.update { s -> if (s.report?.sending == true) s else s.copy(report = null) }

    fun submitReport() {
        val draft = _state.value.report ?: return
        val category = draft.category ?: return
        if (draft.sending) return
        _state.update { it.copy(report = draft.copy(sending = true, failure = null)) }
        viewModelScope.launch {
            try {
                repository.report(draft.responseId, category, context = null)
                _state.update { it.copy(report = null, notice = CoachNotice.REPORT_SENT) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = (e as? CoachException)?.failure ?: CoachFailure.INTERRUPTED
                _state.update { s -> s.copy(report = s.report?.copy(sending = false, failure = failure)) }
            }
        }
    }

    companion object {
        /** The PWA's suggested questions (components/coach-chat.tsx `INSIGHTS`). */
        val SUGGESTED_PROMPTS = listOf("Analiza mi semana", "¿Por qué estoy estancado?", "¿Qué ejercicio sustituyo?")
    }
}
