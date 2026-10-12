package com.chrisgoac.gymlog.domain.billing

import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The two GymLog Pro subscriptions sold in Google Play (no trial, no introductory offer). */
enum class ProProduct(val productId: String) {
    MONTHLY("gymlog_pro_monthly"),
    ANNUAL("gymlog_pro_annual"),
    ;

    companion object {
        fun fromId(productId: String?): ProProduct? = entries.firstOrNull { it.productId == productId }
    }
}

enum class ProTier { FREE, PRO }

/** The server's answer (`NONE`…`EXPIRED`), plus [UNKNOWN] when nothing trustworthy is known yet. */
enum class SubscriptionState { NONE, ACTIVE, GRACE, CANCELED, PAUSED, EXPIRED, UNKNOWN }

/** Where an [Entitlement] comes from: nothing yet, the per-account offline cache, or the server just now. */
enum class EntitlementSource { NONE, CACHED, SERVER }

/**
 * What the app shows about Pro. Only the server grants Pro: [ProTier.PRO] comes from a server answer
 * (fresh, or cached for offline presentation within its [validUntil]); the Play client never sets it.
 */
data class Entitlement(
    val tier: ProTier,
    val product: ProProduct?,
    val state: SubscriptionState,
    /** Server epoch ms the paid/grace period ends, or null without a subscription. */
    val validUntil: Long?,
    val source: EntitlementSource,
) {
    val isPro: Boolean get() = tier == ProTier.PRO

    companion object {
        val Unknown = Entitlement(ProTier.FREE, null, SubscriptionState.UNKNOWN, null, EntitlementSource.NONE)
    }
}

/** A Play-localized subscription offer: [formattedPrice] as Play shows it, [billingPeriod] ISO 8601 (`P1M`, `P1Y`). */
data class ProductOffer(val product: ProProduct, val formattedPrice: String, val billingPeriod: String)

sealed interface ProductCatalog {
    data object Loading : ProductCatalog
    data class Available(val offers: List<ProductOffer>) : ProductCatalog
    data class Unavailable(val reason: BillingFailure) : ProductCatalog
}

enum class BillingFailure {
    /** Google Play billing is missing or unsupported on this device / account. */
    PLAY_UNAVAILABLE,
    /** The product is not offered (not configured, or not available in this country). */
    PRODUCT_UNAVAILABLE,
    /** Play could not be reached (no network, service down). */
    NETWORK,
    /** The server could not verify right now; it is retried on the next start or foreground. */
    VERIFICATION_UNAVAILABLE,
    /** The server rejected the purchase as not a valid GymLog subscription. */
    PURCHASE_INVALID,
    /** The purchase was made for another GymLog account. */
    OTHER_ACCOUNT,
    /** The purchase is already linked to another GymLog account. */
    ALREADY_BOUND,
    /**
     * Play already holds a GymLog subscription on this device that was bought for another (or no) GymLog
     * account: nothing is launched; the user manages it in Play ([BillingRepository.managementUri]).
     */
    SUBSCRIBED_ELSEWHERE,
    /** No signed-in account (or its session ended). */
    SIGNED_OUT,
    UNKNOWN,
}

/** Progress of the purchase the user started; presentation only. */
sealed interface PurchaseStatus {
    data object Idle : PurchaseStatus
    /** The Play purchase sheet is open. */
    data class AwaitingPlay(val product: ProProduct) : PurchaseStatus
    /** Play reported the purchase; the server is verifying it. */
    data object Verifying : PurchaseStatus
    /** Payment still pending in Play: no Pro until it completes. */
    data object Pending : PurchaseStatus
    /** The server verified the purchase; [entitlement] is its answer. */
    data class Completed(val entitlement: Entitlement) : PurchaseStatus
    data object Canceled : PurchaseStatus
    data class Failed(val reason: BillingFailure) : PurchaseStatus
}

sealed interface RestoreResult {
    data class Restored(val entitlement: Entitlement) : RestoreResult
    data object NothingToRestore : RestoreResult
    data object Pending : RestoreResult
    data class Failed(val reason: BillingFailure) : RestoreResult
}

/**
 * Google Play Billing behind the server's verification. Features read it through [EntitlementReader]
 * (and the Pro screen through this interface); they never import Billing.
 */
interface BillingRepository {
    /** The account's entitlement: server-verified, else its offline cache, else [Entitlement.Unknown]. */
    val entitlement: StateFlow<Entitlement>

    /** Monthly/annual offers with Play's localized price and period. */
    val products: StateFlow<ProductCatalog>

    val purchaseStatus: StateFlow<PurchaseStatus>

    /** (Re)loads [products] from Play. */
    suspend fun loadProducts()

    /**
     * Opens the Play purchase sheet for [product]. [activity] is the foreground `android.app.Activity`
     * (typed `Any` because the domain is JVM-only). The result arrives in [purchaseStatus]; the returned
     * value is the status right after launching.
     *
     * Never a second subscription. Before launching, the Play account's active GymLog subscriptions are read:
     * - [product] itself already owned for this GymLog account → no sheet; it is verified like [restore].
     * - the other plan owned for this GymLog account → a plan switch replacing it (`SubscriptionUpdateParams`,
     *   old token from Play, `WITH_TIME_PRORATION`), not a new purchase.
     * - any GymLog subscription owned for another (or no) GymLog account → [BillingFailure.SUBSCRIBED_ELSEWHERE],
     *   nothing launched; [managementUri] points to it.
     * - Play cannot be read → failure, nothing launched.
     */
    suspend fun launchPurchase(activity: Any, product: ProProduct): PurchaseStatus

    /** Sends the account's active Play subscriptions to the server again. */
    suspend fun restore(): RestoreResult

    /** Re-reads the server entitlement and re-verifies the device's purchases. */
    suspend fun refresh()

    /** Play's subscription management page (for the current or Play-owned product when known). */
    fun managementUri(): String
}

/** Pro for the AI entry points, derived from the server-backed [BillingRepository.entitlement]. */
class BillingEntitlementReader(private val billing: BillingRepository) : EntitlementReader {
    override fun observeIsPro(): Flow<Boolean> = billing.entitlement.map { it.isPro }.distinctUntilChanged()
}
