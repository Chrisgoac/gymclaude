package com.chrisgoac.gymlog.data.achievement

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.AchievementEntity
import com.chrisgoac.gymlog.database.entity.ExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedSetEntity
import com.chrisgoac.gymlog.database.entity.MesocycleEntity
import com.chrisgoac.gymlog.database.entity.UserSettingEntity
import com.chrisgoac.gymlog.database.entity.WorkoutSessionEntity
import com.chrisgoac.gymlog.domain.achievement.AchievementRules
import com.chrisgoac.gymlog.domain.achievement.ReconcileAchievementsUseCase
import com.chrisgoac.gymlog.model.DeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.time.ZoneId

/**
 * Reconciliation over Room, driven by contracts/domain/achievement-cases.json: the evidence Room
 * computes must be the PWA's `getLogroMetricas`/`listPRs`, every step must decide and store what the
 * fixture approves, and a repeated run must write nothing (no row, no outbox entry).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineAchievementRepositoryTest {
    private val databases = mutableListOf<GymLogDatabase>()

    private class Manager(db: GymLogDatabase) : UserDatabaseManager {
        override val current = MutableStateFlow<OpenUserDatabase?>(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private fun database(): GymLogDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java).build().also { databases += it }

    @After fun tearDown() = databases.forEach { it.close() }

    private val fixture: JsonObject = run {
        val dir = File(checkNotNull(System.getProperty("gymlog.domainContractsDir")) { "gymlog.domainContractsDir not set" })
        Json.parseToJsonElement(File(dir, "achievement-cases.json").readText()).jsonObject
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonElement.strOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.content
    private fun JsonElement.longOrNull(): Long? = if (this is JsonNull) null else jsonPrimitive.long
    private fun JsonElement.intOrNull(): Int? = if (this is JsonNull) null else jsonPrimitive.int
    private fun JsonObject.deletedAt() = getValue("deletedAt").longOrNull()
    private fun JsonElement.objects() = jsonArray.map { it.jsonObject }

    private suspend fun loadExercises(db: GymLogDatabase) {
        fixture.getValue("exercises").objects().forEach {
            db.exerciseDao().upsert(
                ExerciseEntity(it.str("id"), 1, it.deletedAt(), it.str("nombre"), it.str("grupoMuscular"), "barra", "compuesto", null, null, null, null, null, true, null),
            )
        }
    }

    /** Replaces the whole workout/mesocycle evidence with a step's data (an edit); achievements stay. */
    private suspend fun loadStep(db: GymLogDatabase, step: JsonObject) {
        val sql = db.openHelper.writableDatabase
        listOf("logged_sets", "logged_exercises", "workout_sessions", "mesocycles").forEach { sql.execSQL("DELETE FROM $it") }
        step.getValue("sessions").objects().forEach {
            db.workoutSessionDao().upsert(
                WorkoutSessionEntity(it.str("id"), 1, it.deletedAt(), null, null, it.getValue("gymId").strOrNull(), it.getValue("fecha").jsonPrimitive.long, it.getValue("duracionSegundos").intOrNull(), null, null),
            )
        }
        step.getValue("loggedExercises").objects().forEach {
            db.loggedExerciseDao().upsert(LoggedExerciseEntity(it.str("id"), 1, it.deletedAt(), it.str("sessionId"), it.str("exerciseId"), it.getValue("orden").jsonPrimitive.int, null))
        }
        step.getValue("sets").objects().forEach {
            db.loggedSetDao().upsert(
                LoggedSetEntity(it.str("id"), 1, it.deletedAt(), it.str("loggedExerciseId"), it.getValue("orden").jsonPrimitive.int, it.num("peso"), it.getValue("reps").jsonPrimitive.int, it.getValue("esCalentamiento").jsonPrimitive.boolean, null),
            )
        }
        step.getValue("mesocycles").objects().forEach {
            db.mesocycleDao().upsert(
                MesocycleEntity(it.str("id"), 1, if (it["deleted"]?.jsonPrimitive?.boolean == true) 1 else null, "Meso", "fuerza", it.getValue("semanas").jsonPrimitive.int, 3, null, "[]", it.getValue("fechaInicio").jsonPrimitive.long, null),
            )
        }
    }

    private fun outboxCount(db: GymLogDatabase): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox_mutations").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun rows(db: GymLogDatabase) = db.achievementDao().getAll().map { Triple(it.id, it.clave, it.deletedAt != null) }

    @Test fun `every fixture scenario decides and stores what the PWA-derived fixture approves`() = runBlocking {
        for (scenario in fixture.getValue("scenarios").objects()) {
            val db = database()
            loadExercises(db)
            db.userSettingDao().upsert(UserSettingEntity("objetivoSemanal", 1, null, scenario.getValue("weeklyTarget").toString(), null))
            scenario.getValue("initialStored").objects().forEach {
                db.achievementDao().upsert(AchievementEntity(it.str("id"), 1, if (it.str("state") == "tombstoned") 1 else null, it.str("key"), 1, null))
            }
            val zone = ZoneId.of(scenario.str("zone"))
            // A clock that never moves: the runner must still stamp strictly newer versions (nextTimestamp).
            var nowMillis = 0L
            val repo = OfflineAchievementRepository(Manager(db)) { RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { 1_000L }) }

            for (step in scenario.getValue("steps").objects()) {
                step.getValue("inject").objects().forEach {
                    db.achievementDao().upsert(AchievementEntity(it.str("id"), 1, if (it.str("state") == "tombstoned") 1 else null, it.str("key"), 1, null))
                }
                val allowTombstone = step.str("trigger") == "edit"
                val label = "${scenario.str("name")}: ${step.str("label")}"
                val expected = step.getValue("expected").jsonObject
                nowMillis = step.getValue("now").jsonPrimitive.long
                loadStep(db, step)
                val reconcile = ReconcileAchievementsUseCase(repo, now = { nowMillis }, zone = { zone })

                val metrics = repo.transaction { metrics(java.time.Instant.ofEpochMilli(nowMillis), zone) }
                val m = expected.getValue("metrics").jsonObject
                assertEquals("$label sessions", m.getValue("sesionesTotales").jsonPrimitive.int, metrics.sessions)
                assertEquals("$label volume", m.num("volumenTotal"), metrics.volume, 1e-9)
                assertEquals("$label prs", m.getValue("prsTotales").jsonPrimitive.int, metrics.personalRecords)
                assertEquals("$label streak", m.getValue("mejorRacha").jsonPrimitive.int, metrics.bestStreakWeeks)
                assertEquals("$label mesocycles", m.getValue("mesociclosCompletados").jsonPrimitive.int, metrics.completedMesocycles)
                assertEquals("$label met", expected.getValue("met").jsonArray.map { it.jsonPrimitive.content }, AchievementRules.evaluate(metrics))

                val prs = repo.observePersonalRecords().first()
                assertEquals(
                    "$label PRs",
                    expected.getValue("personalRecords").objects().map { listOf(it.str("exerciseId"), it.str("nombre"), it.num("peso"), it.getValue("fecha").jsonPrimitive.long) },
                    prs.map { listOf(it.exerciseId, it.name, it.weight, it.achievedAt) },
                )

                val outboxBefore = outboxCount(db)
                val result = reconcile(allowTombstone)
                val expectedDecisions = expected.getValue("decisions").jsonObject.mapValues { it.value.jsonPrimitive.content }
                assertEquals(label, expectedDecisions, result.decisions.mapValues { it.value.wire })
                val expectedWrite = expectedDecisions.values.any { it == "unlock" || it == "tombstone" }
                assertEquals("$label wrote", expectedWrite, result.wrote)
                assertEquals("$label one outbox row per writing run", outboxBefore + if (expectedWrite) 1 else 0, outboxCount(db))
                val expectedRows = expected.getValue("storedAfter").objects().map { Triple(it.str("id"), it.str("key"), it.str("state") == "tombstoned") }
                assertEquals("$label stored", expectedRows, rows(db))
                assertEquals(
                    "$label unlocked",
                    expectedRows.filter { !it.third }.map { it.second }.distinct().sorted(),
                    repo.observeUnlocked().first().map { it.key },
                )

                // Idempotent: same evidence, nothing to write, nothing queued.
                val outboxAfter = outboxCount(db)
                val again = reconcile(allowTombstone)
                assertTrue("$label second run", !again.wrote)
                assertEquals("$label second run outbox", outboxAfter, outboxCount(db))
                assertEquals("$label second run rows", expectedRows, rows(db))
            }
            db.close()
        }
    }

    @Test fun `a revived row keeps its id and keeps its original unlock date`() = runBlocking {
        val db = database()
        db.userSettingDao().upsert(UserSettingEntity("objetivoSemanal", 1, null, "3", null))
        val repo = OfflineAchievementRepository(Manager(db)) { RoomLocalMutationRunner(db, DeviceId("d"), clock = { 5_000L }) }
        // 10 sessions earn sessions-10.
        repeat(10) { i -> db.workoutSessionDao().upsert(WorkoutSessionEntity("s$i", 1, null, null, null, null, 1_000L + i, 3600, null, null)) }
        var clock = 10_000L
        val reconcile = ReconcileAchievementsUseCase(repo, now = { clock }, zone = { ZoneId.of("UTC") })
        reconcile()
        val first = db.achievementDao().getAll().single()
        assertEquals(AchievementRules.canonicalId("sesiones-10"), first.id)
        assertEquals(10_000L, first.fechaDesbloqueo)

        // A history edit removes a session -> tombstone; restoring it -> same row alive again, newer version and date.
        db.workoutSessionDao().upsert(WorkoutSessionEntity("s9", 2, 2, null, null, null, 1_009L, 3600, null, null))
        clock = 20_000L
        assertEquals(listOf("sesiones-10"), reconcile(allowTombstone = true).tombstoned)
        val tombstoned = db.achievementDao().getAll().single()
        assertTrue(tombstoned.deletedAt != null && tombstoned.updatedAt > first.updatedAt)

        db.workoutSessionDao().upsert(WorkoutSessionEntity("s9", 3, null, null, null, null, 1_009L, 3600, null, null))
        clock = 30_000L
        assertEquals(listOf("sesiones-10"), reconcile().unlocked)
        val revived = db.achievementDao().getAll().single()
        assertEquals(first.id, revived.id)
        assertEquals(null, revived.deletedAt)
        assertEquals("original unlock date is kept", 10_000L, revived.fechaDesbloqueo)
        assertTrue(revived.updatedAt > tombstoned.updatedAt)
        db.close()
    }
}
