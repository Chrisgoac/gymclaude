package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ApiErrorCode

sealed class SyncApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401/403, or no token available (no request was sent). */
    class SessionRequired(message: String = "Session required") : SyncApiException(message)

    class Api(
        val httpStatus: Int,
        val code: ApiErrorCode,
        val rawCode: String,
        val retryable: Boolean,
        val retryAfterSeconds: Long?,
        val mutationId: String?,
        val details: Map<String, List<String>>?,
        message: String,
        val requestId: String? = null,
    ) : SyncApiException("HTTP $httpStatus $rawCode: $message")

    /** IO failure or timeout; the request may or may not have reached the server. */
    class Transport(cause: Throwable) : SyncApiException("Transport failure: ${cause.message}", cause) {
        val retryable: Boolean get() = true
    }

    /** The response could not be decoded. */
    class Malformed(message: String, cause: Throwable? = null) : SyncApiException(message, cause)
}
