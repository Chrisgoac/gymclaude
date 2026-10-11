package com.chrisgoac.gymlog.media

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chrisgoac.gymlog.domain.media.CaptureSlot
import com.chrisgoac.gymlog.domain.media.MediaRepository
import com.chrisgoac.gymlog.domain.media.PhotoCaptureTargets
import com.chrisgoac.gymlog.exercises.ExercisePhotosViewModel
import com.chrisgoac.gymlog.model.media.EnqueueResult
import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaItem
import com.chrisgoac.gymlog.model.media.MediaKind
import com.chrisgoac.gymlog.model.media.MediaOwner
import com.chrisgoac.gymlog.progress.CaptureDraft
import com.chrisgoac.gymlog.progress.PhotoClock
import com.chrisgoac.gymlog.progress.PhotoGalleryViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

private class NoMedia : MediaRepository {
    val enqueued = mutableListOf<Pair<String, MediaOwner>>()
    override suspend fun enqueue(sourceUri: String, owner: MediaOwner): EnqueueResult { enqueued += sourceUri to owner; return EnqueueResult.Queued("n") }
    override fun observe(kind: MediaKind): Flow<List<MediaItem>> = emptyFlow()
    override suspend fun delete(mediaId: String) = Unit
    override suspend fun retry(mediaId: String) = Unit
    override suspend fun discard(mediaId: String) = Unit
    override suspend fun image(mediaId: String): MediaImage = MediaImage.Unavailable(MediaImage.Unavailable.Reason.OFFLINE)
}

private class Targets : PhotoCaptureTargets {
    val released = mutableListOf<CaptureSlot>()
    override fun create() = CaptureSlot("content://gymlog.media/capture/1.jpg", "t1")
    override fun release(slot: CaptureSlot) { released += slot }
}

/**
 * The photo view models are built the way GymLogNavHost builds them (a factory taking
 * `createSavedStateHandle()`); an activity that is saved and recreated (the process-death path) must
 * hand the new view model the form and the camera slot the old one left behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhotoStateRestorationTest {
    private val now = 1_800_000_000_000L
    private val zone = ZoneId.of("Europe/Madrid")

    private fun gallery(activity: ComponentActivity, media: MediaRepository, targets: PhotoCaptureTargets): PhotoGalleryViewModel =
        ViewModelProvider.create(
            activity.viewModelStore,
            viewModelFactory { initializer { PhotoGalleryViewModel(media, targets, { PhotoClock(now, zone) }, createSavedStateHandle()) } },
            activity.defaultViewModelCreationExtras,
        )[PhotoGalleryViewModel::class]

    @Test fun theGalleryRestoresItsFormAndCameraSlotInANewProcess() {
        val targets = Targets()
        val saved = Bundle()
        Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().also { controller ->
            val vm = gallery(controller.get(), NoMedia(), targets)
            vm.onAddPhoto()
            vm.onDraftAngle("espalda")
            vm.onDraftNote("antes")
            vm.prepareCamera()
            controller.saveInstanceState(saved)
        }

        val media = NoMedia()
        Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume().also { controller ->
            val revived = gallery(controller.get(), media, targets)
            revived.onCameraResult(true)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val draftOwner = media.enqueued.singleOrNull()
            assertEquals("content://gymlog.media/capture/1.jpg", draftOwner?.first)
            val owner = draftOwner?.second as MediaOwner.Progress
            assertEquals("espalda", owner.angulo)
            assertEquals("antes", owner.nota)
        }
    }

    @Test fun theExerciseViewModelRestoresItsCameraSlot() {
        val targets = Targets()
        val saved = Bundle()
        Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().also { controller ->
            val vm = ViewModelProvider.create(
                controller.get().viewModelStore,
                viewModelFactory { initializer { ExercisePhotosViewModel(NoMedia(), targets, "e1", createSavedStateHandle()) } },
                controller.get().defaultViewModelCreationExtras,
            )[ExercisePhotosViewModel::class]
            vm.prepareCamera()
            controller.saveInstanceState(saved)
        }
        val media = NoMedia()
        Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume().also { controller ->
            val revived = ViewModelProvider.create(
                controller.get().viewModelStore,
                viewModelFactory { initializer { ExercisePhotosViewModel(media, targets, "e1", createSavedStateHandle()) } },
                controller.get().defaultViewModelCreationExtras,
            )[ExercisePhotosViewModel::class]
            revived.onCameraResult(true)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(MediaOwner.Exercise("e1"), media.enqueued.single().second)
        }
    }
}
