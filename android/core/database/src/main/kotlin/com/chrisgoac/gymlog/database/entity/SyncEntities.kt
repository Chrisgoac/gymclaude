package com.chrisgoac.gymlog.database.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

/** Delivery state of an outbox row (contracts/v1/README.md "Quarantine of poison mutations"). */
enum class OutboxStatus {
    /** Waiting to be pushed, in [OutboxMutationEntity.sequence] order. */
    PENDING,

    /** Rejected by a non-retryable error naming this mutation: kept locally, flagged, never resent. */
    QUARANTINED,
}

/**
 * One logical local mutation, durable until the server lists its id in `acceptedMutationIds`.
 *
 * `payloadJson` is the ContractJson encoding of the core:model `PushMutation` (mutationId,
 * deviceId and the ordered operations with their `baseServerVersion`). It is written once and
 * resent byte-for-byte: replay on the server is keyed by `mutationId` only.
 * `sequence` gives a stable FIFO order (assigned as max + 1 inside the enqueue transaction).
 */
@Entity(tableName = "outbox_mutations", indices = [Index(value = ["sequence"], unique = true), Index("status")])
data class OutboxMutationEntity(
    @PrimaryKey val mutationId: String,
    val deviceId: String,
    val payloadJson: String,
    val createdAt: Long,
    val attemptCount: Int,
    val lastErrorCode: String?,
    val sequence: Long,
    val status: OutboxStatus,
)

/** Single-row table (id = 0): the opaque pull cursor, persisted only after a page is applied. */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val cursor: String,
    val updatedAt: Long,
)

enum class BootstrapState { IN_PROGRESS, COMPLETED }

/**
 * Single-row table (id = 0): resumable bootstrap checkpoint. A missing row means bootstrap never
 * started. `lastCursor` is the cursor after the last fully applied page; the UI only treats the
 * account as restored when [completed] is true.
 */
@Entity(tableName = "bootstrap_checkpoint")
data class BootstrapCheckpointEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val state: BootstrapState,
    val lastCursor: String?,
    val pagesApplied: Int,
    val updatedAt: Long,
) {
    @Ignore val completed: Boolean = state == BootstrapState.COMPLETED
}

const val SINGLETON_ID: Int = 0
