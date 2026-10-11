package com.chrisgoac.gymlog.data.coach

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CoachMessageEntity
import com.chrisgoac.gymlog.domain.coach.CoachException
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.coach.CoachLimits
import com.chrisgoac.gymlog.domain.coach.CoachMessage
import com.chrisgoac.gymlog.domain.coach.CoachReportCategory
import com.chrisgoac.gymlog.domain.coach.CoachRepository
import com.chrisgoac.gymlog.domain.coach.CoachRole
import com.chrisgoac.gymlog.domain.coach.CoachStreamUpdate
import com.chrisgoac.gymlog.domain.stats.CoachSnapshot
import com.chrisgoac.gymlog.model.CoachMessageRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.ai.AiReportCategory
import com.chrisgoac.gymlog.model.ai.CoachRequestDto
import com.chrisgoac.gymlog.model.ai.CoachSnapshotDto
import com.chrisgoac.gymlog.model.ai.CoachLimits as WireLimits
import com.chrisgoac.gymlog.network.CoachApi
import com.chrisgoac.gymlog.network.CoachStreamEvent
import com.chrisgoac.gymlog.network.SyncApiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [CoachRepository] over the open account's Room database and the v1 Coach endpoint.
 *
 * Message ids follow the contract: the user message is stored under the turn's `clientMessageId` and the
 * assistant message under the server's `responseId`, both through the outbox, so the same turn seen
 * again (a retry replay, a sync from another device) never creates a second row. Nothing is logged: the
 * question, the answer and tokens never reach logs.
 *
 * [snapshot] is read for every attempt (current Room data, never cached) from the same open database the
 * turn was started on, so an account switch mid-turn can never mix another account's data into the request.
 * A `409 AI_RESPONSE_IN_PROGRESS` (another attempt of the same message holds the server lease) is waited
 * out with [inProgressBackoffMillis] (a server `Retry-After` is honoured up to [MAX_RETRY_AFTER_SECONDS])
 * before giving up as [CoachFailure.BUSY].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineCoachRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
    private val api: CoachApi,
    private val snapshot: suspend (GymLogDatabase) -> CoachSnapshot,
    private val inProgressBackoffMillis: List<Long> = DEFAULT_IN_PROGRESS_BACKOFF_MILLIS,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : CoachRepository {

    override fun observeThread(): Flow<List<CoachMessage>> = databases.current.flatMapLatest { open ->
        if (open == null) flowOf(emptyList()) else open.database.coachMessageDao().observeLive().map { rows -> rows.mapNotNull { it.toMessage() } }
    }.distinctUntilChanged()

    override fun send(clientMessageId: String, text: String): Flow<CoachStreamUpdate> = flow {
        val open = databases.current.value ?: throw CoachException(CoachFailure.NO_ACCOUNT)
        val question = storeUserMessage(open, clientMessageId, text)
        var waits = 0
        while (true) {
            try {
                val request = CoachRequestDto(clientMessageId, question, snapshot(open.database).toDto())
                streamOnce(open, request) { emit(it) }
                return@flow
            } catch (e: SyncApiException.Api) {
                if (e.rawCode != IN_PROGRESS || waits >= inProgressBackoffMillis.size) throw e.toCoach()
                wait(retryAfterMillis(e.retryAfterSeconds) ?: inProgressBackoffMillis[waits])
                waits++
            } catch (e: SyncApiException) {
                throw e.toCoach()
            }
        }
    }

    override suspend fun report(responseId: String, category: CoachReportCategory, context: String?) {
        val open = databases.current.value ?: throw CoachException(CoachFailure.NO_ACCOUNT)
        val note = context?.trim()?.takeIf { it.isNotEmpty() }?.take(CoachLimits.MAX_REPORT_CONTEXT_CHARS)?.withoutNul()
        try {
            api.report(open.userId, responseId, AiReportCategory.valueOf(category.name), note)
        } catch (e: SyncApiException) {
            throw e.toCoach()
        }
    }

    /** One attempt; completes normally only after the answer was stored. */
    private suspend fun streamOnce(open: OpenUserDatabase, request: CoachRequestDto, emit: suspend (CoachStreamUpdate) -> Unit) {
        val answer = StringBuilder()
        var responseId: String? = null
        var completed = false
        try {
            api.coach(open.userId, request) { event ->
                if (completed) return@coach
                when (event) {
                    is CoachStreamEvent.Start -> {
                        responseId = event.responseId
                        emit(CoachStreamUpdate.Started(event.responseId))
                    }
                    is CoachStreamEvent.Delta -> {
                        answer.append(event.text)
                        emit(CoachStreamUpdate.Delta(event.text))
                    }
                    is CoachStreamEvent.Complete -> {
                        // A complete whose id is not the started one is not this turn's answer.
                        if (responseId != null && responseId != event.responseId) throw CoachException(CoachFailure.INTERRUPTED)
                        storeAssistantMessage(open, event.responseId, answer.toString())
                        completed = true
                        emit(CoachStreamUpdate.Completed(event.responseId))
                    }
                    is CoachStreamEvent.Error ->
                        throw CoachException(if (event.retryable) CoachFailure.UNAVAILABLE else CoachFailure.REJECTED)
                }
            }
        } catch (e: SyncApiException) {
            // The answer is already stored: a connection drop while closing the stream is not a failure
            // (surfacing it would show a duplicate "incomplete" turn under the completed one).
            if (!completed || !(e is SyncApiException.Transport || e is SyncApiException.Malformed)) throw e
        }
        if (!completed) throw CoachException(CoachFailure.INTERRUPTED)
    }

    /** Stores the question unless the message already exists (retry); returns the text to send. */
    private suspend fun storeUserMessage(open: OpenUserDatabase, id: String, text: String): String {
        val question = text.trim().withoutNul()
        return runnerFor(open.database).run(
            LocalMutation {
                val existing = find(SyncTable.COACH_MESSAGES, RecordId(id)) as? CoachMessageRecord
                if (existing != null) return@LocalMutation existing.contenido
                if (question.isEmpty() || question.length > CoachLimits.MAX_TEXT_CHARS) throw CoachException(CoachFailure.REJECTED)
                val stamp = nextTimestamp(SyncTable.COACH_MESSAGES, RecordId(id))
                put(CoachMessageRecord(RecordId(id), stamp, null, CoachRole.USER.wire, question, stamp))
                question
            },
        )
    }

    /** Stores the answer under [responseId] unless that message already exists (replay, synced from another device). */
    private suspend fun storeAssistantMessage(open: OpenUserDatabase, responseId: String, text: String) {
        runnerFor(open.database).run(
            LocalMutation {
                if (find(SyncTable.COACH_MESSAGES, RecordId(responseId)) != null) return@LocalMutation
                val stamp = nextTimestamp(SyncTable.COACH_MESSAGES, RecordId(responseId))
                put(CoachMessageRecord(RecordId(responseId), stamp, null, CoachRole.ASSISTANT.wire, text.withoutNul(), stamp))
            },
        )
    }

    private fun CoachMessageEntity.toMessage(): CoachMessage? =
        CoachRole.fromWire(rol)?.let { CoachMessage(id, it, contenido, createdAt) }

    companion object {
        /** ~14 s in total: a live attempt's lease ends when it finishes or fails, at worst 60 s later. */
        val DEFAULT_IN_PROGRESS_BACKOFF_MILLIS = listOf(2_000L, 4_000L, 8_000L)

        /** Longest `Retry-After` the in-progress wait honours: a larger one (or a daily quota) is not waited out inline. */
        const val MAX_RETRY_AFTER_SECONDS = 15L
        private const val IN_PROGRESS = "AI_RESPONSE_IN_PROGRESS"
        private const val PRO_REQUIRED = "PRO_REQUIRED"
    }

    private fun SyncApiException.toCoach(): CoachException = CoachException(
        when (this) {
            is SyncApiException.Api -> when {
                rawCode == PRO_REQUIRED -> CoachFailure.PRO_REQUIRED
                rawCode == IN_PROGRESS -> CoachFailure.BUSY
                retryable || httpStatus == 429 || httpStatus >= 500 -> CoachFailure.UNAVAILABLE
                else -> CoachFailure.REJECTED
            }
            is SyncApiException.Transport, is SyncApiException.Malformed -> CoachFailure.INTERRUPTED
            is SyncApiException.SessionRequired, is SyncApiException.SessionRejected -> CoachFailure.SESSION
        },
        this,
    )
}

/** A server `Retry-After` in ms, capped at [OfflineCoachRepository.MAX_RETRY_AFTER_SECONDS]; null when absent. */
internal fun retryAfterMillis(seconds: Long?): Long? =
    seconds?.coerceIn(0L, OfflineCoachRepository.MAX_RETRY_AFTER_SECONDS)?.let { it * 1000 }

/** The wire allowlist of a [CoachSnapshot]; labels are clipped to the server's bounds (a blank one becomes "?"). */
fun CoachSnapshot.toDto(): CoachSnapshotDto = CoachSnapshotDto(
    estancados = stalls.map { CoachSnapshotDto.Estancado(it.exercise.label(WireLimits.MAX_EXERCISE_NAME_CHARS), it.sessionsWithoutImprovement) },
    semana = CoachSnapshotDto.Semana(
        sesiones = week.sessions,
        objetivo = week.target,
        volumen = week.volume,
        deltaPct = week.deltaPct,
        prs = week.records.take(WireLimits.MAX_WEEK_PRS).map { CoachSnapshotDto.Pr(it.exercise.label(WireLimits.MAX_EXERCISE_NAME_CHARS), it.kind.wire) },
    ),
    grupos = groups.map {
        CoachSnapshotDto.Grupo(it.group.label(WireLimits.MAX_GROUP_LABEL_CHARS), it.weekVolume, it.daysWithoutTraining, it.target)
    },
    cuerpo = CoachSnapshotDto.Cuerpo(
        peso = body.weight?.let { CoachSnapshotDto.Resumen(it.current, it.delta4Weeks) },
        medidas = body.measures.map { CoachSnapshotDto.Medida(it.metric.label(WireLimits.MAX_METRIC_LABEL_CHARS), it.current, it.delta4Weeks) },
    ),
)

/** The server counts UTF-16 units (JS `length`); a cut never splits a surrogate pair. */
private fun String.label(max: Int): String {
    val clean = withoutNul().trim()
    if (clean.isEmpty()) return "?"
    if (clean.length <= max) return clean
    val cut = clean.substring(0, max)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

private fun String.withoutNul(): String = replace("\u0000", "")
