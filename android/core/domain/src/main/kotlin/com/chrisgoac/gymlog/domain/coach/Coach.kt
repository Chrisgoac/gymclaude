package com.chrisgoac.gymlog.domain.coach

import kotlinx.coroutines.flow.Flow

/** `coach_messages.rol`. */
enum class CoachRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    ;

    companion object {
        fun fromWire(value: String): CoachRole? = entries.firstOrNull { it.wire == value }
    }
}

/** One persisted turn of the account's single Coach thread (ordered by [createdAt]). */
data class CoachMessage(val id: String, val role: CoachRole, val text: String, val createdAt: Long)

/** What a Coach turn streams back. The answer is the concatenation of the [Delta]s. */
sealed interface CoachStreamUpdate {
    /** The server accepted the turn; [responseId] is stable across retries of the same message. */
    data class Started(val responseId: String) : CoachStreamUpdate

    data class Delta(val text: String) : CoachStreamUpdate

    /** The answer is complete and stored locally as the assistant message [responseId]. */
    data class Completed(val responseId: String) : CoachStreamUpdate
}

/** Why a Coach call failed; decides what the screen offers. */
enum class CoachFailure {
    /** The connection dropped or the stream ended early: retry the same message. */
    INTERRUPTED,

    /** The server says the account is not Pro (`403 PRO_REQUIRED`). */
    PRO_REQUIRED,

    /** Another attempt for the same message is still generating (`409`), even after waiting. */
    BUSY,

    /** The service is temporarily unavailable (provider, rate limit, entitlement store, 5xx): retry later. */
    UNAVAILABLE,

    /** The session needs attention (sign in again); nothing else is wrong with the message. */
    SESSION,

    /** The server refused the request for good (invalid request, unknown response): retrying will not help. */
    REJECTED,

    /** No account database is open. */
    NO_ACCOUNT,
    ;

    val retryable: Boolean get() = this == INTERRUPTED || this == BUSY || this == UNAVAILABLE
}

class CoachException(val failure: CoachFailure, cause: Throwable? = null) : Exception("Coach failure: $failure", cause)

/** `AI_REPORT_CATEGORIES`. */
enum class CoachReportCategory { INACCURATE, HARMFUL, MEDICAL, OFFENSIVE, OTHER }

object CoachLimits {
    /** Longest question the server accepts. */
    const val MAX_TEXT_CHARS = 2000

    /** Longest optional comment on a report. */
    const val MAX_REPORT_CONTEXT_CHARS = 500
}

/**
 * The Pro Coach of the open account: one persistent thread (synced `coach_messages`) and streamed turns.
 * The network is required: a turn is never queued. Pro is enforced by the server; callers only gate
 * the presentation.
 */
interface CoachRepository {
    /** The thread, oldest first; re-emits when a message is stored locally or arrives by sync. */
    fun observeThread(): Flow<List<CoachMessage>>

    /**
     * One turn. The user message [clientMessageId] is stored first (outbox) unless it already exists, so a
     * retry with the same id never duplicates it (and resends the stored text). Then the answer streams;
     * on completion the assistant message is stored under the response id unless it already exists. A
     * fresh snapshot of the current local data is sent with every attempt. Fails with [CoachException].
     */
    fun send(clientMessageId: String, text: String): Flow<CoachStreamUpdate>

    /** Reports a stored assistant response. Fails with [CoachException]. */
    suspend fun report(responseId: String, category: CoachReportCategory, context: String?)
}
