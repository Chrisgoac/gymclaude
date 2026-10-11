package com.chrisgoac.gymlog

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chrisgoac.gymlog.data.exercise.OfflineExerciseRepository
import com.chrisgoac.gymlog.data.routine.OfflineRoutineRepository
import com.chrisgoac.gymlog.data.workout.OfflineHistoryRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutEditRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutLogRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutRepository
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.history.DefaultSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.routine.RoutineExerciseInput
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.timer.RestTimerAlarm
import com.chrisgoac.gymlog.domain.timer.RestTimerController
import com.chrisgoac.gymlog.domain.timer.RestTimerState
import com.chrisgoac.gymlog.domain.timer.RestTimerStore
import com.chrisgoac.gymlog.domain.workout.FinishWorkoutUseCase
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.navigation.HistoryEditServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline workout flow through the real navigation shell, screens, ViewModels, repositories and a
 * Room database on a device, with no network and no Clerk session: [GymLogRoot] is rendered
 * directly in [RootState.Ready] with an in-memory account database (the composable already takes
 * every dependency explicitly, so no production seam is needed). Sync is not wired: every write
 * must land in Room + outbox and render without HTTP.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutOfflineE2eTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var db: GymLogDatabase
    private lateinit var runner: RoomLocalMutationRunner

    private class Manager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase(USER, db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private class MemoryTimerStore : RestTimerStore {
        private var state: RestTimerState? = null
        override suspend fun read() = state
        override suspend fun write(state: RestTimerState?) { this.state = state }
    }

    private object NoAlarm : RestTimerAlarm {
        override fun schedule(runId: String, sessionId: String, endsAtEpochMillis: Long) = Unit
        override fun cancel() = Unit
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java).build()
        runner = RoomLocalMutationRunner(db, DeviceId("e2e-device"))
        val manager = Manager(db)
        val runnerFor = { _: GymLogDatabase -> runner }
        val exercises = OfflineExerciseRepository(manager, runnerFor)
        val routines = OfflineRoutineRepository(manager, runnerFor, exercises)
        val workouts = OfflineWorkoutRepository(manager, runnerFor, exercises)
        val restTimer = RestTimerController(MemoryTimerStore(), NoAlarm)
        runBlocking {
            db.catalogDao().upsertAll(
                listOf(
                    CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null),
                    CatalogExerciseEntity("seed-remo", "Remo con barra", "espalda", "barra", "compuesto", null, null, null),
                ),
            )
            runner.run(LocalMutation { put(GymRecord(RecordId("g1"), nextTimestamp(SyncTable.GYMS, RecordId("g1")), null, "Fitness", 0, false)) })
            routines.create(RoutineInput("Empuje", "", listOf(RoutineExerciseInput("k-press", "seed-press", "Press de banca"))))
        }
        rule.setContent {
            GymLogTheme {
                GymLogRoot(
                    RootState.Ready(USER),
                    authGateway = { error("no auth screen in a Ready session") },
                    exercises = exercises,
                    routines = routines,
                    workouts = workouts,
                    workoutLog = OfflineWorkoutLogRepository(manager, runnerFor),
                    restTimer = restTimer,
                    finishWorkout = FinishWorkoutUseCase(workouts, workouts, restTimer),
                    history = OfflineHistoryRepository(manager, runnerFor, exercises, routines),
                    historyEditing = HistoryEditServices(
                        DefaultSaveWorkoutDraftUseCase(OfflineWorkoutEditRepository(manager, runnerFor), System::currentTimeMillis),
                        System::currentTimeMillis,
                    ),
                )
            }
        }
    }

    @After
    fun tearDown() = db.close()

    private val verticalList = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun awaitText(text: String) = rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitDescription(description: String) =
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }

    private fun outboxSize(): Int = runBlocking { db.syncDao().allOutbox().size }

    private fun liveSets() = db.query("SELECT peso, reps FROM logged_sets WHERE deletedAt IS NULL ORDER BY orden", null)
        .use { c -> buildList { while (c.moveToNext()) add(c.getDouble(0) to c.getInt(1)) } }

    private fun finishedSessions(): Int = db.query("SELECT COUNT(*) FROM workout_sessions WHERE deletedAt IS NULL AND duracionSegundos IS NOT NULL", null)
        .use { c -> c.moveToFirst(); c.getInt(0) }

    /** In the active workout: adds set [number] to Press de banca, types it and marks it done. */
    private fun logPressSet(number: Int, weight: String, reps: String) {
        awaitDescription("Añadir serie a Press de banca")
        rule.onNodeWithContentDescription("Añadir serie a Press de banca").performScrollTo().performClick()
        awaitDescription("Peso de la serie $number, kilos")
        rule.onNodeWithContentDescription("Peso de la serie $number, kilos").performScrollTo().run { performTextClearance(); performTextInput(weight) }
        rule.onNodeWithContentDescription("Repeticiones de la serie $number").performScrollTo().run { performTextClearance(); performTextInput(reps) }
        rule.onNodeWithContentDescription("Marcar la serie $number como hecha").performScrollTo().performClick()
        awaitDescription("Serie $number hecha. Tocar para reabrirla")
    }

    private fun finishWorkout() {
        rule.onNode(verticalList).performScrollToNode(hasText("Terminar entrenamiento"))
        rule.onNodeWithText("Terminar entrenamiento").performClick()
        awaitText("Entrenamiento terminado")
        rule.onNodeWithText("Listo").performClick()
    }

    @Test
    fun routineWorkoutIsLoggedFinishedAndEditedFromHistoryOffline() {
        awaitDescription("Empezar la rutina Empuje")
        rule.onNodeWithContentDescription("Empezar la rutina Empuje").performScrollTo().performClick()

        awaitText("Press de banca")
        logPressSet(1, "60", "8")
        logPressSet(2, "62.5", "6")
        rule.waitUntil(TIMEOUT) { liveSets() == listOf(60.0 to 8, 62.5 to 6) }

        finishWorkout()
        rule.waitUntil(TIMEOUT) { finishedSessions() == 1 }
        val queuedBeforeEdit = outboxSize()
        assertTrue("every write is queued locally", queuedBeforeEdit > 0)

        // History: the finished workout, its detail, then the editor.
        rule.onNodeWithText("Historial").performClick()
        awaitText("Empuje")
        rule.onNode(hasText("Empuje") and hasClickAction()).performClick()
        awaitText("1. 60 kg × 8")
        rule.onNodeWithText("2. 62.5 kg × 6").assertExists()
        rule.onNode(verticalList).performScrollToNode(hasText("Editar entreno"))
        rule.onNodeWithText("Editar entreno").performClick()

        val reps = "Repeticiones de la serie 1 de Press de banca"
        awaitDescription(reps)
        rule.onNode(verticalList).performScrollToNode(hasContentDescription(reps))
        rule.onNodeWithContentDescription(reps).run { performTextClearance(); performTextInput("10") }
        rule.onNode(verticalList).performScrollToNode(hasText("Guardar cambios"))
        rule.onNodeWithText("Guardar cambios").performClick()

        awaitText("1. 60 kg × 10")
        rule.waitUntil(TIMEOUT) { liveSets() == listOf(60.0 to 10, 62.5 to 6) }
        assertEquals("a history edit is ONE outbox mutation", queuedBeforeEdit + 1, outboxSize())
    }

    @Test
    fun freeWorkoutIsLoggedAndFinishedOffline() {
        awaitDescription("Empezar un entrenamiento libre")
        rule.onNodeWithContentDescription("Empezar un entrenamiento libre").performScrollTo().performClick()

        awaitText("Añadir ejercicio")
        rule.onNodeWithText("Añadir ejercicio").performScrollTo().performClick()
        awaitDescription("Añadir Press de banca al entrenamiento")
        rule.onNodeWithContentDescription("Añadir Press de banca al entrenamiento").performScrollTo().performClick()

        logPressSet(1, "70", "5")
        finishWorkout()
        rule.waitUntil(TIMEOUT) { finishedSessions() == 1 && liveSets() == listOf(70.0 to 5) }
        assertNotNull(runBlocking { db.syncDao().allOutbox().firstOrNull() })

        rule.onNodeWithText("Historial").performClick()
        awaitText("Entrenamiento libre")
    }

    private companion object {
        const val USER = "user_e2e"
        const val TIMEOUT = 10_000L
    }
}
