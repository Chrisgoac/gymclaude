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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BillingApiTest {
    private class FakeAuth : AuthSessionProvider {
        override val state = MutableStateFlow<AuthState>(AuthState.SignedIn(USER))
        override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String? =
            if (expectedUserId != USER) null else "tok"
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(
        requests: MutableList<HttpRequestData> = mutableListOf(),
        bodies: MutableList<String> = mutableListOf(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = BillingApiImpl(
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

    @Test
    fun verifyPostsOnlyProductAndTokenAndDecodesTheEntitlement() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val api = api(requests, bodies) {
            respond(
                """{"tier":"PRO","productId":"gymlog_pro_annual","state":"ACTIVE","validUntil":1798761600000,"serverTime":1767225600000}""",
                HttpStatusCode.OK,
                json,
            )
        }

        val result = api.verify(USER, "gymlog_pro_annual", "tok.en-1_x")

        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("https://example.test/api/v1/billing/verify", requests.single().url.toString())
        assertEquals("Bearer tok", requests.single().headers[HttpHeaders.Authorization])
        assertEquals("""{"productId":"gymlog_pro_annual","purchaseToken":"tok.en-1_x"}""", bodies.single())
        assertEquals("PRO", result.tier)
        assertEquals(1798761600000L, result.validUntil)
        assertEquals(1767225600000L, result.serverTime)
    }

    @Test
    fun entitlementIsAGetAndAcceptsAFreeAnswer() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) {
            respond("""{"tier":"FREE","productId":null,"state":"NONE","validUntil":null,"serverTime":5}""", HttpStatusCode.OK, json)
        }

        val result = api.entitlement(USER)

        assertEquals(HttpMethod.Get, requests.single().method)
        assertEquals("https://example.test/api/v1/billing/entitlement", requests.single().url.toString())
        assertEquals("NONE", result.state)
        assertEquals(null, result.productId)
    }

    @Test
    fun accountMismatchIsARouteErrorNotASessionProblem() = runTest {
        val api = api {
            respond(
                """{"error":{"code":"PURCHASE_ACCOUNT_MISMATCH","message":"other account","retryable":false}}""",
                HttpStatusCode.Forbidden,
                json,
            )
        }
        try {
            api.verify(USER, "gymlog_pro_monthly", "t")
            fail("expected an API error")
        } catch (e: SyncApiException.Api) {
            assertEquals(403, e.httpStatus)
            assertEquals("PURCHASE_ACCOUNT_MISMATCH", e.rawCode)
        }
    }

    @Test
    fun pendingPurchaseIsARetryableConflict() = runTest {
        val api = api {
            respond(
                """{"error":{"code":"PURCHASE_PENDING","message":"pending","retryable":true}}""",
                HttpStatusCode.Conflict,
                json,
            )
        }
        try {
            api.verify(USER, "gymlog_pro_monthly", "t")
            fail("expected an API error")
        } catch (e: SyncApiException.Api) {
            assertEquals("PURCHASE_PENDING", e.rawCode)
            assertTrue(e.retryable)
        }
    }

    @Test
    fun theRequestDtoNeverPrintsTheToken() {
        val dto = com.chrisgoac.gymlog.model.billing.VerifyPurchaseRequestDto("gymlog_pro_monthly", "secret-token")
        assertTrue("secret-token" !in dto.toString())
    }

    private companion object {
        const val USER = "user_1"
    }
}
