package com.chrisgoac.gymlog.exercises

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

private const val STOP_TIMEOUT_MILLIS = 5_000L
private const val KEY_CAM_URI = "camera_uri"
private const val KEY_CAM_TOKEN = "camera_token"

/** Photos one exercise may have; the picker offers no more once reached. */
const val MAX_EXERCISE_PHOTOS = 6

data class ExercisePhotoUi(val id: String, val state: MediaUiState)

data class ExercisePhotosUiState(
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val photos: List<ExercisePhotoUi> = emptyList(),
    /** True while a picked image is being saved on the device. */
    val saving: Boolean = false,
    val viewing: ExercisePhotoUi? = null,
    val pendingDelete: ExercisePhotoUi? = null,
    val message: PhotoMessage? = null,
) {
    val canAdd: Boolean get() = photos.size < MAX_EXERCISE_PHOTOS && !saving
}

/**
 * Photos attached to one custom exercise: add from the Photo Picker or the camera (no permission),
 * see each one with its upload state, open it, retry / discard a refused upload and delete with
 * confirmation. Photos stay on screen while a reload fails; images are fetched on demand. Exercise
 * photos are not behind the privacy cover (unlike body progress photos). The camera slot lives in
 * [savedState] so a result arriving after process death is still ingested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExercisePhotosViewModel(
    private val media: MediaRepository,
    captureTargets: PhotoCaptureTargets,
    private val exerciseId: String,
    savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private data class Local(
        val saving: Boolean = false,
        val viewingId: String? = null,
        val pendingDeleteId: String? = null,
        val message: PhotoMessage? = null,
    )

    private val items = MutableStateFlow<List<MediaItem>?>(null)
    private val failed = MutableStateFlow(false)
    private val attempt = MutableStateFlow(0)
    private val loader = MediaImageLoader(media, viewModelScope)
    private val retries = RetryTracker(viewModelScope)
    private val local = MutableStateFlow(Local())
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
                media.observe(MediaKind.EXERCISE)
                    .onEach { all ->
                        val mine = all.filter { (it.owner as? MediaOwner.Exercise)?.exerciseId == exerciseId }
                        items.value = mine
                        failed.value = false
                        loader.prune(mine.mapTo(HashSet()) { it.id })
                        retries.prune(mine)
                    }
                    .catch { failed.value = true }
            }.collect {}
        }
    }

    val state: StateFlow<ExercisePhotosUiState> = combine(items, failed, loader.loaded, retries.ids, local) { list, hasFailed, loaded, retry, ui ->
        val photos = list.orEmpty().map { ExercisePhotoUi(it.id, MediaUiMapper.map(it, MediaImageLoader.imageFor(loaded, it), it.id in retry)) }
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

    fun retryLoad() = attempt.update { it + 1 }

    // ---- Images ----

    fun requestImage(id: String) {
        items.value?.firstOrNull { it.id == id }?.let(loader::request)
    }

    fun retryImage(id: String) {
        items.value?.firstOrNull { it.id == id }?.let(loader::retry)
    }

    // ---- Add ----

    /** Photo Picker result: the picked content URI, or null when the user closed the picker. */
    fun onPhotoPicked(uri: String?) {
        if (uri != null) ingest(uri, null)
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
        if (local.value.saving || (items.value?.size ?: 0) >= MAX_EXERCISE_PHOTOS) {
            slot?.let(camera::release)
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
                slot?.let(camera::release)
            }
            val message = when (outcome) {
                is EnqueueResult.Queued -> null
                is EnqueueResult.Invalid -> PhotoMessage.of(outcome.reason)
                null -> PhotoMessage.ActionFailed
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

    /** Requeues a refused or repeatedly failing upload; it shows as uploading briefly, never indefinitely. */
    fun onRetryUpload(id: String) {
        viewModelScope.launch { if (runAction { media.retry(id) }) retries.mark(id) }
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

    override fun onCleared() {
        camera.close()
    }
}
