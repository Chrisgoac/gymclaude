package com.chrisgoac.gymlog.data.stats

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.achievement.OfflineAchievementRepository
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.ExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedSetEntity
import com.chrisgoac.gymlog.database.entity.WorkoutSessionEntity
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.stats.StatsFilter
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.model.DeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * Scale check for the Progress screen: ~20k sets (2 years, ~800 sessions) in Room, then the first
 * emission of every flow the screen subscribes to, plus the achievement evidence (personal records
 * and the reconcile metrics). Timings print to stdout (see the task report); the bounds are
 * deliberately generous (Robolectric on a loaded CI box), they catch accidental quadratic behaviour
 * or a whole-history read per row, not microbenchmark regressions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineStatsRepositoryScaleTest {
    private val databases = mutableListOf<GymLogDatabase>()
    private val zone = ZoneId.of("Europe/Madrid")
    private val now = LocalDate.of(2026, 8, 12).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private class Manager(db: GymLogDatabase) : UserDatabaseManager {
        override val current = MutableStateFlow<OpenUserDatabase?>(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    @After fun tearDown() = databases.forEach { it.close() }

    private suspend fun seed(db: GymLogDatabase, sessions: Int, exercisesPerSession: Int, setsPerExercise: Int): Int {
        val groups = MuscleGroup.entries.filter { it != MuscleGroup.OTRO }
        val exerciseIds = (0 until 40).map { "ex$it" }
        var setCount = 0
        db.withTransaction {
            exerciseIds.forEachIndexed { i, id ->
                db.exerciseDao().upsert(ExerciseEntity(id, 1, null, "Ejercicio $i", groups[i % groups.size].wire, "barra", "compuesto", null, null, null, null, null, true, null))
            }
            for (s in 0 until sessions) {
                val sessionId = "s$s"
                // Every ~2.6 days over two years, oldest first.
                val startedAt = now - (sessions - s) * 2L * 86_400_000L - 3_600_000L
                db.workoutSessionDao().upsert(WorkoutSessionEntity(sessionId, 1, null, null, null, if (s % 3 == 0) "g1" else null, startedAt, 3600, null, null))
                for (e in 0 until exercisesPerSession) {
                    val loggedId = "$sessionId-e$e"
                    val exerciseId = exerciseIds[(s + e * 7) % exerciseIds.size]
                    db.loggedExerciseDao().upsert(LoggedExerciseEntity(loggedId, 1, null, sessionId, exerciseId, e, null))
                    for (k in 0 until setsPerExercise) {
                        // Slowly rising weights with plateaus, so stalls and records both occur.
                        val weight = 40.0 + (s / 25) * 2.5 + e * 5
                        db.loggedSetDao().upsert(LoggedSetEntity("$loggedId-k$k", 1, null, loggedId, k, weight, 5 + k % 4, false, null))
                        setCount++
                    }
                }
            }
        }
        return setCount
    }

    @Test fun `first emissions on a 20k-set history stay within generous bounds`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java).build().also { databases += it }
        val seedStart = System.nanoTime()
        val sets = seed(db, sessions = 800, exercisesPerSession = 5, setsPerExercise = 5)
        println("SCALE seeded $sets sets in ${(System.nanoTime() - seedStart) / 1_000_000} ms")
        assertEquals(20_000, sets)

        val repo = OfflineStatsRepository(Manager(db), now = { now }, zone = { zone })
        val filter = StatsFilter(Period.ALL, GymFilter.All)
        val timings = linkedMapOf<String, Long>()
        suspend fun <T> timed(name: String, block: suspend () -> T): T {
            val start = System.nanoTime()
            return withTimeout(60_000) { block() }.also { timings[name] = (System.nanoTime() - start) / 1_000_000 }
        }

        timed("weeklyInsight") { repo.observeWeeklyInsight(GymFilter.All).first() }
        timed("streak") { repo.observeStreak().first() }
        val volume = timed("volume(ALL)") { repo.observeVolume(filter).first() }
        timed("muscleBalance(ALL)") { repo.observeMuscleBalance(filter).first() }
        val stalls = timed("stalls") { repo.observeStalls(GymFilter.All).first() }
        timed("suggestions") { repo.observeProgressionSuggestions(GymFilter.All).first() }
        timed("exerciseSeries") { repo.observeExerciseSeries("ex0", filter).first() }
        timed("exerciseRecords") { repo.observeExerciseRecords("ex0", GymFilter.All).first() }
        timed("volume(4 weeks, gym g1)") { repo.observeVolume(StatsFilter(Period.FOUR_WEEKS, GymFilter.Gym("g1"))).first() }

        println("SCALE first-emission ms: $timings")
        assertEquals(800, volume.summary.sessions)
        assertTrue("some exercises plateau", stalls.isNotEmpty())
        timings.forEach { (name, ms) -> assertTrue("$name took $ms ms", ms < 20_000) }

        // Achievement evidence: one grouped pass each, so a realistic (tighter) bound.
        val achievements = OfflineAchievementRepository(Manager(db)) { RoomLocalMutationRunner(it, DeviceId("scale"), clock = { now }) }
        val evidence = linkedMapOf<String, Long>()
        suspend fun <T> evidenceTimed(name: String, block: suspend () -> T): T {
            val start = System.nanoTime()
            return withTimeout(60_000) { block() }.also { evidence[name] = (System.nanoTime() - start) / 1_000_000 }
        }
        val records = evidenceTimed("personalRecords") { achievements.observePersonalRecords().first() }
        val metrics = evidenceTimed("reconcile metrics") { achievements.transaction { metrics(Instant.ofEpochMilli(now), zone) } }
        println("SCALE achievement evidence ms: $evidence")
        assertEquals(40, records.size)
        assertEquals(40, metrics.personalRecords)
        assertEquals(800, metrics.sessions)
        evidence.forEach { (name, ms) -> assertTrue("$name took $ms ms", ms < 2_000) }
    }
}
