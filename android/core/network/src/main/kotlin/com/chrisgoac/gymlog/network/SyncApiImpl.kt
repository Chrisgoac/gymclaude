package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.PullResponse
import com.chrisgoac.gymlog.model.PushRequest
import com.chrisgoac.gymlog.model.PushResponse
import com.chrisgoac.gymlog.model.ResponseJson
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * The sync endpoints. Every call names the account it is made for: the request is sent only with a
 * token of that account's session, so a run for one account can never carry another account's token.
 */
interface SyncApi {
    suspend fun push(expectedUserId: String, request: PushRequest): PushResponse
    suspend fun pull(expectedUserId: String, cursor: String?, limit: Int = 500): PullResponse
}

class SyncApiImpl internal constructor(
    private val client: HttpClient,
    baseUrl: String,
    private val auth: AuthSessionProvider,
    private val serverClock: ServerClock,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : SyncApi {
    private val root = baseUrl.trimEnd('/')

    override suspend fun push(expectedUserId: String, request: PushRequest): PushResponse {
        val body = ContractJson.encodeToString(PushRequest.serializer(), request)
        return execute(
            expectedUserId,
            configure = {
                method = HttpMethod.Post
                url("$root/api/v1/sync/push")
                setBody(TextContent(body, ContentType.Application.Json))
            },
            decode = { ResponseJson.decodeFromString(PushResponse.serializer(), it) },
        )
    }

    override suspend fun pull(expectedUserId: String, cursor: String?, limit: Int): PullResponse = execute(
        expectedUserId,
        configure = {
            method = HttpMethod.Get
            url("$root/api/v1/sync/pull")
            if (cursor != null) parameter("cursor", cursor)
            parameter("limit", limit)
        },
        decode = { ResponseJson.decodeFromString(PullResponse.serializer(), it) },
    )

    /**
     * A 401 is retried once with a force-refreshed token (the cached one may have just expired).
     * A 401/403 that persists is [SyncApiException.SessionRejected] while the identity provider still
     * holds an active session for [expectedUserId], otherwise [SyncApiException.SessionRequired].
     */
    private suspend fun <T> execute(
        expectedUserId: String,
        configure: HttpRequestBuilder.() -> Unit,
        decode: (String) -> T,
    ): T {
        var sent = send(expectedUserId, forceRefresh = false, configure)
        if (sent.response.status.value == HTTP_UNAUTHORIZED) {
            sent = send(expectedUserId, forceRefresh = true, configure)
        }
        val response = sent.response
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncApiException.Transport(e)
        }
        val status = response.status.value
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            throw if (auth.state.value == AuthState.SignedIn(expectedUserId)) {
                SyncApiException.SessionRejected("HTTP $status with an active session")
            } else {
                SyncApiException.SessionRequired("HTTP $status")
            }
        }
        if (status !in 200..299) {
            throw NetworkErrorMapper.map(
                status,
                response.headers[HttpHeaders.RetryAfter],
                text,
                response.headers["x-request-id"] ?: sent.requestId,
            )
        }
        return try {
            decode(text)
        } catch (e: SerializationException) {
            throw SyncApiException.Malformed("Undecodable response body", e)
        }
    }

    /**
     * No token could be obtained. While the identity provider still holds an active session for the
     * account this is a transient failure (refresh hiccup, offline), retried later like any
     * transport error; only an ended session or another account's session needs a new sign-in.
     */
    private fun tokenUnavailable(expectedUserId: String, message: String, cause: Throwable?): SyncApiException =
        if (auth.state.value == AuthState.SignedIn(expectedUserId)) {
            SyncApiException.Transport(cause ?: IllegalStateException(message))
        } else {
            SyncApiException.SessionRequired(message)
        }

    private class Sent(val response: HttpResponse, val requestId: String)

    private suspend fun send(expectedUserId: String, forceRefresh: Boolean, configure: HttpRequestBuilder.() -> Unit): Sent {
        val token = try {
            auth.accessToken(expectedUserId, forceRefresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw tokenUnavailable(expectedUserId, "Access token unavailable: ${e.message}", e)
        } ?: throw tokenUnavailable(expectedUserId, "No access token", null)
        val requestId = requestIdFactory()
        val response = try {
            client.request {
                configure()
                header(HttpHeaders.Authorization, "Bearer $token")
                header("x-request-id", requestId)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncApiException.Transport(e)
        }
        response.headers[HttpHeaders.Date]
            ?.let { runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            ?.let(serverClock::observe)
        return Sent(response, requestId)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/**
 * Production factory (OkHttp engine). Pass the same [serverClock] the rest of the app uses to stamp
 * updatedAt, so the offset learned from response `Date` headers is shared.
 */
fun createSyncApi(baseUrl: String, auth: AuthSessionProvider, serverClock: ServerClock): SyncApi =
    SyncApiImpl(createGymLogHttpClient(), baseUrl, auth, serverClock)
