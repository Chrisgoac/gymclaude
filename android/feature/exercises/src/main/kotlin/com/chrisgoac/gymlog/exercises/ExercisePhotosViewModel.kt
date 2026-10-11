package com.chrisgoac.gymlog.exercises

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

private const val STOP_TIMEOUT_MILLIS = 5_000L
private const val IMAGE_CONCURRENCY = 3

/** Photos one exercise may have; the picker offers no more once reached. */
const val MAX_EXERCISE_PHOTOS = 6

data class ExercisePhotoUi(val id: String, val state: MediaUiState)

enum class ExercisePhotoMessage { Corrupt, Unsupported, TooLarge, Unreadable, ActionFailed, CameraUnavailable }

data class ExercisePhotosUiState(
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val photos: List<ExercisePhotoUi> = emptyList(),
    /** True while a picked image is being saved on the device. */
    val saving: Boolean = false,
    val viewing: ExercisePhotoUi? = null,
    val pendingDelete: ExercisePhotoUi? = null,
    val message: ExercisePhotoMessage? = null,
) {
    val canAdd: Boolean get() = photos.size < MAX_EXERCISE_PHOTOS && !saving
}

/**
 * Photos attached to one custom exercise: add from the Photo Picker or the camera (no permission),
 * see each one with its upload state, open it, retry / discard a refused upload and delete with
 * confirmation. Photos stay on screen while a reload fails; images are fetched on demand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExercisePhotosViewModel(
    private val media: MediaRepository,
    private val captureTargets: PhotoCaptureTargets,
    private val exerciseId: String,
) : ViewModel() {
    private data class Loaded(val tag: String, val image: MediaImage)
    private data class Local(
        val saving: Boolean = false,
        val viewingId: String? = null,
        val pendingDeleteId: String? = null,
        val message: ExercisePhotoMessage? = null,
    )

    private val items = MutableStateFlow<List<MediaItem>?>(null)
    private val failed = MutableStateFlow(false)
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
                media.observe(MediaKind.EXERCISE)
                    .onEach { all -> items.value = all.filter { (it.owner as? MediaOwner.Exercise)?.exerciseId == exerciseId }; failed.value = false }
                    .catch { failed.value = true }
            }.collect {}
        }
    }

    val state: StateFlow<ExercisePhotosUiState> = combine(items, failed, images, retrying, local) { list, hasFailed, loaded, retry, ui ->
        val photos = list.orEmpty().map { ExercisePhotoUi(it.id, MediaUiMapper.map(it, loaded[it.id]?.takeIf { l -> l.tag == tag(it) }?.image, it.id in retry)) }
        ExercisePhotosUiState(
            loaded = list != null,
            failed = list == null && hasFailed,
            photos = photos,
            saving = ui.saving,
            viewing = ui.viewingId?.let { id -> photos.firstOrNull { it.id == id } },
            pendingDelete = ui.pendingDeleteId?.let { id -> photos.firstOrNull { it.id == id } },
            message = ui.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ExercisePhotosUiState())

    private fun tag(item: MediaItem): String = when (item.status) {
        MediaStatus.Synced -> "synced"
        is MediaStatus.PendingUpload -> "pending"
        is MediaStatus.Rejected -> "rejected"
    }

    fun retryLoad() = attempt.update { it + 1 }

    // ---- Images ----

    fun requestImage(id: String) {
        val item = items.value?.firstOrNull { it.id == id } ?: return
        if (images.value[id]?.tag == tag(item)) return
        loadImage(item)
    }

    fun retryImage(id: String) {
        val item = items.value?.firstOrNull { it.id == id } ?: return
        images.update { it - id }
        loadImage(item)
    }

    private fun loadImage(item: MediaItem) {
        if (!inFlight.add(item.id)) return
        val tag = tag(item)
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

    // ---- Add ----

    /** Photo Picker result: the picked content URI, or null when the user closed the picker. */
    fun onPhotoPicked(uri: String?) {
        if (uri != null) ingest(uri, null)
    }

    /** URI the camera app should write into, or null when there is no open account (message shown). */
    fun prepareCamera(): String? {
        cameraSlot?.let(captureTargets::release)
        val slot = captureTargets.create()
        cameraSlot = slot
        if (slot == null) local.update { it.copy(message = ExercisePhotoMessage.CameraUnavailable) }
        return slot?.uri
    }

    fun onCameraResult(saved: Boolean) {
        val slot = cameraSlot ?: return
        cameraSlot = null
        if (saved) ingest(slot.uri, slot) else captureTargets.release(slot)
    }

    private fun ingest(uri: String, slot: CaptureSlot?) {
        if (local.value.saving || (items.value?.size ?: 0) >= MAX_EXERCISE_PHOTOS) {
            slot?.let(captureTargets::release)
            return
        }
        local.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val outcome = try {
                media.enqueue(uri, MediaOwner.Exercise(exerciseId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                slot?.let(captureTargets::release)
            }
            val message = when (outcome) {
                is EnqueueResult.Queued -> null
                is EnqueueResult.Invalid -> when (outcome.reason) {
                    InvalidImage.CORRUPT -> ExercisePhotoMessage.Corrupt
                    InvalidImage.UNSUPPORTED -> ExercisePhotoMessage.Unsupported
                    InvalidImage.TOO_LARGE -> ExercisePhotoMessage.TooLarge
                    InvalidImage.UNREADABLE -> ExercisePhotoMessage.Unreadable
                }
                null -> ExercisePhotoMessage.ActionFailed
            }
            local.update { it.copy(saving = false, message = message) }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    // ---- View, delete, retry ----

    fun onOpen(id: String) = local.update { it.copy(viewingId = id) }

    fun onClose() = local.update { it.copy(viewingId = null, pendingDeleteId = null) }

    fun onAskDelete(id: String) = local.update { it.copy(pendingDeleteId = id) }

    fun onCancelDelete() = local.update { it.copy(pendingDeleteId = null) }

    /** Deletes (or discards a refused upload, which never reached the server) the photo awaiting confirmation. */
    fun onConfirmDelete() {
        val id = local.value.pendingDeleteId ?: return
        val rejected = items.value?.firstOrNull { it.id == id }?.status is MediaStatus.Rejected
        local.update { it.copy(pendingDeleteId = null, viewingId = null) }
        viewModelScope.launch { runAction { if (rejected) media.discard(id) else media.delete(id) } }
    }

    fun onRetryUpload(id: String) {
        retrying.update { it + id }
        viewModelScope.launch { if (!runAction { media.retry(id) }) retrying.update { it - id } }
    }

    private suspend fun runAction(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        local.update { it.copy(message = ExercisePhotoMessage.ActionFailed) }
        false
    }

    override fun onCleared() {
        cameraSlot?.let(captureTargets::release)
        cameraSlot = null
    }
}
