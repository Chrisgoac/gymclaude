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

    /** Hands out [token] (or [refreshedToken] on a forced refresh) only for [owner]'s session. */
    private class FakeAuth(
        private val token: String?,
        private val refreshedToken: String? = token,
        val owner: String = USER,
        state: AuthState = AuthState.SignedIn(owner),
    ) : AuthSessionProvider {
        override val state = MutableStateFlow(state)
        val calls = mutableListOf<Pair<String, Boolean>>()
        override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String? {
            calls += expectedUserId to forceRefresh
            if (expectedUserId != owner || state.value != AuthState.SignedIn(owner)) return null
            return if (forceRefresh) refreshedToken else token
        }
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class Fixture(val api: SyncApiImpl, val requests: MutableList<HttpRequestData>, val clock: ServerClock, val auth: FakeAuth)

    private fun setup(
        token: String? = "tok-123",
        clock: ServerClock = ServerClock(),
        auth: FakeAuth = FakeAuth(token),
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
            auth = auth,
            serverClock = clock,
            requestIdFactory = { "req-00000001" },
        )
        return Fixture(api, requests, clock, auth)
    }

    private fun errorBody(code: String, retryable: Boolean, details: String = "") =
        """{"error":{"code":"$code","message":"m","retryable":$retryable$details}}"""

    @Test
    fun pushSendsBearerRequestIdAndFixtureJson() = runTest {
        val request = ContractJson.decodeFromString(PushRequest.serializer(), fixture("push-valid.json"))
        val f = setup { respond("""{"acceptedMutationIds":[],"conflicts":[]}""", HttpStatusCode.OK, jsonHeaders) }
        val response = f.api.push(USER, request)
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
        val first = f.api.pull(USER, null)
        assertTrue(first.hasMore)
        val second = f.api.pull(USER, first.cursor, limit = 100)
        assertFalse(second.hasMore)
        f.requests.forEach { assertEquals("Bearer tok-123", it.headers[HttpHeaders.Authorization]) }
        val u1 = f.requests[0].url
        assertFalse(u1.parameters.contains("cursor"))
        assertEquals("500", u1.parameters["limit"])
        val u2 = f.requests[1].url
        assertEquals(first.cursor, u2.parameters["cursor"])
        assertEquals("100", u2.parameters["limit"])
    }

    @Test
    fun noTokenWithoutAnActiveSessionShortCircuitsAsSessionRequired() = runTest {
        val f = setup(auth = FakeAuth("tok", state = AuthState.SessionExpired(USER))) { error("must not be called") }
        val e = runCatching { f.api.pull(USER, null) }.exceptionOrNull()
        assertTrue(e is SyncApiException.SessionRequired)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun noTokenWhileSignedInIsATransientFailure() = runTest {
        val f = setup(token = null) { error("must not be called") }
        val e = runCatching { f.api.pull(USER, null) }.exceptionOrNull()
        assertTrue("got $e", e is SyncApiException.Transport)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun throwingTokenProviderWhileSignedInIsATransientFailure() = runTest {
        val auth = object : AuthSessionProvider {
            override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedIn(USER))
            override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String = throw IllegalStateException("clerk down")
        }
        val api = SyncApiImpl(createGymLogHttpClient(MockEngine { error("must not be called") }), "https://example.test", auth, ServerClock())
        assertTrue(runCatching { api.pull(USER, null) }.exceptionOrNull() is SyncApiException.Transport)
    }

    @Test
    fun throwingTokenProviderMapsToSessionRequired() = runTest {
        val engine = MockEngine { error("must not be called") }
        val auth = object : AuthSessionProvider {
            override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.Loading)
            override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String = throw IllegalStateException("clerk down")
        }
        val api = SyncApiImpl(createGymLogHttpClient(engine), "https://example.test", auth, ServerClock())
        assertTrue(runCatching { api.pull(USER, null) }.exceptionOrNull() is SyncApiException.SessionRequired)
    }

    @Test
    fun status401And403WithoutAnActiveSessionMapToSessionRequired() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val auth = FakeAuth("tok-123")
            val f = setup(auth = auth) {
                auth.state.value = AuthState.SessionExpired(USER) // the session ended while the request was in flight
                respond(errorBody("AUTH_REQUIRED", false), status, jsonHeaders)
            }
            assertTrue(runCatching { f.api.pull(USER, null) }.exceptionOrNull() is SyncApiException.SessionRequired)
        }
    }

    @Test
    fun a401IsRetriedOnceWithAForceRefreshedToken() = runTest {
        val f = setup(auth = FakeAuth("stale", refreshedToken = "fresh")) { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer stale") {
                respond(errorBody("AUTH_REQUIRED", false), HttpStatusCode.Unauthorized, jsonHeaders)
            } else {
                respond("""{"changes":[],"cursor":"c","hasMore":false}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val page = f.api.pull(USER, null)
        assertEquals("c", page.cursor)
        assertEquals(listOf("Bearer stale", "Bearer fresh"), f.requests.map { it.headers[HttpHeaders.Authorization] })
        assertEquals(listOf(USER to false, USER to true), f.auth.calls)
    }

    @Test
    fun a401ThatPersistsWithAnActiveSessionIsSessionRejected() = runTest {
        val f = setup(auth = FakeAuth("stale", refreshedToken = "fresh")) {
            respond(errorBody("AUTH_REQUIRED", false), HttpStatusCode.Unauthorized, jsonHeaders)
        }
        val e = runCatching { f.api.pull(USER, null) }.exceptionOrNull()
        assertTrue("got $e", e is SyncApiException.SessionRejected)
        assertEquals("exactly one retry", 2, f.requests.size)
    }

    @Test
    fun a403IsNotRetried() = runTest {
        val f = setup { respond(errorBody("FORBIDDEN", false), HttpStatusCode.Forbidden, jsonHeaders) }
        assertTrue(runCatching { f.api.pull(USER, null) }.exceptionOrNull() is SyncApiException.SessionRejected)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun aCallForAnotherAccountNeverSendsTheActiveAccountsToken() = runTest {
        val f = setup(auth = FakeAuth("tok-b", owner = "user_b")) { error("must not be called") }
        val e = runCatching { f.api.pull(USER, null) }.exceptionOrNull()
        assertTrue(e is SyncApiException.SessionRequired)
        assertTrue(f.requests.isEmpty())
        assertEquals(listOf(USER to false), f.auth.calls)
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
        val e = runCatching { f.api.pull(USER, null) }.exceptionOrNull() as SyncApiException.Api
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
        val e = runCatching { f.api.pull(USER, null) }.exceptionOrNull() as SyncApiException.Api
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
        val e = runCatching { f.api.pull(USER, null) }.exceptionOrNull() as SyncApiException.Api
        assertEquals("m-1", e.mutationId)
        assertEquals(ApiErrorCode.FUTURE_TIMESTAMP, e.code)
        assertFalse(e.retryable)
        assertNull(e.retryAfterSeconds)
    }

    @Test
    fun unknownCodeKeepsRawCodeAndServerRetryable() = runTest {
        val f = setup { respond(errorBody("NEW_THING", true), HttpStatusCode.ServiceUnavailable, jsonHeaders) }
        val e = runCatching { f.api.pull(USER, null) }.exceptionOrNull() as SyncApiException.Api
        assertEquals(ApiErrorCode.Unknown, e.code)
        assertEquals("NEW_THING", e.rawCode)
        assertTrue(e.retryable)
    }

    @Test
    fun nonJsonErrorBodyStillMapsToApi() = runTest {
        val f = setup { respond("<html>bad gateway</html>", HttpStatusCode.BadGateway) }
        val e = runCatching { f.api.pull(USER, null) }.exceptionOrNull() as SyncApiException.Api
        assertEquals(502, e.httpStatus)
        assertTrue(e.retryable)
    }

    @Test
    fun malformedSuccessBodyIsMalformed() = runTest {
        val f = setup { respond("{not json", HttpStatusCode.OK, jsonHeaders) }
        assertTrue(runCatching { f.api.pull(USER, null) }.exceptionOrNull() is SyncApiException.Malformed)
        val g = setup { respond("""{"changes":"x"}""", HttpStatusCode.OK, jsonHeaders) }
        assertTrue(runCatching { g.api.pull(USER, null) }.exceptionOrNull() is SyncApiException.Malformed)
    }

    @Test
    fun ioFailureIsTransport() = runTest {
        val f = setup { throw IOException("boom") }
        val e = runCatching { f.api.pull(USER, null) }.exceptionOrNull()
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
        f.api.pull(USER, null)
        assertEquals(978_307_200_000L - device, clock.offsetMillis)
        device += 5
        assertEquals(978_307_200_005L, clock.nowMillis())
    }

    @Test
    fun seededOffsetAppliesBeforeAnyResponseAndNewMeasurementsAreReported() = runTest {
        val reported = mutableListOf<Long>()
        val clock = ServerClock(deviceNowMillis = { 1_000L }, initialOffsetMillis = 250L, onObserved = { reported += it })
        assertEquals(250L, clock.offsetMillis)
        assertEquals(1_250L, clock.nowMillis())
        clock.observe(serverMillis = 1_600L)
        assertEquals(600L, clock.offsetMillis)
        assertEquals(listOf(600L), reported)
    }

    @Test
    fun seedOffsetCompensatesAWallClockChangeInTheSameBootOnly() {
        val sample = ClockOffsetSample(offsetMillis = 1_000L, measuredAtWallMillis = 10_000L, measuredAtElapsedMillis = 500L, bootCount = 3)
        // 2 s of real time passed and the wall clock moved 2 s: nothing to compensate.
        assertEquals(1_000L, sample.seedOffset(nowWallMillis = 12_000L, nowElapsedMillis = 2_500L, currentBootCount = 3))
        // The user set the device clock 1 h back: the offset grows by the same hour.
        assertEquals(1_000L + 3_600_000L, sample.seedOffset(nowWallMillis = 12_000L - 3_600_000L, nowElapsedMillis = 2_500L, currentBootCount = 3))
        // After a reboot the monotonic anchor means nothing: the stored offset is used as is.
        assertEquals(1_000L, sample.seedOffset(nowWallMillis = 99_999L, nowElapsedMillis = 100L, currentBootCount = 4))
        assertEquals(1_000L, sample.copy(bootCount = null).seedOffset(12_000L - 3_600_000L, 2_500L, null))
    }

    @Test
    fun serverClockDefaultsToZeroOffsetAndIgnoresMissingDate() = runTest {
        val clock = ServerClock { 42L }
        assertEquals(0L, clock.offsetMillis)
        assertEquals(42L, clock.nowMillis())
        val f = setup(clock = clock) { respond("""{"changes":[],"cursor":"c","hasMore":false}""", HttpStatusCode.OK, jsonHeaders) }
        f.api.pull(USER, null)
        assertEquals(0L, clock.offsetMillis)
    }

    private companion object {
        const val USER = "u1"
    }
}
