package com.chrisgoac.gymlog.progress

import com.chrisgoac.gymlog.domain.media.MediaRejection
import com.chrisgoac.gymlog.domain.media.MediaUiState
import com.chrisgoac.gymlog.model.media.EnqueueResult
import com.chrisgoac.gymlog.model.media.InvalidImage
import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaOwner
import com.chrisgoac.gymlog.model.media.MediaStatus
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoGalleryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val media = FakeMedia()
    private val capture = FakeCaptureTargets()
    private val now = dayMillis(2026, 8, 12) + 10 * 3_600_000L
    private val viewModel by lazy { PhotoGalleryViewModel(media, capture) { PhotoClock(now, MADRID) } }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.observed(): PhotoGalleryViewModel {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        advanceUntilIdle()
        return viewModel
    }

    private val first = progressItem("p1", dayMillis(2026, 6, 1), "frente")
    private val second = progressItem("p2", dayMillis(2026, 7, 1), "frente", "tras el verano")
    private val third = progressItem("p3", dayMillis(2026, 7, 1), "lado")

    @Test fun `the gallery is chronological and grouped by day, newest day first`() = runTest(dispatcher) {
        media.items.value = listOf(first, second, third)
        val vm = observed()
        val days = vm.state.value.days
        assertEquals(listOf(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 6, 1)), days.map { it.date })
        assertEquals(listOf("p2", "p3"), days.first().photos.map { it.id })
        assertEquals("tras el verano", days.first().photos.first().note)
        assertEquals(PhotoGalleryStatus.Ready, vm.state.value.status)
    }

    @Test fun `no photos is ready and empty, not loading forever`() = runTest(dispatcher) {
        val vm = observed()
        assertEquals(PhotoGalleryStatus.Ready, vm.state.value.status)
        assertTrue(vm.state.value.days.isEmpty())
        assertFalse(vm.state.value.canCompare)
    }

    @Test fun `a failed first load shows failed and retry recovers`() = runTest(dispatcher) {
        media.observeError = IllegalStateException("db")
        val vm = observed()
        assertEquals(PhotoGalleryStatus.Failed, vm.state.value.status)
        media.observeError = null
        media.items.value = listOf(first)
        vm.retryLoad()
        advanceUntilIdle()
        assertEquals(PhotoGalleryStatus.Ready, vm.state.value.status)
    }

    @Test fun `a synced photo is available once its cached image is loaded`() = runTest(dispatcher) {
        media.items.value = listOf(first)
        media.images["p1"] = MediaImage.Available("/private/p1.jpg")
        val vm = observed()
        assertEquals(MediaUiState.Available(null), vm.state.value.photos.single().state)
        vm.requestImage("p1")
        advanceUntilIdle()
        assertEquals(MediaUiState.Available("/private/p1.jpg"), vm.state.value.photos.single().state)
        vm.requestImage("p1") // already loaded: no second fetch
        advanceUntilIdle()
        assertEquals(listOf("p1"), media.imageCalls)
    }

    @Test fun `an uncached photo offline is unavailable and a retry fetches a fresh signed copy`() = runTest(dispatcher) {
        media.items.value = listOf(first)
        media.images["p1"] = MediaImage.Unavailable(MediaImage.Unavailable.Reason.OFFLINE)
        val vm = observed()
        vm.requestImage("p1")
        advanceUntilIdle()
        assertEquals(MediaUiState.RemoteUnavailableOffline, vm.state.value.photos.single().state)

        media.images["p1"] = MediaImage.Available("/private/p1.jpg") // back online: the repository refreshed its read grant
        vm.retryImage("p1")
        advanceUntilIdle()
        assertEquals(MediaUiState.Available("/private/p1.jpg"), vm.state.value.photos.single().state)
        assertEquals(2, media.imageCalls.size)
    }

    @Test fun `a failing image fetch is a retryable failure, never a crash`() = runTest(dispatcher) {
        media.items.value = listOf(first)
        val vm = observed()
        vm.requestImage("p1")
        advanceUntilIdle()
        assertEquals(MediaUiState.RetryableFailure(), vm.state.value.photos.single().state)
    }

    @Test fun `a queued photo shows its local original and moves to available when the upload lands`() = runTest(dispatcher) {
        val queued = progressItem("p4", dayMillis(2026, 8, 12), status = MediaStatus.PendingUpload(0, null))
        media.items.value = listOf(queued)
        media.images["p4"] = MediaImage.Available("/private/pending/p4.jpg")
        val vm = observed()
        vm.requestImage("p4")
        advanceUntilIdle()
        assertEquals(MediaUiState.LocalPending("/private/pending/p4.jpg"), vm.state.value.photos.single().state)

        media.images["p4"] = MediaImage.Available("/private/cache/p4.jpg")
        media.items.value = listOf(queued.copy(status = MediaStatus.Synced, objectKey = "users/h/progress/p4"))
        advanceUntilIdle()
        vm.requestImage("p4") // the tile re-requests when the state changes
        advanceUntilIdle()
        assertEquals(MediaUiState.Available("/private/cache/p4.jpg"), vm.state.value.photos.single().state)
    }

    @Test fun `a failed upload attempt is a retryable failure and a refused one is rejected`() = runTest(dispatcher) {
        media.items.value = listOf(
            progressItem("a", dayMillis(2026, 8, 1), status = MediaStatus.PendingUpload(2, "timeout")),
            progressItem("b", dayMillis(2026, 8, 2), status = MediaStatus.Rejected("MEDIA_TOO_LARGE")),
        )
        val vm = observed()
        val byId = vm.state.value.photos.associateBy { it.id }
        assertEquals(MediaUiState.RetryableFailure(null, "timeout"), byId.getValue("a").state)
        assertEquals(MediaUiState.Rejected(MediaRejection.TOO_LARGE), byId.getValue("b").state)
    }

    @Test fun `retrying a refused upload requeues it and shows uploading until it changes`() = runTest(dispatcher) {
        media.items.value = listOf(progressItem("b", dayMillis(2026, 8, 2), status = MediaStatus.Rejected("X")))
        val vm = observed()
        vm.onRetryUpload("b")
        advanceUntilIdle()
        assertEquals(listOf("b"), media.retried)
        assertEquals(MediaUiState.Uploading(null), vm.state.value.photos.single().state)
        media.items.value = listOf(progressItem("b", dayMillis(2026, 8, 2), status = MediaStatus.PendingUpload(1, "offline")))
        advanceUntilIdle()
        assertTrue(vm.state.value.photos.single().state is MediaUiState.RetryableFailure)
    }

    @Test fun `a failed retry is reported and does not pretend to upload`() = runTest(dispatcher) {
        media.items.value = listOf(progressItem("b", dayMillis(2026, 8, 2), status = MediaStatus.Rejected("X")))
        val vm = observed()
        media.failNext = IllegalStateException("db")
        vm.onRetryUpload("b")
        advanceUntilIdle()
        assertEquals(PhotoMessage.ActionFailed, vm.state.value.message)
        assertTrue(vm.state.value.photos.single().state is MediaUiState.Rejected)
    }

    @Test fun `discarding a refused upload asks first and then drops it locally`() = runTest(dispatcher) {
        media.items.value = listOf(progressItem("b", dayMillis(2026, 8, 2), status = MediaStatus.Rejected("X")))
        val vm = observed()
        vm.onAskDelete("b")
        advanceUntilIdle()
        assertEquals("b", vm.state.value.pendingDelete?.id)
        assertTrue(media.discarded.isEmpty())
        vm.onConfirmDelete()
        advanceUntilIdle()
        assertEquals(listOf("b"), media.discarded)
        assertTrue(media.deleted.isEmpty())
        assertNull(vm.state.value.pendingDelete)
    }

    @Test fun `deleting a photo needs confirmation, can be cancelled and closes the viewer`() = runTest(dispatcher) {
        media.items.value = listOf(first, second)
        val vm = observed()
        vm.onOpen("p1")
        vm.onAskDelete("p1")
        advanceUntilIdle()
        vm.onCancelDelete()
        advanceUntilIdle()
        assertTrue(media.deleted.isEmpty())
        assertEquals("p1", vm.state.value.viewing?.id)

        vm.onAskDelete("p1")
        vm.onConfirmDelete()
        advanceUntilIdle()
        assertEquals(listOf("p1"), media.deleted)
        assertNull(vm.state.value.viewing)
        assertEquals(listOf("p2"), vm.state.value.photos.map { it.id })
    }

    @Test fun `the viewer closes by itself when its photo disappears`() = runTest(dispatcher) {
        media.items.value = listOf(first, second)
        val vm = observed()
        vm.onOpen("p2")
        advanceUntilIdle()
        assertNotNull(vm.state.value.viewing)
        media.items.value = listOf(first)
        advanceUntilIdle()
        assertNull(vm.state.value.viewing)
    }

    // ---- Capture ----

    @Test fun `picking a photo stores it with the chosen angle, local-midnight date and trimmed note`() = runTest(dispatcher) {
        val vm = observed()
        vm.onAddPhoto()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 8, 12), vm.state.value.draft?.date)
        vm.onDraftAngle("espalda")
        advanceUntilIdle()
        vm.onDraftDate(LocalDate.of(2026, 8, 10))
        advanceUntilIdle()
        vm.onDraftNote("  mañana, en ayunas ")
        advanceUntilIdle()
        vm.onPhotoPicked("content://media/picker/1")
        advanceUntilIdle()
        val (uri, owner) = media.enqueued.single()
        assertEquals("content://media/picker/1", uri)
        assertEquals(MediaOwner.Progress(dayMillis(2026, 8, 10), "espalda", "mañana, en ayunas"), owner)
        assertNull(vm.state.value.draft)
        assertNull(vm.state.value.message)
    }

    @Test fun `a cancelled picker leaves the form as it was`() = runTest(dispatcher) {
        val vm = observed()
        vm.onAddPhoto()
        advanceUntilIdle()
        vm.onDraftNote("x")
        advanceUntilIdle()
        vm.onPhotoPicked(null)
        advanceUntilIdle()
        assertTrue(media.enqueued.isEmpty())
        assertEquals("x", vm.state.value.draft?.note)
    }

    @Test fun `the draft rejects future days, unknown angles and over-long notes`() = runTest(dispatcher) {
        val vm = observed()
        vm.onAddPhoto()
        advanceUntilIdle()
        vm.onDraftDate(LocalDate.of(2026, 8, 13))
        advanceUntilIdle()
        vm.onDraftAngle("cabeza")
        advanceUntilIdle()
        vm.onDraftNote("n".repeat(2500))
        advanceUntilIdle()
        val draft = vm.state.value.draft!!
        assertEquals(LocalDate.of(2026, 8, 12), draft.date)
        assertEquals("frente", draft.angle)
        assertEquals(2000, draft.note.length)
    }

    @Test fun `an unreadable or unsupported image keeps the form and explains why`() = runTest(dispatcher) {
        val vm = observed()
        vm.onAddPhoto()
        advanceUntilIdle()
        media.enqueueResult = EnqueueResult.Invalid(InvalidImage.UNSUPPORTED)
        vm.onPhotoPicked("content://media/picker/2")
        advanceUntilIdle()
        assertEquals(PhotoMessage.Unsupported, vm.state.value.message)
        assertNotNull(vm.state.value.draft)
        assertFalse(vm.state.value.draft!!.busy)
        vm.dismissMessage()
        advanceUntilIdle()
        assertNull(vm.state.value.message)
    }

    @Test fun `an enqueue error keeps the form and reports a failed action`() = runTest(dispatcher) {
        val vm = observed()
        vm.onAddPhoto()
        advanceUntilIdle()
        media.failNext = IllegalStateException("disk")
        vm.onPhotoPicked("content://media/picker/3")
        advanceUntilIdle()
        assertEquals(PhotoMessage.ActionFailed, vm.state.value.message)
        assertNotNull(vm.state.value.draft)
    }

    @Test fun `a camera photo is ingested from the capture slot and the slot is always released`() = runTest(dispatcher) {
        val vm = observed()
        vm.onAddPhoto()
        advanceUntilIdle()
        val uri = vm.prepareCamera()
        assertEquals("content://gymlog.media/capture/1.jpg", uri)
        vm.onCameraResult(true)
        advanceUntilIdle()
        assertEquals("content://gymlog.media/capture/1.jpg", media.enqueued.single().first)
        assertEquals(1, capture.released.size)
        assertNull(vm.state.value.draft)
    }

    @Test fun `a cancelled camera releases the slot without storing anything`() = runTest(dispatcher) {
        val vm = observed()
        vm.onAddPhoto()
        advanceUntilIdle()
        vm.prepareCamera()
        vm.onCameraResult(false)
        advanceUntilIdle()
        assertTrue(media.enqueued.isEmpty())
        assertEquals(1, capture.released.size)
        assertNotNull(vm.state.value.draft)
    }

    @Test fun `a camera failure while ingesting still releases the slot`() = runTest(dispatcher) {
        val vm = observed()
        vm.onAddPhoto()
        advanceUntilIdle()
        vm.prepareCamera()
        media.failNext = IllegalStateException("disk")
        vm.onCameraResult(true)
        advanceUntilIdle()
        assertEquals(1, capture.released.size)
        assertEquals(PhotoMessage.ActionFailed, vm.state.value.message)
    }

    @Test fun `no camera without an open account says so`() = runTest(dispatcher) {
        val vm = observed()
        vm.onAddPhoto()
        advanceUntilIdle()
        capture.available = false
        assertNull(vm.prepareCamera())
        advanceUntilIdle()
        assertEquals(PhotoMessage.CameraUnavailable, vm.state.value.message)
    }

    // ---- Comparison ----

    @Test fun `comparing defaults to the oldest and newest photo of the first angle with two`() = runTest(dispatcher) {
        media.items.value = listOf(first, second, third, progressItem("p5", dayMillis(2026, 5, 1), "frente"))
        val vm = observed()
        assertTrue(vm.state.value.canCompare)
        vm.onCompare()
        advanceUntilIdle()
        val cmp = vm.state.value.comparison!!
        assertEquals(listOf("frente"), cmp.angles)
        assertEquals(listOf("p5", "p1", "p2"), cmp.series.map { it.id })
        assertEquals("p5", cmp.a.id)
        assertEquals("p2", cmp.b.id)
    }

    @Test fun `each side can be chosen and the sides can be swapped`() = runTest(dispatcher) {
        media.items.value = listOf(first, second, progressItem("p5", dayMillis(2026, 5, 1), "frente"))
        val vm = observed()
        vm.onCompare()
        vm.onCompareA("p1")
        vm.onCompareB("p5")
        advanceUntilIdle()
        assertEquals("p1" to "p5", vm.state.value.comparison!!.a.id to vm.state.value.comparison!!.b.id)
        vm.onSwap()
        advanceUntilIdle()
        assertEquals("p5" to "p1", vm.state.value.comparison!!.a.id to vm.state.value.comparison!!.b.id)
        vm.onCloseCompare()
        advanceUntilIdle()
        assertFalse(vm.state.value.comparing)
        assertNull(vm.state.value.comparison)
    }

    @Test fun `one photo per angle cannot be compared and refused photos are left out`() = runTest(dispatcher) {
        media.items.value = listOf(first, third, progressItem("r", dayMillis(2026, 8, 1), "frente", status = MediaStatus.Rejected("X")))
        val vm = observed()
        assertFalse(vm.state.value.canCompare)
        vm.onCompare()
        advanceUntilIdle()
        assertTrue(vm.state.value.comparing)
        assertNull(vm.state.value.comparison)
    }

    @Test fun `changing the angle picks that angle's own defaults`() = runTest(dispatcher) {
        media.items.value = listOf(first, second, progressItem("l1", dayMillis(2026, 6, 2), "lado"), third)
        val vm = observed()
        vm.onCompare()
        vm.onCompareAngle("lado")
        advanceUntilIdle()
        val cmp = vm.state.value.comparison!!
        assertEquals(listOf("frente", "lado"), cmp.angles)
        assertEquals("l1" to "p3", cmp.a.id to cmp.b.id)
    }

    @Test fun `deleting a compared photo falls back to the angle's defaults`() = runTest(dispatcher) {
        media.items.value = listOf(first, second, progressItem("p5", dayMillis(2026, 5, 1), "frente"))
        val vm = observed()
        vm.onCompare()
        vm.onCompareA("p1")
        advanceUntilIdle()
        media.items.value = media.items.value.filterNot { it.id == "p1" }
        advanceUntilIdle()
        assertEquals("p5" to "p2", vm.state.value.comparison!!.a.id to vm.state.value.comparison!!.b.id)
    }

    @Test fun `an outstanding camera slot is released when the screen goes away`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val owned = ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = viewModel as T
        })[PhotoGalleryViewModel::class]
        owned.onAddPhoto()
        owned.prepareCamera()
        store.clear()
        assertEquals(1, capture.released.size)
    }
}
