package com.chrisgoac.gymlog.data.billing

import com.chrisgoac.gymlog.domain.billing.BillingFailure
import com.chrisgoac.gymlog.domain.billing.BillingRepository
import com.chrisgoac.gymlog.domain.billing.Entitlement
import com.chrisgoac.gymlog.domain.billing.EntitlementSource
import com.chrisgoac.gymlog.domain.billing.ProProduct
import com.chrisgoac.gymlog.domain.billing.ProTier
import com.chrisgoac.gymlog.domain.billing.ProductCatalog
import com.chrisgoac.gymlog.domain.billing.ProductOffer
import com.chrisgoac.gymlog.domain.billing.PurchaseStatus
import com.chrisgoac.gymlog.domain.billing.RestoreResult
import com.chrisgoac.gymlog.domain.billing.SubscriptionState
import com.chrisgoac.gymlog.model.ResponseJson
import com.chrisgoac.gymlog.model.billing.BillingWire
import com.chrisgoac.gymlog.model.billing.EntitlementResponseDto
import com.chrisgoac.gymlog.network.BillingApi
import com.chrisgoac.gymlog.network.SyncApiException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/**
 * The account's last server entitlement answer (the JSON of [EntitlementResponseDto]), kept per account so the
 * Pro state can be presented offline. Implemented in `app` on the account's DataStore (deleted with its data).
 */
interface EntitlementCache {
    suspend fun read(userId: String): String?
    suspend fun write(userId: String, json: String)
}

/**
 * Play Billing verified by the server. Play only opens the purchase sheet and reports purchases; every Pro
 * grant comes from the server ([api], `verify` and `entitlement`). The flow for a purchase is: Play callback → server verify →
 * entitlement from the server's answer → acknowledge only after the server verified (the server acknowledges
 * too; the client only acknowledges what Play still reports unacknowledged). Pending purchases never reach the
 * server and never grant.
 *
 * The account's purchases are re-verified on start / sign-in (each new [currentUserId]) and on foreground
 * ([onForeground], throttled), because a notification for a token no account has verified grants nothing.
 * The in-memory entitlement belongs to one account: an account change clears it, and an answer that arrives
 * for the previous account is dropped (neither shown nor cached).
 */
class PlayBillingRepository(
    private val client: PlayBillingClient,
    private val api: BillingApi,
    private val cache: EntitlementCache,
    private val currentUserId: StateFlow<String?>,
    private val scope: CoroutineScope,
    /** Server-corrected clock (epoch ms), compared with the server's `validUntil`. */
    private val now: () -> Long,
    private val packageName: String = PACKAGE_NAME,
    private val foregroundIntervalMillis: Long = FOREGROUND_INTERVAL_MILLIS,
    /** Delays between connection attempts; its size is the number of retries. */
    private val connectBackoffMillis: List<Long> = CONNECT_BACKOFF_MILLIS,
    /** Diagnostics without tokens or account ids. */
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) : BillingRepository {
    private val _entitlement = MutableStateFlow(Entitlement.Unknown)
    override val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    private val _products = MutableStateFlow<ProductCatalog>(ProductCatalog.Loading)
    override val products: StateFlow<ProductCatalog> = _products.asStateFlow()

    private val _purchaseStatus = MutableStateFlow<PurchaseStatus>(PurchaseStatus.Idle)
    override val purchaseStatus: StateFlow<PurchaseStatus> = _purchaseStatus.asStateFlow()

    private val connectMutex = Mutex()
    private val verifyLock = Any()

    /** Verifications in progress, by account + token: duplicate callbacks and a racing restore share one call. */
    private val inFlight = HashMap<String, CompletableDeferred<VerifyOutcome>>()

    /** Tokens the server verified for the current account in this session: replayed callbacks are ignored. */
    private val verifiedTokens = HashSet<String>()

    @Volatile private var offerTokens: Map<ProProduct, String> = emptyMap()
    @Volatile private var lastSyncAt: Long? = null
    private var syncJob: Job? = null
    private var expiryJob: Job? = null

    /** Starts listening to Play purchase updates and to the signed-in account. Call once. */
    fun start() {
        scope.launch { client.purchaseUpdates.collect { handleUpdate(it) } }
        scope.launch {
            currentUserId.collectLatest { userId -> onAccount(userId) }
        }
    }

    /**
     * App came to the foreground: re-read and re-verify, at most once per [foregroundIntervalMillis]. Skipped while
     * the account's first sync (start / sign-in) has not finished: that one is already doing it.
     */
    fun onForeground() {
        val userId = currentUserId.value ?: return
        val last = lastSyncAt ?: return
        if (now() - last < foregroundIntervalMillis) return
        if (syncJob?.isActive == true) return
        syncJob = scope.launch { sync(userId) }
    }

    override suspend fun refresh() {
        val userId = currentUserId.value ?: return
        sync(userId)
    }

    override fun managementUri(): String {
        val product = _entitlement.value.product
        val sku = if (product != null) "sku=${product.productId}&" else ""
        return "https://play.google.com/store/account/subscriptions?${sku}package=$packageName"
    }

    // ── Account lifecycle ──

    private suspend fun onAccount(userId: String?) {
        expiryJob?.cancel()
        syncJob?.cancel()
        _entitlement.value = Entitlement.Unknown
        _purchaseStatus.value = PurchaseStatus.Idle
        lastSyncAt = null
        synchronized(verifyLock) { verifiedTokens.clear() }
        if (userId == null) return
        readCache(userId)?.let { cached ->
            if (isCurrent(userId)) {
                _entitlement.value = presentCached(cached)
                scheduleExpiryCheck(userId, _entitlement.value)
            }
        }
        sync(userId)
    }

    /** Server entitlement first (fast presentation), then each of the account's Play purchases. */
    private suspend fun sync(userId: String) {
        refreshFromServer(userId)
        reverify(userId)
        if (isCurrent(userId)) lastSyncAt = now()
    }

    private suspend fun refreshFromServer(userId: String): Boolean = try {
        apply(userId, api.entitlement(userId)) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: SyncApiException) {
        log("Entitlement refresh failed: ${e.javaClass.simpleName}", null)
        false
    }

    private fun isCurrent(userId: String): Boolean = currentUserId.value == userId

    /** Shows and caches a server answer for [userId], unless the account changed meanwhile. */
    private suspend fun apply(userId: String, dto: EntitlementResponseDto): Entitlement? {
        if (!isCurrent(userId)) return null
        val fresh = dto.toEntitlement(EntitlementSource.SERVER)
        _entitlement.value = fresh
        try {
            cache.write(userId, ResponseJson.encodeToString(EntitlementResponseDto.serializer(), dto))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Could not cache the entitlement", e)
        }
        scheduleExpiryCheck(userId, fresh)
        return fresh
    }

    private suspend fun readCache(userId: String): EntitlementResponseDto? = try {
        cache.read(userId)?.let { ResponseJson.decodeFromString(EntitlementResponseDto.serializer(), it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SerializationException) {
        log("Ignoring an unreadable cached entitlement", null)
        null
    } catch (e: Exception) {
        log("Could not read the cached entitlement", e)
        null
    }

    /** A cached Pro is shown only within its validity; past it the state is unknown until the server answers. */
    private fun presentCached(dto: EntitlementResponseDto): Entitlement {
        val cached = dto.toEntitlement(EntitlementSource.CACHED)
        val validUntil = cached.validUntil
        return if (cached.isPro && (validUntil == null || validUntil <= now())) cached.lapsed() else cached
    }

    /**
     * While Pro is shown, re-asks the server when its period ends (a renewal extends it, otherwise it expires).
     * Unreachable server at that moment: Pro is no longer presented.
     */
    private suspend fun scheduleExpiryCheck(userId: String, shown: Entitlement) {
        val self = currentCoroutineContext()[Job]
        expiryJob?.takeIf { it !== self }?.cancel()
        val validUntil = shown.validUntil
        if (!shown.isPro || validUntil == null) {
            if (expiryJob !== self) expiryJob = null
            return
        }
        expiryJob = scope.launch {
            delay((validUntil - now()).coerceAtLeast(0) + EXPIRY_MARGIN_MILLIS)
            val refreshed = refreshFromServer(userId)
            val current = _entitlement.value
            if (!refreshed && isCurrent(userId) && current.isPro && (current.validUntil ?: 0) <= now()) {
                _entitlement.value = current.lapsed()
            }
        }
    }

    // ── Products and purchase ──

    override suspend fun loadProducts() {
        if (_products.value !is ProductCatalog.Available) _products.value = ProductCatalog.Loading
        val query = playCall(ProductsQuery::code, { ProductsQuery(it, emptyList()) }) {
            client.queryProducts(BillingWire.PRODUCT_IDS)
        }
        if (query.code != BillingCode.OK) {
            _products.value = ProductCatalog.Unavailable(query.code.toFailure())
            return
        }
        val known = query.products
            .mapNotNull { product -> ProProduct.fromId(product.productId)?.let { it to product } }
            .sortedBy { it.first.ordinal }
        offerTokens = known.associate { (product, play) -> product to play.offerToken }
        _products.value = if (known.isEmpty()) {
            ProductCatalog.Unavailable(BillingFailure.PRODUCT_UNAVAILABLE)
        } else {
            ProductCatalog.Available(known.map { (product, play) -> ProductOffer(product, play.formattedPrice, play.billingPeriod) })
        }
    }

    override suspend fun launchPurchase(activity: Any, product: ProProduct): PurchaseStatus {
        val userId = currentUserId.value ?: return report(PurchaseStatus.Failed(BillingFailure.SIGNED_OUT))
        if (product !in offerTokens) loadProducts()
        val offerToken = offerTokens[product]
            ?: return report(PurchaseStatus.Failed((_products.value as? ProductCatalog.Unavailable)?.reason ?: BillingFailure.PRODUCT_UNAVAILABLE))
        val code = playCall({ it }, { it }) { client.launch(activity, product.productId, offerToken, obfuscatedAccountId(userId)) }
        return when (code) {
            BillingCode.OK -> report(PurchaseStatus.AwaitingPlay(product))
            BillingCode.USER_CANCELED -> report(PurchaseStatus.Canceled)
            BillingCode.ITEM_ALREADY_OWNED -> recoverOwned()
            else -> report(PurchaseStatus.Failed(code.toFailure()))
        }
    }

    private fun report(status: PurchaseStatus): PurchaseStatus {
        _purchaseStatus.value = status
        return status
    }

    /** Play says the subscription is already owned: verify what the device holds instead. */
    private suspend fun recoverOwned(): PurchaseStatus {
        report(PurchaseStatus.Verifying)
        return report(
            when (val result = restore()) {
                is RestoreResult.Restored -> PurchaseStatus.Completed(result.entitlement)
                RestoreResult.Pending -> PurchaseStatus.Pending
                RestoreResult.NothingToRestore -> PurchaseStatus.Failed(BillingFailure.UNKNOWN)
                is RestoreResult.Failed -> PurchaseStatus.Failed(result.reason)
            },
        )
    }

    private suspend fun handleUpdate(update: PurchasesUpdate) {
        when (update.code) {
            BillingCode.OK -> handlePurchases(update.purchases)
            BillingCode.USER_CANCELED -> report(PurchaseStatus.Canceled)
            BillingCode.ITEM_ALREADY_OWNED -> recoverOwned()
            else -> report(PurchaseStatus.Failed(update.code.toFailure()))
        }
    }

    private suspend fun handlePurchases(purchases: List<PlayPurchase>) {
        val userId = currentUserId.value
        if (userId == null) {
            report(PurchaseStatus.Failed(BillingFailure.SIGNED_OUT))
            return
        }
        for (purchase in purchases.distinctBy { it.purchaseToken }) {
            if (purchase.product() == null) continue
            when (purchase.state) {
                PlayPurchaseState.PENDING -> report(PurchaseStatus.Pending)
                PlayPurchaseState.UNSPECIFIED -> Unit
                PlayPurchaseState.PURCHASED -> {
                    if (synchronized(verifyLock) { purchase.purchaseToken in verifiedTokens }) continue
                    report(PurchaseStatus.Verifying)
                    report(verify(userId, purchase).toStatus())
                }
            }
        }
    }

    // ── Restore / re-verification ──

    override suspend fun restore(): RestoreResult {
        val userId = currentUserId.value ?: return RestoreResult.Failed(BillingFailure.SIGNED_OUT)
        return reverify(userId)
    }

    /** Verifies each purchased GymLog subscription Play reports for [userId] (purchases of other accounts are skipped). */
    private suspend fun reverify(userId: String): RestoreResult {
        val query = playCall(PurchasesQuery::code, { PurchasesQuery(it, emptyList()) }) { client.queryActiveSubscriptions() }
        if (query.code != BillingCode.OK) return RestoreResult.Failed(query.code.toFailure())
        val ours = query.purchases.filter { it.product() != null }
        val accountId = obfuscatedAccountId(userId)
        val mine = ours.filter { it.obfuscatedAccountId == accountId }
        if (mine.isEmpty()) {
            return if (ours.isEmpty()) RestoreResult.NothingToRestore else RestoreResult.Failed(BillingFailure.OTHER_ACCOUNT)
        }
        var restored: Entitlement? = null
        var pending = false
        var failure: BillingFailure? = null
        for (purchase in mine) {
            when (purchase.state) {
                PlayPurchaseState.PENDING -> pending = true
                PlayPurchaseState.UNSPECIFIED -> Unit
                PlayPurchaseState.PURCHASED -> when (val outcome = verify(userId, purchase)) {
                    is VerifyOutcome.Verified -> restored = outcome.entitlement
                    VerifyOutcome.Pending -> pending = true
                    is VerifyOutcome.Failed -> failure = failure ?: outcome.reason
                }
            }
        }
        return when {
            restored != null -> RestoreResult.Restored(restored)
            pending -> RestoreResult.Pending
            failure != null -> RestoreResult.Failed(failure)
            else -> RestoreResult.NothingToRestore
        }
    }

    // ── Verification ──

    private sealed interface VerifyOutcome {
        data class Verified(val entitlement: Entitlement) : VerifyOutcome
        data object Pending : VerifyOutcome
        data class Failed(val reason: BillingFailure) : VerifyOutcome
    }

    private fun VerifyOutcome.toStatus(): PurchaseStatus = when (this) {
        is VerifyOutcome.Verified -> PurchaseStatus.Completed(entitlement)
        VerifyOutcome.Pending -> PurchaseStatus.Pending
        is VerifyOutcome.Failed -> PurchaseStatus.Failed(reason)
    }

    /** One server verification per account + token at a time; concurrent callers await the same answer. */
    private suspend fun verify(userId: String, purchase: PlayPurchase): VerifyOutcome {
        val key = userId + "\n" + purchase.purchaseToken
        val (deferred, owner) = synchronized(verifyLock) {
            inFlight[key]?.let { it to false } ?: CompletableDeferred<VerifyOutcome>().also { inFlight[key] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        val outcome = try {
            verifyNow(userId, purchase)
        } catch (e: Throwable) {
            // Waiters are not cancelled with this caller (e.g. an account switch cancelled the sync).
            synchronized(verifyLock) { inFlight.remove(key) }
            deferred.complete(VerifyOutcome.Failed(if (isCurrent(userId)) BillingFailure.VERIFICATION_UNAVAILABLE else BillingFailure.SIGNED_OUT))
            throw e
        }
        synchronized(verifyLock) {
            inFlight.remove(key)
            if (outcome is VerifyOutcome.Verified && isCurrent(userId)) verifiedTokens += purchase.purchaseToken
        }
        deferred.complete(outcome)
        return outcome
    }

    private suspend fun verifyNow(userId: String, purchase: PlayPurchase): VerifyOutcome {
        val product = purchase.product() ?: return VerifyOutcome.Failed(BillingFailure.PURCHASE_INVALID)
        val answer = try {
            api.verify(userId, product.productId, purchase.purchaseToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncApiException) {
            log("Purchase verification failed: ${e.javaClass.simpleName}", null)
            return e.toOutcome()
        }
        val shown = apply(userId, answer) ?: return VerifyOutcome.Failed(BillingFailure.SIGNED_OUT)
        acknowledgeIfNeeded(purchase)
        return VerifyOutcome.Verified(shown)
    }

    /**
     * After the server verified: acknowledge only when Play still reports the purchase unacknowledged (the
     * server acknowledges it itself; a failure here is harmless and retried by the next verification).
     */
    private suspend fun acknowledgeIfNeeded(purchase: PlayPurchase) {
        if (purchase.acknowledged) return
        val current = client.queryActiveSubscriptions()
            .takeIf { it.code == BillingCode.OK }
            ?.purchases?.firstOrNull { it.purchaseToken == purchase.purchaseToken }
        if (current?.acknowledged == true) return
        val code = playCall({ it }, { it }) { client.acknowledge(purchase.purchaseToken) }
        if (code != BillingCode.OK) log("Purchase acknowledgement returned $code", null)
    }

    private fun SyncApiException.toOutcome(): VerifyOutcome = when (this) {
        is SyncApiException.Api -> when (rawCode) {
            BillingWire.PURCHASE_PENDING -> VerifyOutcome.Pending
            BillingWire.PURCHASE_ACCOUNT_MISMATCH -> VerifyOutcome.Failed(BillingFailure.OTHER_ACCOUNT)
            BillingWire.PURCHASE_ALREADY_BOUND -> VerifyOutcome.Failed(BillingFailure.ALREADY_BOUND)
            BillingWire.PURCHASE_INVALID -> VerifyOutcome.Failed(BillingFailure.PURCHASE_INVALID)
            else -> VerifyOutcome.Failed(if (retryable || httpStatus >= 500) BillingFailure.VERIFICATION_UNAVAILABLE else BillingFailure.UNKNOWN)
        }
        is SyncApiException.SessionRequired, is SyncApiException.SessionRejected -> VerifyOutcome.Failed(BillingFailure.SIGNED_OUT)
        is SyncApiException.Transport, is SyncApiException.Malformed -> VerifyOutcome.Failed(BillingFailure.VERIFICATION_UNAVAILABLE)
    }

    // ── Play connection ──

    /**
     * Runs [call] on a connected client: connects first (retrying transient failures with backoff) and, when the
     * call reports the service disconnected, reconnects once and retries.
     */
    private suspend fun <T> playCall(code: (T) -> BillingCode, failed: (BillingCode) -> T, call: suspend () -> T): T {
        var result: T
        var attempt = 0
        while (true) {
            val connected = ensureConnected()
            if (connected != BillingCode.OK) return failed(connected)
            result = call()
            if (code(result) != BillingCode.SERVICE_DISCONNECTED || attempt++ > 0) return result
        }
    }

    private suspend fun ensureConnected(): BillingCode = connectMutex.withLock {
        if (client.isReady) return@withLock BillingCode.OK
        var code = client.connect()
        for (backoff in connectBackoffMillis) {
            if (code == BillingCode.OK || !code.isTransient) break
            delay(backoff)
            code = client.connect()
        }
        code
    }

    private fun PlayPurchase.product(): ProProduct? = products.firstNotNullOfOrNull(ProProduct::fromId)

    companion object {
        const val PACKAGE_NAME = "com.chrisgoac.gymlog"
        const val FOREGROUND_INTERVAL_MILLIS = 60_000L
        val CONNECT_BACKOFF_MILLIS = listOf(1_000L, 3_000L, 10_000L)
        private const val EXPIRY_MARGIN_MILLIS = 5_000L

        /** `BillingFlowParams.setObfuscatedAccountId` value the server requires: SHA-256 hex of the Clerk user id. */
        fun obfuscatedAccountId(userId: String): String =
            MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

private fun EntitlementResponseDto.toEntitlement(source: EntitlementSource): Entitlement = Entitlement(
    tier = if (tier == "PRO") ProTier.PRO else ProTier.FREE,
    product = ProProduct.fromId(productId),
    state = when (state) {
        "NONE" -> SubscriptionState.NONE
        "ACTIVE" -> SubscriptionState.ACTIVE
        "GRACE" -> SubscriptionState.GRACE
        "CANCELED" -> SubscriptionState.CANCELED
        "PAUSED" -> SubscriptionState.PAUSED
        "EXPIRED" -> SubscriptionState.EXPIRED
        else -> SubscriptionState.UNKNOWN
    },
    validUntil = validUntil,
    source = source,
)

/** A Pro whose validity passed without a new server answer: not Pro, state unknown. */
private fun Entitlement.lapsed(): Entitlement =
    copy(tier = ProTier.FREE, state = SubscriptionState.UNKNOWN, source = EntitlementSource.CACHED)

private fun BillingCode.toFailure(): BillingFailure = when (this) {
    BillingCode.BILLING_UNAVAILABLE, BillingCode.FEATURE_NOT_SUPPORTED -> BillingFailure.PLAY_UNAVAILABLE
    BillingCode.ITEM_UNAVAILABLE -> BillingFailure.PRODUCT_UNAVAILABLE
    BillingCode.SERVICE_DISCONNECTED, BillingCode.SERVICE_UNAVAILABLE, BillingCode.SERVICE_TIMEOUT, BillingCode.NETWORK_ERROR ->
        BillingFailure.NETWORK
    else -> BillingFailure.UNKNOWN
}
