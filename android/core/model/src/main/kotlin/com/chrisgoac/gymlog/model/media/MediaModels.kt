package com.chrisgoac.gymlog.model.media

import kotlinx.serialization.Serializable

/**
 * Private media wire contract (lib/api/v1/media/contracts.ts). The server always generates the object
 * key; a signed read URL is short-lived transport data and is never persisted.
 */
enum class MediaKind(
    /** `MediaKind` wire value. */
    val wire: String,
    /** Collection segment of `/api/v1/media/<segment>` (upload) and `/api/v1/media/<segment>/<id>` (delete). */
    val pathSegment: String,
) {
    EXERCISE("exercise", "exercises"),
    PROGRESS("progress", "progress"),
    ;

    companion object {
        fun fromWire(value: String): MediaKind? = entries.firstOrNull { it.wire == value }
    }
}

/** Server limits (`MEDIA_LIMITS`) and the Android compression targets that stay well inside them. */
object MediaLimits {
    /** Maximum compressed upload size the server accepts. */
    const val MAX_BYTES: Long = 8L * 1024 * 1024

    /** Maximum decoded side the server accepts, in pixels. */
    const val MAX_DIMENSION: Int = 8192

    /** Maximum decoded pixels (width × height) the server accepts. */
    const val MAX_PIXELS: Long = 40_000_000

    /** Longest side of the JPEG Android uploads (far below [MAX_DIMENSION] / [MAX_PIXELS]). */
    const val COMPRESSED_MAX_SIDE: Int = 2048

    /** JPEG quality of the uploaded image. */
    const val JPEG_QUALITY: Int = 85

    /** Largest picked/captured source read before decoding; anything bigger is rejected unread. */
    const val MAX_SOURCE_BYTES: Long = 64L * 1024 * 1024

    /** `PROGRESS_ANGLES`. */
    val PROGRESS_ANGLES: Set<String> = setOf("frente", "lado", "espalda")
}

/** `MediaUploadResponse`: the stored object of [mediaId] plus a fresh read grant. */
@Serializable
data class MediaUploadResponse(
    val mediaId: String,
    val objectKey: String,
    val readUrl: String,
    val readUrlExpiresAt: Long,
)

/** `MediaReadGrant`: [readUrl] is valid until [expiresAt] (server epoch ms). Memory only. */
@Serializable
data class MediaReadGrant(val mediaId: String, val readUrl: String, val expiresAt: Long)

/** `MediaDeleteResponse`. */
@Serializable
data class MediaDeleteResponse(val mediaId: String, val deleted: Boolean)

/** What a photo is attached to: the upload form fields besides `id` and `file`. */
sealed interface MediaOwner {
    val kind: MediaKind

    data class Exercise(val exerciseId: String) : MediaOwner {
        override val kind: MediaKind get() = MediaKind.EXERCISE
    }

    data class Progress(val fecha: Long, val angulo: String, val nota: String?) : MediaOwner {
        override val kind: MediaKind get() = MediaKind.PROGRESS
    }
}

/** Where a photo stands for the UI. Synced rows come from pull; the others from the local upload queue. */
sealed interface MediaStatus {
    /** Server row (object key known). */
    data object Synced : MediaStatus

    /** Waiting for (or retrying) its upload; [lastError] is the last transient failure, if any. */
    data class PendingUpload(val attempts: Int, val lastError: String?) : MediaStatus

    /** The server refused it for good ([code] = API error code); the local file is kept for retry/discard. */
    data class Rejected(val code: String) : MediaStatus
}

/** One photo as the UI sees it: synced and pending rows merged, a queued delete already hidden. */
data class MediaItem(
    val id: String,
    val owner: MediaOwner,
    val status: MediaStatus,
    /** Server object key; null until the upload is confirmed. */
    val objectKey: String?,
)

/** Result of ingesting a picked or captured image. */
sealed interface EnqueueResult {
    data class Queued(val mediaId: String) : EnqueueResult

    data class Invalid(val reason: InvalidImage) : EnqueueResult
}

enum class InvalidImage {
    /** Not readable as an image (truncated, garbage, empty). */
    CORRUPT,

    /** A real image in a format the app does not accept (e.g. BMP, GIF). */
    UNSUPPORTED,

    /** The source is larger than [MediaLimits.MAX_SOURCE_BYTES] or decodes too large. */
    TOO_LARGE,

    /** The source could not be opened (permission revoked, provider gone). */
    UNREADABLE,
}

/** A displayable local file for a photo, or why there is none. */
sealed interface MediaImage {
    data class Available(val path: String) : MediaImage

    data class Unavailable(val reason: Reason) : MediaImage {
        enum class Reason { OFFLINE, NOT_FOUND, SESSION_REQUIRED }
    }
}

/** What one background upload/delete pass of a queued photo concluded. */
enum class MediaWorkOutcome {
    /** Nothing left to do (uploaded, deleted, rejected for good, or no longer queued). */
    DONE,

    /** Transient failure: try again later. */
    RETRY,

    /** No usable session for the account: wait for sign-in. */
    SESSION_REQUIRED,

    /** The account is not the open one: this work is obsolete. */
    UNAVAILABLE,
}
