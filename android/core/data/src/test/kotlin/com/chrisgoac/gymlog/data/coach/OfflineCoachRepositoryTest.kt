package com.chrisgoac.gymlog.data.coach

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CoachMessageEntity
import com.chrisgoac.gymlog.domain.coach.CoachException
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.coach.CoachReportCategory
import com.chrisgoac.gymlog.domain.coach.CoachRole
import com.chrisgoac.gymlog.domain.coach.CoachStreamUpdate
import com.chrisgoac.gymlog.domain.stats.CoachSnapshot
import com.chrisgoac.gymlog.domain.stats.RecordKind
import com.chrisgoac.gymlog.model.CoachMessageOperation
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.ai.AiReportCategory
import com.chrisgoac.gymlog.model.ai.CoachRequestDto
import com.chrisgoac.gymlog.model.ai.ReportResponseDto
import com.chrisgoac.gymlog.network.CoachApi
import com.chrisgoac.gymlog.network.CoachStreamEvent
import com.chrisgoac.gymlog.network.SyncApiException
import com.chrisgoac.gymlog.model.ApiErrorCode
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineCoachRepositoryTest {
    private var now = 1_800_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, GymLogDatabase::class.java).allowMainThreadQueries().build()

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    /** One scripted outcome per attempt: events to deliver, then optionally a failure. */
    private class Attempt(val events: List<CoachStreamEvent>, val failure: Exception? = null)

    private inner class FakeApi(vararg attempts: Attempt) : CoachApi {
        val queue = ArrayDeque(attempts.toList())
        val requests = mutableListOf<CoachRequestDto>()
        val userRowsAtCall = mutableListOf<Int>()
        val reports = mutableListOf<Triple<String, AiReportCategory, String?>>()

        override suspend fun coach(expectedUserId: String, request: CoachRequestDto, onEvent: suspend (CoachStreamEvent) -> Unit) {
            assertEquals("u1", expectedUserId)
            requests += request
            userRowsAtCall += db.coachMessageDao().getAll().count { it.rol == "user" && it.id == request.clientMessageId }
            val attempt = queue.removeFirst()
            attempt.events.forEach { onEvent(it) }
            attempt.failure?.let { throw it }
        }

        override suspend fun report(expectedUserId: String, responseId: String, category: AiReportCategory, context: String?): ReportResponseDto {
            reports += Triple(responseId, category, context)
            return ReportResponseDto(responseId, true)
        }
    }

    private var snapshotReads = 0
    private val waits = mutableListOf<Long>()

    private fun repo(api: CoachApi) = OfflineCoachRepository(
        FakeManager(db),
        { RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now++ }) },
        api,
        snapshot = { snapshotReads++; SNAPSHOT },
        wait = { waits += it },
    )

    @After fun tearDown() = db.close()

    private fun coachOps(): List<CoachMessageOperation> =
        db.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContractJson.decodeFromString(PushMutation.serializer(), cursor.getString(0))) }
        }.flatMap { it.operations }.filterIsInstance<CoachMessageOperation>()

    private fun complete(text: List<String> = listOf("Sube ", "2,5 kg")) =
        Attempt(listOf(CoachStreamEvent.Start(MSG, RESP)) + text.map { CoachStreamEvent.Delta(it) } + CoachStreamEvent.Complete(RESP, true))

    private suspend fun failureOf(block: suspend () -> Unit): CoachFailure {
        try {
            block()
        } catch (e: CoachException) {
            return e.failure
        }
        fail("expected a CoachException")
        error("unreachable")
    }

    @Test fun `the user message is stored before the request and the answer under the response id`() = runTest {
        val api = FakeApi(complete())
        val coach = repo(api)

        val updates = coach.send(MSG, "  ¿Subo peso?  ").toList()

        assertEquals(
            listOf(CoachStreamUpdate.Started(RESP), CoachStreamUpdate.Delta("Sube "), CoachStreamUpdate.Delta("2,5 kg"), CoachStreamUpdate.Completed(RESP)),
            updates,
        )
        assertEquals("user row existed when the request was sent", listOf(1), api.userRowsAtCall)
        assertEquals("¿Subo peso?", api.requests.single().text)
        val thread = coach.observeThread().first()
        assertEquals(listOf(MSG to CoachRole.USER, RESP to CoachRole.ASSISTANT), thread.map { it.id to it.role })
        assertEquals(listOf("¿Subo peso?", "Sube 2,5 kg"), thread.map { it.text })
        assertTrue("assistant after the question", thread[1].createdAt > thread[0].createdAt)
        assertEquals("both go through the outbox", listOf(MSG, RESP), coachOps().map { it.record.id.value })
    }

    @Test fun `an interrupted stream stores no answer and the retry reuses the message without duplicating it`() = runTest {
        val api = FakeApi(
            Attempt(listOf(CoachStreamEvent.Start(MSG, RESP), CoachStreamEvent.Delta("Sube")), SyncApiException.Transport(IOException("reset"))),
            complete(),
        )
        val coach = repo(api)

        val partial = mutableListOf<CoachStreamUpdate>()
        assertEquals(CoachFailure.INTERRUPTED, failureOf { coach.send(MSG, "¿Subo peso?").collect { partial += it } })
        assertEquals(listOf(CoachStreamUpdate.Started(RESP), CoachStreamUpdate.Delta("Sube")), partial)
        assertEquals("only the question is stored", listOf(MSG), coach.observeThread().first().map { it.id })

        // The retry passes other text: the stored question is what is resent.
        coach.send(MSG, "otro texto").toList()

        assertEquals(listOf(MSG, MSG), api.requests.map { it.clientMessageId })
        assertEquals(listOf("¿Subo peso?", "¿Subo peso?"), api.requests.map { it.text })
        assertEquals(listOf(MSG, RESP), coach.observeThread().first().map { it.id })
        assertEquals("one user op, one assistant op", listOf(MSG, RESP), coachOps().map { it.record.id.value })
        assertEquals("a fresh snapshot per attempt", 2, snapshotReads)
    }

    @Test fun `a stream that ends without complete or with an error event is not an answer`() = runTest {
        val coach = repo(
            FakeApi(
                Attempt(listOf(CoachStreamEvent.Start(MSG, RESP), CoachStreamEvent.Delta("a"))),
                Attempt(listOf(CoachStreamEvent.Start(MSG, RESP), CoachStreamEvent.Delta("a"), CoachStreamEvent.Error("AI_TIMEOUT", true))),
                Attempt(listOf(CoachStreamEvent.Error("WHATEVER", false))),
            ),
        )
        assertEquals(CoachFailure.INTERRUPTED, failureOf { coach.send(MSG, "q").toList() })
        assertEquals(CoachFailure.UNAVAILABLE, failureOf { coach.send(MSG, "q").toList() })
        assertEquals(CoachFailure.REJECTED, failureOf { coach.send(MSG, "q").toList() })
        assertEquals(listOf(MSG), coach.observeThread().first().map { it.id })
    }

    @Test fun `an answer already present (replay or synced from another device) is not stored twice`() = runTest {
        db.coachMessageDao().upsert(CoachMessageEntity(RESP, 5, null, "assistant", "Sube 2,5 kg", 5, 9))
        val coach = repo(FakeApi(complete()))

        coach.send(MSG, "¿Subo peso?").toList()

        assertEquals(listOf(MSG), coachOps().map { it.record.id.value })
        assertEquals(1, coach.observeThread().first().count { it.id == RESP })
    }

    @Test fun `in progress is waited out with backoff then retried, and gives up as busy`() = runTest {
        val busy = { SyncApiException.Api(409, ApiErrorCode.Unknown, "AI_RESPONSE_IN_PROGRESS", true, null, null, null, "busy") }
        val waited = repo(FakeApi(Attempt(emptyList(), busy()), Attempt(emptyList(), busy()), complete()))
        waited.send(MSG, "q").toList()
        assertEquals(listOf(2_000L, 4_000L), waits)
        assertEquals(listOf(MSG, RESP), waited.observeThread().first().map { it.id })

        waits.clear()
        val other = "7d1f9c1e-2b4a-4c55-9a77-0d6c1e2f3a4b"
        val stuck = repo(FakeApi(Attempt(emptyList(), busy()), Attempt(emptyList(), busy()), Attempt(emptyList(), busy()), Attempt(emptyList(), busy())))
        assertEquals(CoachFailure.BUSY, failureOf { stuck.send(other, "q2").toList() })
        assertEquals(listOf(2_000L, 4_000L, 8_000L), waits)
    }

    @Test fun `server errors map to what the screen offers`() = runTest {
        fun api(status: Int, code: String, retryable: Boolean) =
            FakeApi(Attempt(emptyList(), SyncApiException.Api(status, ApiErrorCode.Unknown, code, retryable, null, null, null, "m")))
        assertEquals(CoachFailure.PRO_REQUIRED, failureOf { repo(api(403, "PRO_REQUIRED", false)).send(MSG, "q").toList() })
        assertEquals(CoachFailure.UNAVAILABLE, failureOf { repo(api(503, "AI_UNAVAILABLE", true)).send(MSG, "q").toList() })
        assertEquals(CoachFailure.UNAVAILABLE, failureOf { repo(api(503, "ENTITLEMENT_UNAVAILABLE", true)).send(MSG, "q").toList() })
        assertEquals(CoachFailure.REJECTED, failureOf { repo(api(400, "INVALID_REQUEST", false)).send(MSG, "q").toList() })
        assertEquals(CoachFailure.SESSION, failureOf { repo(FakeApi(Attempt(emptyList(), SyncApiException.SessionRequired()))).send(MSG, "q").toList() })
        // Each of these left the question in the thread, once.
        assertEquals(listOf(MSG), coachOps().map { it.record.id.value })
    }

    @Test fun `a blank or oversized new question is refused before anything is stored or sent`() = runTest {
        val api = FakeApi()
        val coach = repo(api)
        assertEquals(CoachFailure.REJECTED, failureOf { coach.send(MSG, "   ").toList() })
        assertEquals(CoachFailure.REJECTED, failureOf { coach.send(MSG, "x".repeat(2001)).toList() })
        assertTrue(api.requests.isEmpty())
        assertTrue(coachOps().isEmpty())
    }

    @Test fun `report sends the category and a trimmed optional comment`() = runTest {
        val api = FakeApi()
        val coach = repo(api)
        coach.report(RESP, CoachReportCategory.MEDICAL, "  ")
        coach.report(RESP, CoachReportCategory.OTHER, " " + "y".repeat(600))
        assertEquals(Triple(RESP, AiReportCategory.MEDICAL, null), api.reports[0])
        assertEquals(500, api.reports[1].third!!.length)
    }

    @Test fun `nothing about the conversation is logged`() = runTest {
        ShadowLog.clear()
        val coach = repo(FakeApi(Attempt(listOf(CoachStreamEvent.Start(MSG, RESP), CoachStreamEvent.Delta("SECRETO-RESPUESTA")), SyncApiException.Transport(IOException("x"))), complete(listOf("SECRETO-RESPUESTA"))))
        runCatching { coach.send(MSG, "SECRETO-PREGUNTA").toList() }
        coach.send(MSG, "SECRETO-PREGUNTA").toList()
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        assertFalse(logged, logged.contains("SECRETO"))
    }

    @Test fun `the snapshot goes out as the strict allowlist`() {
        val json = ContractJson.encodeToString(com.chrisgoac.gymlog.model.ai.CoachSnapshotDto.serializer(), SNAPSHOT.toDto())
        assertEquals(
            """{"estancados":[{"ejercicio":"Press de banca","sesionesSinMejora":4}],""" +
                """"semana":{"sesiones":2,"objetivo":3,"volumen":5000,"deltaPct":null,"prs":[{"ejercicio":"Sentadilla","tipo":"1rm"}]},""" +
                """"grupos":[{"grupo":"Pecho","volumenSemana":5000,"diasSinEntrenar":1,"objetivo":null}],""" +
                """"cuerpo":{"peso":{"actual":80.5,"delta4sem":-1.0},"medidas":[]}}""",
            json,
        )
        val long = SNAPSHOT.copy(stalls = listOf(CoachSnapshot.Line("a".repeat(199) + "💪", 1), CoachSnapshot.Line(" ", 1)))
        assertEquals(listOf("a".repeat(199), "?"), long.toDto().estancados.map { it.ejercicio })
    }

    private companion object {
        const val MSG = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff"
        const val RESP = "0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9"
        val SNAPSHOT = CoachSnapshot(
            stalls = listOf(CoachSnapshot.Line("Press de banca", 4)),
            week = CoachSnapshot.Week(2, 3, 5000, null, listOf(CoachSnapshot.Record("Sentadilla", RecordKind.ONE_REP_MAX))),
            groups = listOf(CoachSnapshot.Group("Pecho", 5000, 1, null)),
            body = CoachSnapshot.Body(CoachSnapshot.Weight(80.5, -1.0), emptyList()),
        )
    }
}
