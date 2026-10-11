package com.chrisgoac.gymlog.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.achievement.OfflineAchievementRepository
import com.chrisgoac.gymlog.data.exercise.OfflineExerciseRepository
import com.chrisgoac.gymlog.data.routine.OfflineRoutineRepository
import com.chrisgoac.gymlog.data.workout.OfflineHistoryRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutEditRepository
import com.chrisgoac.gymlog.data.workout.readWorkout
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedSetEntity
import com.chrisgoac.gymlog.database.entity.UserSettingEntity
import com.chrisgoac.gymlog.database.entity.WorkoutSessionEntity
import com.chrisgoac.gymlog.domain.achievement.AchievementReconcileScheduler
import com.chrisgoac.gymlog.domain.achievement.ReconcileAchievementsUseCase
import com.chrisgoac.gymlog.domain.history.DefaultSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.history.SaveResult
import com.chrisgoac.gymlog.domain.history.WorkoutDraft
import com.chrisgoac.gymlog.domain.stats.BalanceRow
import com.chrisgoac.gymlog.domain.stats.ExerciseRecords
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.stats.InvalidatingHistoryRepository
import com.chrisgoac.gymlog.domain.stats.InvalidatingSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.stats.Streak
import com.chrisgoac.gymlog.domain.stats.RecordKind
import com.chrisgoac.gymlog.domain.stats.StatsFilter
import com.chrisgoac.gymlog.domain.stats.StatsInvalidation
import com.chrisgoac.gymlog.domain.stats.VolumeOverview
import com.chrisgoac.gymlog.domain.stats.WeeklyInsight
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/**
 * End to end over Room, no UI: ONE historical edit (then ONE delete) must move every derived value
 * consistently: PRs, 1RM, volume, balance, streak, weekly summary, stalls, achievements (via the
 * scheduler, tombstoning only after a LOCAL edit/delete) and the Coach snapshot invalidation event
 * (there is no Coach snapshot builder on Android yet, so the COACH_SNAPSHOT scope event is the input).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HistoricalEditConsistencyTest {
    private val databases = mutableListOf<GymLogDatabase>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Manager(db: GymLogDatabase) : UserDatabaseManager {
        override val current = MutableStateFlow<OpenUserDatabase?>(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    @After fun tearDown() {
        scope.cancel()
        databases.forEach { it.close() }
    }

    private val now = Instant.parse("2026-06-10T12:00:00Z").toEpochMilli()
    private val days = listOf("2026-05-14", "2026-05-17", "2026-05-20", "2026-05-23", "2026-05-26", "2026-05-29", "2026-06-01", "2026-06-04", "2026-06-07", "2026-06-09")

    private fun <T> live(flow: kotlinx.coroutines.flow.Flow<T>): MutableStateFlow<Any?> {
        val latest = MutableStateFlow<Any?>(UNSET)
        scope.launch { flow.collect { latest.value = it } }
        return latest
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> MutableStateFlow<Any?>.awaitValue(message: String, predicate: (T) -> Boolean) {
        try {
            withTimeout(10_000) { first { it !== UNSET && predicate(it as T) } }
        } catch (e: Exception) {
            throw AssertionError("$message: last value $value", e)
        }
    }

    @Test fun `one historical edit and one delete move every derived value consistently`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java).build().also { databases += it }
        db.catalogDao().upsertAll(listOf(CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null)))
        db.userSettingDao().upsert(UserSettingEntity("objetivoSemanal", 1, null, "1", null))
        days.forEachIndexed { i, day ->
            val at = Instant.parse("${day}T08:00:00Z").toEpochMilli()
            db.workoutSessionDao().upsert(WorkoutSessionEntity("s$i", 1, null, null, null, null, at, 3600, null, null))
            db.loggedExerciseDao().upsert(LoggedExerciseEntity("le$i", 1, null, "s$i", "seed-press", 0, null))
            db.loggedSetDao().upsert(LoggedSetEntity("set$i", 1, null, "le$i", 0, 100.0, 5, false, null))
        }
        val manager = Manager(db)
        val runner = RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now })
        val runnerFor = { _: GymLogDatabase -> runner }
        val invalidation = StatsInvalidation()
        val zone = ZoneId.of("UTC")
        val stats = OfflineStatsRepository(manager, now = { now }, zone = { zone })
        val achievements = OfflineAchievementRepository(manager, runnerFor)
        val reconcile = ReconcileAchievementsUseCase(achievements, now = { now }, zone = { zone })

        val coachEvents = MutableStateFlow(0)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { invalidation.changes(setOf(InvalidationScope.COACH_SNAPSHOT)).collect { coachEvents.update { n -> n + 1 } } }
        // Every finished scheduler run bumps a counter: a positive signal that a reconcile completed.
        val runs = MutableStateFlow(0)
        scope.launch {
            AchievementReconcileScheduler(
                invalidation, extraSignals = flowOf(), canRun = flowOf(true),
                reconcile = { allowTombstone -> reconcile(allowTombstone); runs.update { n -> n + 1 } }, debounceMillis = 50,
            ).run()
        }
        reconcile() // account ready: unlock only
        // Wait until the scheduler is subscribed: publish a harmless, non-local ACHIEVEMENTS event until it runs once.
        withTimeout(10_000) {
            while (runs.value == 0) {
                invalidation.publish(setOf(InvalidationScope.ACHIEVEMENTS))
                delay(100)
            }
        }
        delay(200) // let any extra warm-up runs drain, then take the baseline
        val warmCoach = coachEvents.value // warm-up events never touch COACH_SNAPSHOT
        assertEquals(0, warmCoach)
        assertEquals(listOf("racha-4", "sesiones-10"), achievements.observeUnlocked().first().map { it.key }.sorted())

        val filter = StatsFilter(Period.FOUR_WEEKS, GymFilter.All)
        val weekly = live(stats.observeWeeklyInsight(GymFilter.All))
        val records = live(stats.observeExerciseRecords("seed-press", GymFilter.All))
        val oneRm = live(stats.observeEstimatedOneRepMax("seed-press", GymFilter.All))
        val volume = live(stats.observeVolume(filter))
        val balance = live(stats.observeMuscleBalance(filter))
        val streak = live(stats.observeStreak())
        val stalls = live(stats.observeStalls(GymFilter.All))
        val unlocked = live(achievements.observeUnlocked())

        stalls.awaitValue<List<*>>("stalled before") { it.size == 1 }
        records.awaitValue<ExerciseRecords?>("PR before") { it?.maxWeight == 100.0 }
        volume.awaitValue<VolumeOverview>("volume before") { it.summary.volume == 5000.0 && it.summary.sessions == 10 }
        streak.awaitValue<Streak>("streak before") { it.days == 0 }

        // ---- ONE edit: last session 110 kg and moved to today ----
        val original = checkNotNull(db.readWorkout("s9")).let { w ->
            w.copy(session = w.session.copy(updatedAt = 1), exercises = w.exercises.map { e -> e.copy(updatedAt = 1, sets = e.sets.map { it.copy(updatedAt = 1) }) })
        }
        val draft = WorkoutDraft.from(original).withSet("set9") { it.copy(weight = "110") }.copy(startedAt = Instant.parse("2026-06-10T08:00:00Z"))
        val save = InvalidatingSaveWorkoutDraftUseCase(DefaultSaveWorkoutDraftUseCase(OfflineWorkoutEditRepository(manager, runnerFor)) { now }, invalidation)
        val runsBeforeEdit = runs.value
        val result = save(original, draft)
        assertTrue("$result", result is SaveResult.Saved)

        records.awaitValue<ExerciseRecords?>("PR after") { it?.maxWeight == 110.0 }
        oneRm.awaitValue<Double?>("1RM after") { it == 128.3 }
        volume.awaitValue<VolumeOverview>("volume after") { it.summary.volume == 5050.0 }
        balance.awaitValue<List<BalanceRow>>("balance after") { it.single().volume == 5050.0 && it.single().daysSinceTrained == 0L }
        streak.awaitValue<Streak>("streak after") { it.days == 1 }
        weekly.awaitValue<WeeklyInsight>("weekly after") { it.summary.volume == 550.0 && it.records.map { r -> r.kind } == listOf(RecordKind.WEIGHT) }
        stalls.awaitValue<List<*>>("stalls after") { it.isEmpty() }
        withTimeout(10_000) { coachEvents.first { it >= 1 } }
        assertEquals("coach snapshot invalidated once by the edit", 1, coachEvents.value)
        // The edit-triggered reconcile (local edit, may tombstone) runs to completion; 10 sessions remain, so both stay unlocked.
        withTimeout(10_000) { runs.first { it > runsBeforeEdit } }
        assertEquals(listOf("racha-4", "sesiones-10"), achievements.observeUnlocked().first().map { it.key }.sorted())

        // ---- ONE delete of that session: 9 sessions left, sesiones-10 must be tombstoned ----
        val history = InvalidatingHistoryRepository(OfflineHistoryRepository(manager, runnerFor, OfflineExerciseRepository(manager, runnerFor), OfflineRoutineRepository(manager, runnerFor, OfflineExerciseRepository(manager, runnerFor)), now = { now }), invalidation)
        history.deleteSession("s9")

        records.awaitValue<ExerciseRecords?>("PR after delete") { it?.maxWeight == 100.0 }
        oneRm.awaitValue<Double?>("1RM after delete") { it == 116.7 }
        volume.awaitValue<VolumeOverview>("volume after delete") { it.summary.volume == 4500.0 && it.summary.sessions == 9 }
        balance.awaitValue<List<BalanceRow>>("balance after delete") { it.single().volume == 4500.0 }
        streak.awaitValue<Streak>("streak after delete") { it.days == 0 }
        unlocked.awaitValue<List<com.chrisgoac.gymlog.domain.achievement.UnlockedAchievement>>("sesiones-10 tombstoned by the local delete, racha-4 kept") { u -> u.map { it.key } == listOf("racha-4") }
        withTimeout(10_000) { coachEvents.first { it >= 2 } }
        assertEquals("coach snapshot invalidated again by the delete", 2, coachEvents.value)
    }

    private companion object {
        val UNSET = Any()
    }
}
