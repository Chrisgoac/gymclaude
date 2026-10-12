package com.chrisgoac.gymlog.domain.workout

import com.chrisgoac.gymlog.domain.timer.RestTimerAlarm
import com.chrisgoac.gymlog.domain.timer.RestTimerController
import com.chrisgoac.gymlog.domain.timer.RestTimerState
import com.chrisgoac.gymlog.domain.timer.RestTimerStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishWorkoutUseCaseTest {
    private val started = 1_000_000L

    private class Fake(initial: WorkoutAggregate?) : WorkoutRepository, WorkoutFinishRepository {
        val workout = MutableStateFlow(initial)
        var finishCalls = 0
        var lastNotes: String? = "unset"
        var now = 0L

        override fun observeWorkout(sessionId: String): Flow<WorkoutAggregate?> = workout
        override fun observeActiveWorkout(): Flow<WorkoutAggregate?> = workout
        override fun observeGyms(): Flow<List<Gym>> = flowOf(emptyList())
        override fun observeTrainingSummary(): Flow<TrainingSummary> = flowOf(TrainingSummary(0, 3, 0))
        override suspend fun startFreeWorkout(gymId: String?) = error("unused")
        override suspend fun startRoutineWorkout(routineId: String, gymId: String?) = error("unused")

        override suspend fun finish(sessionId: String, notes: String?): Int {
            val current = workout.value ?: throw WorkoutRowMissingException(sessionId)
            current.session.durationSeconds?.let { return it }
            finishCalls++
            lastNotes = notes
            val duration = ((now - current.session.startedAt) / 1000).toInt()
            workout.value = current.copy(session = current.session.copy(durationSeconds = duration, notes = notes ?: current.session.notes))
            return duration
        }
    }

    private class MemoryStore(var saved: RestTimerState?) : RestTimerStore {
        override suspend fun read() = saved
        override suspend fun write(state: RestTimerState?) { saved = state }
    }

    private class Alarm : RestTimerAlarm {
        var cancels = 0
        override fun schedule(runId: String, sessionId: String, endsAtEpochMillis: Long) = Unit
        override fun cancel() { cancels++ }
    }

    private fun aggregate(vararg sets: List<WorkoutSet>) = WorkoutAggregate(
        WorkoutSession("s1", null, null, started, null, null),
        sets.mapIndexed { i, s -> WorkoutExercise("le$i", "ex$i", "Ex $i", i, s) },
    )

    private fun set(id: String, weight: Double, reps: Int, warmup: Boolean = false) = WorkoutSet(id, 0, weight, reps, warmup)

    @Test fun `finishing persists once and reports duration, counts and volume`() = runTest {
        val repo = Fake(aggregate(listOf(set("a", 100.0, 5), set("b", 50.0, 10, warmup = true)), listOf(set("c", 20.0, 12)))).also { it.now = started + 3_723_000 }
        val result = FinishWorkoutUseCase(repo, repo)("s1", "notas") as FinishResult.Finished

        assertEquals(WorkoutSummary("s1", 3723, 2, 3, 100.0 * 5 + 50.0 * 10 + 20.0 * 12), result.summary)
        assertEquals(false, result.alreadyFinished)
        assertEquals(1, repo.finishCalls)
        assertEquals("notas", repo.lastNotes)
    }

    @Test fun `the finish hook fires once for a write and never for a repeated finish`() = runTest {
        val repo = Fake(aggregate(listOf(set("a", 100.0, 5)))).also { it.now = started + 60_000 }
        var finished = 0
        val useCase = FinishWorkoutUseCase(repo, repo, onFinished = { finished++ })

        useCase("s1")
        useCase("s1")

        assertEquals(1, finished)
    }

    @Test fun `finishing an already finished workout is a successful no-op`() = runTest {
        val repo = Fake(aggregate(listOf(set("a", 100.0, 5)))).also { it.now = started + 60_000 }
        val useCase = FinishWorkoutUseCase(repo, repo)
        useCase("s1")

        val again = useCase("s1", "otra") as FinishResult.Finished

        assertTrue(again.alreadyFinished)
        assertEquals(60, again.summary.durationSeconds)
        assertEquals(1, repo.finishCalls)
    }

    @Test fun `without a repetition it asks for confirmation and writes nothing`() = runTest {
        val repo = Fake(aggregate(listOf(set("a", 0.0, 0)), emptyList()))
        val useCase = FinishWorkoutUseCase(repo, repo)

        assertEquals(FinishResult.NeedsConfirmation, useCase("s1"))
        assertEquals(0, repo.finishCalls)
        assertNull(repo.workout.value!!.session.durationSeconds)

        val confirmed = useCase("s1", confirmEmpty = true)
        assertTrue(confirmed is FinishResult.Finished)
        assertEquals(1, repo.finishCalls)
    }

    @Test fun `an empty workout with no exercises also asks`() = runTest {
        val repo = Fake(aggregate())
        assertEquals(FinishResult.NeedsConfirmation, FinishWorkoutUseCase(repo, repo)("s1"))
    }

    @Test fun `a single repetition is meaningful, a warm-up included`() = runTest {
        val repo = Fake(aggregate(listOf(set("a", 0.0, 1, warmup = true))))
        assertTrue(FinishWorkoutUseCase(repo, repo)("s1") is FinishResult.Finished)
    }

    @Test fun `unknown or vanished workouts are NotFound`() = runTest {
        val missing = Fake(null)
        assertEquals(FinishResult.NotFound, FinishWorkoutUseCase(missing, missing)("s1"))

        // Deleted between the read and the write.
        val racing = object : WorkoutFinishRepository {
            override suspend fun finish(sessionId: String, notes: String?): Int = throw WorkoutRowMissingException(sessionId)
        }
        val repo = Fake(aggregate(listOf(set("a", 10.0, 5))))
        assertEquals(FinishResult.NotFound, FinishWorkoutUseCase(repo, racing)("s1"))
    }

    @Test fun `finishing resets the rest timer, also on a repeated finish`() = runTest {
        val repo = Fake(aggregate(listOf(set("a", 10.0, 5)))).also { it.now = started + 1000 }
        val store = MemoryStore(RestTimerState("r1", "s1", 60_000, started + 60_000))
        val alarm = Alarm()
        val timer = RestTimerController(store, alarm, clock = { started })
        timer.restore("s1")
        val useCase = FinishWorkoutUseCase(repo, repo, timer)

        useCase("s1")

        assertNull(timer.state.value)
        assertNull(store.saved)
        val cancelsAfterFirst = alarm.cancels
        assertTrue(cancelsAfterFirst >= 1)

        useCase("s1")
        assertTrue(alarm.cancels > cancelsAfterFirst)
    }

    @Test fun `the timer is left alone while the confirmation is pending`() = runTest {
        val repo = Fake(aggregate(listOf(set("a", 0.0, 0))))
        val store = MemoryStore(RestTimerState("r1", "s1", 60_000, started + 60_000))
        val timer = RestTimerController(store, Alarm(), clock = { started })
        timer.restore("s1")

        FinishWorkoutUseCase(repo, repo, timer)("s1")

        assertTrue(timer.state.value != null)
    }
}
