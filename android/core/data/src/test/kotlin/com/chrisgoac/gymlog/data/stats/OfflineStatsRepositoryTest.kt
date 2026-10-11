package com.chrisgoac.gymlog.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutEditRepository
import com.chrisgoac.gymlog.data.workout.readWorkout
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.database.entity.ExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedSetEntity
import com.chrisgoac.gymlog.database.entity.UserSettingEntity
import com.chrisgoac.gymlog.database.entity.WorkoutSessionEntity
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.history.DefaultSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.history.SaveResult
import com.chrisgoac.gymlog.domain.history.WorkoutDraft
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.stats.InvalidatingSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.stats.RecordKind
import com.chrisgoac.gymlog.domain.stats.StatsFilter
import com.chrisgoac.gymlog.domain.stats.StatsInvalidation
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * The Room-backed repository must give the PWA's numbers: every dataset of
 * contracts/domain/insight-cases.json (tombstones included) is loaded into Room and each flow is
 * compared with the fixture. Then a historical edit must refresh every live flow, once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineStatsRepositoryTest {
    private val databases = mutableListOf<GymLogDatabase>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Manager(db: GymLogDatabase) : UserDatabaseManager {
        override val current = MutableStateFlow<OpenUserDatabase?>(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private fun database(): GymLogDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java).build().also { databases += it }

    @After fun tearDown() {
        scope.cancel()
        databases.forEach { it.close() }
    }

    private val fixture: JsonObject = run {
        val dir = File(checkNotNull(System.getProperty("gymlog.domainContractsDir")) { "gymlog.domainContractsDir not set" })
        Json.parseToJsonElement(File(dir, "insight-cases.json").readText()).jsonObject
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonElement.strOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.content
    private fun JsonElement.longOrNull(): Long? = if (this is JsonNull) null else jsonPrimitive.long
    private fun JsonElement.intOrNull(): Int? = if (this is JsonNull) null else jsonPrimitive.int
    private fun JsonObject.deletedAt() = getValue("deletedAt").longOrNull()
    private fun group(wire: String) = MuscleGroup.entries.first { it.wire == wire }
    private fun close(message: String, expected: Double, actual: Double) = assertEquals(message, expected, actual, 1e-9)

    private suspend fun load(db: GymLogDatabase, c: JsonObject, weeklyTarget: Int) {
        c.getValue("exercises").jsonArray.map { it.jsonObject }.forEach {
            db.exerciseDao().upsert(
                ExerciseEntity(it.str("id"), 1, it.deletedAt(), it.str("nombre"), it.str("grupoMuscular"), "barra", "compuesto", null, null, null, null, null, true, null),
            )
        }
        c.getValue("sessions").jsonArray.map { it.jsonObject }.forEach {
            db.workoutSessionDao().upsert(
                WorkoutSessionEntity(
                    it.str("id"), 1, it.deletedAt(), null, null, it.getValue("gymId").strOrNull(), it.getValue("fecha").jsonPrimitive.long,
                    it.getValue("duracionSegundos").intOrNull(), null, null,
                ),
            )
        }
        c.getValue("loggedExercises").jsonArray.map { it.jsonObject }.forEach {
            db.loggedExerciseDao().upsert(LoggedExerciseEntity(it.str("id"), 1, it.deletedAt(), it.str("sessionId"), it.str("exerciseId"), it.getValue("orden").jsonPrimitive.int, null))
        }
        c.getValue("sets").jsonArray.map { it.jsonObject }.forEach {
            db.loggedSetDao().upsert(
                LoggedSetEntity(
                    it.str("id"), 1, it.deletedAt(), it.str("loggedExerciseId"), it.getValue("orden").jsonPrimitive.int, it.num("peso"),
                    it.getValue("reps").jsonPrimitive.int, it.getValue("esCalentamiento").jsonPrimitive.boolean, null,
                ),
            )
        }
        db.userSettingDao().upsert(UserSettingEntity("objetivosVolumen", 1, null, c.getValue("objetivosVolumen").toString(), null))
        db.userSettingDao().upsert(UserSettingEntity("objetivoSemanal", 1, null, weeklyTarget.toString(), null))
    }

    @Test fun `every fixture dataset loaded into Room gives the PWA's statistics`() = runBlocking {
        for (c in fixture.getValue("datasets").jsonArray.map { it.jsonObject }) {
            val name = c.str("name")
            val expected = c.getValue("expected").jsonObject
            val weeklyTarget = 2
            val db = database()
            load(db, c, weeklyTarget)
            val repo = OfflineStatsRepository(Manager(db), now = { c.getValue("now").jsonPrimitive.long }, zone = { ZoneId.of(c.str("zone")) })

            val streak = repo.observeStreak().first()
            assertEquals("$name days", expected.getValue("currentStreakDays").jsonPrimitive.int, streak.days)
            val racha = expected.getValue("racha").jsonArray.map { it.jsonObject }.first { it.getValue("objetivo").jsonPrimitive.int == weeklyTarget }
            assertEquals("$name weeks", racha.getValue("actual").jsonPrimitive.int to racha.getValue("mejor").jsonPrimitive.int, streak.weeks.current to streak.weeks.best)
            assertEquals(weeklyTarget, streak.weeklyTarget)

            for (g in expected.getValue("byGym").jsonArray.map { it.jsonObject }) {
                val gym = GymFilter.fromWire(g.getValue("gymId").strOrNull())
                val at = "$name / $gym"

                val insight = repo.observeWeeklyInsight(gym).first()
                val ws = g.getValue("weeklySummary").jsonObject
                assertEquals("$at sessions", ws.getValue("sesiones").jsonPrimitive.int, insight.summary.sessions)
                close("$at volume", ws.num("volumenSemana"), insight.summary.volume)
                close("$at previous", ws.num("volumenSemanaPrevia"), insight.summary.previousVolume)
                assertEquals("$at delta", ws.getValue("deltaPct").intOrNull(), insight.summary.deltaPct)
                assertEquals(
                    "$at records",
                    g.getValue("prsThisWeek").jsonArray.map { it.jsonObject }.map { Triple(it.str("exerciseId"), it.str("nombre"), it.str("tipo")) },
                    insight.records.map { Triple(it.exerciseId, it.name, it.kind.wire) },
                )

                val stalls = g.getValue("estancados").jsonArray.map { it.jsonObject }
                assertEquals(
                    "$at stalls",
                    stalls.map { listOf(it.str("exerciseId"), it.str("nombre"), it.getValue("sesionesSinMejora").jsonPrimitive.int, it.getValue("ultimaMejoraFecha").longOrNull()) },
                    repo.observeStalls(gym).first().map { listOf(it.exerciseId, it.name, it.sessionsWithoutImprovement, it.lastImprovementAt) },
                )
                assertEquals("$at suggestions", stalls.map { it.str("exerciseId") }, repo.observeProgressionSuggestions(gym).first().map { it.exerciseId })

                for (p in g.getValue("periods").jsonArray.map { it.jsonObject }) {
                    val filter = StatsFilter(Period.fromWire(p.str("periodo"))!!, gym)
                    val pat = "$at ${p.str("periodo")}"
                    val volume = repo.observeVolume(filter).first()
                    val ps = p.getValue("periodSummary").jsonObject
                    assertEquals("$pat sessions", ps.getValue("sesiones").jsonPrimitive.int, volume.summary.sessions)
                    close("$pat volume", ps.num("volumen"), volume.summary.volume)
                    val weeks = p.getValue("weeklyVolume").jsonArray.map { it.jsonObject }
                    assertEquals("$pat weeks", weeks.map { it.getValue("semanaInicioTs").jsonPrimitive.long to it.getValue("deltaPct").intOrNull() }, volume.weekly.map { it.weekStart to it.deltaPct })
                    weeks.zip(volume.weekly).forEach { (e, a) -> close("$pat week volume", e.num("volumen"), a.volume) }

                    val balance = repo.observeMuscleBalance(filter).first()
                    val rows = p.getValue("balance").jsonArray.map { it.jsonObject }
                    assertEquals(
                        "$pat balance",
                        rows.map { listOf(group(it.str("grupo")), it.getValue("diasSinEntrenar").longOrNull(), it.getValue("descuidado").jsonPrimitive.boolean, it.getValue("metaPct").intOrNull()) },
                        balance.map { listOf(it.group, it.daysSinceTrained, it.neglected, it.goalPct) },
                    )
                    rows.zip(balance).forEach { (e, a) -> close("$pat balance volume", e.num("volumen"), a.volume) }
                }

                for (e in g.getValue("exercises").jsonArray.map { it.jsonObject }) {
                    val exerciseId = e.str("exerciseId")
                    val prs = e.getValue("prs")
                    val records = repo.observeExerciseRecords(exerciseId, gym).first()
                    assertEquals("$at $exerciseId records", if (prs is JsonNull) null else prs.jsonObject.num("maxPeso") to prs.jsonObject.num("mejor1RM"), records?.let { it.maxWeight to it.bestOneRepMax })
                    assertEquals("$at $exerciseId 1RM", records?.bestOneRepMax, repo.observeEstimatedOneRepMax(exerciseId, gym).first())
                    for (p in e.getValue("progress").jsonArray.map { it.jsonObject }) {
                        val series = repo.observeExerciseSeries(exerciseId, StatsFilter(Period.fromWire(p.str("periodo"))!!, gym)).first()
                        val points = p.getValue("points").jsonArray.map { it.jsonObject }
                        assertEquals(
                            "$at $exerciseId ${p.str("periodo")} series",
                            points.map { Triple(it.getValue("fecha").jsonPrimitive.long, it.num("maxPeso"), it.num("mejor1RM")) },
                            series.map { Triple(it.at, it.maxWeight, it.bestOneRepMax) },
                        )
                        points.zip(series).forEach { (x, a) -> close("$at $exerciseId series volume", x.num("volumen"), a.volume) }
                    }
                }
            }
            db.close()
        }
    }

    // ---- invalidation ----

    private val now = Instant.parse("2026-06-10T12:00:00Z").toEpochMilli()

    private suspend fun seedPressHistory(db: GymLogDatabase) {
        db.catalogDao().upsertAll(listOf(CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null)))
        db.userSettingDao().upsert(UserSettingEntity("objetivoSemanal", 1, null, "1", null))
        listOf("2026-05-20", "2026-05-27", "2026-06-03", "2026-06-09").forEachIndexed { i, day ->
            val at = Instant.parse("${day}T08:00:00Z").toEpochMilli()
            db.workoutSessionDao().upsert(WorkoutSessionEntity("s$i", 1, null, null, null, null, at, 3600, null, null))
            db.loggedExerciseDao().upsert(LoggedExerciseEntity("le$i", 1, null, "s$i", "seed-press", 0, null))
            db.loggedSetDao().upsert(LoggedSetEntity("set$i", 1, null, "le$i", 0, 100.0, 5, false, null))
        }
    }

    /** Collects [flow] on a real dispatcher; the returned state holds the latest value. */
    private fun <T> live(flow: Flow<T>): MutableStateFlow<Any?> {
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

    @Test fun `a historical edit refreshes every affected flow`() = runBlocking {
        val db = database()
        seedPressHistory(db)
        val manager = Manager(db)
        val invalidation = StatsInvalidation()
        val repo = OfflineStatsRepository(manager, now = { now }, zone = { ZoneId.of("UTC") })
        val published = mutableListOf<Set<InvalidationScope>>()
        scope.launch { invalidation.scopes.collect { published += it } }
        val stallEmissions = MutableStateFlow(0)
        scope.launch { repo.observeStalls(GymFilter.All).collect { stallEmissions.value++ } }
        val filter = StatsFilter(Period.FOUR_WEEKS, GymFilter.All)

        val weekly = live(repo.observeWeeklyInsight(GymFilter.All))
        val records = live(repo.observeExerciseRecords("seed-press", GymFilter.All))
        val oneRm = live(repo.observeEstimatedOneRepMax("seed-press", GymFilter.All))
        val series = live(repo.observeExerciseSeries("seed-press", filter))
        val volume = live(repo.observeVolume(filter))
        val balance = live(repo.observeMuscleBalance(filter))
        val streak = live(repo.observeStreak())
        val stalls = live(repo.observeStalls(GymFilter.All))
        val suggestions = live(repo.observeProgressionSuggestions(GymFilter.All))

        // Before: the press is stalled (4 sessions at 100x5), nothing today, no record this week.
        stalls.awaitValue<List<*>>("stalled before") { it.size == 1 }
        suggestions.awaitValue<List<*>>("suggested before") { it.size == 1 }
        streak.awaitValue<com.chrisgoac.gymlog.domain.stats.Streak>("streak before") { it.days == 0 }
        weekly.awaitValue<com.chrisgoac.gymlog.domain.stats.WeeklyInsight>("weekly before") { it.records.isEmpty() && it.summary.volume == 500.0 }

        // Edit last Tuesday's workout: 110 kg instead of 100 and moved to today.
        // Seeded rows are all version 1 (readWorkout does not carry versions).
        val original = checkNotNull(db.readWorkout("s3")).let { w ->
            w.copy(session = w.session.copy(updatedAt = 1), exercises = w.exercises.map { e -> e.copy(updatedAt = 1, sets = e.sets.map { it.copy(updatedAt = 1) }) })
        }
        val draft = WorkoutDraft.from(original).withSet("set3") { it.copy(weight = "110") }
            .copy(startedAt = Instant.parse("2026-06-10T08:00:00Z"))
        val runner = RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now })
        val save = InvalidatingSaveWorkoutDraftUseCase(DefaultSaveWorkoutDraftUseCase(OfflineWorkoutEditRepository(manager) { runner }) { now }, invalidation)
        val result = save(original, draft)
        assertTrue("$result", result is SaveResult.Saved)

        weekly.awaitValue<com.chrisgoac.gymlog.domain.stats.WeeklyInsight>("weekly summary + records") {
            it.summary.volume == 550.0 && it.records.map { r -> r.kind } == listOf(RecordKind.WEIGHT)
        }
        records.awaitValue<com.chrisgoac.gymlog.domain.stats.ExerciseRecords?>("PRs") { it?.maxWeight == 110.0 }
        oneRm.awaitValue<Double?>("1RM") { it == 128.3 }
        series.awaitValue<List<com.chrisgoac.gymlog.domain.stats.ProgressPoint>>("series") { it.last().bestOneRepMax == 128.3 && it.last().at == Instant.parse("2026-06-10T08:00:00Z").toEpochMilli() }
        volume.awaitValue<com.chrisgoac.gymlog.domain.stats.VolumeOverview>("volume") { it.summary.volume == 2050.0 }
        balance.awaitValue<List<com.chrisgoac.gymlog.domain.stats.BalanceRow>>("balance") { it.single().volume == 2050.0 && it.single().daysSinceTrained == 0L }
        streak.awaitValue<com.chrisgoac.gymlog.domain.stats.Streak>("streak") { it.days == 1 }
        stalls.awaitValue<List<*>>("stalls") { it.isEmpty() }
        suggestions.awaitValue<List<*>>("suggestions") { it.isEmpty() }

        // One recompute per edit (no second, redundant pass), and the scopes reached the coordinator.
        delay(300)
        assertEquals("stall reads: initial + one after the edit", 2, stallEmissions.value)
        assertEquals(listOf((result as SaveResult.Saved).invalidatedScopes), published)
        assertTrue(published.single().containsAll(InvalidationScope.entries.toSet() - InvalidationScope.ACHIEVEMENTS - InvalidationScope.COACH_SNAPSHOT))
    }

    @Test fun `stored settings parse like the PWA`() {
        assertEquals(3, OfflineStatsRepository.weeklyTarget(null))
        assertEquals(3, OfflineStatsRepository.weeklyTarget("\"x\""))
        assertEquals(4, OfflineStatsRepository.weeklyTarget("4"))
        assertEquals(3, OfflineStatsRepository.weeklyTarget("2.5")) // sessions >= 2.5 means 3
        assertEquals(
            mapOf(MuscleGroup.PECHO to 2000.0, MuscleGroup.ESPALDA to 0.0),
            OfflineStatsRepository.volumeGoals("""{"pecho":2000,"espalda":0,"alas":5,"gemelo":"x"}"""),
        )
        assertEquals(emptyMap<MuscleGroup, Double>(), OfflineStatsRepository.volumeGoals("[1]"))
    }

    private companion object {
        val UNSET = Any()
    }
}
