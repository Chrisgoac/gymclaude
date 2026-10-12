package com.chrisgoac.gymlog.data.billing

import kotlinx.coroutines.flow.Flow

/**
 * The narrow slice of Google Play Billing GymLog uses, so [PlayBillingRepository] is testable without Play.
 * Production: [GooglePlayBillingClient]. Every result carries Play's response code as a [BillingCode].
 */
interface PlayBillingClient {
    /** Play's `onPurchasesUpdated` callbacks (hot; delivered while a collector is active). */
    val purchaseUpdates: Flow<PurchasesUpdate>

    /** True while connected to the Play service. */
    val isReady: Boolean

    /** Starts (or restarts) the connection; returns the setup result. */
    suspend fun connect(): BillingCode

    /** Subscription details for [productIds]; products Play does not know are simply absent. */
    suspend fun queryProducts(productIds: List<String>): ProductsQuery

    /** Purchases of the device's Play account for active subscriptions (Play's local cache). */
    suspend fun queryActiveSubscriptions(): PurchasesQuery

    /**
     * Opens the purchase sheet (main thread; [activity] is the foreground `android.app.Activity`) for the
     * base plan [offerToken] of [productId], tagged with [obfuscatedAccountId]. With [replacingPurchaseToken] it is a
     * plan switch replacing that subscription (`SubscriptionUpdateParams`, `WITH_TIME_PRORATION`), never a second one.
     */
    fun launch(
        activity: Any,
        productId: String,
        offerToken: String,
        obfuscatedAccountId: String,
        replacingPurchaseToken: String? = null,
    ): BillingCode

    suspend fun acknowledge(purchaseToken: String): BillingCode
}

/** Play's `BillingResponseCode`, plus [UNKNOWN] for codes this app does not know. */
enum class BillingCode {
    OK,
    USER_CANCELED,
    ITEM_ALREADY_OWNED,
    ITEM_NOT_OWNED,
    ITEM_UNAVAILABLE,
    SERVICE_DISCONNECTED,
    SERVICE_UNAVAILABLE,
    SERVICE_TIMEOUT,
    NETWORK_ERROR,
    BILLING_UNAVAILABLE,
    FEATURE_NOT_SUPPORTED,
    DEVELOPER_ERROR,
    ERROR,
    UNKNOWN,
    ;

    /** Transient connection-level failures worth reconnecting for. */
    val isTransient: Boolean
        get() = this == SERVICE_DISCONNECTED || this == SERVICE_UNAVAILABLE || this == SERVICE_TIMEOUT ||
            this == NETWORK_ERROR || this == ERROR
}

/** The base-plan offer of one subscription product (GymLog sells no trials or introductory offers). */
data class PlayProduct(val productId: String, val offerToken: String, val formattedPrice: String, val billingPeriod: String)

enum class PlayPurchaseState { PURCHASED, PENDING, UNSPECIFIED }

/** One Play purchase. [purchaseToken] is a secret: [toString] redacts it so it never reaches a log. */
data class PlayPurchase(
    val purchaseToken: String,
    val products: List<String>,
    val state: PlayPurchaseState,
    val acknowledged: Boolean,
    val obfuscatedAccountId: String?,
) {
    override fun toString(): String =
        "PlayPurchase(products=$products, state=$state, acknowledged=$acknowledged, purchaseToken=<redacted>)"
}

data class PurchasesUpdate(val code: BillingCode, val purchases: List<PlayPurchase>)

data class ProductsQuery(val code: BillingCode, val products: List<PlayProduct>)

data class PurchasesQuery(val code: BillingCode, val purchases: List<PlayPurchase>)
