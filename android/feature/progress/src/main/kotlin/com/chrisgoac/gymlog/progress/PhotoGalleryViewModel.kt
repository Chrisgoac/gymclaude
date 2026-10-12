package com.chrisgoac.gymlog.progress

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.media.CaptureSlot
import com.chrisgoac.gymlog.domain.media.CaptureSession
import com.chrisgoac.gymlog.domain.media.MediaImageLoader
import com.chrisgoac.gymlog.domain.media.MediaRepository
import com.chrisgoac.gymlog.domain.media.PhotoMessage
import com.chrisgoac.gymlog.domain.media.RetryTracker
import com.chrisgoac.gymlog.domain.media.MediaUiMapper
import com.chrisgoac.gymlog.domain.media.MediaUiState
import com.chrisgoac.gymlog.domain.media.PhotoCaptureTargets
import com.chrisgoac.gymlog.model.media.EnqueueResult
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val STOP_TIMEOUT_MILLIS = 5_000L
private const val KEY_CAM_URI = "camera_uri"
private const val KEY_CAM_TOKEN = "camera_token"
private const val KEY_DRAFT_OPEN = "draft_open"
private const val KEY_DRAFT_ANGLE = "draft_angle"
private const val KEY_DRAFT_DAY = "draft_day"
private const val KEY_DRAFT_NOTE = "draft_note"
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
    /** Hidden behind the privacy cover until the user taps it (per screen session, never persisted). */
    val concealed: Boolean = true,
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
 * Progress photo gallery: chronological list (newest day first) grouped by day, capture (Photo Picker or
 * camera, with angle / day / note), full-screen viewer, two-photo comparison, delete with confirmation and
 * retry / discard of refused uploads. Photos stay hidden behind a privacy cover until tapped (like the
 * PWA). Data stays on screen while a reload fails; images are fetched on demand per tile.
 *
 * The open form and the outstanding camera slot live in [savedState]: the camera app can outlive this
 * process, and its result is still ingested (or, if the slot is lost, Task 3's capture sweep removes the
 * orphan file and the user just takes the photo again).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoGalleryViewModel(
    private val media: MediaRepository,
    captureTargets: PhotoCaptureTargets,
    private val clock: () -> PhotoClock,
    private val savedState: SavedStateHandle,
) : ViewModel() {
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
    private val loader = MediaImageLoader(media, viewModelScope)
    private val retries = RetryTracker(viewModelScope)
    private val revealed = MutableStateFlow<Set<String>>(emptySet())
    private val local = MutableStateFlow(Local(draft = restoreDraft()))
    private val camera = CaptureSession(
        captureTargets,
        restored = savedState.get<String>(KEY_CAM_URI)?.let { uri -> savedState.get<String>(KEY_CAM_TOKEN)?.let { CaptureSlot(uri, it) } },
        onChange = { slot ->
            savedState[KEY_CAM_URI] = slot?.uri
            savedState[KEY_CAM_TOKEN] = slot?.token
        },
    )

    init {
        viewModelScope.launch {
            attempt.flatMapLatest {
                media.observe(MediaKind.PROGRESS)
                    .onEach { all ->
                        items.value = all
                        loadFailed.value = false
                        val ids = all.mapTo(HashSet()) { it.id }
                        loader.prune(ids)
                        loader.refresh(all)
                        retries.prune(all)
                        revealed.update { shown -> shown.filterTo(HashSet()) { it in ids } }
                    }
                    .catch { loadFailed.value = true }
            }.collect {}
        }
    }

    val state: StateFlow<PhotoGalleryUiState> =
        combine(items, loadFailed, loader.loaded, retries.ids, local, revealed) { values ->
            @Suppress("UNCHECKED_CAST")
            build(
                values[0] as List<MediaItem>?, values[1] as Boolean, values[2] as Map<String, MediaImageLoader.Loaded>,
                values[3] as Set<String>, values[4] as Local, values[5] as Set<String>,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PhotoGalleryUiState())

    private fun restoreDraft(): CaptureDraft? {
        if (savedState.get<Boolean>(KEY_DRAFT_OPEN) != true) return null
        val day = savedState.get<Long>(KEY_DRAFT_DAY)?.let(LocalDate::ofEpochDay) ?: return null
        val angle = savedState.get<String>(KEY_DRAFT_ANGLE)?.takeIf { it in PHOTO_ANGLES } ?: PHOTO_ANGLES.first()
        return CaptureDraft(angle, day, savedState.get<String>(KEY_DRAFT_NOTE).orEmpty())
    }

    private fun build(
        list: List<MediaItem>?,
        failed: Boolean,
        loaded: Map<String, MediaImageLoader.Loaded>,
        retry: Set<String>,
        ui: Local,
        shown: Set<String>,
    ): PhotoGalleryUiState {
        val now = clock()
        if (list == null) {
            return PhotoGalleryUiState(status = if (failed) PhotoGalleryStatus.Failed else PhotoGalleryStatus.Loading, clock = now, draft = ui.draft, message = ui.message)
        }
        val photos = list.mapNotNull { item -> toUi(item, MediaImageLoader.imageFor(loaded, item), item.id in retry, item.id !in shown, now.zone) }
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

    private fun toUi(item: MediaItem, image: MediaImage?, isRetrying: Boolean, concealed: Boolean, zone: ZoneId): ProgressPhotoUi? {
        val owner = item.owner as? MediaOwner.Progress ?: return null
        return ProgressPhotoUi(
            id = item.id,
            fecha = owner.fecha,
            date = Instant.ofEpochMilli(owner.fecha).atZone(zone).toLocalDate(),
            angle = owner.angulo,
            note = owner.nota?.takeIf { it.isNotBlank() },
            state = MediaUiMapper.map(item, image, isRetrying),
            concealed = concealed,
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

    // ---- Images and privacy cover ----

    /** Loads the local copy of [id] (cache, pending original or a download through a fresh read grant) once. */
    fun requestImage(id: String) {
        items.value?.firstOrNull { it.id == id }?.let(loader::request)
    }

    /** Tries again to get the image of [id] after a failed, offline or undecodable attempt. */
    fun retryImage(id: String) {
        items.value?.firstOrNull { it.id == id }?.let(loader::retry)
    }

    /** Lifts the privacy cover of one photo for this screen session. */
    fun onReveal(id: String) = revealed.update { it + id }

    fun retryLoad() = attempt.update { it + 1 }

    // ---- Capture ----

    fun onAddPhoto() = setDraft(CaptureDraft(PHOTO_ANGLES.first(), clock().today), message = null)

    fun onDraftAngle(angle: String) = updateDraft { if (angle in MediaLimits.PROGRESS_ANGLES) it.copy(angle = angle) else it }

    fun onDraftDate(date: LocalDate) = updateDraft { if (date.isAfter(clock().today)) it else it.copy(date = date) }

    fun onDraftNote(note: String) = updateDraft { it.copy(note = note.take(MAX_NOTE)) }

    fun onCancelDraft() = setDraft(null)

    private fun updateDraft(change: (CaptureDraft) -> CaptureDraft) {
        val draft = local.value.draft?.takeIf { !it.busy } ?: return
        setDraft(change(draft))
    }

    private fun setDraft(draft: CaptureDraft?, message: PhotoMessage? = local.value.message) {
        savedState[KEY_DRAFT_OPEN] = draft != null
        savedState[KEY_DRAFT_ANGLE] = draft?.angle
        savedState[KEY_DRAFT_DAY] = draft?.date?.toEpochDay()
        savedState[KEY_DRAFT_NOTE] = draft?.note
        local.update { it.copy(draft = draft, message = message) }
    }

    /** Photo Picker result: the picked content URI, or null when the user closed the picker. */
    fun onPhotoPicked(uri: String?) {
        if (uri != null) ingest(uri, slot = null)
    }

    /** URI the camera app should write into, or null when there is no open account (message shown). */
    fun prepareCamera(): String? {
        val uri = camera.prepare()
        if (uri == null) local.update { it.copy(message = PhotoMessage.CameraUnavailable) }
        return uri
    }

    fun onCameraResult(saved: Boolean) {
        val slot = camera.take() ?: return
        if (saved) ingest(slot.uri, slot) else camera.release(slot)
    }

    private fun ingest(uri: String, slot: CaptureSlot?) {
        val draft = local.value.draft?.takeIf { !it.busy }
        if (draft == null) {
            slot?.let(camera::release)
            return
        }
        setDraft(draft.copy(busy = true), message = null)
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
                slot?.let(camera::release)
            }
            when (outcome) {
                is EnqueueResult.Queued -> setDraft(null, message = null)
                is EnqueueResult.Invalid -> setDraft(draft, message = PhotoMessage.of(outcome.reason))
                null -> setDraft(draft, message = PhotoMessage.ActionFailed)
            }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    // ---- Viewer, delete, retry ----

    /** Opening a photo reveals it. */
    fun onOpen(id: String) {
        revealed.update { it + id }
        local.update { it.copy(viewingId = id) }
    }

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

    /** Requeues a refused or repeatedly failing upload; it shows as uploading briefly, never indefinitely. */
    fun onRetryUpload(id: String) {
        viewModelScope.launch {
            if (runAction { media.retry(id) }) retries.mark(id)
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
        camera.close()
    }
}
