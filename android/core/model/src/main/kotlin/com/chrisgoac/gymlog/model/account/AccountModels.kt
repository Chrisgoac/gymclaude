package com.chrisgoac.gymlog.model.account

import kotlinx.serialization.Serializable

/** Wire contract of `DELETE /api/v1/account` (contracts/v1/README.md, "Account deletion"). */
object AccountWire {
    /** The only accepted body: `{ "confirm": "ELIMINAR" }`. */
    const val CONFIRMATION = "ELIMINAR"

    /** 403 on the delete route: the session's first factor is older than 10 minutes. */
    const val REAUTH_REQUIRED = "REAUTH_REQUIRED"

    /** 403 on every other v1 route once the account's deletion has started. */
    const val ACCOUNT_DELETING = "ACCOUNT_DELETING"

    const val STATUS_DELETED = "deleted"
    const val STATUS_IN_PROGRESS = "in_progress"
}

@Serializable
data class DeleteAccountRequestDto(val confirm: String)

/**
 * 200 (`status: "deleted"`) or 202 (`status: "in_progress"`, the account is locked and the deletion resumes
 * on the server). [activeSubscription] is a Google Play subscription still live: the server does not cancel it.
 */
@Serializable
data class DeleteAccountResponseDto(
    val status: String,
    val step: String? = null,
    val retryable: Boolean = false,
    val finalCleanupPending: Boolean = false,
    val errorCode: String? = null,
    val activeSubscription: ActiveSubscriptionDto? = null,
)

/** [validUntil] is server epoch ms; [manageUrl] is Play's subscription page for that product. */
@Serializable
data class ActiveSubscriptionDto(
    val productId: String,
    val state: String,
    val validUntil: Long? = null,
    val willRenew: Boolean,
    val manageUrl: String,
)
