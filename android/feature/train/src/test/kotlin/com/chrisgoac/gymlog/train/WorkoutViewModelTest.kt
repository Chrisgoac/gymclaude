package com.chrisgoac.gymlog.train

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
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
import com.chrisgoac.gymlog.domain.workout.ExerciseHistory
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.HistorySet
import com.chrisgoac.gymlog.domain.workout.ProgressionSettings
import com.chrisgoac.gymlog.domain.workout.SetChange
import com.chrisgoac.gymlog.domain.workout.SetFieldError
import com.chrisgoac.gymlog.domain.workout.StartedWorkout
import com.chrisgoac.gymlog.domain.workout.TrainingSummary
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.domain.workout.WorkoutLogRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.domain.workout.WorkoutSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory session store with the repository's rules (compact order, no-op on unchanged/missing). */
private class FakeLog : WorkoutRepository, WorkoutLogRepository {
    val workout = MutableStateFlow(
        WorkoutAggregate(
            WorkoutSession("s1", routineId = "r1", gymId = null, startedAt = 1_000L, durationSeconds = null, notes = null),
            listOf(WorkoutExercise("le1", "seed-press", "Press de banca", 0, listOf(WorkoutSet("set1", 0, 60.0, 8, false)))),
        ),
    )
    val history = MutableStateFlow<Map<String, ExerciseHistory>>(emptyMap())
    val writes = mutableListOf<String>()
    var addGate: CompletableDeferred<Unit>? = null
    var failRemove = false
    var failObserve = 0
    private var ids = 0

    override fun observeActiveWorkout(): Flow<WorkoutAggregate?> = workout
    override fun observeWorkout(sessionId: String): Flow<WorkoutAggregate?> =
        if (failObserve > 0) {
            failObserve--
            flow { throw IllegalStateException("disk") }
        } else {
            workout.map { it.takeIf { w -> w.session.id == sessionId } }
        }
    override fun observeGyms(): Flow<List<Gym>> = flowOf(emptyList())
    override fun observeTrainingSummary(): Flow<TrainingSummary> = flowOf(TrainingSummary(0, 3, 0))
    override suspend fun startFreeWorkout(gymId: String?) = StartedWorkout("s1", true)
    override suspend fun startRoutineWorkout(routineId: String, gymId: String?) = StartedWorkout("s1", true)

    override fun observeExerciseHistory(exerciseIds: Set<String>, excludeSessionId: String, gymId: String?) =
        history.map { all -> all.filterKeys { it in exerciseIds } }
    override fun observeProgressionSettings(): Flow<ProgressionSettings> = flowOf(ProgressionSettings())

    private fun edit(transform: (List<WorkoutExercise>) -> List<WorkoutExercise>) {
        workout.value = workout.value.copy(
            exercises = transform(workout.value.exercises).mapIndexed { i, e ->
                e.copy(position = i, sets = e.sets.mapIndexed { j, s -> s.copy(position = j) })
            },
        )
    }

    private fun findSet(id: String) = workout.value.exercises.flatMap { it.sets }.firstOrNull { it.id == id }

    override suspend fun addExercise(sessionId: String, exerciseId: String): String {
        val id = "le-new${++ids}"
        writes += "addExercise:$exerciseId"
        edit { it + WorkoutExercise(id, exerciseId, exerciseId, 0, emptyList()) }
        return id
    }
    override suspend fun removeExercise(loggedExerciseId: String) {
        if (workout.value.exercises.none { it.id == loggedExerciseId }) return
        writes += "removeExercise:$loggedExerciseId"
        edit { list -> list.filterNot { it.id == loggedExerciseId } }
    }
    override suspend fun moveExercise(loggedExerciseId: String, delta: Int) {
        writes += "moveExercise:$loggedExerciseId:$delta"
        edit { list ->
            val i = list.indexOfFirst { it.id == loggedExerciseId }
            list.toMutableList().apply { add((i + delta).coerceIn(0, lastIndex), removeAt(i)) }
        }
    }
    override suspend fun addSet(loggedExerciseId: String, weight: Double, reps: Int, warmup: Boolean): String {
        addGate?.await()
        val id = "set-new${++ids}"
        writes += "addSet:$loggedExerciseId:$weight:$reps"
        edit { list -> list.map { if (it.id == loggedExerciseId) it.copy(sets = it.sets + WorkoutSet(id, 0, weight, reps, warmup)) else it } }
        return id
    }
    override suspend fun updateSet(setId: String, change: SetChange) {
        val set = findSet(setId) ?: return
        val next = set.copy(weight = change.weight ?: set.weight, reps = change.reps ?: set.reps, warmup = change.warmup ?: set.warmup)
        if (next == set) return
        writes += "updateSet:$setId:${next.weight}:${next.reps}:${next.warmup}"
        edit { list -> list.map { e -> e.copy(sets = e.sets.map { if (it.id == setId) next else it }) } }
    }
    override suspend fun removeSet(setId: String) {
        if (failRemove) throw IllegalStateException("disk full")
        if (findSet(setId) == null) return
        writes += "removeSet:$setId"
        edit { list -> list.map { e -> e.copy(sets = e.sets.filterNot { it.id == setId }) } }
    }
    override suspend fun moveSet(setId: String, delta: Int) {
        writes += "moveSet:$setId:$delta"
        edit { list ->
            list.map { e ->
                val i = e.sets.indexOfFirst { it.id == setId }
                if (i < 0) e else e.copy(sets = e.sets.toMutableList().apply { add((i + delta).coerceIn(0, lastIndex), removeAt(i)) })
            }
        }
    }
    override suspend fun updateNotes(sessionId: String, notes: String) {
        writes += "notes:${notes.trim()}"
        workout.value = workout.value.copy(session = workout.value.session.copy(notes = notes.trim().ifEmpty { null }))
    }
}

private val press = Exercise("seed-press", "Press de banca", MuscleGroup.PECHO, Equipment.BARRA, ExerciseType.COMPUESTO, isCustom = false)
private val curl = Exercise("seed-curl", "Curl con barra Z", MuscleGroup.BICEPS, Equipment.BARRA, ExerciseType.AISLAMIENTO, isCustom = false)

private class FakeExercises : ExerciseRepository {
    override fun observeExercises(filter: ExerciseFilter): Flow<List<Exercise>> = flowOf(listOf(press, curl).filter(filter::matches))
    override fun observeExercise(id: String): Flow<Exercise?> = flowOf(listOf(press, curl).firstOrNull { it.id == id })
    override suspend fun createCustom(input: ExerciseInput): Exercise = error("unused")
    override suspend fun updateCustom(id: String, input: ExerciseInput): Exercise = error("unused")
    override suspend fun deleteCustom(id: String) = error("unused")
}

private class TargetRoutines : RoutineRepository {
    private val routine = Routine(
        "r1", "Push", null, false,
        listOf(RoutineExercise("re1", "seed-press", "Press de banca", 0, sets = 3, repsMin = 6, repsMax = 8, restSeconds = 90, notes = null)),
    )
    override fun observeRoutines() = flowOf(emptyList<com.chrisgoac.gymlog.domain.routine.RoutineSummary>())
    override fun observeRoutine(id: String): Flow<Routine?> = flowOf(routine.takeIf { it.id == id })
    override fun observeNextRoutineId(): Flow<String?> = flowOf(null)
    override suspend fun create(input: RoutineInput): Routine = error("unused")
    override suspend fun update(id: String, input: RoutineInput): Routine = error("unused")
    override suspend fun reorder(idsInOrder: List<String>) = error("unused")
    override suspend fun setArchived(id: String, archived: Boolean) = error("unused")
    override suspend fun delete(id: String) = error("unused")
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeLog()
    private val rests = mutableListOf<Int?>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(saved: SavedStateHandle = SavedStateHandle(), debounce: Long = 400): WorkoutViewModel {
        val vm = WorkoutViewModel("s1", repo, repo, FakeExercises(), TargetRoutines(), saved, onSetCompleted = { rests += it }, debounceMillis = debounce)
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private fun WorkoutViewModel.row(setId: String) = state.value.exercises.flatMap { it.sets }.first { it.id == setId }

    private fun recreate(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })

    @Test fun `comma and dot decimals commit once after the debounce and the typed text stays`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onWeight("set1", "6")
        vm.onWeight("set1", "62")
        vm.onWeight("set1", "62,")
        vm.onWeight("set1", "62,5")
        advanceTimeBy(399)
        assertTrue("no write while typing", repo.writes.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("updateSet:set1:62.5:8:false"), repo.writes)
        // Stable focus: the stored 62.5 re-emits but the field keeps exactly what was typed.
        assertEquals("62,5", vm.row("set1").weightText)
        vm.onReps("set1", "10")
        advanceUntilIdle()
        assertEquals("updateSet:set1:62.5:10:false", repo.writes.last())
    }

    @Test fun `invalid input shows an inline error and never writes`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onWeight("set1", "abc")
        advanceUntilIdle()
        assertEquals(SetFieldError.NOT_A_NUMBER, vm.row("set1").weightError)
        vm.onWeight("set1", "-5")
        vm.onReps("set1", "1001")
        advanceUntilIdle()
        assertEquals(SetFieldError.NEGATIVE, vm.row("set1").weightError)
        assertEquals(SetFieldError.TOO_LARGE, vm.row("set1").repsError)
        vm.onWeight("set1", "1000,5")
        vm.onReps("set1", "")
        advanceUntilIdle()
        assertEquals(SetFieldError.TOO_LARGE, vm.row("set1").weightError)
        assertEquals(SetFieldError.REQUIRED, vm.row("set1").repsError)
        assertTrue(repo.writes.isEmpty())
        // A valid value right before an invalid one is not written either: the latest text wins.
        vm.onReps("set1", "9")
        vm.onReps("set1", "9x")
        advanceUntilIdle()
        assertTrue(repo.writes.isEmpty())
    }

    @Test fun `completing a set commits immediately, once, and hands the rest to the timer hook`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onWeight("set1", "65")
        vm.completeSet("set1")
        vm.completeSet("set1")
        runCurrent()
        assertEquals(listOf("updateSet:set1:65.0:8:false"), repo.writes)
        assertTrue(vm.row("set1").completed)
        assertEquals(listOf<Int?>(90), rests)
        advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        assertEquals(listOf<Int?>(90), rests)
    }

    @Test fun `a set needs reps and valid fields to complete`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onReps("set1", "0")
        vm.completeSet("set1")
        advanceUntilIdle()
        assertFalse(vm.row("set1").completed)
        assertEquals(SetFieldError.ZERO_REPS, vm.row("set1").repsError)
        assertTrue(rests.isEmpty())
        vm.onWeight("set1", "x")
        vm.onReps("set1", "5")
        vm.completeSet("set1")
        advanceUntilIdle()
        assertFalse(vm.row("set1").completed)
        assertEquals(SetFieldError.NOT_A_NUMBER, vm.row("set1").weightError)
        // Valid fields are still stored (field by field); the invalid weight is not.
        assertEquals(listOf("updateSet:set1:60.0:0:false", "updateSet:set1:60.0:5:false"), repo.writes)
        assertTrue(rests.isEmpty())
    }

    @Test fun `adding a set flushes the pending edit and copies the last set`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onWeight("set1", "70")
        vm.addSet("le1")
        runCurrent()
        assertEquals(listOf("updateSet:set1:70.0:8:false", "addSet:le1:70.0:8"), repo.writes)
        assertEquals(listOf(1, 2), vm.state.value.exercises.single().sets.map { it.number })
    }

    @Test fun `rapid duplicate add taps create one set`() = runTest(dispatcher) {
        val vm = viewModel()
        repo.addGate = CompletableDeferred()
        repeat(3) { vm.addSet("le1") }
        runCurrent()
        repo.addGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repo.writes.count { it.startsWith("addSet") })
    }

    @Test fun `first set of an exercise uses the progression suggestion and previous values are shown`() = runTest(dispatcher) {
        repo.workout.value = repo.workout.value.copy(exercises = listOf(WorkoutExercise("le1", "seed-press", "Press de banca", 0, emptyList())))
        repo.history.value = mapOf("seed-press" to ExerciseHistory(500L, listOf(HistorySet(40.0, 10, true), HistorySet(60.0, 8, false), HistorySet(60.0, 8, false))))
        val vm = viewModel()
        val exercise = vm.state.value.exercises.single()
        assertEquals(HistorySet(60.0, 8, false), exercise.previous)
        assertEquals(500L, exercise.previousDate)
        assertEquals("▲ +2.5 kg", exercise.suggestionBadge)
        assertEquals(62.5, exercise.suggestion!!.weight, 0.0)
        assertEquals(90, exercise.target!!.restSeconds)

        vm.addSet("le1")
        advanceUntilIdle()
        assertEquals(listOf("addSet:le1:62.5:8"), repo.writes)
        // Row 1 shows the first set of last time (the warm-up), row-by-row.
        assertEquals(HistorySet(40.0, 10, true), vm.state.value.exercises.single().sets.single().previous)
    }

    @Test fun `removals compact numbering and duplicate removals are harmless`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.addSet("le1")
        advanceUntilIdle()
        vm.addSet("le1")
        advanceUntilIdle()
        val ids = vm.state.value.exercises.single().sets.map { it.id }
        vm.removeSet(ids[1])
        vm.removeSet(ids[1])
        advanceUntilIdle()
        val rows = vm.state.value.exercises.single().sets
        assertEquals(listOf(ids[0], ids[2]), rows.map { it.id })
        assertEquals(listOf(1, 2), rows.map { it.number })
        assertEquals(1, repo.writes.count { it.startsWith("removeSet") })

        vm.moveSet(ids[2], -1)
        advanceUntilIdle()
        assertEquals(listOf(ids[2], ids[0]), vm.state.value.exercises.single().sets.map { it.id })
    }

    @Test fun `drafts and completed marks survive process recreation and still get committed`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        // The original process never gets to commit its last edit (it "dies" first).
        val vm = viewModel(handle, debounce = 3_600_000)
        vm.onWeight("set1", "80,5")
        vm.addExercise(curl)
        runCurrent()
        vm.flushPending()
        runCurrent()
        val completedId = repo.workout.value.exercises.first().sets.single().id
        vm.completeSet(completedId)
        runCurrent()
        vm.onReps(completedId, "12")
        runCurrent()
        val writesBefore = repo.writes.size

        // Process death before the debounce fired: a new ViewModel from the saved state.
        val restored = viewModel(recreate(handle))
        assertEquals("12", restored.row(completedId).repsText)
        assertTrue(restored.row(completedId).completed)
        advanceTimeBy(401)
        runCurrent()
        assertEquals(listOf("updateSet:set1:80.5:12:false"), repo.writes.drop(writesBefore))
    }

    @Test fun `leaving flushes pending edits without waiting for the debounce`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onWeight("set1", "75")
        vm.onNotes("Buen día")
        vm.flushPending()
        runCurrent()
        assertEquals(setOf("updateSet:set1:75.0:8:false", "notes:Buen día"), repo.writes.toSet())
    }

    @Test fun `clearing the ViewModel still commits pending edits`() = runTest(dispatcher) {
        val vm = viewModel()
        val store = ViewModelStore()
        store.put("vm", vm)
        vm.onReps("set1", "11")
        store.clear()
        runCurrent()
        assertEquals(listOf("updateSet:set1:60.0:11:false"), repo.writes)
    }

    @Test fun `exercises can be added via search, reordered and removed`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.openPicker()
        vm.onPickerQuery("curl")
        runCurrent()
        assertEquals(listOf(curl), vm.pickerResults.value)
        vm.addExercise(curl)
        vm.addExercise(curl)
        advanceUntilIdle()
        assertFalse(vm.state.value.pickerOpen)
        assertEquals(listOf("le1", "le-new1"), vm.state.value.exercises.map { it.id })
        assertEquals(1, repo.writes.count { it.startsWith("addExercise") })

        vm.moveExercise("le-new1", -1)
        advanceUntilIdle()
        assertEquals(listOf("le-new1", "le1"), vm.state.value.exercises.map { it.id })
        vm.removeExercise("le1")
        advanceUntilIdle()
        assertEquals(listOf("le-new1"), vm.state.value.exercises.map { it.id })
    }

    @Test fun `warmup edits write the flag and notes over the limit are rejected inline`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setWarmup("set1", true)
        vm.setWarmup("set1", true)
        advanceUntilIdle()
        assertEquals(listOf("updateSet:set1:60.0:8:true"), repo.writes)
        assertTrue(vm.row("set1").warmup)

        vm.onNotes("x".repeat(2001))
        advanceUntilIdle()
        assertTrue(vm.state.value.notesTooLong)
        assertEquals(1, repo.writes.size)
    }

    @Test fun `a missing workout is reported`() = runTest(dispatcher) {
        val vm = WorkoutViewModel("nope", repo, repo, FakeExercises(), TargetRoutines(), SavedStateHandle())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        runCurrent()
        assertEquals(WorkoutLogStatus.Missing, vm.state.value.status)
        assertNull(vm.state.value.startedAt)
    }

    /** Simulates a sync pull changing the stored set behind the screen's back. */
    private fun remoteEdit(setId: String, weight: Double) {
        repo.workout.value = repo.workout.value.copy(
            exercises = repo.workout.value.exercises.map { e -> e.copy(sets = e.sets.map { if (it.id == setId) it.copy(weight = weight) else it }) },
        )
    }

    @Test fun `a committed edit never overwrites a newer synced value on leave or restore`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.onWeight("set1", "62,5")
        advanceUntilIdle()
        assertEquals(listOf("updateSet:set1:62.5:8:false"), repo.writes)
        assertEquals("62,5", vm.row("set1").weightText)

        remoteEdit("set1", 70.0)
        runCurrent()
        // The synced value replaces the settled draft on screen ...
        assertEquals("70", vm.row("set1").weightText)
        // ... and leaving, clearing or recreating the screen writes nothing over it.
        vm.flushPending()
        advanceUntilIdle()
        val restored = viewModel(recreate(handle))
        advanceUntilIdle()
        val store = ViewModelStore()
        store.put("vm", vm)
        store.clear()
        advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        assertEquals("70", restored.row("set1").weightText)
        assertEquals(70.0, repo.workout.value.exercises.single().sets.single().weight, 0.0)
    }

    @Test fun `notes typed once are not rewritten over a synced change`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onNotes("Pecho")
        advanceUntilIdle()
        repo.workout.value = repo.workout.value.copy(session = repo.workout.value.session.copy(notes = "Pecho y hombro"))
        vm.flushPending()
        advanceUntilIdle()
        assertEquals(listOf("notes:Pecho"), repo.writes)
        assertEquals("Pecho y hombro", vm.state.value.notes)
    }

    @Test fun `a failed removal keeps the pending edit and still stores it`() = runTest(dispatcher) {
        val vm = viewModel()
        repo.failRemove = true
        vm.onWeight("set1", "70")
        vm.removeSet("set1")
        advanceUntilIdle()
        assertTrue(vm.state.value.writeFailed)
        assertEquals("70", vm.row("set1").weightText)
        assertEquals(listOf("updateSet:set1:70.0:8:false"), repo.writes)
    }

    @Test fun `a failed load can be retried`() = runTest(dispatcher) {
        repo.failObserve = 1
        val vm = viewModel()
        assertEquals(WorkoutLogStatus.Failed, vm.state.value.status)
        vm.retry()
        runCurrent()
        assertEquals(WorkoutLogStatus.Ready, vm.state.value.status)
    }

    @Test fun `the first set uses the suggestion even when nothing observes the screen state`() = runTest(dispatcher) {
        repo.workout.value = repo.workout.value.copy(exercises = listOf(WorkoutExercise("le1", "seed-press", "Press de banca", 0, emptyList())))
        repo.history.value = mapOf("seed-press" to ExerciseHistory(500L, listOf(HistorySet(60.0, 8, false))))
        val vm = WorkoutViewModel("s1", repo, repo, FakeExercises(), TargetRoutines(), SavedStateHandle())
        vm.addSet("le1")
        advanceUntilIdle()
        assertEquals(listOf("addSet:le1:62.5:8"), repo.writes)
    }
}
