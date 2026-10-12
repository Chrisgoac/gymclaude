package com.chrisgoac.gymlog.model.billing

import kotlinx.serialization.Serializable

/**
 * Wire contract of `POST /api/v1/billing/verify` and `GET /api/v1/billing/entitlement`
 * (lib/billing/entitlements.ts, contracts/v1/README.md "Billing"). The client never sends a state or tier.
 */
object BillingWire {
    const val PRODUCT_MONTHLY = "gymlog_pro_monthly"
    const val PRODUCT_ANNUAL = "gymlog_pro_annual"
    val PRODUCT_IDS = listOf(PRODUCT_MONTHLY, PRODUCT_ANNUAL)

    const val PURCHASE_PENDING = "PURCHASE_PENDING"
    const val PURCHASE_ALREADY_BOUND = "PURCHASE_ALREADY_BOUND"
    const val PURCHASE_ACCOUNT_MISMATCH = "PURCHASE_ACCOUNT_MISMATCH"
    const val PURCHASE_INVALID = "PURCHASE_INVALID"
    const val BILLING_UNAVAILABLE = "BILLING_UNAVAILABLE"
}

/** Strict on the server: exactly these two fields. [purchaseToken] is a secret: never logged ([toString] redacts it). */
@Serializable
data class VerifyPurchaseRequestDto(val productId: String, val purchaseToken: String) {
    override fun toString(): String = "VerifyPurchaseRequestDto(productId=$productId, purchaseToken=<redacted>)"
}

/**
 * The caller's best entitlement. [tier] `FREE|PRO`; [state] `NONE|ACTIVE|GRACE|CANCELED|PAUSED|EXPIRED`;
 * [validUntil] and [serverTime] are server epoch ms (compare them with each other, not with the device clock).
 */
@Serializable
data class EntitlementResponseDto(
    val tier: String,
    val productId: String?,
    val state: String,
    val validUntil: Long?,
    val serverTime: Long,
)
