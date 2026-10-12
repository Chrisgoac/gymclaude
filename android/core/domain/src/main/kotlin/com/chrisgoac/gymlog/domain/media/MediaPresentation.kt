package com.chrisgoac.gymlog.domain.media

import com.chrisgoac.gymlog.model.media.InvalidImage
import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaItem
import com.chrisgoac.gymlog.model.media.MediaStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One-shot feedback about adding or changing a photo, shared by every photo screen. */
enum class PhotoMessage {
    Corrupt, Unsupported, TooLarge, Unreadable, ActionFailed, CameraUnavailable,
    ;

    companion object {
        fun of(reason: InvalidImage): PhotoMessage = when (reason) {
            InvalidImage.CORRUPT -> Corrupt
            InvalidImage.UNSUPPORTED -> Unsupported
            InvalidImage.TOO_LARGE -> TooLarge
            InvalidImage.UNREADABLE -> Unreadable
        }
    }
}

/** Which status sentence a photo needs. Screens map it to their own string resources. */
enum class MediaStatusKind {
    NONE, PENDING, UPLOADING, UPLOADING_PERCENT, LOADING, OFFLINE, FAILURE_UPLOAD, FAILURE_LOAD, REJECTED_LARGE, REJECTED_TYPE, REJECTED_OTHER
}

data class MediaStatusLine(val kind: MediaStatusKind, val percent: Int? = null)

fun MediaUiState.statusLine(): MediaStatusLine = when (this) {
    is MediaUiState.LocalPending -> MediaStatusLine(MediaStatusKind.PENDING)
    is MediaUiState.Uploading ->
        progress?.let { MediaStatusLine(MediaStatusKind.UPLOADING_PERCENT, (it * 100).toInt()) } ?: MediaStatusLine(MediaStatusKind.UPLOADING)
    is MediaUiState.Available -> MediaStatusLine(if (localThumbnail == null) MediaStatusKind.LOADING else MediaStatusKind.NONE)
    MediaUiState.RemoteUnavailableOffline -> MediaStatusLine(MediaStatusKind.OFFLINE)
    is MediaUiState.RetryableFailure -> MediaStatusLine(if (detail != null) MediaStatusKind.FAILURE_UPLOAD else MediaStatusKind.FAILURE_LOAD)
    is MediaUiState.Rejected -> MediaStatusLine(
        when (reason) {
            MediaRejection.TOO_LARGE -> MediaStatusKind.REJECTED_LARGE
            MediaRejection.UNSUPPORTED -> MediaStatusKind.REJECTED_TYPE
            MediaRejection.OTHER -> MediaStatusKind.REJECTED_OTHER
        },
    )
}

/** True for states the user may want to act on (shown in the error colour). */
fun MediaUiState.isProblem(): Boolean =
    this is MediaUiState.Rejected || this is MediaUiState.RetryableFailure || this == MediaUiState.RemoteUnavailableOffline

/** Which actions the viewer offers for a photo in a given state. */
data class MediaActions(val retryImage: Boolean, val retryUpload: Boolean, val discard: Boolean, val delete: Boolean)

fun MediaUiState.actions(): MediaActions {
    val rejected = this is MediaUiState.Rejected
    val failingUpload = this is MediaUiState.RetryableFailure && detail != null
    return MediaActions(
        retryImage = this == MediaUiState.RemoteUnavailableOffline || (this is MediaUiState.RetryableFailure && detail == null),
        retryUpload = rejected || failingUpload,
        discard = rejected,
        delete = !rejected,
    )
}

/**
 * Identity of the image a photo currently has: it changes when the photo moves between queue and storage,
 * and when a synced photo gets a new object key (replaced on another device under the same id).
 */
fun MediaItem.imageTag(): String = when (status) {
    MediaStatus.Synced -> "synced:${objectKey.orEmpty()}"
    is MediaStatus.PendingUpload -> "pending"
    is MediaStatus.Rejected -> "rejected"
}

/**
 * Loads the local copy of photos on demand (cache, pending original or a download through a fresh read
 * grant): at most [concurrency] at a time, once per photo and state, with an explicit retry. Call from the
 * main thread of [scope] (a ViewModel scope).
 */
class MediaImageLoader(
    private val media: MediaRepository,
    private val scope: CoroutineScope,
    concurrency: Int = 3,
) {
    data class Loaded(val tag: String, val image: MediaImage)

    private val state = MutableStateFlow<Map<String, Loaded>>(emptyMap())
    val loaded: StateFlow<Map<String, Loaded>> get() = state
    private val gate = Semaphore(concurrency)
    private val inFlight = HashSet<String>()

    fun request(item: MediaItem) {
        if (state.value[item.id]?.tag == item.imageTag()) return
        load(item)
    }

    fun retry(item: MediaItem) {
        state.update { it - item.id }
        load(item)
    }

    /** Reloads photos already loaded whose image changed (e.g. a new object key arrived by sync). */
    fun refresh(items: List<MediaItem>) {
        val current = state.value
        items.filter { item -> current[item.id]?.let { it.tag != item.imageTag() } == true }.forEach(::load)
    }

    /** Forgets images of photos that are gone. */
    fun prune(keep: Set<String>) {
        if (state.value.keys.all { it in keep }) return
        state.update { map -> map.filterKeys { it in keep } }
    }

    private fun load(item: MediaItem) {
        if (!inFlight.add(item.id)) return
        val tag = item.imageTag()
        scope.launch {
            try {
                val result = gate.withPermit {
                    try {
                        media.image(item.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        MediaImage.Unavailable(MediaImage.Unavailable.Reason.NOT_FOUND)
                    }
                }
                state.update { it + (item.id to Loaded(tag, result)) }
            } finally {
                inFlight.remove(item.id)
            }
        }
    }

    companion object {
        /** The loaded image of [item] if it belongs to its current state. */
        fun imageFor(loaded: Map<String, Loaded>, item: MediaItem): MediaImage? =
            loaded[item.id]?.takeIf { it.tag == item.imageTag() }?.image
    }
}

/**
 * The camera slot of one capture: [prepare] hands the camera app a URI, [take] returns the slot when its
 * result arrives (to ingest and then release), [close] releases an unused one. [onChange] lets the owner
 * persist the slot (SavedStateHandle) so a result arriving after process death is still ingested.
 */
class CaptureSession(
    private val targets: PhotoCaptureTargets,
    restored: CaptureSlot? = null,
    private val onChange: (CaptureSlot?) -> Unit = {},
) {
    private var slot: CaptureSlot? = restored

    /** The URI for the camera app, or null when no account is open. */
    fun prepare(): String? {
        slot?.let(targets::release)
        val next = targets.create()
        set(next)
        return next?.uri
    }

    /** The slot the camera result belongs to (cleared), or null when none is outstanding. */
    fun take(): CaptureSlot? = slot.also { set(null) }

    fun release(slot: CaptureSlot) = targets.release(slot)

    fun close() {
        slot?.let(targets::release)
        set(null)
    }

    private fun set(value: CaptureSlot?) {
        slot = value
        onChange(value)
    }
}

/**
 * Photos whose upload the user just asked to retry. They show as uploading only while the queue entry is
 * still the fresh one and only for [windowMillis]: offline, nothing happens and the photo falls back to
 * "pending" instead of claiming to upload forever.
 */
class RetryTracker(private val scope: CoroutineScope, private val windowMillis: Long = UPLOADING_WINDOW_MILLIS) {
    private val state = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> get() = state

    fun mark(id: String) {
        state.update { it + id }
        scope.launch {
            delay(windowMillis)
            state.update { it - id }
        }
    }

    fun clear(id: String) = state.update { it - id }

    /** Drops photos that are gone or whose status moved on (failed again, uploaded, refused). */
    fun prune(items: List<MediaItem>) {
        if (state.value.isEmpty()) return
        val fresh = items.filter { it.status == MediaStatus.PendingUpload(0, null) }.mapTo(HashSet()) { it.id }
        state.update { ids -> ids.filterTo(HashSet()) { it in fresh } }
    }

    companion object {
        const val UPLOADING_WINDOW_MILLIS = 10_000L
    }
}
