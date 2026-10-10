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

interface SyncApi {
    suspend fun push(request: PushRequest): PushResponse
    suspend fun pull(cursor: String?, limit: Int = 500): PullResponse
}

class SyncApiImpl internal constructor(
    private val client: HttpClient,
    baseUrl: String,
    private val auth: AuthSessionProvider,
    private val serverClock: ServerClock,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : SyncApi {
    private val root = baseUrl.trimEnd('/')

    override suspend fun push(request: PushRequest): PushResponse {
        val body = ContractJson.encodeToString(PushRequest.serializer(), request)
        return execute(
            configure = {
                method = HttpMethod.Post
                url("$root/api/v1/sync/push")
                setBody(TextContent(body, ContentType.Application.Json))
            },
            decode = { ResponseJson.decodeFromString(PushResponse.serializer(), it) },
        )
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResponse = execute(
        configure = {
            method = HttpMethod.Get
            url("$root/api/v1/sync/pull")
            if (cursor != null) parameter("cursor", cursor)
            parameter("limit", limit)
        },
        decode = { ResponseJson.decodeFromString(PullResponse.serializer(), it) },
    )

    private suspend fun <T> execute(
        configure: HttpRequestBuilder.() -> Unit,
        decode: (String) -> T,
    ): T {
        val token = try {
            auth.accessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncApiException.SessionRequired("Access token unavailable: ${e.message}")
        } ?: throw SyncApiException.SessionRequired("No access token")
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
        response.headers[HttpHeaders.Date]?.let { runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            ?.let(serverClock::observe)
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncApiException.Transport(e)
        }
        val status = response.status.value
        if (status !in 200..299) {
            throw NetworkErrorMapper.map(
                status,
                response.headers[HttpHeaders.RetryAfter],
                text,
                response.headers["x-request-id"] ?: requestId,
            )
        }
        return try {
            decode(text)
        } catch (e: SerializationException) {
            throw SyncApiException.Malformed("Undecodable response body", e)
        }
    }
}

/**
 * Production factory (OkHttp engine). Pass the same [serverClock] the rest of the app uses to stamp
 * updatedAt, so the offset learned from response `Date` headers is shared.
 */
fun createSyncApi(baseUrl: String, auth: AuthSessionProvider, serverClock: ServerClock): SyncApi =
    SyncApiImpl(createGymLogHttpClient(), baseUrl, auth, serverClock)
