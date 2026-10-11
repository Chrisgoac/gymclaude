package com.chrisgoac.gymlog.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local-only upload/delete queue of private photos (schema 3). Never synced and never in the outbox:
 * the v1 media endpoints create and tombstone the server rows themselves, which then arrive by pull.
 *
 * [id] is the photo id (client UUID, sent as the upload `id`, so a retry replays the same object).
 * [fileName] is relative to the account's private pending directory (`files/users/<hash>/media/pending`).
 */
@Entity(tableName = "pending_media")
data class PendingMediaEntity(
    @PrimaryKey val id: String,
    /** `MediaKind` wire value. */
    val kind: String,
    /** [PendingMediaOperation]. */
    val operation: String,
    /** [PendingMediaState]. */
    val state: String,
    val exerciseId: String?,
    val fecha: Long?,
    val angulo: String?,
    val nota: String?,
    val fileName: String?,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
)

object PendingMediaOperation {
    const val UPLOAD = "UPLOAD"
    const val DELETE = "DELETE"
}

object PendingMediaState {
    const val PENDING = "PENDING"

    /** Refused for good by the server; kept with its file until the user retries or discards it. */
    const val REJECTED = "REJECTED"
}
