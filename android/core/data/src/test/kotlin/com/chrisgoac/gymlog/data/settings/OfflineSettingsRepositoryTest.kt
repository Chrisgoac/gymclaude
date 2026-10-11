package com.chrisgoac.gymlog.data.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.stats.OfflineStatsRepository
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.settings.InvalidSettingException
import com.chrisgoac.gymlog.domain.settings.SettingProblem
import com.chrisgoac.gymlog.domain.workout.ProgressionMode
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingOperation
import com.chrisgoac.gymlog.model.UserSettingRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineSettingsRepositoryTest {
    private var now = 1_800_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, GymLogDatabase::class.java).allowMainThreadQueries().build()

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private val runner = RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now })
    private val settings = OfflineSettingsRepository(FakeManager(db)) { runner }

    @After fun tearDown() = db.close()

    private fun outbox(): List<PushMutation> =
        db.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContractJson.decodeFromString(PushMutation.serializer(), cursor.getString(0))) }
        }

    private fun written() = outbox().flatMap { it.operations }.map { (it as UserSettingOperation).record }

    private suspend fun seed(key: String, valor: String) {
        runner.run(LocalMutation { put(UserSettingRecord(RecordId(key), nextTimestamp(SyncTable.USER_SETTINGS, RecordId(key)), null, valor)) })
        now += 1000
    }

    @Test fun `known keys are written exactly as the PWA stringifies them`() = runTest {
        settings.setWeeklyTarget(4)
        now += 1000
        settings.setProgressionMode(ProgressionMode.DOBLE)
        now += 1000
        settings.setIncrement(Equipment.BARRA, 5.0)
        now += 1000
        settings.setIncrement(Equipment.MANCUERNA, 1.25)
        now += 1000
        settings.setVolumeGoal(MuscleGroup.PECHO, 12000.0)

        val rows = written().associate { it.id.value to it.valor }
        assertEquals("4", rows["objetivoSemanal"])
        assertEquals("\"doble\"", rows["modoProgresion"])
        assertEquals("""{"barra":5,"mancuerna":1.25}""", rows["incrementos"])
        assertEquals("""{"pecho":12000}""", rows["objetivosVolumen"])

        val read = settings.observeTraining().first()
        assertEquals(4, read.weeklyTarget)
        assertEquals(ProgressionMode.DOBLE, read.progression.mode)
        assertEquals(5.0, read.progression.increments.getValue(Equipment.BARRA), 0.0)
        assertEquals(mapOf(MuscleGroup.PECHO to 12000.0), read.volumeGoals)
    }

    @Test fun `stats and achievements decode through the same codec as the settings reader`() = runTest {
        seed("objetivoSemanal", "4.2")
        assertEquals(5, settings.observeTraining().first().weeklyTarget)
        assertEquals(5, OfflineStatsRepository.weeklyTarget("4.2"))
        assertEquals(3, settings.let { OfflineStatsRepository.weeklyTarget(null) })
    }

    @Test fun `unknown rows and unknown members of known objects survive our writes untouched`() = runTest {
        seed("futureSetting", """{"v":2,"x":[1,2]}""")
        seed("incrementos", """{"barra":2.5,"kettlebell":4}""")
        seed("objetivosVolumen", """{"pecho":100,"grupoNuevo":5}""")

        settings.setWeeklyTarget(5)
        now += 1000
        settings.setIncrement(Equipment.POLEA, 2.5)
        now += 1000
        settings.setVolumeGoal(MuscleGroup.PECHO, null)

        val last = written().groupBy { it.id.value }.mapValues { it.value.last().valor }
        assertEquals("""{"barra":2.5,"kettlebell":4,"polea":2.5}""", last["incrementos"])
        assertEquals("""{"grupoNuevo":5}""", last["objetivosVolumen"])
        // The unknown row was seeded once and never rewritten by a typed setter.
        assertEquals(1, written().count { it.id.value == "futureSetting" })
        assertEquals("""{"v":2,"x":[1,2]}""", db.userSettingDao().findById("futureSetting")!!.valor)
    }

    @Test fun `an unchanged value writes nothing and an unreadable stored object is never overwritten`() = runTest {
        settings.setWeeklyTarget(3)
        now += 1000
        val before = outbox().size
        settings.setWeeklyTarget(3)
        assertEquals(before, outbox().size)

        seed("incrementos", "not json")
        try {
            settings.setIncrement(Equipment.BARRA, 2.5)
            fail("expected STORED_VALUE_UNREADABLE")
        } catch (e: InvalidSettingException) {
            assertEquals(SettingProblem.STORED_VALUE_UNREADABLE, e.problem)
        }
        assertEquals("not json", db.userSettingDao().findById("incrementos")!!.valor)
    }

    @Test fun `invalid values are refused before anything is stored`() = runTest {
        suspend fun rejects(problem: SettingProblem, block: suspend () -> Unit) = try {
            block(); fail("expected $problem")
        } catch (e: InvalidSettingException) {
            assertEquals(problem, e.problem)
        }
        rejects(SettingProblem.WEEKLY_TARGET_OUT_OF_RANGE) { settings.setWeeklyTarget(0) }
        rejects(SettingProblem.WEEKLY_TARGET_OUT_OF_RANGE) { settings.setWeeklyTarget(15) }
        rejects(SettingProblem.INCREMENT_OUT_OF_RANGE) { settings.setIncrement(Equipment.BARRA, -1.0) }
        rejects(SettingProblem.INCREMENT_OUT_OF_RANGE) { settings.setIncrement(Equipment.BARRA, Double.NaN) }
        rejects(SettingProblem.VOLUME_GOAL_OUT_OF_RANGE) { settings.setVolumeGoal(MuscleGroup.PECHO, 0.0) }
        assertTrue(outbox().isEmpty())
    }
}
