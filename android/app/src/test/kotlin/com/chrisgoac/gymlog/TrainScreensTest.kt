package com.chrisgoac.gymlog

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.routine.Routine
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.routine.RoutineSummary
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.StartedWorkout
import com.chrisgoac.gymlog.domain.workout.TrainingSummary
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.train.StartWorkoutUseCase
import com.chrisgoac.gymlog.train.TrainHomeScreen
import com.chrisgoac.gymlog.train.TrainHomeViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.chrisgoac.gymlog.navigation.GymLogApp
import com.chrisgoac.gymlog.navigation.GymLogDestination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class MemoryWorkouts : WorkoutRepository {
    val active = MutableStateFlow<WorkoutAggregate?>(null)
    val startedGyms = mutableListOf<String?>()
    /** Like the real repository: with an active workout a start writes nothing and returns it as resumed. */
    var resumeExisting = false
    override fun observeActiveWorkout(): Flow<WorkoutAggregate?> = active
    override fun observeWorkout(sessionId: String): Flow<WorkoutAggregate?> = active
    override fun observeGyms(): Flow<List<Gym>> = MutableStateFlow(listOf(Gym("g1", "Central")))
    override fun observeTrainingSummary(): Flow<TrainingSummary> = MutableStateFlow(TrainingSummary(2, 3, 4))
    override suspend fun startFreeWorkout(gymId: String?): StartedWorkout {
        startedGyms += gymId
        return StartedWorkout("s1", resumed = false)
    }
    override suspend fun startRoutineWorkout(routineId: String, gymId: String?): StartedWorkout {
        active.value?.takeIf { resumeExisting }?.let { return StartedWorkout(it.session.id, resumed = true) }
        startedGyms += gymId
        return StartedWorkout("s2", resumed = false)
    }
}

private class OneRoutine : RoutineRepository {
    override fun observeRoutines() = MutableStateFlow(listOf(RoutineSummary("r1", "Push", null, false, 2, listOf("Press de banca"))))
    override fun observeRoutine(id: String): Flow<Routine?> = MutableStateFlow(null)
    override fun observeNextRoutineId(): Flow<String?> = MutableStateFlow("r1")
    override suspend fun create(input: RoutineInput): Routine = error("unused")
    override suspend fun update(id: String, input: RoutineInput): Routine = error("unused")
    override suspend fun reorder(idsInOrder: List<String>) = Unit
    override suspend fun setArchived(id: String, archived: Boolean) = Unit
    override suspend fun delete(id: String) = Unit
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class TrainScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun show(workouts: MemoryWorkouts, opened: MutableList<String> = mutableListOf(), onAchievements: () -> Unit = {}): TrainHomeViewModel {
        val viewModel = TrainHomeViewModel(workouts, OneRoutine(), StartWorkoutUseCase(workouts))
        rule.setContent {
            GymLogTheme {
                TrainHomeScreen(viewModel, onOpenWorkout = { opened += it }, onOpenBody = {}, onOpenAchievements = onAchievements)
            }
        }
        rule.waitForIdle()
        return viewModel
    }

    @Test fun idleHomeShowsNextRoutineGymSummaryAndInertCoach() {
        val workouts = MemoryWorkouts()
        show(workouts)

        rule.onNodeWithText("Push").assertExists()
        rule.onNodeWithText("2 sesiones de 3").assertExists()
        rule.onNodeWithText("Racha: 4 semanas").assertExists()
        rule.onNodeWithText("Sin gimnasio").assertExists()
        rule.onNodeWithText("Central").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Empezar la rutina Push").performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>("g1"), workouts.startedGyms)

        rule.onNodeWithContentDescription("Coach, Pro · próximamente").performScrollTo().assertIsNotEnabled()
    }

    @Test fun theWeekAndStreakCardOpensAchievementsAndSaysSo() {
        var opened = 0
        show(MemoryWorkouts(), onAchievements = { opened++ })

        rule.onNodeWithContentDescription("2 sesiones de 3. Racha: 4 semanas. Ver logros").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun activeWorkoutReplacesStartCardsWithContinue() {
        val workouts = MemoryWorkouts().apply {
            active.value = WorkoutAggregate(WorkoutSession("s9", null, null, 1_000L, null, null), emptyList())
        }
        val opened = mutableListOf<String>()
        show(workouts, opened)

        rule.onNodeWithText("Continuar entrenamiento").assertExists()
        rule.onNodeWithText("Entrenamiento libre").assertDoesNotExist()
        rule.onNodeWithText("Continuar").performClick()
        assertEquals(listOf("s9"), opened)
    }

    @Test fun startingFromRoutinesWhileActiveAsksAndContinueOpensTheRunningWorkout() {
        val workouts = MemoryWorkouts().apply {
            active.value = WorkoutAggregate(WorkoutSession("s9", null, null, 1_000L, null, null), emptyList())
            resumeExisting = true
        }
        var nav: NavHostController? = null
        rule.setContent {
            GymLogTheme {
                val controller = rememberNavController()
                nav = controller
                GymLogApp(navController = controller, workouts = workouts, routines = OneRoutine())
            }
        }
        rule.onNodeWithText("Rutinas").performClick()
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Empezar la rutina Push").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Tienes un entrenamiento en curso").assertExists()
        // "Cancelar" starts and opens nothing.
        rule.onNodeWithText("Cancelar").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Tienes un entrenamiento en curso").assertDoesNotExist()
        assertTrue(nav!!.currentDestination!!.hasRoute(GymLogDestination.Routines::class))

        rule.onNodeWithContentDescription("Empezar la rutina Push").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Continuar el actual").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Tienes un entrenamiento en curso").assertDoesNotExist()
        val destination = nav!!.currentBackStackEntry!!
        assertTrue(destination.destination.hasRoute(GymLogDestination.ActiveWorkout::class))
        assertEquals("s9", destination.toRoute<GymLogDestination.ActiveWorkout>().sessionId)
    }
}
