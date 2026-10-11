package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.account.AccountWire
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
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
     * [freshToken] skips the token cache from the first attempt (e.g. right after a reverification, whose
     * new factor age only a newly minted token carries).
     */
    suspend fun <T> execute(
        expectedUserId: String,
        configure: HttpRequestBuilder.() -> Unit,
        decode: (String) -> T,
        freshToken: Boolean = false,
    ): T {
        var sent = send(expectedUserId, forceRefresh = freshToken, configure)
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
        // 401/403 → session problem, except a 403 with a route-level code (e.g. PRO_REQUIRED), as in [stream].
        if (response.status.value !in 200..299) throw failure(expectedUserId, response, text, sent.requestId)
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
        val token = token(expectedUserId, forceRefresh)
        val requestId = requestIdFactory()
        val response = try {
            client.request {
                configure()
                authorize(token, requestId, ContentType.Application.Json.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncApiException.Transport(e)
        }
        observeDate(response)
        return Sent(response, requestId)
    }

    /**
     * Like [execute] but the 2xx body is handed to [consume] while it streams (never buffered whole). A 401
     * before any body is retried once with a refreshed token; any other non-2xx is read and mapped as in
     * [execute], except that a 403 whose body carries a route-level code (e.g. `PRO_REQUIRED`) is an
     * [SyncApiException.Api], not a session problem. Failures while reading are [SyncApiException.Transport];
     * whatever [consume]'s own callbacks throw is rethrown unchanged.
     */
    suspend fun <T> stream(
        expectedUserId: String,
        accept: String,
        configure: HttpRequestBuilder.() -> Unit,
        consume: suspend (HttpResponse) -> T,
    ): T {
        var forceRefresh = false
        while (true) {
            val token = token(expectedUserId, forceRefresh)
            val requestId = requestIdFactory()
            val outcome: StreamOutcome<T> = try {
                client.prepareRequest {
                    configure()
                    authorize(token, requestId, accept)
                }.execute { response ->
                    observeDate(response)
                    val status = response.status.value
                    when {
                        status == HTTP_UNAUTHORIZED && !forceRefresh -> StreamOutcome.RetryWithFreshToken
                        status !in 200..299 -> throw failure(expectedUserId, response, response.bodyAsText(), requestId)
                        else -> StreamOutcome.Done(consume(response))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownstreamFailure) {
                throw e.cause
            } catch (e: SyncApiException) {
                throw e
            } catch (e: Exception) {
                throw SyncApiException.Transport(e)
            }
            when (outcome) {
                is StreamOutcome.Done -> return outcome.value
                StreamOutcome.RetryWithFreshToken -> forceRefresh = true
            }
        }
    }

    /** Wraps what a stream consumer's own callback threw, so it is not mistaken for a transport failure. */
    class DownstreamFailure(override val cause: Throwable) : Exception(cause)

    private sealed interface StreamOutcome<out T> {
        data object RetryWithFreshToken : StreamOutcome<Nothing>
        class Done<T>(val value: T) : StreamOutcome<T>
    }

    private fun failure(expectedUserId: String, response: HttpResponse, text: String, requestId: String): SyncApiException {
        val status = response.status.value
        val retryAfter = response.headers[HttpHeaders.RetryAfter]
        val reqId = response.headers["x-request-id"] ?: requestId
        if (status == HTTP_FORBIDDEN) {
            val routeError = NetworkErrorMapper.mapApiError(status, retryAfter, text, reqId)
            if (routeError.rawCode == AccountWire.ACCOUNT_DELETING) auth.onAccountDeleting(expectedUserId)
            if (routeError.rawCode in ROUTE_FORBIDDEN_CODES) return routeError
        }
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            return if (auth.state.value == AuthState.SignedIn(expectedUserId)) {
                SyncApiException.SessionRejected("HTTP $status with an active session")
            } else {
                SyncApiException.SessionRequired("HTTP $status")
            }
        }
        return NetworkErrorMapper.map(status, retryAfter, text, reqId)
    }

    private suspend fun token(expectedUserId: String, forceRefresh: Boolean): String = try {
        auth.accessToken(expectedUserId, forceRefresh)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw tokenUnavailable(expectedUserId, "Access token unavailable: ${e.message}", e)
    } ?: throw tokenUnavailable(expectedUserId, "No access token", null)

    private fun HttpRequestBuilder.authorize(token: String, requestId: String, accept: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header("x-request-id", requestId)
        header(HttpHeaders.Accept, accept)
    }

    private fun observeDate(response: HttpResponse) {
        response.headers[HttpHeaders.Date]
            ?.let { runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            ?.let(serverClock::observe)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403

        /**
         * 403 codes that are a route's own answer (entitlement, purchase owner, recent sign-in needed, account
         * being deleted), not an authentication failure.
         */
        val ROUTE_FORBIDDEN_CODES = setOf("PRO_REQUIRED", "PURCHASE_ACCOUNT_MISMATCH", AccountWire.REAUTH_REQUIRED, AccountWire.ACCOUNT_DELETING)
    }
}
