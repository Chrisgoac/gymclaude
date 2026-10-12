package com.chrisgoac.gymlog.domain.timer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Durable storage of the signed-in account's single rest-timer run (null = none). Implemented in app (DataStore). */
interface RestTimerStore {
    suspend fun read(): RestTimerState?
    suspend fun write(state: RestTimerState?)
}

/** Background wake-up for the end of a run (alarm + local notification). Implemented in app. */
interface RestTimerAlarm {
    /** Arms (replacing any previous) the wake-up for [endsAtEpochMillis]. */
    fun schedule(runId: String, sessionId: String, endsAtEpochMillis: Long)

    /** Disarms the wake-up and dismisses a shown notification. */
    fun cancel()
}

/** The end of run [runId] was reached; [lateMillis] is how long ago (large after a long background stay). */
data class RestTimerCompletion(val runId: String, val lateMillis: Long)

/**
 * Owns the rest timer: state in [store], wake-up in [alarm]. The completion event is exactly-once
 * per run: the delivered marker is persisted *before* the event is emitted, so neither a repeated
 * tick nor a restored process emits it again.
 */
class RestTimerController(
    private val store: RestTimerStore,
    private val alarm: RestTimerAlarm,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<RestTimerState?>(null)
    val state: StateFlow<RestTimerState?> = _state.asStateFlow()

    private val _completions = MutableSharedFlow<RestTimerCompletion>(extraBufferCapacity = 8)
    val completions: SharedFlow<RestTimerCompletion> = _completions.asSharedFlow()

    fun now(): Long = clock()

    /** Loads the persisted run for the workout [sessionId]; a run of another workout is dropped. */
    suspend fun restore(sessionId: String) = mutex.withLock {
        val saved = safeRead()
        when {
            saved == null -> {
                _state.value = null
                alarm.cancel() // a wake-up armed for a previous account/run must not outlive it
            }
            saved.sessionId != sessionId -> clearLocked()
            else -> {
                _state.value = saved
                // An end already in the past is not armed: tick() delivers it right away.
                if (!RestTimer.isPaused(saved) && !saved.completionDelivered && saved.endsAtEpochMillis > clock()) {
                    alarm.schedule(saved.runId, saved.sessionId, saved.endsAtEpochMillis)
                }
            }
        }
    }

    /** Starts a fresh countdown for [seconds] (ignored when null or not positive). */
    suspend fun start(sessionId: String, seconds: Int?) = mutex.withLock {
        val next = RestTimer.startOrNull(newRunId(), sessionId, clock(), seconds) ?: return@withLock
        apply(next)
        alarm.schedule(next.runId, next.sessionId, next.endsAtEpochMillis)
    }

    suspend fun pause() = update { RestTimer.pause(it, clock()) }.also { if (it?.let(RestTimer::isPaused) == true) alarm.cancel() }

    suspend fun resume() {
        val next = update { RestTimer.resume(it, clock()) } ?: return
        if (!RestTimer.isPaused(next) && !next.completionDelivered) alarm.schedule(next.runId, next.sessionId, next.endsAtEpochMillis)
    }

    /**
     * Adds time. Extending a run that already ended (delivered or not) starts a NEW run; the old
     * run's completion is dropped (never delivered late), and its alarm and any "Descanso terminado"
     * notification are cancelled before the new wake-up is armed.
     */
    suspend fun extend(seconds: Int) {
        val before = _state.value
        val next = update { RestTimer.extend(it, clock(), seconds, newRunId()) } ?: return
        if (before != null && next.runId != before.runId) alarm.cancel()
        if (!RestTimer.isPaused(next) && !next.completionDelivered) alarm.schedule(next.runId, next.sessionId, next.endsAtEpochMillis)
    }

    /**
     * The signed-in account changed or signed out: forgets the in-memory run and disarms the
     * wake-up, WITHOUT touching storage (the run still belongs to that account's own file).
     */
    suspend fun detach() = mutex.withLock {
        _state.value = null
        alarm.cancel()
    }

    /** Skip / workout finished / sign-out: forgets the run and disarms the wake-up. */
    suspend fun reset() = mutex.withLock { clearLocked() }

    /** Emits the completion once when a running run has reached its end. Cheap; call from the UI tick. */
    suspend fun tick() = mutex.withLock {
        val current = _state.value ?: return@withLock
        val now = clock()
        if (!RestTimer.completionDue(current, now)) return@withLock
        apply(RestTimer.markDelivered(current))
        alarm.cancel() // also dismisses the notification the alarm may have raised meanwhile
        _completions.tryEmit(RestTimerCompletion(current.runId, lateMillis = now - current.endsAtEpochMillis))
    }

    private suspend fun update(transform: (RestTimerState) -> RestTimerState): RestTimerState? = mutex.withLock {
        val current = _state.value ?: return@withLock null
        val next = transform(current)
        if (next != current) apply(next)
        next
    }

    private suspend fun apply(next: RestTimerState) {
        _state.value = next
        safeWrite(next)
    }

    private suspend fun clearLocked() {
        _state.value = null
        safeWrite(null)
        alarm.cancel()
    }

    // A storage failure must not break the workout screen: the timer then lives in memory only.
    private suspend fun safeRead(): RestTimerState? = try {
        store.read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun safeWrite(state: RestTimerState?) {
        try {
            store.write(state)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
