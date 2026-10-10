package com.chrisgoac.gymlog.domain

/** What the user needs to know about sync, derived from [SyncStatus]. */
sealed interface SyncUiState {
    /** Nothing waiting. */
    data object Synced : SyncUiState

    /** [count] local changes will be sent shortly. */
    data class Pending(val count: Int) : SyncUiState

    /** The last attempt could not reach the server; [count] local changes are kept and will be sent later. */
    data class Offline(val count: Int) : SyncUiState

    /** Sign in again to keep syncing; local data is kept. */
    data object SessionRequired : SyncUiState

    /** [count] changes were rejected by the server for good and need a decision. */
    data class NeedsAction(val count: Int) : SyncUiState
}

/**
 * Priority: expired session > rejected changes > offline (with pending changes) > pending > synced. Rejected changes
 * outrank connectivity because waiting never resolves them.
 */
fun SyncStatus.toUiState(): SyncUiState = when {
    lastResult is SyncResult.SessionRequired -> SyncUiState.SessionRequired
    quarantinedMutations > 0 -> SyncUiState.NeedsAction(quarantinedMutations)
    // Offline only when local changes are actually waiting: with nothing to send, a failed pull
    // puts none of the user's data at risk, so "0 changes saved here" would only alarm.
    lastResult is SyncResult.Retryable && pendingMutations > 0 -> SyncUiState.Offline(pendingMutations)
    pendingMutations > 0 -> SyncUiState.Pending(pendingMutations)
    else -> SyncUiState.Synced
}

/**
 * One unsynced local mutation, safe to display: no payload values, tokens or user ids.
 * [mutationIdPrefix] is the first characters of the mutation id; [tables] are wire table names.
 */
data class OutboxDiagnostic(
    val mutationIdPrefix: String,
    val tables: List<String>,
    val attemptCount: Int,
    val lastErrorCode: String?,
    /** True when the server rejected it for good (it will not be resent). */
    val rejected: Boolean,
)
