package com.chrisgoac.gymlog.history

import androidx.lifecycle.SavedStateHandle
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseInput
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.exercise.ExerciseType
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.history.DefaultSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.EditOutcome
import com.chrisgoac.gymlog.domain.history.ExerciseRowEdit
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.history.SessionEdit
import com.chrisgoac.gymlog.domain.history.SetRowEdit
import com.chrisgoac.gymlog.domain.history.WorkoutEdit
import com.chrisgoac.gymlog.domain.history.WorkoutEditRepository
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.HistoryDetail
import com.chrisgoac.gymlog.domain.workout.HistoryFilter
import com.chrisgoac.gymlog.domain.workout.HistoryPage
import com.chrisgoac.gymlog.domain.workout.HistoryRepository
import com.chrisgoac.gymlog.domain.workout.SetFieldError
import com.chrisgoac.gymlog.domain.workout.StartedWorkout
import com.chrisgoac.gymlog.domain.workout.TrainingSummary
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.domain.workout.WorkoutSet
import com.chrisgoac.gymlog.domain.workout.summary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.time.ZoneOffset

private const val NOW = 1_800_000_000_000L // 2027-01-15T08:00:00Z
private const val DAY = 24L * 60 * 60 * 1000

private val squat = Exercise("seed-squat", "Sentadilla", MuscleGroup.CUADRICEPS, Equipment.BARRA, ExerciseType.COMPUESTO, isCustom = false)

private fun aggregate(reps: Int = 8, version: Long = 1) = WorkoutAggregate(
    // 2027-01-13T08:00:12.345Z: seconds that the dd/MM/yyyy HH:mm fields cannot show.
    WorkoutSession("s1", null, "g1", NOW - 2 * DAY + 12_345, 3600, "Bien", updatedAt = version),
    listOf(
        WorkoutExercise("le1", "seed-press", "Press de banca", 0, listOf(WorkoutSet("a", 0, 60.0, reps, false, version), WorkoutSet("b", 1, 62.5, 6, false, version)), version),
        WorkoutExercise("le2", "seed-row", "Remo", 1, listOf(WorkoutSet("c", 0, 50.0, 10, true, version)), version),
    ),
)

private class EditorHistory : HistoryRepository {
    val detail = MutableStateFlow<HistoryDetail?>(detailOf(aggregate()))
    var reads = 0

    fun detailOf(agg: WorkoutAggregate) = HistoryDetail(agg, "Casa", null, agg.summary(3600))

    override fun observeHistory(filter: HistoryFilter, limit: Int): Flow<HistoryPage> = flowOf(HistoryPage(emptyList(), false))
    override fun observeDetail(sessionId: String): Flow<HistoryDetail?> {
        reads++
        return detail
    }
    override suspend fun changeGym(sessionId: String, gymId: String?) = error("not used")
    override suspend fun deleteSession(sessionId: String) = error("not used")
}

private class EditorGyms : WorkoutRepository {
    override fun observeActiveWorkout(): Flow<WorkoutAggregate?> = flowOf(null)
    override fun observeWorkout(sessionId: String): Flow<WorkoutAggregate?> = flowOf(null)
    override fun observeGyms(): Flow<List<Gym>> = flowOf(listOf(Gym("g1", "Casa"), Gym("g2", "Fitness")))
    override fun observeTrainingSummary(): Flow<TrainingSummary> = flowOf(TrainingSummary(0, 3, 0))
    override suspend fun startFreeWorkout(gymId: String?) = StartedWorkout("x", false)
    override suspend fun startRoutineWorkout(routineId: String, gymId: String?) = StartedWorkout("x", false)
}

private class EditorExercises : ExerciseRepository {
    override fun observeExercises(filter: ExerciseFilter): Flow<List<Exercise>> = flowOf(listOf(squat).filter(filter::matches))
    override fun observeExercise(id: String): Flow<Exercise?> = flowOf(null)
    override suspend fun createCustom(input: ExerciseInput): Exercise = error("not used")
    override suspend fun updateCustom(id: String, input: ExerciseInput): Exercise = error("not used")
    override suspend fun deleteCustom(id: String) = Unit
}

/** Storage stand-in: records every write; [stored] is the version a conflict check compares with. */
private class EditorStorage(private val history: EditorHistory) : WorkoutEditRepository {
    val writes = mutableListOf<WorkoutEdit>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun missingExercises(exerciseIds: Set<String>): Set<String> = exerciseIds - setOf("seed-press", "seed-row", "seed-squat")
    override suspend fun isGymAvailable(gymId: String): Boolean = gymId in setOf("g1", "g2")
    override suspend fun apply(original: WorkoutAggregate, edit: WorkoutEdit): EditOutcome {
        gate?.await()
        val stored = history.detail.value?.workout ?: return EditOutcome.NotFound
        if (stored != original) return EditOutcome.Conflict
        writes += edit
        return EditOutcome.Applied
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val history = EditorHistory()
    private val storage = EditorStorage(history)
    private var ids = 0

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.editor(handle: SavedStateHandle = SavedStateHandle()): HistoryEditorViewModel {
        val vm = HistoryEditorViewModel(
            "s1", history, EditorGyms(), EditorExercises(), DefaultSaveWorkoutDraftUseCase(storage) { NOW }, handle,
            now = { NOW }, zone = ZoneOffset.UTC, newId = { "new-${++ids}" },
        )
        backgroundScope.launch(dispatcher) { vm.pickerResults.collect {} }
        advanceUntilIdle()
        return vm
    }

    private fun recreate(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })

    @Test fun `opens the stored workout as a clean draft`() = runTest(dispatcher) {
        val state = editor().state.value
        assertEquals(EditorStatus.Ready, state.status)
        assertEquals("13/01/2027", state.dateText)
        assertEquals("08:00", state.timeText)
        assertEquals("g1", state.gymId)
        assertEquals("Bien", state.notes)
        assertEquals(listOf("le1", "le2"), state.exercises.map { it.id })
        assertEquals(listOf("60", "62.5"), state.exercises[0].sets.map { it.weightText })
        assertFalse(state.dirty)
    }

    @Test fun `a full edit saves once with stable ids and returns the invalidated scopes`() = runTest(dispatcher) {
        val vm = editor()
        vm.onDate("10/01/2027")
        vm.onTime("19:30")
        vm.onGym("g2")
        vm.onNotes("Editado")
        vm.onWeight("a", "65")
        vm.removeSet("b")
        vm.moveExercise("le2", -1)
        vm.openPicker()
        vm.onPickerQuery("senta")
        advanceUntilIdle()
        assertEquals(listOf(squat), vm.pickerResults.value)
        vm.addExercise(squat)
        vm.addSet("new-1")
        vm.onWeight("new-2", "100")
        vm.onReps("new-2", "5")
        vm.addSet("new-1") // copies the last set
        vm.setWarmup("c", false)
        assertTrue(vm.state.value.dirty)

        vm.save()
        vm.save() // a second tap while saving is ignored
        advanceUntilIdle()

        val edit = storage.writes.single()
        assertEquals(SessionEdit(1_799_609_400_000L, "g2", "Editado"), edit.session) // 2027-01-10T19:30Z
        assertEquals(
            listOf(ExerciseRowEdit("le2", "seed-row", 0), ExerciseRowEdit("le1", "seed-press", 1), ExerciseRowEdit("new-1", "seed-squat", 2)),
            edit.upsertExercises,
        )
        assertEquals(
            listOf(
                SetRowEdit("c", "le2", 0, 50.0, 10, false),
                SetRowEdit("a", "le1", 0, 65.0, 8, false),
                SetRowEdit("new-2", "new-1", 0, 100.0, 5, false),
                SetRowEdit("new-3", "new-1", 1, 100.0, 5, false),
            ),
            edit.upsertSets,
        )
        assertEquals(listOf("b"), edit.removedSetIds)
        assertTrue(edit.removedExerciseIds.isEmpty())
        assertTrue(vm.exit.value)
        assertEquals(InvalidationScope.entries.toSet(), vm.lastInvalidatedScopes.value)
    }

    @Test fun `untouched date fields keep the original instant to the millisecond`() = runTest(dispatcher) {
        val vm = editor()
        vm.onNotes("Solo notas")
        vm.save()
        advanceUntilIdle()
        assertEquals(aggregate().session.startedAt, storage.writes.single().session!!.startedAt)
        assertEquals(setOf(InvalidationScope.COACH_SNAPSHOT), vm.lastInvalidatedScopes.value)
    }

    @Test fun `cancel without changes closes and writes nothing`() = runTest(dispatcher) {
        val vm = editor()
        vm.requestExit()
        assertTrue(vm.exit.value)
        assertTrue(storage.writes.isEmpty())
    }

    @Test fun `cancel with changes asks first, and discarding writes nothing`() = runTest(dispatcher) {
        val vm = editor()
        vm.onReps("a", "12")
        vm.removeExercise("le2")
        vm.requestExit()
        advanceUntilIdle()
        assertTrue(vm.state.value.confirmingExit)
        assertFalse(vm.exit.value)

        vm.keepEditing()
        advanceUntilIdle()
        assertFalse(vm.state.value.confirmingExit)
        assertEquals("12", vm.state.value.exercises[0].sets[0].repsText)

        vm.requestExit()
        vm.requestExit() // back again closes the prompt
        advanceUntilIdle()
        assertFalse(vm.state.value.confirmingExit)

        vm.requestExit()
        vm.discard()
        advanceUntilIdle()
        assertTrue(vm.exit.value)
        assertTrue(storage.writes.isEmpty())
    }

    @Test fun `saving an unchanged draft closes without writing`() = runTest(dispatcher) {
        val vm = editor()
        vm.onWeight("a", "60,0") // same value, typed differently
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.exit.value)
        assertTrue(storage.writes.isEmpty())
        assertEquals(emptySet<InvalidationScope>(), vm.lastInvalidatedScopes.value)
    }

    @Test fun `every problem is listed before anything is written, and fixing one removes it`() = runTest(dispatcher) {
        val vm = editor()
        vm.onWeight("a", "2000")
        vm.onReps("b", "")
        vm.onNotes("x".repeat(2001))
        vm.onDate("20/01/2027") // in the future
        advanceUntilIdle()
        assertEquals(SetFieldError.TOO_LARGE, vm.state.value.exercises[0].sets[0].weightError)
        assertFalse(vm.state.value.showIssues)

        vm.save()
        advanceUntilIdle()
        assertTrue(storage.writes.isEmpty())
        assertFalse(vm.exit.value)
        val state = vm.state.value
        assertTrue(state.showIssues)
        assertEquals(
            listOf(
                EditorIssue.DateInFuture,
                EditorIssue.NotesTooLong,
                EditorIssue.InvalidWeight("Press de banca", 1, SetFieldError.TOO_LARGE),
                EditorIssue.InvalidReps("Press de banca", 2, SetFieldError.REQUIRED),
            ),
            state.issues,
        )

        vm.onWeight("a", "100")
        advanceUntilIdle()
        assertEquals(3, vm.state.value.issues.size)
    }

    @Test fun `an unreadable date blocks saving and a deleted exercise added meanwhile is reported`() = runTest(dispatcher) {
        val vm = editor()
        vm.onDate("31/02/2027")
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf(EditorIssue.DateUnreadable), vm.state.value.issues)
        assertTrue(vm.state.value.dateUnreadable)

        vm.onDate("13/01/2027")
        vm.addExercise(Exercise("custom-gone", "Borrado", MuscleGroup.OTRO, Equipment.OTRO, ExerciseType.COMPUESTO, isCustom = true))
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf(EditorIssue.UnknownExercise(3)), vm.state.value.issues)
        vm.removeExercise("new-1")
        advanceUntilIdle()
        assertTrue(vm.state.value.issues.isEmpty())
        assertTrue(storage.writes.isEmpty())
    }

    @Test fun `a version synced meanwhile is a conflict and reload starts from it`() = runTest(dispatcher) {
        val vm = editor()
        vm.onReps("b", "7")
        history.detail.value = history.detailOf(aggregate(reps = 9, version = 2)) // pulled from another device
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.conflict)
        assertFalse(vm.exit.value)
        assertTrue(storage.writes.isEmpty())

        vm.save() // disabled while in conflict
        advanceUntilIdle()
        assertTrue(storage.writes.isEmpty())

        vm.reload()
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.conflict)
        assertFalse(state.dirty)
        assertEquals("9", state.exercises[0].sets[0].repsText)
        assertEquals("6", state.exercises[0].sets[1].repsText)
    }

    @Test fun `the draft and its original survive process recreation without re-reading`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val first = editor(handle)
        first.onTime("07:15")
        first.onWeight("a", "70")
        first.addExercise(squat)
        first.addSet("new-1")
        first.onWeight("new-2", "80")
        first.onReps("new-2", "3")
        first.requestExit()
        advanceUntilIdle()
        val before = first.state.value
        val reads = history.reads

        // Meanwhile sync changes the stored version: the restored editor must still notice it.
        history.detail.value = history.detailOf(aggregate(reps = 10, version = 3))
        val restored = editor(recreate(handle))
        assertEquals(reads, history.reads)
        val after = restored.state.value
        assertEquals(before.copy(gyms = after.gyms), after)
        assertTrue(after.confirmingExit)

        restored.keepEditing()
        restored.save()
        advanceUntilIdle()
        assertTrue(restored.state.value.conflict)
        assertTrue(storage.writes.isEmpty())
    }

    @Test fun `restored edits save normally when nothing changed elsewhere`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        editor(handle).onReps("a", "11")
        val restored = editor(recreate(handle))
        restored.save()
        advanceUntilIdle()
        assertEquals(listOf(SetRowEdit("a", "le1", 0, 60.0, 11, false)), storage.writes.single().upsertSets)
    }

    @Test fun `edits are ignored while saving and a deleted workout is reported`() = runTest(dispatcher) {
        val vm = editor()
        val gate = CompletableDeferred<Unit>()
        storage.gate = gate
        vm.onNotes("x")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.saving)
        vm.onNotes("y")
        history.detail.value = null
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.gone)
        assertEquals("x", vm.state.value.notes)
        assertTrue(storage.writes.isEmpty())
    }

    @Test fun `a missing workout shows not found`() = runTest(dispatcher) {
        history.detail.value = null
        assertEquals(EditorStatus.NotFound, editor().state.value.status)
        assertNull(editor().lastInvalidatedScopes.value)
    }
}
