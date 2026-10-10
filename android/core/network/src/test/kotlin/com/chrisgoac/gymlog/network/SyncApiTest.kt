package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ApiErrorCode
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.PushRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncApiTest {
    private val fixtures = File(System.getProperty("gymlog.fixturesDir")!!)
    private fun fixture(name: String) = File(fixtures, name).readText()

    private class FakeAuth(private val token: String?) : AuthSessionProvider {
        override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedIn("u1"))
        override suspend fun accessToken() = token
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class Fixture(val api: SyncApiImpl, val requests: MutableList<HttpRequestData>, val clock: ServerClock)

    private fun setup(
        token: String? = "tok-123",
        clock: ServerClock = ServerClock(),
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Fixture {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        val api = SyncApiImpl(
            client = createGymLogHttpClient(engine),
            baseUrl = "https://example.test/",
            auth = FakeAuth(token),
            serverClock = clock,
            requestIdFactory = { "req-00000001" },
        )
        return Fixture(api, requests, clock)
    }

    private fun errorBody(code: String, retryable: Boolean, details: String = "") =
        """{"error":{"code":"$code","message":"m","retryable":$retryable$details}}"""

    @Test
    fun pushSendsBearerRequestIdAndFixtureJson() = runTest {
        val request = ContractJson.decodeFromString(PushRequest.serializer(), fixture("push-valid.json"))
        val f = setup { respond("""{"acceptedMutationIds":[],"conflicts":[]}""", HttpStatusCode.OK, jsonHeaders) }
        val response = f.api.push(request)
        assertTrue(response.acceptedMutationIds.isEmpty())
        val sent = f.requests.single()
        assertEquals("Bearer tok-123", sent.headers[HttpHeaders.Authorization])
        assertEquals("req-00000001", sent.headers["x-request-id"])
        assertEquals("https://example.test/api/v1/sync/push", sent.url.toString())
        assertTrue(sent.body.contentType.toString().startsWith("application/json"))
        val sentJson = Json.parseToJsonElement((sent.body as io.ktor.http.content.TextContent).text)
        assertEquals(Json.parseToJsonElement(fixture("push-valid.json")), sentJson)
    }

    @Test
    fun pullOmitsCursorWhenNullAndPagesWithHasMore() = runTest {
        val page = fixture("pull-page.json")
        val last = """{"changes":[],"cursor":"c2","hasMore":false}"""
        var n = 0
        val f = setup { respond(if (n++ == 0) page else last, HttpStatusCode.OK, jsonHeaders) }
        val first = f.api.pull(null)
        assertTrue(first.hasMore)
        val second = f.api.pull(first.cursor, limit = 100)
        assertFalse(second.hasMore)
        val u1 = f.requests[0].url
        assertFalse(u1.parameters.contains("cursor"))
        assertEquals("500", u1.parameters["limit"])
        val u2 = f.requests[1].url
        assertEquals(first.cursor, u2.parameters["cursor"])
        assertEquals("100", u2.parameters["limit"])
    }

    @Test
    fun noTokenShortCircuitsWithoutRequest() = runTest {
        val f = setup(token = null) { error("must not be called") }
        val e = runCatching { f.api.pull(null) }.exceptionOrNull()
        assertTrue(e is SyncApiException.SessionRequired)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun status401And403MapToSessionRequired() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val f = setup { respond(errorBody("AUTH_REQUIRED", false), status, jsonHeaders) }
            assertTrue(runCatching { f.api.pull(null) }.exceptionOrNull() is SyncApiException.SessionRequired)
        }
    }

    @Test
    fun rateLimitedCarriesRetryAfterHeaderFirst() = runTest {
        val f = setup {
            respond(
                errorBody("RATE_LIMITED", true, ""","details":{"retryAfterSeconds":["9"]}"""),
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("30")),
            )
        }
        val e = runCatching { f.api.pull(null) }.exceptionOrNull() as SyncApiException.Api
        assertEquals(429, e.httpStatus)
        assertEquals(ApiErrorCode.RATE_LIMITED, e.code)
        assertTrue(e.retryable)
        assertEquals(30L, e.retryAfterSeconds)
        assertEquals(1, f.requests.size) // never auto-retries
    }

    @Test
    fun retryAfterFallsBackToDetails() = runTest {
        val f = setup {
            respond(
                errorBody("RATE_LIMITED", true, ""","details":{"retryAfterSeconds":["7"]}"""),
                HttpStatusCode.TooManyRequests,
                jsonHeaders,
            )
        }
        val e = runCatching { f.api.pull(null) }.exceptionOrNull() as SyncApiException.Api
        assertEquals(7L, e.retryAfterSeconds)
    }

    @Test
    fun poisonMutationErrorExposesMutationId() = runTest {
        val f = setup {
            respond(
                errorBody("FUTURE_TIMESTAMP", false, ""","details":{"mutationId":["m-1"]}"""),
                HttpStatusCode.BadRequest,
                jsonHeaders,
            )
        }
        val e = runCatching { f.api.pull(null) }.exceptionOrNull() as SyncApiException.Api
        assertEquals("m-1", e.mutationId)
        assertEquals(ApiErrorCode.FUTURE_TIMESTAMP, e.code)
        assertFalse(e.retryable)
        assertNull(e.retryAfterSeconds)
    }

    @Test
    fun unknownCodeKeepsRawCodeAndServerRetryable() = runTest {
        val f = setup { respond(errorBody("NEW_THING", true), HttpStatusCode.ServiceUnavailable, jsonHeaders) }
        val e = runCatching { f.api.pull(null) }.exceptionOrNull() as SyncApiException.Api
        assertEquals(ApiErrorCode.Unknown, e.code)
        assertEquals("NEW_THING", e.rawCode)
        assertTrue(e.retryable)
    }

    @Test
    fun nonJsonErrorBodyStillMapsToApi() = runTest {
        val f = setup { respond("<html>bad gateway</html>", HttpStatusCode.BadGateway) }
        val e = runCatching { f.api.pull(null) }.exceptionOrNull() as SyncApiException.Api
        assertEquals(502, e.httpStatus)
        assertTrue(e.retryable)
    }

    @Test
    fun malformedSuccessBodyIsMalformed() = runTest {
        val f = setup { respond("{not json", HttpStatusCode.OK, jsonHeaders) }
        assertTrue(runCatching { f.api.pull(null) }.exceptionOrNull() is SyncApiException.Malformed)
        val g = setup { respond("""{"changes":"x"}""", HttpStatusCode.OK, jsonHeaders) }
        assertTrue(runCatching { g.api.pull(null) }.exceptionOrNull() is SyncApiException.Malformed)
    }

    @Test
    fun ioFailureIsTransport() = runTest {
        val f = setup { throw IOException("boom") }
        val e = runCatching { f.api.pull(null) }.exceptionOrNull()
        assertTrue(e is SyncApiException.Transport)
        assertTrue((e as SyncApiException.Transport).retryable)
    }

    @Test
    fun dateHeaderFeedsServerClock() = runTest {
        var device = 1_000_000_000_000L
        val clock = ServerClock { device }
        val f = setup(clock = clock) {
            respond(
                """{"changes":[],"cursor":"c","hasMore":false}""",
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.Date to listOf("Mon, 01 Jan 2001 00:00:00 GMT"),
                ),
            )
        }
        f.api.pull(null)
        assertEquals(978_307_200_000L - device, clock.offsetMillis)
        device += 5
        assertEquals(978_307_200_005L, clock.nowMillis())
    }

    @Test
    fun serverClockDefaultsToZeroOffsetAndIgnoresMissingDate() = runTest {
        val clock = ServerClock { 42L }
        assertEquals(0L, clock.offsetMillis)
        assertEquals(42L, clock.nowMillis())
        val f = setup(clock = clock) { respond("""{"changes":[],"cursor":"c","hasMore":false}""", HttpStatusCode.OK, jsonHeaders) }
        f.api.pull(null)
        assertEquals(0L, clock.offsetMillis)
    }

}
