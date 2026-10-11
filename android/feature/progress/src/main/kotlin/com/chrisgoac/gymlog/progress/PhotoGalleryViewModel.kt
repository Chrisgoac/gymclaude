package com.chrisgoac.gymlog.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.media.CaptureSlot
import com.chrisgoac.gymlog.domain.media.MediaRepository
import com.chrisgoac.gymlog.domain.media.MediaUiMapper
import com.chrisgoac.gymlog.domain.media.MediaUiState
import com.chrisgoac.gymlog.domain.media.PhotoCaptureTargets
import com.chrisgoac.gymlog.model.media.EnqueueResult
import com.chrisgoac.gymlog.model.media.InvalidImage
import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaItem
import com.chrisgoac.gymlog.model.media.MediaKind
import com.chrisgoac.gymlog.model.media.MediaLimits
import com.chrisgoac.gymlog.model.media.MediaOwner
import com.chrisgoac.gymlog.model.media.MediaStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val STOP_TIMEOUT_MILLIS = 5_000L
private const val IMAGE_CONCURRENCY = 3
private const val MAX_NOTE = 2000

/** Progress photo angles in the PWA's order (`frente`, `lado`, `espalda`). */
val PHOTO_ANGLES: List<String> = listOf("frente", "lado", "espalda")

/** Server-corrected clock and device zone the gallery is computed with. */
data class PhotoClock(val nowMillis: Long, val zone: ZoneId) {
    val today: LocalDate get() = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
}

enum class PhotoGalleryStatus { Loading, Ready, Failed }

/** One progress photo as the gallery shows it. [date] is the user-chosen day in the device zone. */
data class ProgressPhotoUi(
    val id: String,
    val fecha: Long,
    val date: LocalDate,
    val angle: String,
    val note: String?,
    val state: MediaUiState,
)

data class PhotoDay(val date: LocalDate, val photos: List<ProgressPhotoUi>)

/** The pre-capture form: angle, day and optional note, filled before choosing the picture source. */
data class CaptureDraft(val angle: String, val date: LocalDate, val note: String = "", val busy: Boolean = false)

/** Two photos of one angle side by side; [series] is that angle's photos, oldest first. */
data class ComparisonUi(
    val angles: List<String>,
    val angle: String,
    val series: List<ProgressPhotoUi>,
    val a: ProgressPhotoUi,
    val b: ProgressPhotoUi,
)

/** One-shot feedback shown in a live region until dismissed. */
enum class PhotoMessage { Corrupt, Unsupported, TooLarge, Unreadable, ActionFailed, CameraUnavailable }

data class PhotoGalleryUiState(
    val status: PhotoGalleryStatus = PhotoGalleryStatus.Loading,
    /** Newest day first; inside a day, newest capture first. */
    val days: List<PhotoDay> = emptyList(),
    val clock: PhotoClock? = null,
    val draft: CaptureDraft? = null,
    val viewing: ProgressPhotoUi? = null,
    val pendingDelete: ProgressPhotoUi? = null,
    val comparing: Boolean = false,
    /** Null while comparing means there are not two comparable photos of one angle. */
    val comparison: ComparisonUi? = null,
    val message: PhotoMessage? = null,
    /** At least two non-refused photos of one angle exist. */
    val canCompare: Boolean = false,
) {
    val photos: List<ProgressPhotoUi> get() = days.flatMap { it.photos }
}

/**
 * Progress photo gallery: chronological list grouped by day, capture (Photo Picker or camera, with
 * angle / day / note), full-screen viewer, two-photo comparison, delete with confirmation and
 * retry / discard of refused uploads. Data stays on screen while a reload fails; images are fetched
 * on demand per tile (never all at once) and shown from the account's private storage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoGalleryViewModel(
    private val media: MediaRepository,
    private val captureTargets: PhotoCaptureTargets,
    private val clock: () -> PhotoClock,
) : ViewModel() {
    private data class Loaded(val tag: String, val image: MediaImage)

    /** UI-only state that is not derived from the repository. */
    private data class Local(
        val draft: CaptureDraft? = null,
        val viewingId: String? = null,
        val pendingDeleteId: String? = null,
        val comparing: Boolean = false,
        val angle: String? = null,
        val aId: String? = null,
        val bId: String? = null,
        val message: PhotoMessage? = null,
    )

    private val items = MutableStateFlow<List<MediaItem>?>(null)
    private val loadFailed = MutableStateFlow(false)
    private val attempt = MutableStateFlow(0)
    private val images = MutableStateFlow<Map<String, Loaded>>(emptyMap())
    private val retrying = MutableStateFlow<Set<String>>(emptySet())
    private val local = MutableStateFlow(Local())
    private val gate = Semaphore(IMAGE_CONCURRENCY)
    private val inFlight = HashSet<String>()
    private var cameraSlot: CaptureSlot? = null

    init {
        viewModelScope.launch {
            attempt.flatMapLatest {
                media.observe(MediaKind.PROGRESS)
                    .onEach { items.value = it; loadFailed.value = false }
                    .catch { loadFailed.value = true }
            }.collect {}
        }
    }

    val state: StateFlow<PhotoGalleryUiState> = combine(items, loadFailed, images, retrying, local) { list, failed, loaded, retry, ui ->
        build(list, failed, loaded, retry, ui)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PhotoGalleryUiState())

    private fun build(list: List<MediaItem>?, failed: Boolean, loaded: Map<String, Loaded>, retry: Set<String>, ui: Local): PhotoGalleryUiState {
        val now = clock()
        if (list == null) return PhotoGalleryUiState(status = if (failed) PhotoGalleryStatus.Failed else PhotoGalleryStatus.Loading, clock = now, draft = ui.draft, message = ui.message)
        val photos = list.mapNotNull { item -> toUi(item, loaded[item.id], item.id in retry, now.zone) }
            .sortedWith(compareByDescending<ProgressPhotoUi> { it.fecha }.thenBy { it.id })
        val days = photos.groupBy { it.date }.map { (date, group) -> PhotoDay(date, group) }.sortedByDescending { it.date }
        val comparison = comparison(photos, ui)
        return PhotoGalleryUiState(
            status = PhotoGalleryStatus.Ready,
            days = days,
            clock = now,
            draft = ui.draft,
            viewing = ui.viewingId?.let { id -> photos.firstOrNull { it.id == id } },
            pendingDelete = ui.pendingDeleteId?.let { id -> photos.firstOrNull { it.id == id } },
            comparing = ui.comparing,
            comparison = if (ui.comparing) comparison else null,
            message = ui.message,
            canCompare = comparison != null,
        )
    }

    private fun toUi(item: MediaItem, loaded: Loaded?, isRetrying: Boolean, zone: ZoneId): ProgressPhotoUi? {
        val owner = item.owner as? MediaOwner.Progress ?: return null
        val image = loaded?.takeIf { it.tag == tag(item) }?.image
        return ProgressPhotoUi(
            id = item.id,
            fecha = owner.fecha,
            date = Instant.ofEpochMilli(owner.fecha).atZone(zone).toLocalDate(),
            angle = owner.angulo,
            note = owner.nota?.takeIf { it.isNotBlank() },
            state = MediaUiMapper.map(item, image, isRetrying),
        )
    }

    private fun comparison(photos: List<ProgressPhotoUi>, ui: Local): ComparisonUi? {
        val comparable = photos.filter { it.state !is MediaUiState.Rejected }
        val angles = PHOTO_ANGLES.filter { angle -> comparable.count { it.angle == angle } >= 2 }
        val angle = ui.angle?.takeIf { it in angles } ?: angles.firstOrNull() ?: return null
        val series = comparable.filter { it.angle == angle }.sortedWith(compareBy<ProgressPhotoUi> { it.fecha }.thenBy { it.id })
        val a = series.firstOrNull { it.id == ui.aId } ?: series.first()
        val b = series.firstOrNull { it.id == ui.bId } ?: series.last()
        return ComparisonUi(angles, angle, series, a, b)
    }

    // ---- Images ----

    /** Loads the local copy of [id] (cache, pending original or a download through a fresh read grant) once. */
    fun requestImage(id: String) {
        val item = items.value?.firstOrNull { it.id == id } ?: return
        val tag = tag(item)
        val have = images.value[id]
        if (have != null && have.tag == tag) return
        loadImage(item, tag)
    }

    /** Tries again to get the image of [id] after a failed or offline attempt. */
    fun retryImage(id: String) {
        val item = items.value?.firstOrNull { it.id == id } ?: return
        images.update { it - id }
        loadImage(item, tag(item))
    }

    private fun loadImage(item: MediaItem, tag: String) {
        if (!inFlight.add(item.id)) return
        viewModelScope.launch {
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
                images.update { it + (item.id to Loaded(tag, result)) }
            } finally {
                inFlight.remove(item.id)
            }
        }
    }

    private fun tag(item: MediaItem): String = when (item.status) {
        MediaStatus.Synced -> "synced"
        is MediaStatus.PendingUpload -> "pending"
        is MediaStatus.Rejected -> "rejected"
    }

    fun retryLoad() = attempt.update { it + 1 }

    // ---- Capture ----

    fun onAddPhoto() {
        val today = clock().today
        local.update { it.copy(draft = CaptureDraft(PHOTO_ANGLES.first(), today), message = null) }
    }

    fun onDraftAngle(angle: String) = updateDraft { if (angle in MediaLimits.PROGRESS_ANGLES) it.copy(angle = angle) else it }

    fun onDraftDate(date: LocalDate) = updateDraft { if (date.isAfter(clock().today)) it else it.copy(date = date) }

    fun onDraftNote(note: String) = updateDraft { it.copy(note = note.take(MAX_NOTE)) }

    fun onCancelDraft() = local.update { it.copy(draft = null) }

    private fun updateDraft(change: (CaptureDraft) -> CaptureDraft) =
        local.update { ui -> ui.draft?.takeIf { !it.busy }?.let { ui.copy(draft = change(it)) } ?: ui }

    /** Photo Picker result: the picked content URI, or null when the user closed the picker. */
    fun onPhotoPicked(uri: String?) {
        if (uri != null) ingest(uri, cameraSlot = null)
    }

    /** URI the camera app should write into, or null when there is no open account (message shown). */
    fun prepareCamera(): String? {
        cameraSlot?.let(captureTargets::release)
        val slot = captureTargets.create()
        cameraSlot = slot
        if (slot == null) local.update { it.copy(message = PhotoMessage.CameraUnavailable) }
        return slot?.uri
    }

    fun onCameraResult(saved: Boolean) {
        val slot = cameraSlot ?: return
        cameraSlot = null
        if (saved) ingest(slot.uri, slot) else captureTargets.release(slot)
    }

    private fun ingest(uri: String, cameraSlot: CaptureSlot?) {
        val draft = local.value.draft?.takeIf { !it.busy }
        if (draft == null) {
            cameraSlot?.let(captureTargets::release)
            return
        }
        local.update { it.copy(draft = draft.copy(busy = true), message = null) }
        viewModelScope.launch {
            val owner = MediaOwner.Progress(
                fecha = draft.date.atStartOfDay(clock().zone).toInstant().toEpochMilli(),
                angulo = draft.angle,
                nota = draft.note.trim().takeIf { it.isNotEmpty() },
            )
            val outcome = try {
                media.enqueue(uri, owner)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                cameraSlot?.let(captureTargets::release)
            }
            when (outcome) {
                is EnqueueResult.Queued -> local.update { it.copy(draft = null, message = null) }
                is EnqueueResult.Invalid -> local.update { it.copy(draft = draft, message = invalidMessage(outcome.reason)) }
                null -> local.update { it.copy(draft = draft, message = PhotoMessage.ActionFailed) }
            }
        }
    }

    private fun invalidMessage(reason: InvalidImage): PhotoMessage = when (reason) {
        InvalidImage.CORRUPT -> PhotoMessage.Corrupt
        InvalidImage.UNSUPPORTED -> PhotoMessage.Unsupported
        InvalidImage.TOO_LARGE -> PhotoMessage.TooLarge
        InvalidImage.UNREADABLE -> PhotoMessage.Unreadable
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    // ---- Viewer, delete, retry ----

    fun onOpen(id: String) = local.update { it.copy(viewingId = id) }

    fun onCloseViewer() = local.update { it.copy(viewingId = null) }

    fun onAskDelete(id: String) = local.update { it.copy(pendingDeleteId = id) }

    fun onCancelDelete() = local.update { it.copy(pendingDeleteId = null) }

    /** Deletes (synced / queued photo) or discards (refused upload, never on the server) the photo awaiting confirmation. */
    fun onConfirmDelete() {
        val id = local.value.pendingDeleteId ?: return
        val rejected = items.value?.firstOrNull { it.id == id }?.status is MediaStatus.Rejected
        local.update { it.copy(pendingDeleteId = null, viewingId = it.viewingId.takeIf { open -> open != id }) }
        viewModelScope.launch {
            runAction { if (rejected) media.discard(id) else media.delete(id) }
        }
    }

    fun onRetryUpload(id: String) {
        retrying.update { it + id }
        viewModelScope.launch {
            if (!runAction { media.retry(id) }) retrying.update { it - id }
        }
    }

    private suspend fun runAction(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        local.update { it.copy(message = PhotoMessage.ActionFailed) }
        false
    }

    // ---- Comparison ----

    fun onCompare() = local.update { it.copy(comparing = true, angle = null, aId = null, bId = null) }

    fun onCloseCompare() = local.update { it.copy(comparing = false) }

    fun onCompareAngle(angle: String) = local.update { it.copy(angle = angle, aId = null, bId = null) }

    fun onCompareA(id: String) = local.update { it.copy(aId = id) }

    fun onCompareB(id: String) = local.update { it.copy(bId = id) }

    /** Swaps the two sides (the current, possibly default, selection). */
    fun onSwap() {
        val current = state.value.comparison ?: return
        local.update { it.copy(aId = current.b.id, bId = current.a.id) }
    }

    override fun onCleared() {
        cameraSlot?.let(captureTargets::release)
        cameraSlot = null
    }
}
