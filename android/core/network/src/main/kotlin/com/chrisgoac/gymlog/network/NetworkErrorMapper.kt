package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ApiErrorBody
import com.chrisgoac.gymlog.model.ApiErrorCode
import com.chrisgoac.gymlog.model.ResponseJson

/** Maps a non-2xx response to the [SyncApiException] hierarchy. Pure, no I/O. */
object NetworkErrorMapper {
    fun map(status: Int, retryAfterHeader: String?, body: String, requestId: String? = null): SyncApiException {
        if (status == 401 || status == 403) return SyncApiException.SessionRequired("HTTP $status")
        val detail = runCatching { ResponseJson.decodeFromString<ApiErrorBody>(body).error }.getOrNull()
            ?: return SyncApiException.Api(
                httpStatus = status,
                code = ApiErrorCode.Unknown,
                rawCode = "",
                retryable = status == 429 || status >= 500,
                retryAfterSeconds = parseRetryAfter(retryAfterHeader),
                mutationId = null,
                details = null,
                message = "Unparseable error body",
                requestId = requestId,
            )
        val retryAfter = parseRetryAfter(retryAfterHeader)
            ?: detail.details?.get("retryAfterSeconds")?.firstOrNull()?.let(::parseRetryAfter)
        return SyncApiException.Api(
            httpStatus = status,
            code = detail.knownCode,
            rawCode = detail.code,
            retryable = detail.retryable,
            retryAfterSeconds = retryAfter,
            mutationId = detail.details?.get("mutationId")?.firstOrNull(),
            details = detail.details,
            message = detail.message,
            requestId = requestId,
        )
    }

    private fun parseRetryAfter(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it > 0 }
}
