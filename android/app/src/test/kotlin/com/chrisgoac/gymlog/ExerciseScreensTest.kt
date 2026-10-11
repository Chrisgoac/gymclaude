package com.chrisgoac.gymlog

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseInput
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.exercise.ExerciseType
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.media.CaptureSlot
import com.chrisgoac.gymlog.domain.media.MediaRepository
import com.chrisgoac.gymlog.domain.media.PhotoCaptureTargets
import com.chrisgoac.gymlog.exercises.ExercisePhotosViewModel
import com.chrisgoac.gymlog.model.media.EnqueueResult
import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaItem
import com.chrisgoac.gymlog.model.media.MediaKind
import com.chrisgoac.gymlog.model.media.MediaOwner
import com.chrisgoac.gymlog.model.media.MediaStatus
import com.chrisgoac.gymlog.exercises.ExerciseDetailScreen
import com.chrisgoac.gymlog.exercises.ExerciseDetailViewModel
import com.chrisgoac.gymlog.exercises.ExerciseEditorScreen
import com.chrisgoac.gymlog.exercises.ExerciseEditorViewModel
import com.chrisgoac.gymlog.exercises.ExerciseListScreen
import com.chrisgoac.gymlog.exercises.ExerciseListViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class PhotoMedia : MediaRepository {
    val items = MutableStateFlow<List<MediaItem>>(emptyList())
    val discarded = mutableListOf<String>()
    override suspend fun enqueue(sourceUri: String, owner: MediaOwner): EnqueueResult = EnqueueResult.Queued("n")
    override fun observe(kind: MediaKind): Flow<List<MediaItem>> = items.map { all -> all.filter { it.owner.kind == kind } }
    override suspend fun delete(mediaId: String) = Unit
    override suspend fun retry(mediaId: String) = Unit
    override suspend fun discard(mediaId: String) { discarded += mediaId; items.value = items.value.filterNot { it.id == mediaId } }
    override suspend fun image(mediaId: String): MediaImage = MediaImage.Unavailable(MediaImage.Unavailable.Reason.OFFLINE)
}

private class PhotoCapture(val calls: MutableList<String>) : PhotoCaptureTargets {
    override fun create(): CaptureSlot? = null
    override fun release(slot: CaptureSlot) { calls += "release" }
}

private class StaticExercises(initial: List<Exercise>) : ExerciseRepository {
    val store = MutableStateFlow(initial)
    var creates = 0
    override fun observeExercises(filter: ExerciseFilter): Flow<List<Exercise>> =
        store.map { all -> all.filter(filter::matches).sortedBy { it.name } }
    override fun observeExercise(id: String): Flow<Exercise?> = store.map { all -> all.firstOrNull { it.id == id } }
    override suspend fun createCustom(input: ExerciseInput): Exercise { creates++; error("not expected") }
    override suspend fun updateCustom(id: String, input: ExerciseInput): Exercise = error("not expected")
    override suspend fun deleteCustom(id: String) = Unit
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class ExerciseScreensTest {
    @get:Rule val rule = createComposeRule()

    private val press = Exercise("seed-press", "Press de banca", MuscleGroup.PECHO, Equipment.BARRA, ExerciseType.COMPUESTO, isCustom = false)
    private val lunge = Exercise("c1", "Zancada búlgara", MuscleGroup.CUADRICEPS, Equipment.MANCUERNA, ExerciseType.COMPUESTO, isCustom = true)
    private val repository = StaticExercises(listOf(press, lunge))

    private fun showList(opened: MutableList<String> = mutableListOf()) {
        val viewModel = ExerciseListViewModel(repository)
        rule.setContent { GymLogTheme { ExerciseListScreen(viewModel, onOpen = { opened += it }, onCreate = {}) } }
    }

    @Test fun listShowsCatalogAndCustomWithAccessibleRows(): Unit = with(rule) {
        showList()
        onNodeWithText("Press de banca").assertExists()
        onNodeWithText("Zancada búlgara").assertExists()
        onNodeWithText("2 ejercicios").assertExists()
        // One merged TalkBack description per row, custom ones announced as such.
        onNodeWithContentDescription("Zancada búlgara, personalizado. Cuádriceps, Mancuerna, Compuesto.").assertExists()
        onNodeWithContentDescription("Press de banca. Pecho, Barra, Compuesto.").assertExists()
    }

    @Test fun searchIsAccentAndCaseInsensitive(): Unit = with(rule) {
        showList()
        onNode(hasSetTextAction()).performTextInput("BULGARA")
        // The search is debounced: let its pause elapse.
        mainClock.advanceTimeBy(ExerciseListViewModel.QUERY_DEBOUNCE_MILLIS + 50)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ExerciseListViewModel.QUERY_DEBOUNCE_MILLIS + 50))
        waitForIdle()
        onNodeWithText("Zancada búlgara").assertExists()
        onAllNodesWithText("Press de banca").assertCountEquals(0)
        onNodeWithText("1 ejercicio").assertExists()
    }

    @Test fun muscleFilterNarrowsAndNoResultsOffersClearing(): Unit = with(rule) {
        showList()
        onNodeWithContentDescription("Pecho").performClick()
        waitForIdle()
        onNodeWithText("Press de banca").assertExists()
        onAllNodesWithText("Zancada búlgara").assertCountEquals(0)
        onNodeWithContentDescription("Pecho, seleccionado").assertExists()

        onNodeWithContentDescription("Mancuerna").performClick()
        waitForIdle()
        onNodeWithText("SIN RESULTADOS").assertExists()
        onNodeWithText("Quitar filtros").performClick()
        waitForIdle()
        onNodeWithText("Press de banca").assertExists()
        onNodeWithText("Zancada búlgara").assertExists()
    }

    @Test fun tappingARowOpensIt(): Unit = with(rule) {
        val opened = mutableListOf<String>()
        showList(opened)
        onNodeWithContentDescription("Zancada búlgara, personalizado. Cuádriceps, Mancuerna, Compuesto.").performClick()
        assertEquals(listOf("c1"), opened)
    }

    @Test fun emptyCatalogOffersCreating(): Unit = with(rule) {
        repository.store.value = emptyList()
        showList()
        onNodeWithText("AÚN NO HAY EJERCICIOS").assertExists()
        onNodeWithText("Crear ejercicio").assertExists()
    }

    private val photoCalls = mutableListOf<String>()
    private val media = PhotoMedia()
    private fun photosViewModel(id: String) = ExercisePhotosViewModel(media, PhotoCapture(photoCalls), id)

    @Test fun catalogDetailIsReadOnlyAndExplainsThatPhotosAreForOwnExercises(): Unit = with(rule) {
        val viewModel = ExerciseDetailViewModel(repository, "seed-press")
        setContent { GymLogTheme { ExerciseDetailScreen(viewModel, onBack = {}, onEdit = {}, photos = photosViewModel("seed-press")) } }
        waitForIdle()
        onNodeWithText("Ejercicio del catálogo: es de solo lectura.").assertExists()
        onAllNodesWithText("Editar").assertCountEquals(0)
        onAllNodesWithText("Eliminar").assertCountEquals(0)
        onNodeWithText("Las fotos solo se pueden añadir a tus propios ejercicios.").assertExists()
        onAllNodesWithText("Elegir de la galería").assertCountEquals(0)
    }

    @Test fun customDetailOffersPickerAndCameraAndListsItsPhotosWithTheirState(): Unit = with(rule) {
        media.items.value = listOf(
            MediaItem("m1", MediaOwner.Exercise("c1"), MediaStatus.PendingUpload(0, null), null),
            MediaItem("m2", MediaOwner.Exercise("c1"), MediaStatus.Rejected("MEDIA_TOO_LARGE"), null),
            MediaItem("other", MediaOwner.Exercise("zz"), MediaStatus.Synced, "k"),
        )
        val viewModel = ExerciseDetailViewModel(repository, "c1")
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, 2f),
            ) { GymLogTheme { ExerciseDetailScreen(viewModel, onBack = {}, onEdit = {}, photos = photosViewModel("c1")) } }
        }
        waitForIdle()
        onNodeWithText("Elegir de la galería").performScrollTo().assertExists()
        onNodeWithText("Hacer una foto").performScrollTo().assertExists()
        onNodeWithContentDescription("Foto 1 de 2. Guardada en este dispositivo, pendiente de subir").performScrollTo().assertExists()
        onNodeWithContentDescription("Foto 2 de 2. Rechazada: la foto es demasiado grande").performScrollTo().assertExists()
    }

    @Test fun aRefusedPhotoCanBeRetriedOrDiscardedAfterConfirming(): Unit = with(rule) {
        media.items.value = listOf(MediaItem("m2", MediaOwner.Exercise("c1"), MediaStatus.Rejected("MEDIA_TOO_LARGE"), null))
        val viewModel = ExerciseDetailViewModel(repository, "c1")
        setContent { GymLogTheme { ExerciseDetailScreen(viewModel, onBack = {}, onEdit = {}, photos = photosViewModel("c1")) } }
        waitForIdle()
        onNodeWithContentDescription("Foto 1 de 1. Rechazada: la foto es demasiado grande").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Reintentar subida").assertExists()
        onNodeWithText("Descartar foto").performClick()
        waitForIdle()
        onNodeWithText("¿Descartar esta foto?").assertExists()
        assertEquals(emptyList<String>(), media.discarded)
        onNodeWithText("Descartar").performClick()
        waitForIdle()
        assertEquals(listOf("m2"), media.discarded)
    }

    @Test fun customDetailOffersEditAndConfirmedDelete(): Unit = with(rule) {
        val viewModel = ExerciseDetailViewModel(repository, "c1")
        setContent { GymLogTheme { ExerciseDetailScreen(viewModel, onBack = {}, onEdit = {}) } }
        waitForIdle()
        onNodeWithText("Editar").assertExists()
        onNodeWithText("Eliminar").performClick()
        onNodeWithText("¿ELIMINAR ESTE EJERCICIO?").assertExists()
        onNodeWithText("Cancelar").performClick()
        onNodeWithText("Editar").assertExists()
    }

    @Test fun editorShowsValidationErrorsWithoutSaving(): Unit = with(rule) {
        val viewModel = ExerciseEditorViewModel(repository, null)
        setContent { GymLogTheme { ExerciseEditorScreen(viewModel, onBack = {}, onSaved = {}) } }
        onNodeWithText("Guardar").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Escribe un nombre.").assertExists()
        assertEquals(0, repository.creates)
    }
}
