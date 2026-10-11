package com.chrisgoac.gymlog.domain.media

import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaItem
import com.chrisgoac.gymlog.model.media.MediaStatus

/** Why the server refused a photo for good, in terms the UI can explain. */
enum class MediaRejection {
    /** Over the server's size / dimension limits. */
    TOO_LARGE,

    /** Not an accepted image type, or the bytes do not match the type. */
    UNSUPPORTED,

    /** Anything else (the photo can be retried or discarded). */
    OTHER,
    ;

    companion object {
        fun fromCode(code: String): MediaRejection = when (code) {
            "MEDIA_TOO_LARGE", "MEDIA_DIMENSIONS_INVALID" -> TOO_LARGE
            "UNSUPPORTED_MEDIA_TYPE", "MEDIA_TYPE_MISMATCH" -> UNSUPPORTED
            else -> OTHER
        }
    }
}

/**
 * What one photo looks like on screen. [localThumbnail] is a path in the account's private storage
 * (original or cache), never a URL: signed URLs are transport data and are not shown or stored.
 */
sealed interface MediaUiState {
    val localThumbnail: String?

    /** Saved on the device, waiting for connectivity or its turn to upload. */
    data class LocalPending(override val localThumbnail: String? = null) : MediaUiState

    /** An upload was started by the user; [progress] is null while the byte count is unknown (indeterminate). */
    data class Uploading(val progress: Float?, override val localThumbnail: String? = null) : MediaUiState

    /** Stored remotely and shown from a local copy. [localThumbnail] is null while the copy is still loading. */
    data class Available(override val localThumbnail: String?) : MediaUiState

    /** Stored remotely, not cached, and the device cannot reach it now (offline or signed out). */
    data object RemoteUnavailableOffline : MediaUiState {
        override val localThumbnail: String? get() = null
    }

    /** A transient failure (upload retry pending, or the image could not be fetched); trying again may work. */
    data class RetryableFailure(override val localThumbnail: String? = null, val detail: String? = null) : MediaUiState

    /** The server refused the upload for good; the file is kept so the user can retry or discard. */
    data class Rejected(val reason: MediaRejection, override val localThumbnail: String? = null) : MediaUiState
}

/** Pure mapping from the repository's [MediaItem] plus the last known [MediaImage] to a [MediaUiState]. */
object MediaUiMapper {
    /**
     * @param image result of `MediaRepository.image(item.id)` once loaded; null while not requested or loading.
     * @param retrying the user just asked to retry this item, so a queued upload is shown as in progress.
     */
    fun map(item: MediaItem, image: MediaImage?, retrying: Boolean = false): MediaUiState {
        val local = (image as? MediaImage.Available)?.path
        return when (val status = item.status) {
            is MediaStatus.Rejected -> MediaUiState.Rejected(MediaRejection.fromCode(status.code), local)
            is MediaStatus.PendingUpload -> when {
                status.lastError != null -> MediaUiState.RetryableFailure(local, status.lastError)
                retrying -> MediaUiState.Uploading(null, local)
                else -> MediaUiState.LocalPending(local)
            }
            MediaStatus.Synced -> when (image) {
                null -> MediaUiState.Available(null)
                is MediaImage.Available -> MediaUiState.Available(image.path)
                is MediaImage.Unavailable -> when (image.reason) {
                    MediaImage.Unavailable.Reason.NOT_FOUND -> MediaUiState.RetryableFailure()
                    MediaImage.Unavailable.Reason.OFFLINE,
                    MediaImage.Unavailable.Reason.SESSION_REQUIRED,
                    -> MediaUiState.RemoteUnavailableOffline
                }
            }
        }
    }
}

/** A camera capture slot: [uri] is handed to the camera app, [token] releases it after ingest. */
data class CaptureSlot(val uri: String, val token: String)

/** Camera capture without the CAMERA permission (implemented in app over a FileProvider). */
interface PhotoCaptureTargets {
    /** Null when no account is open. */
    fun create(): CaptureSlot?

    /** Deletes the capture file once ingested, failed or cancelled. */
    fun release(slot: CaptureSlot)
}
