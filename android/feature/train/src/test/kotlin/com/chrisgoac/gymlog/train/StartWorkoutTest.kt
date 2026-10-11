package com.chrisgoac.gymlog.train

import com.chrisgoac.gymlog.domain.routine.Routine
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.routine.RoutineSummary
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.StartedWorkout
import com.chrisgoac.gymlog.domain.workout.TrainingSummary
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.domain.workout.WorkoutStartException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory repository that enforces the same rules as the real one, with a suspension between check and write. */
private class FakeWorkouts : WorkoutRepository {
    val active = MutableStateFlow<WorkoutAggregate?>(null)
    val gyms = MutableStateFlow(listOf(Gym("g1", "Central")))
    val summary = MutableStateFlow(TrainingSummary(2, 3, 1))
    var createdSessions = 0
    var routines = mapOf("r1" to listOf("seed-press", "seed-curl"))
    var archivedRoutines = setOf("r-old")
    var failure: Exception? = null
    val gymsUsed = mutableListOf<String?>()
    var gate: CompletableDeferred<Unit>? = null

    override fun observeActiveWorkout(): Flow<WorkoutAggregate?> = active
    override fun observeWorkout(sessionId: String): Flow<WorkoutAggregate?> = active.map { it?.takeIf { w -> w.session.id == sessionId } }
    override fun observeGyms(): Flow<List<Gym>> = gyms
    override fun observeTrainingSummary(): Flow<TrainingSummary> = summary

    override suspend fun startFreeWorkout(gymId: String?) = create(null, gymId)
    override suspend fun startRoutineWorkout(routineId: String, gymId: String?) = create(routineId, gymId)

    private suspend fun create(routineId: String?, gymId: String?): StartedWorkout {
        failure?.let { throw it }
        gate?.await()
        active.value?.let { return StartedWorkout(it.session.id, resumed = true) }
        yield() // a racing caller would slip in here without serialization
        active.value?.let { return StartedWorkout(it.session.id, resumed = true) }
        if (gymId != null && gyms.value.none { it.id == gymId }) throw WorkoutStartException.GymUnavailable(gymId)
        val exercises = if (routineId == null) {
            emptyList()
        } else {
            if (routineId in archivedRoutines) throw WorkoutStartException.RoutineArchived(routineId)
            val ids = routines[routineId] ?: throw WorkoutStartException.RoutineNotFound(routineId)
            ids.mapIndexed { i, id -> WorkoutExercise("le$i", id, id, i, emptyList()) }
        }
        createdSessions++
        gymsUsed += gymId
        val session = WorkoutSession("s$createdSessions", routineId, gymId, 1_000L, null, null)
        active.value = WorkoutAggregate(session, exercises)
        return StartedWorkout(session.id, resumed = false)
    }
}

private class FakeRoutines : RoutineRepository {
    val summaries = MutableStateFlow(listOf(RoutineSummary("r1", "Push", null, false, 2, listOf("Press"))))
    val next = MutableStateFlow<String?>("r1")
    override fun observeRoutines() = summaries
    override fun observeRoutine(id: String): Flow<Routine?> = MutableStateFlow(null)
    override fun observeNextRoutineId() = next
    override suspend fun create(input: RoutineInput): Routine = error("unused")
    override suspend fun update(id: String, input: RoutineInput): Routine = error("unused")
    override suspend fun reorder(idsInOrder: List<String>) = error("unused")
    override suspend fun setArchived(id: String, archived: Boolean) = error("unused")
    override suspend fun delete(id: String) = error("unused")
}

class StartWorkoutTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `free start returns the new session`() = runTest {
        val repo = FakeWorkouts()
        val result = StartWorkoutUseCase(repo).startFree("g1")
        assertEquals(StartWorkoutResult.Started("s1", resumed = false), result)
        assertEquals("g1", repo.active.value!!.session.gymId)
    }

    @Test fun `routine start copies the routine exercises`() = runTest {
        val repo = FakeWorkouts()
        val result = StartWorkoutUseCase(repo).startRoutine("r1", null)
        assertTrue(result is StartWorkoutResult.Started && !result.resumed)
        assertEquals(listOf("seed-press", "seed-curl"), repo.active.value!!.exercises.map { it.exerciseId })
    }

    @Test fun `duplicate concurrent taps create a single session`() = runTest {
        val repo = FakeWorkouts()
        val useCase = StartWorkoutUseCase(repo)
        val results = coroutineScope { List(5) { async { useCase.startFree(null) } }.awaitAll() }
        assertEquals(1, repo.createdSessions)
        assertEquals(1, results.filterIsInstance<StartWorkoutResult.Started>().count { !it.resumed })
        assertEquals(1, results.filterIsInstance<StartWorkoutResult.Started>().map { it.sessionId }.toSet().size)
    }

    @Test fun `starting again while active resumes the existing session`() = runTest {
        val repo = FakeWorkouts()
        val useCase = StartWorkoutUseCase(repo)
        useCase.startFree(null)
        assertEquals(StartWorkoutResult.Started("s1", resumed = true), useCase.startRoutine("r1", null))
        assertEquals(1, repo.createdSessions)
    }

    @Test fun `invalid gym and routines map to typed results and create nothing`() = runTest {
        val repo = FakeWorkouts()
        val useCase = StartWorkoutUseCase(repo)
        assertEquals(StartWorkoutResult.GymUnavailable, useCase.startFree("nope"))
        assertEquals(StartWorkoutResult.RoutineUnavailable, useCase.startRoutine("missing", null))
        assertEquals(StartWorkoutResult.RoutineArchived, useCase.startRoutine("r-old", null))
        assertEquals(0, repo.createdSessions)
        assertNull(repo.active.value)
    }

    @Test fun `unexpected failures are reported and the use case stays usable`() = runTest {
        val repo = FakeWorkouts().apply { failure = IllegalStateException("disk") }
        val useCase = StartWorkoutUseCase(repo)
        assertEquals(StartWorkoutResult.Failed, useCase.startFree(null))
        repo.failure = null
        assertTrue(useCase.startFree(null) is StartWorkoutResult.Started)
    }

    // ---- View model ----

    private fun viewModel(repo: FakeWorkouts = FakeWorkouts(), routines: FakeRoutines = FakeRoutines()) =
        TrainHomeViewModel(repo, routines, StartWorkoutUseCase(repo))

    @Test fun `state shows next routine, gyms, summary and no active workout`() = runTest {
        val vm = viewModel()
        val job = launch { vm.state.collect { } }
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(TrainLoadStatus.Ready, state.status)
        assertEquals("Push", state.nextRoutine?.name)
        assertEquals(listOf("g1"), state.gyms.map { it.id })
        assertEquals(2, state.summary?.weekSessions)
        assertNull(state.activeWorkout)
        job.cancel()
    }

    @Test fun `the default gym is preselected, used by a start, and an explicit choice beats it`() = runTest {
        val repo = FakeWorkouts()
        val default = MutableStateFlow<String?>("g1")
        val vm = TrainHomeViewModel(repo, FakeRoutines(), StartWorkoutUseCase(repo), defaultGymId = default)
        advanceUntilIdle()
        // Starting works even when the Train state is not collected (the Routines tab starts too).
        vm.startFree()
        assertEquals(TrainEvent.OpenWorkout("s1"), vm.eventFlow.first())
        assertEquals(listOf<String?>("g1"), repo.gymsUsed)

        val job = launch { vm.state.collect { } }
        advanceUntilIdle()
        assertEquals("g1", vm.state.value.selectedGymId)
        vm.selectGym(null) // explicit "Sin gimnasio"
        advanceUntilIdle()
        assertNull(vm.state.value.selectedGymId)
        default.value = "g1"
        advanceUntilIdle()
        assertNull(vm.state.value.selectedGymId)
        job.cancel()
    }

    @Test fun `a default gym that no longer exists is ignored`() = runTest {
        val repo = FakeWorkouts().apply { gyms.value = emptyList() }
        val vm = TrainHomeViewModel(repo, FakeRoutines(), StartWorkoutUseCase(repo), defaultGymId = MutableStateFlow("gone"))
        val job = launch { vm.state.collect { } }
        advanceUntilIdle()
        assertNull(vm.state.value.selectedGymId)
        vm.startFree()
        assertEquals(TrainEvent.OpenWorkout("s1"), vm.eventFlow.first())
        assertEquals(listOf<String?>(null), repo.gymsUsed)
        job.cancel()
    }

    @Test fun `an archived next routine is not offered`() = runTest {
        val routines = FakeRoutines().apply {
            summaries.value = listOf(RoutineSummary("r1", "Push", null, true, 2, emptyList()))
        }
        val vm = viewModel(routines = routines)
        val job = launch { vm.state.collect { } }
        advanceUntilIdle()
        assertNull(vm.state.value.nextRoutine)
        job.cancel()
    }

    @Test fun `starting emits the workout to open and then shows it as active`() = runTest {
        val vm = viewModel()
        val job = launch { vm.state.collect { } }
        advanceUntilIdle()
        vm.selectGym("g1")
        vm.startRoutine("r1")

        assertEquals(TrainEvent.OpenWorkout("s1"), vm.eventFlow.first())
        advanceUntilIdle()
        assertEquals("s1", vm.state.value.activeWorkout?.session?.id)
        assertEquals("g1", vm.state.value.selectedGymId)
        job.cancel()
    }

    @Test fun `a tap while a start is in flight is dropped`() = runTest {
        val repo = FakeWorkouts().apply { gate = CompletableDeferred() }
        val vm = viewModel(repo)
        val job = launch { vm.state.collect { } }
        advanceUntilIdle()
        vm.startFree()
        advanceUntilIdle()
        assertTrue(vm.state.value.starting)
        vm.startFree()
        repo.gate!!.complete(Unit)

        assertEquals(TrainEvent.OpenWorkout("s1"), vm.eventFlow.first())
        assertEquals(1, repo.createdSessions)
        job.cancel()
    }

    @Test fun `a vanished gym resets the selection and surfaces the error`() = runTest {
        val repo = FakeWorkouts()
        val vm = viewModel(repo)
        val job = launch { vm.state.collect { } }
        advanceUntilIdle()
        vm.selectGym("g1")
        repo.gyms.value = emptyList()
        advanceUntilIdle()
        vm.startFree()

        assertEquals(TrainEvent.StartFailed(StartError.GymUnavailable), vm.eventFlow.first())
        advanceUntilIdle()
        assertEquals(StartError.GymUnavailable, vm.state.value.startError)
        assertNull(vm.state.value.selectedGymId)
        vm.dismissError()
        assertNull(vm.state.value.startError)
        assertNull(repo.active.value)
        job.cancel()
    }

    @Test fun `an archived routine start surfaces the typed error`() = runTest {
        val repo = FakeWorkouts()
        val vm = viewModel(repo)
        val job = launch { vm.state.collect { } }
        advanceUntilIdle()
        vm.startRoutine("r-old")
        assertEquals(TrainEvent.StartFailed(StartError.RoutineArchived), vm.eventFlow.first())
        advanceUntilIdle()
        assertNotNull(vm.state.value.startError)
        job.cancel()
    }

    // ---- Starting while a workout is active ----

    @Test fun `starting a routine while one is active asks instead of opening it`() = runTest {
        val repo = FakeWorkouts()
        val vm = viewModel(repo)
        val events = mutableListOf<TrainEvent>()
        val collector = launch { vm.eventFlow.collect { events += it } }
        vm.startFree()
        advanceUntilIdle()
        assertEquals(listOf<TrainEvent>(TrainEvent.OpenWorkout("s1")), events)
        assertNull(vm.activeChoice.value)

        vm.startRoutine("r1") // e.g. "Empezar" on the Routines tab
        advanceUntilIdle()

        assertEquals("s1", vm.activeChoice.value)
        assertEquals(1, events.size) // nothing opened silently
        assertEquals(1, repo.createdSessions)
        assertTrue(!vm.state.value.starting)
        collector.cancel()
    }

    @Test fun `continue opens the running workout and clears the prompt`() = runTest {
        val repo = FakeWorkouts()
        val vm = viewModel(repo)
        val events = mutableListOf<TrainEvent>()
        val collector = launch { vm.eventFlow.collect { events += it } }
        vm.startFree()
        advanceUntilIdle()
        vm.startFree() // free start from Train while active
        advanceUntilIdle()
        assertEquals("s1", vm.activeChoice.value)

        vm.continueActive()
        advanceUntilIdle()

        assertNull(vm.activeChoice.value)
        assertEquals(listOf<TrainEvent>(TrainEvent.OpenWorkout("s1"), TrainEvent.OpenWorkout("s1")), events)
        vm.continueActive() // a second tap does nothing
        advanceUntilIdle()
        assertEquals(2, events.size)
        collector.cancel()
    }

    @Test fun `cancel neither starts nor opens anything`() = runTest {
        val repo = FakeWorkouts()
        val vm = viewModel(repo)
        val events = mutableListOf<TrainEvent>()
        val collector = launch { vm.eventFlow.collect { events += it } }
        vm.startFree()
        advanceUntilIdle()
        vm.startRoutine("r1")
        advanceUntilIdle()

        vm.dismissActiveChoice()
        advanceUntilIdle()

        assertNull(vm.activeChoice.value)
        assertEquals(1, events.size)
        assertEquals(1, repo.createdSessions)
        collector.cancel()
    }
}
