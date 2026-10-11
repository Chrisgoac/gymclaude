package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.PullResponse
import com.chrisgoac.gymlog.model.PushRequest
import com.chrisgoac.gymlog.model.PushResponse
import com.chrisgoac.gymlog.model.ResponseJson
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import java.util.UUID

/**
 * The sync endpoints. Every call names the account it is made for: the request is sent only with a
 * token of that account's session, so a run for one account can never carry another account's token.
 */
interface SyncApi {
    suspend fun push(expectedUserId: String, request: PushRequest): PushResponse
    suspend fun pull(expectedUserId: String, cursor: String?, limit: Int = 500): PullResponse
}

class SyncApiImpl internal constructor(
    client: HttpClient,
    baseUrl: String,
    auth: AuthSessionProvider,
    serverClock: ServerClock,
    requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : SyncApi {
    private val root = baseUrl.trimEnd('/')
    private val calls = AuthorizedCalls(client, auth, serverClock, requestIdFactory)

    override suspend fun push(expectedUserId: String, request: PushRequest): PushResponse {
        val body = ContractJson.encodeToString(PushRequest.serializer(), request)
        return calls.execute(
            expectedUserId,
            configure = {
                method = HttpMethod.Post
                url("$root/api/v1/sync/push")
                setBody(TextContent(body, ContentType.Application.Json))
            },
            decode = { ResponseJson.decodeFromString(PushResponse.serializer(), it) },
        )
    }

    override suspend fun pull(expectedUserId: String, cursor: String?, limit: Int): PullResponse = calls.execute(
        expectedUserId,
        configure = {
            method = HttpMethod.Get
            url("$root/api/v1/sync/pull")
            if (cursor != null) parameter("cursor", cursor)
            parameter("limit", limit)
        },
        decode = { ResponseJson.decodeFromString(PullResponse.serializer(), it) },
    )

}

/**
 * Production factory (OkHttp engine). Pass the same [serverClock] the rest of the app uses to stamp
 * updatedAt, so the offset learned from response `Date` headers is shared.
 */
fun createSyncApi(baseUrl: String, auth: AuthSessionProvider, serverClock: ServerClock): SyncApi =
    SyncApiImpl(createGymLogHttpClient(), baseUrl, auth, serverClock)
