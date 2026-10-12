package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.ResponseJson
import com.chrisgoac.gymlog.model.billing.EntitlementResponseDto
import com.chrisgoac.gymlog.model.billing.VerifyPurchaseRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import java.util.UUID

/**
 * The billing endpoints (`/api/v1/billing/verify`, `/api/v1/billing/entitlement`). Every call names the
 * account it is made for. Route errors (409 `PURCHASE_PENDING`/`PURCHASE_ALREADY_BOUND`, 403
 * `PURCHASE_ACCOUNT_MISMATCH`, 422 `PURCHASE_INVALID`, 503 `BILLING_UNAVAILABLE`…) are
 * [SyncApiException.Api]. The purchase token is never logged.
 */
interface BillingApi {
    /** Server verification with Google; the answer is the caller's best entitlement afterwards. */
    suspend fun verify(expectedUserId: String, productId: String, purchaseToken: String): EntitlementResponseDto

    /** The caller's server-verified entitlement (never asks Google). */
    suspend fun entitlement(expectedUserId: String): EntitlementResponseDto
}

class BillingApiImpl internal constructor(
    client: HttpClient,
    baseUrl: String,
    auth: AuthSessionProvider,
    serverClock: ServerClock,
    requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : BillingApi {
    private val root = baseUrl.trimEnd('/')
    private val calls = AuthorizedCalls(client, auth, serverClock, requestIdFactory)

    override suspend fun verify(expectedUserId: String, productId: String, purchaseToken: String): EntitlementResponseDto {
        val body = ContractJson.encodeToString(VerifyPurchaseRequestDto.serializer(), VerifyPurchaseRequestDto(productId, purchaseToken))
        return calls.execute(
            expectedUserId,
            configure = {
                method = HttpMethod.Post
                url("$root/api/v1/billing/verify")
                setBody(TextContent(body, ContentType.Application.Json))
            },
            decode = ::decode,
        )
    }

    override suspend fun entitlement(expectedUserId: String): EntitlementResponseDto =
        calls.execute(
            expectedUserId,
            configure = {
                method = HttpMethod.Get
                url("$root/api/v1/billing/entitlement")
            },
            decode = ::decode,
        )

    private fun decode(text: String): EntitlementResponseDto = ResponseJson.decodeFromString(EntitlementResponseDto.serializer(), text)
}

/** Production factory (OkHttp engine); share the app's [serverClock]. */
fun createBillingApi(baseUrl: String, auth: AuthSessionProvider, serverClock: ServerClock): BillingApi =
    BillingApiImpl(createGymLogHttpClient(), baseUrl, auth, serverClock)
