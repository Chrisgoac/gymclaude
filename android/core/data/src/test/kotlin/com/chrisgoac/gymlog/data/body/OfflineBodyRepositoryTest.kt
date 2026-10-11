package com.chrisgoac.gymlog.data.body

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.body.BodyMeasurementMissingException
import com.chrisgoac.gymlog.domain.body.BodyProblem
import com.chrisgoac.gymlog.domain.body.CustomMetricProblem
import com.chrisgoac.gymlog.domain.body.InvalidBodyMeasurementException
import com.chrisgoac.gymlog.domain.body.InvalidCustomMetricException
import com.chrisgoac.gymlog.model.BodyMetricOperation
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.UserSettingOperation
import com.chrisgoac.gymlog.model.UserSettingRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private const val DAY = 86_400_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineBodyRepositoryTest {
    private var now = 1_800_000_000_000L
    private var ids = 0
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<GymLogDatabase>()

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private fun memoryDb() = Room.inMemoryDatabaseBuilder(context, GymLogDatabase::class.java).allowMainThreadQueries().build().also { opened += it }

    private fun repo(db: GymLogDatabase) = OfflineBodyRepository(
        FakeManager(db),
        { RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now }) },
        now = { now },
        newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
    )

    private val db = memoryDb()
    private val body = repo(db)

    @After fun tearDown() = opened.forEach { if (it.isOpen) it.close() }

    private fun outbox(database: GymLogDatabase = db): List<PushMutation> =
        database.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContractJson.decodeFromString(PushMutation.serializer(), cursor.getString(0))) }
        }

    @Test fun `create edit and delete write one outbox mutation each with advancing timestamps`() = runTest {
        val id = body.addMeasurement("peso", 80.5, now - DAY)
        now += 1000
        body.updateMeasurement(id, 79.0, now - DAY)
        now += 1000
        body.deleteMeasurement(id)

        val mutations = outbox()
        assertEquals(3, mutations.size)
        val records = mutations.map { (it.operations.single() as BodyMetricOperation).record }
        assertEquals(listOf(80.5, 79.0, 79.0), records.map { it.valor })
        assertEquals(listOf(null, null), records.take(2).map { it.deletedAt })
        assertNotNull(records[2].deletedAt)
        assertEquals(records[2].updatedAt, records[2].deletedAt)
        assertTrue(records[0].updatedAt < records[1].updatedAt && records[1].updatedAt < records[2].updatedAt)
        assertTrue(body.observeMeasurements("peso").first().isEmpty())
        // The tombstone stays in Room so sync can push it.
        assertEquals(1, db.bodyMetricDao().getAll().size)
        assertNotNull(db.bodyMetricDao().getAll().single().deletedAt)
    }

    @Test fun `deleting a missing or already deleted measurement is a no-op and editing it fails`() = runTest {
        val id = body.addMeasurement("peso", 80.0, now)
        body.deleteMeasurement(id)
        val before = outbox().size
        body.deleteMeasurement(id)
        body.deleteMeasurement("nope")
        assertEquals(before, outbox().size)
        try {
            body.updateMeasurement(id, 81.0, now)
            fail("editing a tombstone must throw")
        } catch (expected: BodyMeasurementMissingException) {
            assertEquals(id, expected.id)
        }
    }

    @Test fun `an unchanged edit writes nothing`() = runTest {
        val id = body.addMeasurement("cintura", 90.0, now - DAY)
        val before = outbox().size
        body.updateMeasurement(id, 90.0, now - DAY)
        assertEquals(before, outbox().size)
    }

    @Test fun `series are scoped by type and ordered by date then write order`() = runTest {
        body.addMeasurement("peso", 82.0, now - 2 * DAY)
        now += 10
        body.addMeasurement("cintura", 90.0, now - 5 * DAY)
        now += 10
        val late = body.addMeasurement("peso", 80.0, now - 10 * DAY) // older date, written later
        now += 10
        val sameDayA = body.addMeasurement("peso", 81.0, now - DAY)
        now += 10
        val sameDayB = body.addMeasurement("peso", 81.4, now - DAY)

        val series = body.observeMeasurements("peso").first()
        assertEquals(listOf(80.0, 82.0, 81.0, 81.4), series.map { it.value })
        assertEquals(late, series.first().id)
        assertEquals(listOf(sameDayA, sameDayB), series.takeLast(2).map { it.id })
        assertEquals(listOf("cintura"), body.observeMeasurements("cintura").first().map { it.type })
        assertEquals(setOf("peso", "cintura"), body.observeMeasuredTypes().first())
    }

    @Test fun `same day entries can be edited and deleted independently`() = runTest {
        val a = body.addMeasurement("peso", 81.0, now - DAY)
        now += 10
        val b = body.addMeasurement("peso", 81.4, now - DAY)
        now += 10
        body.deleteMeasurement(a)
        assertEquals(listOf(b), body.observeMeasurements("peso").first().map { it.id })
    }

    @Test fun `validation rejects out of range values dates and unknown metrics before any write`() = runTest {
        fun expect(problem: BodyProblem, block: suspend () -> Unit) = runCatching { kotlinx.coroutines.runBlocking { block() } }
            .let { result -> assertEquals(problem, (result.exceptionOrNull() as? InvalidBodyMeasurementException)?.problem) }
        expect(BodyProblem.VALUE_OUT_OF_RANGE) { body.addMeasurement("peso", 19.99, now) }
        expect(BodyProblem.VALUE_OUT_OF_RANGE) { body.addMeasurement("peso", 400.01, now) }
        expect(BodyProblem.VALUE_OUT_OF_RANGE) { body.addMeasurement("peso", Double.NaN, now) }
        expect(BodyProblem.VALUE_OUT_OF_RANGE) { body.addMeasurement("cintura", Double.POSITIVE_INFINITY, now) }
        expect(BodyProblem.VALUE_OUT_OF_RANGE) { body.addMeasurement("cuello", 9.0, now) }
        expect(BodyProblem.DATE_IN_FUTURE) { body.addMeasurement("peso", 80.0, now + 6 * 60 * 1000) }
        expect(BodyProblem.DATE_TOO_OLD) { body.addMeasurement("peso", 80.0, 1_000L) }
        expect(BodyProblem.UNKNOWN_METRIC) { body.addMeasurement("grasa", 20.0, now) }
        assertTrue(outbox().isEmpty())
        // 5 minutes of clock skew is tolerated.
        body.addMeasurement("peso", 80.0, now + 4 * 60 * 1000)
        assertEquals(1, outbox().size)
    }

    @Test fun `custom metrics use the PWA setting format, validate, and accept measurements`() = runTest {
        val sleep = body.addCustomMetric("  Grasa corporal ", " % ")
        assertEquals("grasa-corporal", sleep.key)
        assertEquals("Grasa corporal", sleep.label)
        assertEquals("%", sleep.unit)
        assertEquals(listOf(sleep), body.observeCustomMetrics().first())

        val setting = (outbox().single().operations.single() as UserSettingOperation).record
        assertEquals("metricasPersonalizadas", setting.id.value)
        assertEquals("""[{"clave":"grasa-corporal","label":"Grasa corporal","unidad":"%"}]""", setting.valor)

        // Idempotent by key: no second write.
        assertEquals(sleep, body.addCustomMetric("grasa corporal", "x"))
        assertEquals(1, outbox().size)

        body.addMeasurement("grasa-corporal", 18.5, now)
        assertEquals(listOf(18.5), body.observeMeasurements("grasa-corporal").first().map { it.value })
        try {
            body.addMeasurement("grasa-corporal", 0.0, now)
            fail("custom metrics need a positive value")
        } catch (expected: InvalidBodyMeasurementException) {
            assertEquals(BodyProblem.VALUE_OUT_OF_RANGE, expected.problem)
        }
    }

    @Test fun `custom metric names are validated`() = runTest {
        fun problem(name: String, unit: String = "x") = runCatching { kotlinx.coroutines.runBlocking { body.addCustomMetric(name, unit) } }
            .exceptionOrNull().let { (it as? InvalidCustomMetricException)?.problem }
        assertEquals(CustomMetricProblem.NAME_EMPTY, problem("  "))
        assertEquals(CustomMetricProblem.NAME_EMPTY, problem("¡¡!!"))
        assertEquals(CustomMetricProblem.RESERVED, problem("Bíceps"))
        assertEquals(CustomMetricProblem.RESERVED, problem("peso"))
        assertEquals(CustomMetricProblem.NAME_TOO_LONG, problem("a".repeat(41)))
        assertEquals(CustomMetricProblem.UNIT_TOO_LONG, problem("Algo", "u".repeat(13)))
        assertTrue(outbox().isEmpty())
    }

    @Test fun `unknown future entries of the custom list survive an append`() = runTest {
        val seeded = UserSettingRecord(
            id = com.chrisgoac.gymlog.model.RecordId("metricasPersonalizadas"), updatedAt = 5, deletedAt = null,
            valor = """[{"clave":"muneca","label":"Muñeca","unidad":"cm","color":"azul"},"junk"]""",
        )
        db.userSettingDao().upsert(com.chrisgoac.gymlog.database.entity.UserSettingEntity(seeded.id.value, 5, null, seeded.valor, 1))
        assertEquals(listOf("muneca"), body.observeCustomMetrics().first().map { it.key })
        body.addCustomMetric("Tobillo", "cm")
        val stored = (outbox().single().operations.single() as UserSettingOperation).record.valor
        assertTrue(stored.contains(""""color":"azul""""))
        assertTrue(stored.contains("junk"))
        assertEquals(listOf("muneca", "tobillo"), body.observeCustomMetrics().first().map { it.key })
    }

    @Test fun `data and pending outbox survive an offline restart`() = runTest {
        val file = File(context.cacheDir, "body-restart.db").also { it.delete() }
        fun fileDb() = Room.databaseBuilder(context, GymLogDatabase::class.java, file.absolutePath).allowMainThreadQueries().build().also { opened += it }

        val first = fileDb()
        val firstRepo = repo(first)
        val id = firstRepo.addMeasurement("peso", 80.0, now - DAY)
        firstRepo.addCustomMetric("Muñeca", "cm")
        first.close()

        val second = fileDb()
        val secondRepo = repo(second)
        assertEquals(listOf(80.0), secondRepo.observeMeasurements("peso").first().map { it.value })
        assertEquals(listOf("muneca"), secondRepo.observeCustomMetrics().first().map { it.key })
        assertEquals(2, outbox(second).size)
        now += 1000
        secondRepo.deleteMeasurement(id)
        assertNull(secondRepo.observeMeasurements("peso").first().firstOrNull())
        assertEquals(3, outbox(second).size)
    }
}
