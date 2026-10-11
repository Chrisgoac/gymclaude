package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ai.AiReportCategory
import com.chrisgoac.gymlog.model.ai.CoachRequestDto
import com.chrisgoac.gymlog.model.ai.CoachSnapshotDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoachApiTest {
    private class FakeAuth(private val owner: String = USER) : AuthSessionProvider {
        override val state = MutableStateFlow<AuthState>(AuthState.SignedIn(owner))
        override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String? =
            if (expectedUserId != owner) null else if (forceRefresh) "tok-2" else "tok-1"
    }

    private val ndjson = headersOf(HttpHeaders.ContentType, "application/x-ndjson; charset=utf-8")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(
        requests: MutableList<HttpRequestData> = mutableListOf(),
        bodies: MutableList<String> = mutableListOf(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = CoachApiImpl(
        createGymLogHttpClient(
            MockEngine { request ->
                requests += request
                bodies += request.body.toByteArray().decodeToString()
                handler(request)
            },
        ),
        "https://example.test/",
        FakeAuth(),
        ServerClock(),
        requestIdFactory = { "req-1" },
    )

    private val snapshot = CoachSnapshotDto(
        estancados = listOf(CoachSnapshotDto.Estancado("Press de banca", 4)),
        semana = CoachSnapshotDto.Semana(2, 3, 5000, null, listOf(CoachSnapshotDto.Pr("Sentadilla", "1rm"))),
        grupos = listOf(CoachSnapshotDto.Grupo("Pecho", 5000, 1, null)),
        cuerpo = CoachSnapshotDto.Cuerpo(CoachSnapshotDto.Resumen(80.0, null), emptyList()),
    )
    private val request = CoachRequestDto(CLIENT_ID, "¿Qué tal mi semana?", snapshot)

    private suspend fun CoachApi.collect(request: CoachRequestDto = this@CoachApiTest.request): List<CoachStreamEvent> {
        val events = mutableListOf<CoachStreamEvent>()
        coach(USER, request) { events += it }
        return events
    }

    @Test
    fun streamsEveryEventInOrderUnderBearerWithTheStrictRequestBody() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val api = api(requests, bodies) {
            respond(
                """{"type":"start","clientMessageId":"$CLIENT_ID","responseId":"$RESPONSE_ID"}
                  |{"type":"delta","text":"Hola, "}
                  |{"type":"delta","text":"atleta"}
                  |{"type":"complete","responseId":"$RESPONSE_ID","disclaimer":true}
                  |""".trimMargin(),
                HttpStatusCode.OK, ndjson,
            )
        }

        val events = api.collect()

        assertEquals(
            listOf(
                CoachStreamEvent.Start(CLIENT_ID, RESPONSE_ID),
                CoachStreamEvent.Delta("Hola, "),
                CoachStreamEvent.Delta("atleta"),
                CoachStreamEvent.Complete(RESPONSE_ID, true),
            ),
            events,
        )
        val sent = requests.single()
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("https://example.test/api/v1/ai/coach", sent.url.toString())
        assertEquals("Bearer tok-1", sent.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(setOf("clientMessageId", "text", "snapshot"), body.keys)
        assertEquals(setOf("estancados", "semana", "grupos", "cuerpo"), body.getValue("snapshot").jsonObject.keys)
        // Nullable fields are present as null: the server's strict schema requires the keys.
        val semana = body.getValue("snapshot").jsonObject.getValue("semana").jsonObject
        assertTrue("deltaPct" in semana && semana.getValue("deltaPct").jsonPrimitive.content == "null")
    }

    @Test
    fun linesSplitAcrossChunksAndMultiByteCharactersAreReassembled() {
        val text = "{\"type\":\"delta\",\"text\":\"Más peso 💪\"}\n{\"type\":\"delta\",\"text\":\"ñ\"}\n"
        val bytes = text.encodeToByteArray()
        // Every possible cut point, including inside the emoji and the ñ.
        for (cut in 1 until bytes.size) {
            val lines = NdjsonLines()
            val got = lines.feed(bytes.copyOfRange(0, cut)) + lines.feed(bytes.copyOfRange(cut, bytes.size))
            assertEquals("cut at $cut", listOf("{\"type\":\"delta\",\"text\":\"Más peso 💪\"}", "{\"type\":\"delta\",\"text\":\"ñ\"}"), got)
            assertNull(lines.finish())
        }
    }

    @Test
    fun aTrailingLineWithoutNewlineIsKeptWhenWholeAndDroppedWhenTruncated() = runTest {
        val whole = api { respond("""{"type":"delta","text":"a"}""" + "\n" + """{"type":"complete","responseId":"$RESPONSE_ID","disclaimer":true}""", HttpStatusCode.OK, ndjson) }
        assertEquals(listOf(CoachStreamEvent.Delta("a"), CoachStreamEvent.Complete(RESPONSE_ID, true)), whole.collect())

        val truncated = api { respond("""{"type":"delta","text":"a"}""" + "\n" + """{"type":"compl""", HttpStatusCode.OK, ndjson) }
        assertEquals(listOf(CoachStreamEvent.Delta("a")), truncated.collect())
    }

    @Test
    fun unknownEventTypesAndBlankLinesAreIgnored() = runTest {
        val api = api {
            respond(
                "{\"type\":\"start\",\"clientMessageId\":\"$CLIENT_ID\",\"responseId\":\"$RESPONSE_ID\"}\r\n\n" +
                    "{\"type\":\"usage\",\"tokens\":12}\n{\"type\":\"delta\",\"text\":\"x\"}\n",
                HttpStatusCode.OK, ndjson,
            )
        }
        assertEquals(listOf(CoachStreamEvent.Start(CLIENT_ID, RESPONSE_ID), CoachStreamEvent.Delta("x")), api.collect())
    }

    @Test
    fun aStreamErrorEventIsDeliveredAsAnEvent() = runTest {
        val api = api {
            respond(
                "{\"type\":\"start\",\"clientMessageId\":\"$CLIENT_ID\",\"responseId\":\"$RESPONSE_ID\"}\n" +
                    "{\"type\":\"delta\",\"text\":\"parcial\"}\n{\"type\":\"error\",\"code\":\"AI_TIMEOUT\",\"retryable\":true}\n",
                HttpStatusCode.OK, ndjson,
            )
        }
        assertEquals(CoachStreamEvent.Error("AI_TIMEOUT", true), api.collect().last())
    }

    @Test
    fun anInvalidCompleteLineIsMalformed() = runTest {
        val api = api { respond("not json\n", HttpStatusCode.OK, ndjson) }
        try {
            api.collect()
            fail("expected Malformed")
        } catch (e: SyncApiException.Malformed) {
            assertFalse("no content in the message", e.message.orEmpty().contains("not json"))
        }
    }

    @Test
    fun aConnectionCutMidStreamIsATransportFailureAfterTheDeliveredEvents() = runTest {
        val events = mutableListOf<CoachStreamEvent>()
        val channel = ByteChannel(autoFlush = true)
        val api = api {
            channel.writeFully("{\"type\":\"delta\",\"text\":\"medio\"}\n{\"type\":\"del".encodeToByteArray())
            respond(channel as ByteReadChannel, HttpStatusCode.OK, ndjson)
        }
        try {
            api.coach(USER, request) { event ->
                events += event
                channel.close(IOException("reset")) // the connection drops once the first delta is in
            }
            fail("expected Transport")
        } catch (e: SyncApiException.Transport) {
            assertEquals(listOf(CoachStreamEvent.Delta("medio")), events)
        }
    }

    @Test
    fun proRequiredIsAnApiErrorNotASessionProblem() = runTest {
        val api = api {
            respond("""{"error":{"code":"PRO_REQUIRED","message":"An active Pro subscription is required","retryable":false}}""", HttpStatusCode.Forbidden, json)
        }
        try {
            api.collect()
            fail("expected Api")
        } catch (e: SyncApiException.Api) {
            assertEquals(403, e.httpStatus)
            assertEquals("PRO_REQUIRED", e.rawCode)
            assertFalse(e.retryable)
        }
    }

    @Test
    fun aPlain403StaysASessionFailure() = runTest {
        val api = api { respond("", HttpStatusCode.Forbidden) }
        try {
            api.collect()
            fail("expected SessionRejected")
        } catch (_: SyncApiException.SessionRejected) {
        }
    }

    @Test
    fun inProgressAndUnavailableAreRetryableApiErrors() = runTest {
        for ((status, code) in listOf(HttpStatusCode.Conflict to "AI_RESPONSE_IN_PROGRESS", HttpStatusCode.ServiceUnavailable to "AI_UNAVAILABLE")) {
            val api = api { respond("""{"error":{"code":"$code","message":"m","retryable":true}}""", status, json) }
            try {
                api.collect()
                fail("expected Api for $code")
            } catch (e: SyncApiException.Api) {
                assertEquals(code, e.rawCode)
                assertTrue(e.retryable)
            }
        }
    }

    @Test
    fun a401IsRetriedOnceWithARefreshedTokenBeforeStreaming() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val api = api(requests, bodies) {
            if (requests.size == 1) respond("", HttpStatusCode.Unauthorized)
            else respond("{\"type\":\"delta\",\"text\":\"ok\"}\n", HttpStatusCode.OK, ndjson)
        }
        assertEquals(listOf(CoachStreamEvent.Delta("ok")), api.collect())
        assertEquals(listOf("Bearer tok-1", "Bearer tok-2"), requests.map { it.headers[HttpHeaders.Authorization] })
        assertEquals(bodies[0], bodies[1])
    }

    @Test
    fun whatTheConsumerThrowsIsRethrownUnchanged() = runTest {
        val api = api { respond("{\"type\":\"delta\",\"text\":\"x\"}\n", HttpStatusCode.OK, ndjson) }
        val boom = IllegalStateException("persist failed")
        try {
            api.coach(USER, request) { throw boom }
            fail("expected the consumer's exception")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
    }

    @Test
    fun reportPostsTheCategoryAndOmitsAnAbsentContext() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val api = api(requests, bodies) { respond("""{"responseId":"$RESPONSE_ID","created":true}""", HttpStatusCode.Created, json) }

        val result = api.report(USER, RESPONSE_ID, AiReportCategory.MEDICAL, null)
        api.report(USER, RESPONSE_ID, AiReportCategory.OTHER, "Dice algo raro")

        assertTrue(result.created)
        assertEquals("https://example.test/api/v1/ai/responses/$RESPONSE_ID/report", requests.first().url.toString())
        assertEquals("""{"category":"medical"}""", bodies[0])
        assertEquals("""{"category":"other","context":"Dice algo raro"}""", bodies[1])
    }

    @Test
    fun anUnknownReportedResponseIsA404ApiError() = runTest {
        val api = api { respond("""{"error":{"code":"RESPONSE_NOT_FOUND","message":"m","retryable":false}}""", HttpStatusCode.NotFound, json) }
        try {
            api.report(USER, RESPONSE_ID, AiReportCategory.INACCURATE, null)
            fail("expected Api")
        } catch (e: SyncApiException.Api) {
            assertEquals("RESPONSE_NOT_FOUND", e.rawCode)
        }
    }

    private companion object {
        const val USER = "user_1"
        const val CLIENT_ID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff"
        const val RESPONSE_ID = "0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9"
    }
}
