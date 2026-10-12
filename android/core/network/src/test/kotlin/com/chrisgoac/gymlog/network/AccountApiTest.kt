package com.chrisgoac.gymlog.network

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountApiTest {
    private class FakeAuth : AuthSessionProvider {
        override val state = MutableStateFlow<AuthState>(AuthState.SignedIn(USER))
        val refreshes = mutableListOf<Boolean>()
        val signals = mutableListOf<Pair<String, AccountSignal>>()
        override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String? {
            refreshes += forceRefresh
            return if (expectedUserId != USER) null else "tok"
        }
        override fun onAccountSignal(userId: String, signal: AccountSignal) { signals += userId to signal }
    }

    private val auth = FakeAuth()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun engine(
        requests: MutableList<HttpRequestData>,
        bodies: MutableList<String>,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = createGymLogHttpClient(
        MockEngine { request ->
            requests += request
            bodies += request.body.toByteArray().decodeToString()
            handler(request)
        },
    )

    private fun api(
        requests: MutableList<HttpRequestData> = mutableListOf(),
        bodies: MutableList<String> = mutableListOf(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = AccountApiImpl(engine(requests, bodies, handler), "https://example.test/", auth, ServerClock(), requestIdFactory = { "req-1" })

    @Test
    fun deleteSendsTheTypedConfirmationAndDecodesTheSubscriptionWarning() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val api = api(requests, bodies) {
            respond(
                """{"status":"deleted","step":"database_sweep","retryable":false,"finalCleanupPending":true,""" +
                    """"activeSubscription":{"productId":"gymlog_pro_annual","state":"ACTIVE","validUntil":1798761600000,""" +
                    """"willRenew":true,"manageUrl":"https://play.google.com/store/account/subscriptions?sku=gymlog_pro_annual&package=com.chrisgoac.gymlog"}}""",
                HttpStatusCode.OK,
                json,
            )
        }

        val result = api.deleteAccount(USER)

        assertEquals(HttpMethod.Delete, requests.single().method)
        assertEquals("https://example.test/api/v1/account", requests.single().url.toString())
        assertEquals("""{"confirm":"ELIMINAR"}""", bodies.single())
        assertEquals("deleted", result.status)
        assertTrue(result.finalCleanupPending)
        assertEquals(true, result.activeSubscription?.willRenew)
        assertEquals(1798761600000L, result.activeSubscription?.validUntil)
        assertEquals(listOf(false), auth.refreshes)
        assertTrue("a 2xx of the delete route is not an 'account active' signal", auth.signals.isEmpty())
    }

    @Test
    fun aFreshTokenIsMintedAfterReverification() = runTest {
        val api = api { respond("""{"status":"in_progress","step":"storage","retryable":true,"finalCleanupPending":false,"errorCode":"STORAGE_FAILED","activeSubscription":null}""", HttpStatusCode.Accepted, json) }

        val result = api.deleteAccount(USER, freshToken = true)

        assertEquals("in_progress", result.status)
        assertEquals("STORAGE_FAILED", result.errorCode)
        assertNull(result.activeSubscription)
        assertEquals(listOf(true), auth.refreshes)
    }

    @Test
    fun reauthRequiredIsARouteAnswerNotASessionFailure() = runTest {
        val api = api {
            respond("""{"error":{"code":"REAUTH_REQUIRED","message":"sign in again","retryable":false}}""", HttpStatusCode.Forbidden, json)
        }
        try {
            api.deleteAccount(USER)
            fail("expected an API error")
        } catch (e: SyncApiException.Api) {
            assertEquals(403, e.httpStatus)
            assertEquals("REAUTH_REQUIRED", e.rawCode)
        }
        assertTrue(auth.signals.isEmpty())
    }

    @Test
    fun accountDeletingFromAnyRouteIsReportedToTheSession() = runTest {
        val billing = BillingApiImpl(
            engine(mutableListOf(), mutableListOf()) {
                respond("""{"error":{"code":"ACCOUNT_DELETING","message":"locked","retryable":false}}""", HttpStatusCode.Forbidden, json)
            },
            "https://example.test/", auth, ServerClock(), requestIdFactory = { "req-1" },
        )
        try {
            billing.entitlement(USER)
            fail("expected an API error")
        } catch (e: SyncApiException.Api) {
            assertEquals("ACCOUNT_DELETING", e.rawCode)
            assertFalse(e.retryable)
        }
        assertEquals(listOf(USER to AccountSignal.DELETING), auth.signals)
    }

    @Test
    fun aSuccessfulOrdinaryCallReportsTheAccountActive() = runTest {
        val billing = BillingApiImpl(
            engine(mutableListOf(), mutableListOf()) {
                respond("""{"tier":"FREE","productId":null,"state":"NONE","validUntil":null,"serverTime":5}""", HttpStatusCode.OK, json)
            },
            "https://example.test/", auth, ServerClock(), requestIdFactory = { "req-1" },
        )
        billing.entitlement(USER)
        assertEquals(listOf(USER to AccountSignal.ACTIVE), auth.signals)
    }

    @Test
    fun aRejectedActiveSessionIsReported() = runTest {
        val billing = BillingApiImpl(
            engine(mutableListOf(), mutableListOf()) { respond("", HttpStatusCode.Unauthorized, json) },
            "https://example.test/", auth, ServerClock(), requestIdFactory = { "req-1" },
        )
        try {
            billing.entitlement(USER)
            fail("expected a session failure")
        } catch (_: SyncApiException.SessionRejected) {
        }
        assertEquals(listOf(USER to AccountSignal.SESSION_REJECTED), auth.signals)
    }

    private companion object {
        const val USER = "user_1"
    }
}
