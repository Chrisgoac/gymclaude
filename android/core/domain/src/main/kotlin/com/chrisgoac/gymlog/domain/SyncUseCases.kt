package com.chrisgoac.gymlog.domain

import kotlinx.coroutines.flow.Flow

/** Outcome of one [SyncRepository.syncOnce] run. [quarantined] counts mutations quarantined during that run. */
sealed interface SyncResult {
    val quarantined: Int

    /** Outbox drained (minus quarantined rows) and every pull page applied. */
    data class Success(val pushed: Int, val pulled: Int, override val quarantined: Int) : SyncResult

    /**
     * Offline, timeout or a retryable server error. Retry later with backoff; wait at least
     * [retryAfterMillis] when the server asked for it (429 `Retry-After`).
     */
    data class Retryable(val retryAfterMillis: Long?, override val quarantined: Int) : SyncResult

    /** No usable session (401/403 or no token): nothing was deleted or quarantined; sign in again. */
    data class SessionRequired(override val quarantined: Int) : SyncResult

    /** A non-retryable error that is not tied to one mutation (e.g. a pull rejected as invalid). */
    data class Failed(val code: String, override val quarantined: Int) : SyncResult
}

/** Snapshot of the open account's sync state, for status UI. */
data class SyncStatus(
    val running: Boolean,
    /** Local mutations waiting to be pushed. */
    val pendingMutations: Int,
    /** Local mutations the server rejected for good: kept locally, flagged, never resent. */
    val quarantinedMutations: Int,
    /** False while a (replacement) bootstrap is downloading: local data is not a complete copy. */
    val restoreComplete: Boolean,
    val lastResult: SyncResult?,
)

/** Offline-first convergence of the open account's database with the cloud (spec 10). */
interface SyncRepository {
    fun observeStatus(): Flow<SyncStatus>

    /** Push the outbox, then pull until up to date. At most one run per user at a time. */
    suspend fun syncOnce(): SyncResult
}

/** What features may do with sync. */
interface SyncUseCases {
    fun observeStatus(): Flow<SyncStatus>
    suspend fun syncNow(): SyncResult
}

class DefaultSyncUseCases(private val repository: SyncRepository) : SyncUseCases {
    override fun observeStatus(): Flow<SyncStatus> = repository.observeStatus()
    override suspend fun syncNow(): SyncResult = repository.syncOnce()
}

/**
 * Time source for `updatedAt`/`deletedAt` of local writes: device time corrected by the offset to
 * the server clock learned from response `Date` headers (contracts/v1/README.md, Clock).
 */
fun interface SyncClock {
    fun nowMillis(): Long
}
