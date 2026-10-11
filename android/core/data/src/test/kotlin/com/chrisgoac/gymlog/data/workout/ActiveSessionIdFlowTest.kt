package com.chrisgoac.gymlog.data.workout

import com.chrisgoac.gymlog.database.dao.WorkoutSessionDao
import com.chrisgoac.gymlog.database.entity.WorkoutSessionEntity
import com.chrisgoac.gymlog.domain.workout.ActiveWorkoutPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The active id ages out on a timer, not only on database changes (virtual time drives the clock). */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSessionIdFlowTest {
    private class FakeSessions(rows: List<WorkoutSessionEntity>) : WorkoutSessionDao {
        val unfinished = MutableStateFlow(rows)
        override suspend fun upsert(entity: WorkoutSessionEntity) = Unit
        override suspend fun findById(id: String): WorkoutSessionEntity? = null
        override fun observeLive(): Flow<List<WorkoutSessionEntity>> = unfinished
        override fun observeActive(): Flow<List<WorkoutSessionEntity>> = unfinished.map { rows -> rows.sortedByDescending { it.fecha } }
        override suspend fun findActive(): List<WorkoutSessionEntity> = unfinished.value.sortedByDescending { it.fecha }
        override suspend fun getAll(): List<WorkoutSessionEntity> = unfinished.value
    }

    private fun session(id: String, fecha: Long) = WorkoutSessionEntity(id, 1, null, null, null, null, fecha, null, null, null)

    private fun TestScope.collect(dao: FakeSessions, base: Long): MutableList<String?> {
        val seen = mutableListOf<String?>()
        backgroundScope.launch { dao.observeActiveSessionId { base + testScheduler.currentTime }.collect { seen += it } }
        runCurrent()
        return seen
    }

    @Test fun `the active session becomes null when it passes the 12 h window, with no database change`() = runTest {
        val base = 1_000_000_000L
        val dao = FakeSessions(listOf(session("s1", base - 60_000L)))
        val seen = collect(dao, base)
        assertEquals(listOf<String?>("s1"), seen)

        advanceTimeBy(ActiveWorkoutPolicy.WINDOW_MILLIS - 60_000L)
        runCurrent()
        assertEquals(listOf<String?>("s1"), seen)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("s1", null), seen)
    }

    @Test fun `when the newest ages out an older one never becomes active`() = runTest {
        val base = 1_000_000_000L
        val dao = FakeSessions(listOf(session("old", base - 2 * ActiveWorkoutPolicy.WINDOW_MILLIS), session("new", base)))
        val seen = collect(dao, base)
        advanceTimeBy(ActiveWorkoutPolicy.WINDOW_MILLIS + 2)
        runCurrent()
        assertEquals(listOf("new", null), seen)
    }

    @Test fun `a newly started session replaces an abandoned one`() = runTest {
        val base = 10 * ActiveWorkoutPolicy.WINDOW_MILLIS
        val dao = FakeSessions(listOf(session("abandoned", base - 3 * ActiveWorkoutPolicy.WINDOW_MILLIS)))
        val seen = collect(dao, base)
        dao.unfinished.value = dao.unfinished.value + session("fresh", base)
        runCurrent()
        assertEquals(listOf(null, "fresh"), seen)
    }
}
