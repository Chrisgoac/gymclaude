package com.chrisgoac.gymlog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import com.chrisgoac.gymlog.domain.routine.RoutineExercise
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.routine.RoutineSummary
import com.chrisgoac.gymlog.domain.workout.ExerciseHistory
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.HistorySet
import com.chrisgoac.gymlog.domain.workout.ProgressionSettings
import com.chrisgoac.gymlog.domain.workout.SetChange
import com.chrisgoac.gymlog.domain.workout.StartedWorkout
import com.chrisgoac.gymlog.domain.workout.TrainingSummary
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.domain.workout.WorkoutLogRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.domain.workout.WorkoutSet
import com.chrisgoac.gymlog.train.ActiveWorkoutScreen
import com.chrisgoac.gymlog.train.WorkoutViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val benchPress = Exercise("seed-press", "Press de banca", MuscleGroup.PECHO, Equipment.BARRA, ExerciseType.COMPUESTO, isCustom = false)
private val zCurl = Exercise("seed-curl", "Curl con barra Z", MuscleGroup.BICEPS, Equipment.BARRA, ExerciseType.AISLAMIENTO, isCustom = false)

/** Minimal in-memory session for screen tests: records writes, applies set edits and additions. */
private class ScreenWorkouts : WorkoutRepository, WorkoutLogRepository {
    val workout = MutableStateFlow(
        WorkoutAggregate(
            WorkoutSession("s1", "r1", null, 1_000L, null, null),
            listOf(WorkoutExercise("le1", "seed-press", "Press de banca", 0, listOf(WorkoutSet("set1", 0, 60.0, 8, false)))),
        ),
    )
    val writes = mutableListOf<String>()

    override fun observeActiveWorkout(): Flow<WorkoutAggregate?> = workout
    override fun observeWorkout(sessionId: String): Flow<WorkoutAggregate?> = workout
    override fun observeGyms(): Flow<List<Gym>> = flowOf(emptyList())
    override fun observeTrainingSummary(): Flow<TrainingSummary> = flowOf(TrainingSummary(0, 3, 0))
    override suspend fun startFreeWorkout(gymId: String?) = StartedWorkout("s1", true)
    override suspend fun startRoutineWorkout(routineId: String, gymId: String?) = StartedWorkout("s1", true)
    override fun observeExerciseHistory(exerciseIds: Set<String>, excludeSessionId: String, gymId: String?) =
        flowOf(mapOf("seed-press" to ExerciseHistory(500L, listOf(HistorySet(57.5, 8, false), HistorySet(60.0, 8, false)))))
    override fun observeProgressionSettings(): Flow<ProgressionSettings> = flowOf(ProgressionSettings())

    override suspend fun addExercise(sessionId: String, exerciseId: String): String {
        writes += "addExercise:$exerciseId"
        val w = workout.value
        workout.value = w.copy(exercises = w.exercises + WorkoutExercise("le2", exerciseId, "Curl con barra Z", 1, emptyList()))
        return "le2"
    }
    override suspend fun removeExercise(loggedExerciseId: String) { writes += "removeExercise:$loggedExerciseId" }
    override suspend fun moveExercise(loggedExerciseId: String, delta: Int) { writes += "moveExercise" }
    override suspend fun addSet(loggedExerciseId: String, weight: Double, reps: Int, warmup: Boolean): String {
        writes += "addSet:$loggedExerciseId:$weight:$reps"
        return "new"
    }
    override suspend fun updateSet(setId: String, change: SetChange) {
        writes += "updateSet:$setId:${change.weight}:${change.reps}"
        val w = workout.value
        workout.value = w.copy(
            exercises = w.exercises.map { e ->
                e.copy(sets = e.sets.map { if (it.id == setId) it.copy(weight = change.weight ?: it.weight, reps = change.reps ?: it.reps) else it })
            },
        )
    }
    override suspend fun removeSet(setId: String) { writes += "removeSet:$setId" }
    override suspend fun moveSet(setId: String, delta: Int) { writes += "moveSet" }
    override suspend fun updateNotes(sessionId: String, notes: String) { writes += "notes" }
}

private class Catalog : ExerciseRepository {
    override fun observeExercises(filter: ExerciseFilter): Flow<List<Exercise>> = MutableStateFlow(listOf(benchPress, zCurl).filter(filter::matches))
    override fun observeExercise(id: String): Flow<Exercise?> = MutableStateFlow(listOf(benchPress, zCurl).firstOrNull { it.id == id })
    override suspend fun createCustom(input: ExerciseInput): Exercise = error("not expected")
    override suspend fun updateCustom(id: String, input: ExerciseInput): Exercise = error("not expected")
    override suspend fun deleteCustom(id: String) = Unit
}

private class PushRoutine : RoutineRepository {
    private val routine = Routine("r1", "Push", null, false, listOf(RoutineExercise("re1", "seed-press", "Press de banca", 0, 3, 6, 8, 90, null)))
    override fun observeRoutines() = MutableStateFlow(emptyList<RoutineSummary>())
    override fun observeRoutine(id: String): Flow<Routine?> = MutableStateFlow(routine).map { it.takeIf { r -> r.id == id } }
    override fun observeNextRoutineId(): Flow<String?> = MutableStateFlow(null)
    override suspend fun create(input: RoutineInput): Routine = error("unused")
    override suspend fun update(id: String, input: RoutineInput): Routine = error("unused")
    override suspend fun reorder(idsInOrder: List<String>) = Unit
    override suspend fun setArchived(id: String, archived: Boolean) = Unit
    override suspend fun delete(id: String) = Unit
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class ActiveWorkoutScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun show(repo: ScreenWorkouts, rests: MutableList<Int?>, fontScale: Float = 2f) {
        val viewModel = WorkoutViewModel("s1", repo, repo, Catalog(), PushRoutine(), SavedStateHandle(), onSetCompleted = { rests += it })
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GymLogTheme { ActiveWorkoutScreen(viewModel, onBack = {}) }
            }
        }
        rule.waitForIdle()
    }

    @Test fun setRowFitsAndLogsAtDoubleFontScale() {
        val repo = ScreenWorkouts()
        val rests = mutableListOf<Int?>()
        show(repo, rests)

        rule.onNodeWithText("Press de banca").assertIsDisplayed()
        rule.onNodeWithText("Objetivo · 3 × 6–8 · desc. 90 s").assertExists()
        rule.onNodeWithText("▲ +2.5 kg · sugerido 62.5 kg × 8").assertExists()
        rule.onNodeWithText("Ant. 57.5 × 8").assertExists()

        val weight = rule.onNodeWithContentDescription("Peso de la serie 1, kilos").performScrollTo().assertIsDisplayed()
        val reps = rule.onNodeWithContentDescription("Repeticiones de la serie 1").assertIsDisplayed()
        val done = rule.onNodeWithContentDescription("Marcar la serie 1 como hecha").assertIsDisplayed()
        // One-hand row at 200% font: weight, reps and the complete button stay inside a 360dp screen.
        listOf(weight, reps, done).forEach { node ->
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("inside the screen: $bounds", bounds.right <= 360.dp && bounds.left >= 0.dp)
        }

        weight.performTextClearance()
        weight.performTextInput("62,5")
        done.performClick()
        rule.waitForIdle()

        assertTrue(repo.writes.toString(), repo.writes.contains("updateSet:set1:62.5:null"))
        assertEquals(listOf<Int?>(90), rests)
        rule.onNodeWithContentDescription("Serie 1 hecha. Tocar para reabrirla").assertExists()
    }

    @Test fun invalidWeightShowsInlineErrorAndBlocksCompletion() {
        val repo = ScreenWorkouts()
        show(repo, mutableListOf())
        val weight = rule.onNodeWithContentDescription("Peso de la serie 1, kilos").performScrollTo()
        weight.performTextClearance()
        weight.performTextInput("1001")
        rule.onNodeWithText("Máximo 1000 kg").assertExists()
        rule.onNodeWithContentDescription("Marcar la serie 1 como hecha").performClick()
        rule.waitForIdle()
        assertTrue(repo.writes.isEmpty())
    }

    @Test fun addsAnExerciseFromSearch() {
        val repo = ScreenWorkouts()
        show(repo, mutableListOf(), fontScale = 1f)
        rule.onNodeWithText("Añadir ejercicio").performScrollTo().performClick()
        rule.onNodeWithText("Nombre del ejercicio".uppercase()).assertExists()
        rule.onNodeWithContentDescription("Añadir Curl con barra Z al entrenamiento").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("addExercise:seed-curl"), repo.writes)
        rule.onNodeWithText("Curl con barra Z").assertExists()
    }
}
