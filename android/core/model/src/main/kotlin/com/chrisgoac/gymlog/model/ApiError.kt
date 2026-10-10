package com.chrisgoac.gymlog.model

import kotlinx.serialization.Serializable

// Mirrors ApiErrorBodySchema in lib/api/v1/contracts/common.ts and the error table in contracts/v1/README.md.

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail)

@Serializable
data class ApiErrorDetail(
    val code: String,
    val message: String,
    val retryable: Boolean,
    /** Field (or `retryAfterSeconds` / `mutationId`) to messages. */
    val details: Map<String, List<String>>? = null,
) {
    val knownCode: ApiErrorCode get() = ApiErrorCode.fromWire(code)
}

/** Error codes documented for contract v1. Codes added by a newer server map to [Unknown]; trust `retryable`, not the code, for retry policy. */
enum class ApiErrorCode(val wire: String) {
    INVALID_REQUEST("INVALID_REQUEST"),
    INVALID_JSON("INVALID_JSON"),
    INVALID_CURSOR("INVALID_CURSOR"),
    INVALID_REFERENCE("INVALID_REFERENCE"),
    FUTURE_TIMESTAMP("FUTURE_TIMESTAMP"),
    UNKNOWN_TABLE("UNKNOWN_TABLE"),
    AUTH_REQUIRED("AUTH_REQUIRED"),
    RECORD_ID_CONFLICT("RECORD_ID_CONFLICT"),
    MUTATION_IN_FLIGHT("MUTATION_IN_FLIGHT"),
    WRITE_RACE("WRITE_RACE"),
    PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE"),
    UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE"),
    RATE_LIMITED("RATE_LIMITED"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
    RETRY_LATER("RETRY_LATER"),
    Unknown(""),
    ;

    companion object {
        fun fromWire(code: String): ApiErrorCode =
            entries.firstOrNull { it != Unknown && it.wire == code } ?: Unknown
    }
}
