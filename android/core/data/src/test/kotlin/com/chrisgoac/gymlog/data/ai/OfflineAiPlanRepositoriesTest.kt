package com.chrisgoac.gymlog.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.ai.MesocycleDraft
import com.chrisgoac.gymlog.domain.ai.MesocycleGoal
import com.chrisgoac.gymlog.domain.ai.MesocycleParams
import com.chrisgoac.gymlog.domain.ai.MesocycleSaveException
import com.chrisgoac.gymlog.domain.coach.CoachException
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseInput
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.exercise.ExerciseType
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.stats.CoachSnapshot
import com.chrisgoac.gymlog.domain.stats.RecordKind
import com.chrisgoac.gymlog.model.ApiErrorCode
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.MesocycleOperation
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RoutineExerciseOperation
import com.chrisgoac.gymlog.model.RoutineOperation
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.ai.IntelligentReportRequestDto
import com.chrisgoac.gymlog.model.ai.IntelligentReportResponseDto
import com.chrisgoac.gymlog.model.ai.MesocycleDraftDto
import com.chrisgoac.gymlog.model.ai.MesocycleRequestDto
import com.chrisgoac.gymlog.model.ai.MesocycleResponseDto
import com.chrisgoac.gymlog.network.AiPlanApi
import com.chrisgoac.gymlog.network.SyncApiException
import com.chrisgoac.gymlog.database.LocalMutation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineAiPlanRepositoriesTest {
    private var now = 1_800_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, GymLogDatabase::class.java).allowMainThreadQueries().build()
    private val manager = FakeManager(db)

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current = MutableStateFlow<OpenUserDatabase?>(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private class FakeExercises(var all: List<Exercise>) : ExerciseRepository {
        override fun observeExercises(filter: ExerciseFilter): Flow<List<Exercise>> = flowOf(all)
        override fun observeExercise(id: String): Flow<Exercise?> = flowOf(all.firstOrNull { it.id == id })
        override suspend fun createCustom(input: ExerciseInput): Exercise = error("unused")
        override suspend fun updateCustom(id: String, input: ExerciseInput): Exercise = error("unused")
        override suspend fun deleteCustom(id: String) = Unit
    }

    private class FakeApi : AiPlanApi {
        val mesoRequests = mutableListOf<MesocycleRequestDto>()
        val mesoAnswers = ArrayDeque<() -> MesocycleResponseDto>()
        val reportRequests = mutableListOf<IntelligentReportRequestDto>()
        var reportAnswer: () -> IntelligentReportResponseDto = { error("unset") }

        override suspend fun generateMesocycle(expectedUserId: String, request: MesocycleRequestDto): MesocycleResponseDto {
            mesoRequests += request
            return mesoAnswers.removeFirst()()
        }

        override suspend fun intelligentReport(expectedUserId: String, request: IntelligentReportRequestDto): IntelligentReportResponseDto {
            reportRequests += request
            return reportAnswer()
        }
    }

    private fun ex(id: String, name: String, custom: Boolean = false, group: MuscleGroup = MuscleGroup.PECHO) =
        Exercise(id, name, group, Equipment.BARRA, ExerciseType.COMPUESTO, isCustom = custom)

    private val exercises = FakeExercises(
        listOf(
            ex("seed-press", "Press banca"),
            ex("seed-squat", "Sentadilla", group = MuscleGroup.CUADRICEPS),
            ex("seed-row", "Remo con barra", group = MuscleGroup.ESPALDA),
            // A custom exercise with the same normalized name as a catalog one wins.
            ex("custom-press", "press  BANCA", custom = true),
        ),
    )
    private val api = FakeApi()
    private val waits = mutableListOf<Long>()
    private var ids = 0
    private val mesocycles = OfflineMesocycleRepository(
        manager,
        { RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now++ }) },
        api,
        exercises,
        snapshot = { SNAPSHOT },
        now = { 1_234L },
        newId = { "local-${++ids}" },
        wait = { waits += it },
    )

    @After fun tearDown() = db.close()

    private fun response(days: List<MesocycleDraftDto.DayDto> = TWO_DAYS, omitted: List<String> = emptyList()) = MesocycleResponseDto(
        clientRequestId = REQ,
        responseId = "server-response-id",
        draft = MesocycleDraftDto(
            nombre = "Bloque",
            objetivo = "hipertrofia",
            semanas = 4,
            diasPorSemana = days.size,
            notas = null,
            progresion = (1..4).map { MesocycleDraftDto.WeekDto(it, it == 4, "Semana $it") },
            dias = days,
        ),
        omitidos = omitted,
        disclaimer = true,
    )

    private fun pushed(): List<PushMutation> =
        db.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContractJson.decodeFromString(PushMutation.serializer(), cursor.getString(0))) }
        }

    @Test fun `generate sends params, snapshot and a deduplicated name-only catalog, and maps names to the account's ids`() = runTest {
        api.mesoAnswers += { response(omitted = listOf("Press militar")) }
        val draft = mesocycles.generate(REQ, PARAMS)

        val request = api.mesoRequests.single()
        assertEquals(REQ, request.clientRequestId)
        assertEquals("hipertrofia", request.params.objetivo)
        assertEquals(listOf("press  BANCA", "Remo con barra", "Sentadilla"), request.catalogo.map { it.nombre })
        assertEquals("cuadriceps", request.catalogo.last().grupo)
        val body = ContractJson.encodeToString(MesocycleRequestDto.serializer(), request)
        assertFalse("no exercise ids leave the device", body.contains("seed-") || body.contains("custom-"))

        assertEquals(listOf("custom-press", "seed-row"), draft.days[0].exercises.map { it.exerciseId })
        assertEquals(listOf("seed-squat"), draft.days[1].exercises.map { it.exerciseId })
        assertEquals(listOf("Press militar"), draft.omitted)
        assertTrue(draft.progression.last().deload)
        assertTrue("generation writes nothing", pushed().isEmpty() && db.mesocycleDao().getAll().isEmpty())
    }

    @Test fun `a name the catalog no longer has is omitted, never guessed`() = runTest {
        api.mesoAnswers += {
            response(listOf(MesocycleDraftDto.DayDto("A", listOf(exerciseDto("Press banca"), exerciseDto("Curl araña"))), TWO_DAYS[1]))
        }
        val draft = mesocycles.generate(REQ, PARAMS)
        assertEquals(listOf("custom-press"), draft.days[0].exercises.map { it.exerciseId })
        assertEquals(listOf("Curl araña"), draft.omitted)
    }

    @Test fun `in-progress 409 is waited out and failures map to Coach failures`() = runTest {
        api.mesoAnswers += { throw apiError(409, "AI_RESPONSE_IN_PROGRESS", true) }
        api.mesoAnswers += { response() }
        mesocycles.generate(REQ, PARAMS)
        assertEquals(listOf(2_000L), waits)
        assertEquals(listOf(REQ, REQ), api.mesoRequests.map { it.clientRequestId })

        suspend fun failureOf(e: Exception): CoachFailure {
            api.mesoAnswers += { throw e }
            return try {
                mesocycles.generate(REQ, PARAMS)
                fail("expected failure")
                error("unreachable")
            } catch (c: CoachException) {
                c.failure
            }
        }
        assertEquals(CoachFailure.UNAVAILABLE, failureOf(apiError(502, "AI_BAD_OUTPUT", true)))
        assertEquals(CoachFailure.PRO_REQUIRED, failureOf(apiError(403, "PRO_REQUIRED", false)))
        assertEquals(CoachFailure.REJECTED, failureOf(apiError(409, "CLIENT_MESSAGE_ID_CONFLICT", false)))
        assertEquals(CoachFailure.INTERRUPTED, failureOf(SyncApiException.Malformed("bad body")))
    }

    @Test fun `save writes the PWA row shapes with local ids in one mutation`() = runTest {
        // An existing routine: the mesocycle's routines go after it.
        RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now++ }).run(
            LocalMutation { put(RoutineRecord(RecordId("old"), nextTimestamp(com.chrisgoac.gymlog.model.SyncTable.ROUTINES, RecordId("old")), null, "Vieja", orden = 0, archivada = false)) },
        )
        api.mesoAnswers += { response() }
        val draft = mesocycles.generate(REQ, PARAMS)
        val edited = draft.copy(name = "Mi bloque", days = draft.days.map { d -> d.copy(exercises = d.exercises.map { it.copy(sets = 5) }) })
        val mesocycleId = mesocycles.save(edited)

        val meso = db.mesocycleDao().findById(mesocycleId)!!
        assertEquals("Mi bloque", meso.nombre)
        assertEquals(1_234L, meso.fechaInicio)
        val progression = Json.parseToJsonElement(meso.progresionJson).jsonArray
        assertEquals(listOf("1", "2", "3", "4"), progression.map { it.jsonObject["semana"]!!.jsonPrimitive.content })
        assertEquals("true", progression[3].jsonObject["descarga"]!!.jsonPrimitive.content)

        val routines = db.routineDao().getAll().filter { it.mesocycleId == mesocycleId }.sortedBy { it.orden }
        assertEquals(listOf("Torso" to 1, "Pierna" to 2), routines.map { it.nombre to it.orden })
        val children = db.routineExerciseDao().getAll().filter { it.routineId == routines[0].id }.sortedBy { it.orden }
        assertEquals(listOf("custom-press" to 0, "seed-row" to 1), children.map { it.exerciseId to it.orden })
        assertEquals(listOf(5, 5), children.map { it.seriesObjetivo })
        assertEquals(listOf(8, 10), children.map { it.repsObjetivo })
        assertEquals(listOf(null, null), children.map { it.repsObjetivoMin })
        // Every id is a fresh local one; the server's response id is never used for a row.
        val allIds = listOf(mesocycleId) + routines.map { it.id } + db.routineExerciseDao().getAll().map { it.id }
        assertTrue(allIds.all { it.startsWith("local-") })
        assertFalse(allIds.contains("server-response-id"))

        val mutation = pushed().last()
        assertEquals(1 + 2 + 3, mutation.operations.size)
        assertTrue(mutation.operations.first() is MesocycleOperation)
        assertTrue(mutation.operations.drop(1).take(2).all { it is RoutineOperation })
        assertTrue(mutation.operations.drop(3).all { it is RoutineExerciseOperation })
    }

    @Test fun `a draft over 50 operations is chunked into ordered outbox rows in one transaction`() = runTest {
        val many = (1..6).map { d -> MesocycleDraftDto.DayDto("Día $d", List(9) { exerciseDto(listOf("Press banca", "Sentadilla", "Remo con barra")[it % 3]) }) }
        api.mesoAnswers += { response(many) }
        val draft = mesocycles.generate(REQ, PARAMS.copy(daysPerWeek = 6))
        mesocycles.save(draft)

        val chunks = pushed()
        assertEquals(listOf(50, 11), chunks.map { it.operations.size })
        val ops = chunks.flatMap { it.operations }
        assertTrue(ops.first() is MesocycleOperation)
        // Routines all come before any routine exercise, so a referenced routine is never in a later chunk.
        val lastRoutine = ops.indexOfLast { it is RoutineOperation }
        val firstChild = ops.indexOfFirst { it is RoutineExerciseOperation }
        assertTrue(lastRoutine < firstChild)
        assertEquals(54, db.routineExerciseDao().getAll().size)
    }

    @Test fun `save refuses an unsavable draft or a vanished exercise and writes nothing`() = runTest {
        api.mesoAnswers += { response() }
        val draft = mesocycles.generate(REQ, PARAMS)
        suspend fun refused(d: MesocycleDraft) {
            try {
                mesocycles.save(d)
                fail("expected MesocycleSaveException")
            } catch (_: MesocycleSaveException) {
            }
        }
        refused(draft.copy(name = " "))
        refused(draft.copy(days = listOf(draft.days[0].copy(exercises = emptyList()), draft.days[1])))
        exercises.all = exercises.all.filterNot { it.id == "seed-squat" }
        refused(draft)
        assertTrue(pushed().isEmpty())
        assertTrue(db.mesocycleDao().getAll().isEmpty() && db.routineDao().getAll().isEmpty())
    }

    @Test fun `the report maps the response and is remembered for its account only`() = runTest {
        api.reportAnswer = {
            IntelligentReportResponseDto(
                "rep-1", 99L, true,
                IntelligentReportResponseDto.ReportDto("Bien", listOf("Constancia"), listOf("Press"), listOf("Descansa")),
                true,
            )
        }
        val reports = OfflineIntelligentReportRepository(manager, api, snapshot = { SNAPSHOT })
        assertNull(reports.lastReport())
        val report = reports.generate()
        assertEquals("rep-1", report.id)
        assertTrue(report.cached)
        assertEquals(listOf("Descansa"), report.recommendations)
        assertEquals("Press de banca", api.reportRequests.single().snapshot.estancados.single().ejercicio)
        assertEquals(report, reports.lastReport())

        manager.current.value = OpenUserDatabase("u2", db)
        assertNull("another account never sees it", reports.lastReport())

        api.reportAnswer = { throw apiError(502, "AI_BAD_OUTPUT", true) }
        try {
            reports.generate()
            fail("expected failure")
        } catch (e: CoachException) {
            assertEquals(CoachFailure.UNAVAILABLE, e.failure)
        }
    }

    @Test fun `nothing about the plan or the report reaches the log`() = runTest {
        ShadowLog.clear()
        api.mesoAnswers += { response() }
        mesocycles.save(mesocycles.generate(REQ, PARAMS))
        val logged = ShadowLog.getLogs().joinToString("\n") { it.msg.orEmpty() }
        listOf("Bloque", "Press banca", "Torso").forEach { assertFalse(logged.contains(it)) }
    }

    private companion object {
        const val REQ = "1b4e28ba-2fa1-4d2b-883f-0016d3cca427"
        val PARAMS = MesocycleParams(MesocycleGoal.HIPERTROFIA, daysPerWeek = 2, weeks = 4, minutesPerSession = 60)

        fun exerciseDto(name: String, reps: Int = 8) = MesocycleDraftDto.ExerciseDto(name, 4, reps, 120)

        val TWO_DAYS = listOf(
            MesocycleDraftDto.DayDto("Torso", listOf(exerciseDto("Press banca"), exerciseDto("Remo con barra", reps = 10))),
            MesocycleDraftDto.DayDto("Pierna", listOf(exerciseDto("Sentadilla"))),
        )

        val SNAPSHOT = CoachSnapshot(
            stalls = listOf(CoachSnapshot.Line("Press de banca", 4)),
            week = CoachSnapshot.Week(2, 3, 5000, null, listOf(CoachSnapshot.Record("Sentadilla", RecordKind.ONE_REP_MAX))),
            groups = listOf(CoachSnapshot.Group("Pecho", 5000, 1, null)),
            body = CoachSnapshot.Body(CoachSnapshot.Weight(80.5, -1.0), emptyList()),
        )

        fun apiError(status: Int, code: String, retryable: Boolean) =
            SyncApiException.Api(status, ApiErrorCode.Unknown, code, retryable, null, null, null, "m")
    }
}
