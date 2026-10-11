package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.ResponseJson
import com.chrisgoac.gymlog.model.account.AccountWire
import com.chrisgoac.gymlog.model.account.DeleteAccountRequestDto
import com.chrisgoac.gymlog.model.account.DeleteAccountResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import java.util.UUID

/**
 * `DELETE /api/v1/account`: deletes the caller's account on the server. `403 REAUTH_REQUIRED` (sign-in older
 * than 10 minutes) and `503 DELETION_UNAVAILABLE` are [SyncApiException.Api].
 */
interface AccountApi {
    /** [freshToken] mints a new session token first (needed right after a reverification). */
    suspend fun deleteAccount(expectedUserId: String, freshToken: Boolean = false): DeleteAccountResponseDto
}

class AccountApiImpl internal constructor(
    client: HttpClient,
    baseUrl: String,
    auth: AuthSessionProvider,
    serverClock: ServerClock,
    requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : AccountApi {
    private val root = baseUrl.trimEnd('/')
    private val calls = AuthorizedCalls(client, auth, serverClock, requestIdFactory)

    override suspend fun deleteAccount(expectedUserId: String, freshToken: Boolean): DeleteAccountResponseDto {
        val body = ContractJson.encodeToString(DeleteAccountRequestDto.serializer(), DeleteAccountRequestDto(AccountWire.CONFIRMATION))
        return calls.execute(
            expectedUserId,
            configure = {
                method = HttpMethod.Delete
                url("$root/api/v1/account")
                setBody(TextContent(body, ContentType.Application.Json))
            },
            decode = { ResponseJson.decodeFromString(DeleteAccountResponseDto.serializer(), it) },
            freshToken = freshToken,
        )
    }
}

/** Production factory (OkHttp engine); share the app's [serverClock]. */
fun createAccountApi(baseUrl: String, auth: AuthSessionProvider, serverClock: ServerClock): AccountApi =
    AccountApiImpl(createGymLogHttpClient(), baseUrl, auth, serverClock)
