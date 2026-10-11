package com.chrisgoac.gymlog.exercises

import com.chrisgoac.gymlog.domain.media.CaptureSlot
import com.chrisgoac.gymlog.domain.media.MediaRepository
import com.chrisgoac.gymlog.domain.media.MediaUiState
import com.chrisgoac.gymlog.domain.media.PhotoCaptureTargets
import com.chrisgoac.gymlog.domain.media.PhotoMessage
import com.chrisgoac.gymlog.model.media.EnqueueResult
import com.chrisgoac.gymlog.model.media.InvalidImage
import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaItem
import com.chrisgoac.gymlog.model.media.MediaKind
import com.chrisgoac.gymlog.model.media.MediaOwner
import com.chrisgoac.gymlog.model.media.MediaStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeMedia : MediaRepository {
    val items = MutableStateFlow<List<MediaItem>>(emptyList())
    val images = mutableMapOf<String, MediaImage>()
    var enqueueResult: EnqueueResult = EnqueueResult.Queued("n")
    var failNext: Exception? = null
    val enqueued = mutableListOf<Pair<String, MediaOwner>>()
    val deleted = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    val retried = mutableListOf<String>()
    var imageCalls = 0

    override suspend fun enqueue(sourceUri: String, owner: MediaOwner): EnqueueResult {
        failNext?.let { failNext = null; throw it }
        enqueued += sourceUri to owner
        return enqueueResult
    }

    override fun observe(kind: MediaKind): Flow<List<MediaItem>> = items.map { all -> all.filter { it.owner.kind == kind } }
    override suspend fun delete(mediaId: String) { deleted += mediaId; items.value = items.value.filterNot { it.id == mediaId } }
    override suspend fun retry(mediaId: String) { retried += mediaId }
    override suspend fun discard(mediaId: String) { discarded += mediaId; items.value = items.value.filterNot { it.id == mediaId } }
    override suspend fun image(mediaId: String): MediaImage {
        imageCalls++
        return images[mediaId] ?: MediaImage.Unavailable(MediaImage.Unavailable.Reason.NOT_FOUND)
    }
}

private class FakeCapture : PhotoCaptureTargets {
    var available = true
    val released = mutableListOf<CaptureSlot>()
    override fun create(): CaptureSlot? = if (available) CaptureSlot("content://gymlog.media/capture/1.jpg", "t1") else null
    override fun release(slot: CaptureSlot) { released += slot }
}

private fun exerciseItem(id: String, exerciseId: String = "e1", status: MediaStatus = MediaStatus.Synced) =
    MediaItem(id, MediaOwner.Exercise(exerciseId), status, if (status == MediaStatus.Synced) "users/h/exercise/$id" else null)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExercisePhotosViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val media = FakeMedia()
    private val capture = FakeCapture()
    private val viewModel by lazy { ExercisePhotosViewModel(media, capture, "e1") }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.observed(): ExercisePhotosViewModel {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        advanceUntilIdle()
        return viewModel
    }

    @Test fun `only this exercise's photos are listed`() = runTest(dispatcher) {
        media.items.value = listOf(exerciseItem("a"), exerciseItem("b", "other"), exerciseItem("c"))
        val vm = observed()
        assertEquals(listOf("a", "c"), vm.state.value.photos.map { it.id })
        assertTrue(vm.state.value.loaded)
    }

    @Test fun `a picked photo is attached to the exercise and the picker can be closed without one`() = runTest(dispatcher) {
        val vm = observed()
        vm.onPhotoPicked(null)
        advanceUntilIdle()
        assertTrue(media.enqueued.isEmpty())
        vm.onPhotoPicked("content://picker/1")
        advanceUntilIdle()
        assertEquals("content://picker/1" to MediaOwner.Exercise("e1"), media.enqueued.single())
        assertFalse(vm.state.value.saving)
        assertNull(vm.state.value.message)
    }

    @Test fun `refused images and failures are explained`() = runTest(dispatcher) {
        val vm = observed()
        media.enqueueResult = EnqueueResult.Invalid(InvalidImage.TOO_LARGE)
        vm.onPhotoPicked("content://picker/1")
        advanceUntilIdle()
        assertEquals(PhotoMessage.TooLarge, vm.state.value.message)
        media.failNext = IllegalStateException("disk")
        vm.onPhotoPicked("content://picker/2")
        advanceUntilIdle()
        assertEquals(PhotoMessage.ActionFailed, vm.state.value.message)
        vm.dismissMessage()
        advanceUntilIdle()
        assertNull(vm.state.value.message)
    }

    @Test fun `the camera slot is released after ingest, after a cancel and when the screen goes`() = runTest(dispatcher) {
        val vm = observed()
        assertEquals("content://gymlog.media/capture/1.jpg", vm.prepareCamera())
        vm.onCameraResult(true)
        advanceUntilIdle()
        assertEquals("content://gymlog.media/capture/1.jpg", media.enqueued.single().first)
        assertEquals(1, capture.released.size)
        vm.prepareCamera()
        vm.onCameraResult(false)
        assertEquals(2, capture.released.size)
        capture.available = false
        assertNull(vm.prepareCamera())
        advanceUntilIdle()
        assertEquals(PhotoMessage.CameraUnavailable, vm.state.value.message)
    }

    @Test fun `no more than the limit can be added`() = runTest(dispatcher) {
        media.items.value = (1..MAX_EXERCISE_PHOTOS).map { exerciseItem("p$it") }
        val vm = observed()
        assertFalse(vm.state.value.canAdd)
        vm.onPhotoPicked("content://picker/1")
        advanceUntilIdle()
        assertTrue(media.enqueued.isEmpty())
    }

    @Test fun `an uncached photo offline shows unavailable and a retry can fetch it`() = runTest(dispatcher) {
        media.items.value = listOf(exerciseItem("a"))
        media.images["a"] = MediaImage.Unavailable(MediaImage.Unavailable.Reason.OFFLINE)
        val vm = observed()
        vm.requestImage("a")
        advanceUntilIdle()
        assertEquals(MediaUiState.RemoteUnavailableOffline, vm.state.value.photos.single().state)
        media.images["a"] = MediaImage.Available("/private/a.jpg")
        vm.retryImage("a")
        advanceUntilIdle()
        assertEquals(MediaUiState.Available("/private/a.jpg"), vm.state.value.photos.single().state)
    }

    @Test fun `upload states are mapped for queued, failing and refused photos`() = runTest(dispatcher) {
        media.items.value = listOf(
            exerciseItem("q", status = MediaStatus.PendingUpload(0, null)),
            exerciseItem("f", status = MediaStatus.PendingUpload(3, "timeout")),
            exerciseItem("r", status = MediaStatus.Rejected("UNSUPPORTED_MEDIA_TYPE")),
        )
        val vm = observed()
        val byId = vm.state.value.photos.associateBy { it.id }
        assertTrue(byId.getValue("q").state is MediaUiState.LocalPending)
        assertTrue(byId.getValue("f").state is MediaUiState.RetryableFailure)
        assertTrue(byId.getValue("r").state is MediaUiState.Rejected)
    }

    @Test fun `a refused upload is retried or discarded after confirming, a synced one is deleted after confirming`() = runTest(dispatcher) {
        media.items.value = listOf(exerciseItem("r", status = MediaStatus.Rejected("X")), exerciseItem("s"))
        val vm = observed()
        vm.onRetryUpload("r")
        advanceUntilIdle()
        assertEquals(listOf("r"), media.retried)

        vm.onOpen("r"); vm.onAskDelete("r")
        advanceUntilIdle()
        assertEquals("r", vm.state.value.pendingDelete?.id)
        vm.onCancelDelete()
        advanceUntilIdle()
        assertTrue(media.discarded.isEmpty())
        vm.onAskDelete("r"); vm.onConfirmDelete()
        advanceUntilIdle()
        assertEquals(listOf("r"), media.discarded)

        vm.onOpen("s"); vm.onAskDelete("s"); vm.onConfirmDelete()
        advanceUntilIdle()
        assertEquals(listOf("s"), media.deleted)
        assertNull(vm.state.value.viewing)
    }
}
