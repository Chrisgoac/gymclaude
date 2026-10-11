package com.chrisgoac.gymlog.network

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Bearer calls bound to one account, shared by every v1 API client: the token is requested for
 * `expectedUserId` only, a 401 is retried once with a refreshed token, failures map to
 * [SyncApiException], and every response `Date` feeds the [ServerClock].
 */
internal class AuthorizedCalls(
    private val client: HttpClient,
    private val auth: AuthSessionProvider,
    private val serverClock: ServerClock,
    private val requestIdFactory: () -> String,
) {
    /**
     * A 401 is retried once with a force-refreshed token (the cached one may have just expired).
     * A 401/403 that persists is [SyncApiException.SessionRejected] while the identity provider still
     * holds an active session for [expectedUserId], otherwise [SyncApiException.SessionRequired].
     */
    suspend fun <T> execute(
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
