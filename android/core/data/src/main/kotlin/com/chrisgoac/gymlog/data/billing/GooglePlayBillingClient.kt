package com.chrisgoac.gymlog.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [PlayBillingClient] on Play Billing Library 9 (one instance per process, application context: no Activity is
 * retained). The library reconnects by itself after a service disconnection (`enableAutoServiceReconnection`);
 * [PlayBillingRepository] still connects on demand with backoff. Tokens never leave this class except in
 * [PlayPurchase]s, whose `toString` redacts them.
 */
class GooglePlayBillingClient(context: Context) : PlayBillingClient {
    private val updates = MutableSharedFlow<PurchasesUpdate>(extraBufferCapacity = UPDATE_BUFFER)
    override val purchaseUpdates: Flow<PurchasesUpdate> = updates.asSharedFlow()

    /** Last Play details per product id: `launchBillingFlow` needs the native object. */
    private val details = ConcurrentHashMap<String, ProductDetails>()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> updates.tryEmit(PurchasesUpdate(result.code(), purchases.orEmpty().map(::toPlay))) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override val isReady: Boolean get() = client.isReady

    override suspend fun connect(): BillingCode = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (continuation.isActive) continuation.resume(result.code())
                    }

                    // Next calls reconnect on demand (and the library reconnects by itself).
                    override fun onBillingServiceDisconnected() = Unit
                },
            )
        }
    } ?: BillingCode.SERVICE_TIMEOUT

    override suspend fun queryProducts(productIds: List<String>): ProductsQuery {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map {
                    QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
                },
            )
            .build()
        val result = client.queryProductDetails(params)
        val code = result.billingResult.code()
        if (code != BillingCode.OK) return ProductsQuery(code, emptyList())
        val products = result.productDetailsList.orEmpty().mapNotNull { product ->
            // The base plan offer: GymLog sells no trials or introductory offers.
            val offer = product.subscriptionOfferDetails?.firstOrNull { it.offerId == null } ?: return@mapNotNull null
            val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
            details[product.productId] = product
            PlayProduct(product.productId, offer.offerToken, phase.formattedPrice, phase.billingPeriod)
        }
        return ProductsQuery(BillingCode.OK, products)
    }

    override suspend fun queryActiveSubscriptions(): PurchasesQuery {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        val result = client.queryPurchasesAsync(params)
        val code = result.billingResult.code()
        return PurchasesQuery(code, if (code == BillingCode.OK) result.purchasesList.map(::toPlay) else emptyList())
    }

    override fun launch(activity: Any, productId: String, offerToken: String, obfuscatedAccountId: String): BillingCode {
        require(activity is Activity) { "launch needs the foreground Activity" }
        val product = details[productId] ?: return BillingCode.ITEM_UNAVAILABLE
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).setOfferToken(offerToken).build()),
            )
            .setObfuscatedAccountId(obfuscatedAccountId)
            .build()
        return client.launchBillingFlow(activity, params).code()
    }

    override suspend fun acknowledge(purchaseToken: String): BillingCode =
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()).code()

    private fun toPlay(purchase: Purchase): PlayPurchase = PlayPurchase(
        purchaseToken = purchase.purchaseToken,
        products = purchase.products,
        state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PlayPurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PlayPurchaseState.PENDING
            else -> PlayPurchaseState.UNSPECIFIED
        },
        acknowledged = purchase.isAcknowledged,
        obfuscatedAccountId = purchase.accountIdentifiers?.obfuscatedAccountId,
    )

    private fun BillingResult.code(): BillingCode = when (responseCode) {
        BillingResponseCode.OK -> BillingCode.OK
        BillingResponseCode.USER_CANCELED -> BillingCode.USER_CANCELED
        BillingResponseCode.ITEM_ALREADY_OWNED -> BillingCode.ITEM_ALREADY_OWNED
        BillingResponseCode.ITEM_NOT_OWNED -> BillingCode.ITEM_NOT_OWNED
        BillingResponseCode.ITEM_UNAVAILABLE -> BillingCode.ITEM_UNAVAILABLE
        BillingResponseCode.SERVICE_DISCONNECTED -> BillingCode.SERVICE_DISCONNECTED
        BillingResponseCode.SERVICE_UNAVAILABLE -> BillingCode.SERVICE_UNAVAILABLE
        BillingResponseCode.SERVICE_TIMEOUT -> BillingCode.SERVICE_TIMEOUT
        BillingResponseCode.NETWORK_ERROR -> BillingCode.NETWORK_ERROR
        BillingResponseCode.BILLING_UNAVAILABLE -> BillingCode.BILLING_UNAVAILABLE
        BillingResponseCode.FEATURE_NOT_SUPPORTED -> BillingCode.FEATURE_NOT_SUPPORTED
        BillingResponseCode.DEVELOPER_ERROR -> BillingCode.DEVELOPER_ERROR
        BillingResponseCode.ERROR -> BillingCode.ERROR
        else -> BillingCode.UNKNOWN
    }

    private companion object {
        const val UPDATE_BUFFER = 16
        const val CONNECT_TIMEOUT_MILLIS = 20_000L
    }
}
