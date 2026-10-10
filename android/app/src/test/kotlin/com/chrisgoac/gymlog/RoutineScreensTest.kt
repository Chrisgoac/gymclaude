package com.chrisgoac.gymlog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseInput
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.exercise.ExerciseType
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.routine.Routine
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.routine.RoutineSummary
import com.chrisgoac.gymlog.routines.RoutineEditorScreen
import com.chrisgoac.gymlog.routines.RoutineEditorViewModel
import com.chrisgoac.gymlog.routines.RoutineListScreen
import com.chrisgoac.gymlog.routines.RoutineListViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class MemoryRoutines(initial: List<RoutineSummary>) : RoutineRepository {
    val summaries = MutableStateFlow(initial)
    val next = MutableStateFlow<String?>(null)
    var reordered: List<String>? = null
    var created: RoutineInput? = null
    var editorLoads = 0

    override fun observeRoutines(): Flow<List<RoutineSummary>> = summaries
    override fun observeRoutine(id: String): Flow<Routine?> {
        editorLoads++
        return summaries.map { null }
    }
    override fun observeNextRoutineId(): Flow<String?> = next
    override suspend fun create(input: RoutineInput): Routine {
        created = input
        return Routine("new", input.name, null, false, emptyList())
    }
    override suspend fun update(id: String, input: RoutineInput): Routine = error("not expected")
    override suspend fun reorder(idsInOrder: List<String>) { reordered = idsInOrder }
    override suspend fun setArchived(id: String, archived: Boolean) = Unit
    override suspend fun delete(id: String) = Unit
}

private class MemoryExercises(val all: List<Exercise>) : ExerciseRepository {
    override fun observeExercises(filter: ExerciseFilter): Flow<List<Exercise>> = MutableStateFlow(all.filter(filter::matches))
    override fun observeExercise(id: String): Flow<Exercise?> = MutableStateFlow(all.firstOrNull { it.id == id })
    override suspend fun createCustom(input: ExerciseInput): Exercise = error("not expected")
    override suspend fun updateCustom(id: String, input: ExerciseInput): Exercise = error("not expected")
    override suspend fun deleteCustom(id: String) = Unit
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class RoutineScreensTest {
    @get:Rule val rule = createComposeRule()

    private val push = RoutineSummary("r1", "Push", "Pecho y hombro", false, 2, listOf("Press de banca", "Fondos"))
    private val pull = RoutineSummary("r2", "Pull", null, false, 1, emptyList())
    private val old = RoutineSummary("r3", "Antigua", null, true, 0, emptyList())
    private val press = Exercise("seed-press", "Press de banca", MuscleGroup.PECHO, Equipment.BARRA, ExerciseType.COMPUESTO, isCustom = false)

    @Test fun listMarksNextStartsAndReorders(): Unit = with(rule) {
        val repo = MemoryRoutines(listOf(push, pull, old)).apply { next.value = "r2" }
        val started = mutableListOf<String>()
        val viewModel = RoutineListViewModel(repo)
        setContent { GymLogTheme { RoutineListScreen(viewModel, onOpen = {}, onCreate = {}, onStartRoutine = { started += it }) } }
        waitForIdle()

        onNodeWithText("SIGUIENTE").assertExists()
        onNodeWithText("Pull").assertExists()
        onNodeWithText("2 ejercicios · Press de banca, Fondos").assertExists()
        onNodeWithText("ARCHIVADAS").assertExists()
        onNodeWithText("Antigua").assertExists()

        onNodeWithContentDescription("Subir la rutina Push").assertIsNotEnabled()
        onNodeWithContentDescription("Bajar la rutina Pull").assertIsNotEnabled()
        onNodeWithContentDescription("Bajar la rutina Push").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(listOf("r2", "r1", "r3"), repo.reordered)

        onNodeWithContentDescription("Empezar la rutina Pull").performClick()
        assertEquals(listOf("r2"), started)
    }

    @Test fun emptyListOffersCreating(): Unit = with(rule) {
        val viewModel = RoutineListViewModel(MemoryRoutines(emptyList()))
        setContent { GymLogTheme { RoutineListScreen(viewModel, onOpen = {}, onCreate = {}, onStartRoutine = {}) } }
        waitForIdle()
        onNodeWithText("AÚN NO TIENES RUTINAS").assertExists()
        onNodeWithText("Crear rutina").assertExists()
    }

    @Test fun editorShowsRangeErrorAndAddsFromPicker(): Unit = with(rule) {
        val repo = MemoryRoutines(emptyList())
        val viewModel = RoutineEditorViewModel(repo, MemoryExercises(listOf(press)), SavedStateHandle(), null)
        setContent { GymLogTheme { RoutineEditorScreen(viewModel, onBack = {}, onDone = {}) } }

        onNodeWithText("Añadir ejercicio").performScrollTo().performClick()
        onNodeWithContentDescription("Añadir Press de banca").performScrollTo().performClick()
        onNodeWithText("Listo").performScrollTo().performClick()
        onNodeWithText("Press de banca").assertExists()

        val key = viewModel.state.value.input.exercises.single().key
        viewModel.onRepsMin(key, "12")
        viewModel.onRepsMax(key, "8")
        viewModel.onName("Push")
        onNodeWithText("Guardar").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Las reps mínimas no pueden superar a las máximas.").assertExists()
        assertNull(repo.created)
    }

    /** Process death: the draft is rebuilt from the saved-state values alone, and shows on screen. */
    @Test fun unsavedDraftReappearsAfterRecreation(): Unit = with(rule) {
        val exercises = MemoryExercises(listOf(press))
        val handle = SavedStateHandle()
        val repo = MemoryRoutines(emptyList())
        var viewModel by mutableStateOf(RoutineEditorViewModel(repo, exercises, handle, null))
        setContent { GymLogTheme { RoutineEditorScreen(viewModel, onBack = {}, onDone = {}) } }

        onAllNodesWithSetText()[0].performTextInput("Pierna pesada")
        viewModel.addExercise(press)
        waitForIdle()
        onNodeWithText("Pierna pesada").assertExists()

        // New view model from nothing but the persisted values (what the platform restores).
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
        viewModel = RoutineEditorViewModel(repo, exercises, restoredHandle, null)
        waitForIdle()

        onNodeWithText("Pierna pesada").assertExists()
        onNodeWithText("Press de banca").assertExists()
        assertEquals(0, repo.editorLoads)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithSetText() =
        onAllNodes(hasSetTextAction())
}
