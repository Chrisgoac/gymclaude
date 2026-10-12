package com.chrisgoac.gymlog.data.billing

import com.chrisgoac.gymlog.domain.billing.BillingEntitlementReader
import com.chrisgoac.gymlog.domain.billing.BillingFailure
import com.chrisgoac.gymlog.domain.billing.EntitlementSource
import com.chrisgoac.gymlog.domain.billing.ProProduct
import com.chrisgoac.gymlog.domain.billing.ProTier
import com.chrisgoac.gymlog.domain.billing.ProductCatalog
import com.chrisgoac.gymlog.domain.billing.ProductOffer
import com.chrisgoac.gymlog.domain.billing.PurchaseStatus
import com.chrisgoac.gymlog.domain.billing.RestoreResult
import com.chrisgoac.gymlog.domain.billing.SubscriptionState
import com.chrisgoac.gymlog.model.ApiErrorCode
import com.chrisgoac.gymlog.model.billing.EntitlementResponseDto
import com.chrisgoac.gymlog.network.BillingApi
import com.chrisgoac.gymlog.network.SyncApiException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayBillingRepositoryTest {
    private val events = mutableListOf<String>()
    private val client = FakePlayClient(events)
    private val api = FakeBillingApi(events)
    private val cache = FakeCache()
    private val account = MutableStateFlow<String?>(USER)

    /** The root state read at apply time (by default the same as [account]). */
    private var rootAccount: () -> String? = { account.value }

    private fun TestScope.repository(start: Boolean = true): PlayBillingRepository =
        PlayBillingRepository(
            client = client,
            api = api,
            cache = cache,
            currentUserId = account,
            scope = backgroundScope,
            currentUserIdNow = { rootAccount() },
            now = { BASE + testScheduler.currentTime },
            foregroundIntervalMillis = MINUTE,
            connectBackoffMillis = listOf(1_000, 2_000),
        ).also { if (start) it.start() }

    // ── Connection ──

    @Test
    fun connectionRetriesTransientFailuresWithBackoffThenLoadsProducts() = runTest {
        client.connectResults += listOf(BillingCode.SERVICE_UNAVAILABLE, BillingCode.SERVICE_DISCONNECTED)
        val repo = repository(start = false)

        val load = async { repo.loadProducts() }
        runCurrent()
        assertEquals(1, client.connectCalls)
        advanceTimeBy(1_001)
        assertEquals(2, client.connectCalls)
        advanceTimeBy(2_001)
        load.await()

        assertEquals(3, client.connectCalls)
        assertTrue(repo.products.value is ProductCatalog.Available)
    }

    @Test
    fun billingUnavailableIsNotRetriedAndMarksProductsUnavailable() = runTest {
        client.connectResults += BillingCode.BILLING_UNAVAILABLE
        val repo = repository(start = false)

        repo.loadProducts()

        assertEquals(1, client.connectCalls)
        assertEquals(ProductCatalog.Unavailable(BillingFailure.PLAY_UNAVAILABLE), repo.products.value)
    }

    @Test
    fun aDisconnectedCallReconnectsOnceAndRetries() = runTest {
        val repo = repository(start = false)
        repo.loadProducts()
        assertEquals(1, client.connectCalls)

        client.productCodes += BillingCode.SERVICE_DISCONNECTED // the service dropped under this call
        repo.loadProducts()

        assertEquals(2, client.connectCalls)
        assertEquals(3, client.productQueries) // first load, the disconnected attempt, the retry
        assertTrue(repo.products.value is ProductCatalog.Available)
    }

    // ── Product details ──

    @Test
    fun loadsMonthlyAndAnnualWithPlayLocalizedPriceAndPeriod() = runTest {
        client.products += PlayProduct("unknown_product", "offer-x", "1 €", "P1W")
        val repo = repository(start = false)

        repo.loadProducts()

        assertEquals(listOf(BillingWireIds.MONTHLY, BillingWireIds.ANNUAL), client.lastQueriedIds)
        assertEquals(
            ProductCatalog.Available(
                listOf(
                    ProductOffer(ProProduct.MONTHLY, "4,99 €", "P1M"),
                    ProductOffer(ProProduct.ANNUAL, "39,99 €", "P1Y"),
                ),
            ),
            repo.products.value,
        )
    }

    @Test
    fun noKnownProductMeansProductUnavailable() = runTest {
        client.products.clear()
        val repo = repository(start = false)

        repo.loadProducts()

        assertEquals(ProductCatalog.Unavailable(BillingFailure.PRODUCT_UNAVAILABLE), repo.products.value)
    }

    // ── Purchase flow ──

    @Test
    fun launchTagsThePurchaseWithTheHashedAccountIdAndTheBaseOffer() = runTest {
        val repo = repository()
        runCurrent()

        val status = repo.launchPurchase(ACTIVITY, ProProduct.ANNUAL)

        assertEquals(PurchaseStatus.AwaitingPlay(ProProduct.ANNUAL), status)
        assertEquals(PurchaseStatus.AwaitingPlay(ProProduct.ANNUAL), repo.purchaseStatus.value)
        val launch = client.launches.single()
        assertEquals(BillingWireIds.ANNUAL, launch.productId)
        assertEquals("offer-annual", launch.offerToken)
        assertEquals(sha256Hex(USER), launch.accountId)
        assertEquals(64, launch.accountId.length)
        assertNull(launch.replacing) // nothing owned: a fresh subscription
        assertTrue(launch.activity === ACTIVITY)
    }

    @Test
    fun launchWithoutAnAccountFailsWithoutOpeningPlay() = runTest {
        account.value = null
        val repo = repository()
        runCurrent()

        assertEquals(PurchaseStatus.Failed(BillingFailure.SIGNED_OUT), repo.launchPurchase(ACTIVITY, ProProduct.MONTHLY))
        assertTrue(client.launches.isEmpty())
    }

    @Test
    fun successfulPurchaseIsVerifiedThenAcknowledgedAndGrantsOnlyFromTheServer() = runTest {
        api.entitlementAnswer = { free() }
        val repo = repository()
        runCurrent()
        repo.launchPurchase(ACTIVITY, ProProduct.MONTHLY)
        api.verifyAnswer = { _, _ -> pro(BillingWireIds.MONTHLY) }
        val purchase = purchase(TOKEN_A, BillingWireIds.MONTHLY, acknowledged = false)
        client.owned += purchase.copy() // Play's cache still says unacknowledged after the server answered

        client.updates.emit(PurchasesUpdate(BillingCode.OK, listOf(purchase)))
        runCurrent()

        assertEquals(listOf("verify:$TOKEN_A", "ack:$TOKEN_A"), events.filter { it.startsWith("verify") || it.startsWith("ack") })
        val entitlement = repo.entitlement.value
        assertEquals(ProTier.PRO, entitlement.tier)
        assertEquals(EntitlementSource.SERVER, entitlement.source)
        assertEquals(PurchaseStatus.Completed(entitlement), repo.purchaseStatus.value)
        assertTrue(cache.values[USER]!!.contains("\"PRO\""))
        assertFalse(cache.values[USER]!!.contains(TOKEN_A))
    }

    @Test
    fun aPurchaseTheServerAlreadyAcknowledgedIsNotAcknowledgedAgain() = runTest {
        val repo = repository()
        runCurrent()
        api.verifyAnswer = { _, _ -> pro(BillingWireIds.MONTHLY) }
        val purchase = purchase(TOKEN_A, BillingWireIds.MONTHLY, acknowledged = false)
        client.owned += purchase.copy(acknowledged = true) // the server acknowledged it

        client.updates.emit(PurchasesUpdate(BillingCode.OK, listOf(purchase)))
        runCurrent()

        assertTrue(client.acks.isEmpty())
        assertTrue(repo.entitlement.value.isPro)
    }

    @Test
    fun pendingPurchaseNeverGrantsNorReachesTheServer() = runTest {
        val repo = repository()
        runCurrent()
        val verifiesBefore = api.verifyCalls

        client.updates.emit(PurchasesUpdate(BillingCode.OK, listOf(purchase(TOKEN_A, BillingWireIds.MONTHLY, state = PlayPurchaseState.PENDING))))
        runCurrent()

        assertEquals(PurchaseStatus.Pending, repo.purchaseStatus.value)
        assertFalse(repo.entitlement.value.isPro)
        assertEquals(verifiesBefore, api.verifyCalls)
        assertTrue(client.acks.isEmpty())
    }

    @Test
    fun serverPendingAnswerIsPendingWithoutGrant() = runTest {
        val repo = repository()
        runCurrent()
        api.verifyAnswer = { _, _ -> throw apiError(409, "PURCHASE_PENDING", retryable = true) }

        client.updates.emit(PurchasesUpdate(BillingCode.OK, listOf(purchase(TOKEN_A, BillingWireIds.MONTHLY))))
        runCurrent()

        assertEquals(PurchaseStatus.Pending, repo.purchaseStatus.value)
        assertFalse(repo.entitlement.value.isPro)
        assertTrue(client.acks.isEmpty())
    }

    @Test
    fun canceledAndErrorResultsAreReportedWithoutVerification() = runTest {
        val repo = repository()
        runCurrent()
        val verifiesBefore = api.verifyCalls

        client.updates.emit(PurchasesUpdate(BillingCode.USER_CANCELED, emptyList()))
        runCurrent()
        assertEquals(PurchaseStatus.Canceled, repo.purchaseStatus.value)

        client.updates.emit(PurchasesUpdate(BillingCode.NETWORK_ERROR, emptyList()))
        runCurrent()
        assertEquals(PurchaseStatus.Failed(BillingFailure.NETWORK), repo.purchaseStatus.value)

        client.updates.emit(PurchasesUpdate(BillingCode.DEVELOPER_ERROR, emptyList()))
        runCurrent()
        assertEquals(PurchaseStatus.Failed(BillingFailure.UNKNOWN), repo.purchaseStatus.value)
        assertEquals(verifiesBefore, api.verifyCalls)
    }

    @Test
    fun launchCanceledImmediatelyIsCanceled() = runTest {
        client.launchCode = BillingCode.USER_CANCELED
        val repo = repository()
        runCurrent()

        assertEquals(PurchaseStatus.Canceled, repo.launchPurchase(ACTIVITY, ProProduct.MONTHLY))
    }

    @Test
    fun theSameProductAlreadyOwnedIsVerifiedWithoutOpeningPlay() = runTest {
        api.entitlementAnswer = { free() }
        val repo = repository()
        runCurrent()
        client.owned += purchase(TOKEN_A, BillingWireIds.ANNUAL)
        api.verifyAnswer = { _, _ -> pro(BillingWireIds.ANNUAL) }

        val status = repo.launchPurchase(ACTIVITY, ProProduct.ANNUAL)

        assertTrue(status is PurchaseStatus.Completed)
        assertTrue(repo.entitlement.value.isPro)
        assertEquals(1, api.verifyCalls)
        assertTrue(client.launches.isEmpty())
    }

    @Test
    fun playReportingAlreadyOwnedAtLaunchRestores() = runTest {
        client.launchCode = BillingCode.ITEM_ALREADY_OWNED
        val repo = repository()
        runCurrent()
        api.verifyAnswer = { _, _ -> pro(BillingWireIds.ANNUAL) }
        client.onLaunch = { client.owned += purchase(TOKEN_A, BillingWireIds.ANNUAL) } // Play's cache caught up late

        val status = repo.launchPurchase(ACTIVITY, ProProduct.ANNUAL)

        assertTrue(status is PurchaseStatus.Completed)
        assertEquals(1, api.verifyCalls)
    }

    @Test
    fun switchingPlanReplacesTheOwnedSubscriptionInsteadOfAddingOne() = runTest {
        val repo = repository()
        runCurrent()
        client.owned += purchase(TOKEN_A, BillingWireIds.MONTHLY)

        val status = repo.launchPurchase(ACTIVITY, ProProduct.ANNUAL)

        assertEquals(PurchaseStatus.AwaitingPlay(ProProduct.ANNUAL), status)
        val launch = client.launches.single()
        assertEquals(BillingWireIds.ANNUAL, launch.productId)
        assertEquals(TOKEN_A, launch.replacing)
        assertEquals(sha256Hex(USER), launch.accountId)
    }

    @Test
    fun aSubscriptionOfAnotherOrNoAccountIsNeverReplacedNorDuplicated() = runTest {
        val repo = repository()
        runCurrent()
        for (foreignAccount in listOf(sha256Hex("user_2"), null)) {
            client.owned.clear()
            client.owned += purchase("foreign", BillingWireIds.MONTHLY, account = foreignAccount)

            for (product in ProProduct.entries) {
                assertEquals(PurchaseStatus.Failed(BillingFailure.SUBSCRIBED_ELSEWHERE), repo.launchPurchase(ACTIVITY, product))
            }
        }
        assertTrue(client.launches.isEmpty())
        assertEquals(0, api.verifyCalls)
        assertEquals(
            "https://play.google.com/store/account/subscriptions?sku=gymlog_pro_monthly&package=com.chrisgoac.gymlog",
            repo.managementUri(),
        )
    }

    @Test
    fun aPendingOtherPlanBlocksTheSwitch() = runTest {
        val repo = repository()
        runCurrent()
        client.owned += purchase(TOKEN_A, BillingWireIds.MONTHLY, state = PlayPurchaseState.PENDING)

        assertEquals(PurchaseStatus.Pending, repo.launchPurchase(ACTIVITY, ProProduct.ANNUAL))
        assertTrue(client.launches.isEmpty())
    }

    @Test
    fun unreadablePlayPurchasesMeanNoLaunch() = runTest {
        val repo = repository()
        runCurrent()
        client.purchaseQueryCodes += BillingCode.SERVICE_UNAVAILABLE

        assertEquals(PurchaseStatus.Failed(BillingFailure.NETWORK), repo.launchPurchase(ACTIVITY, ProProduct.MONTHLY))
        assertTrue(client.launches.isEmpty())
    }

    @Test
    fun alreadyOwnedFromThePurchaseCallbackAlsoRestores() = runTest {
        val repo = repository()
        runCurrent()
        client.owned += purchase(TOKEN_A, BillingWireIds.ANNUAL)
        api.verifyAnswer = { _, _ -> pro(BillingWireIds.ANNUAL) }

        client.updates.emit(PurchasesUpdate(BillingCode.ITEM_ALREADY_OWNED, emptyList()))
        runCurrent()

        assertTrue(repo.purchaseStatus.value is PurchaseStatus.Completed)
    }

    @Test
    fun duplicateCallbacksForOnePurchaseVerifyItOnce() = runTest {
        val repo = repository()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        api.verifyGate = gate
        api.verifyAnswer = { _, _ -> pro(BillingWireIds.MONTHLY) }
        val purchase = purchase(TOKEN_A, BillingWireIds.MONTHLY)

        client.updates.emit(PurchasesUpdate(BillingCode.OK, listOf(purchase)))
        client.updates.emit(PurchasesUpdate(BillingCode.OK, listOf(purchase, purchase)))
        val restore = async { repo.restore() } // a restore racing the callback shares the same verification
        client.owned += purchase
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        restore.await()
        // A later replay of the already-verified callback is ignored too.
        client.updates.emit(PurchasesUpdate(BillingCode.OK, listOf(purchase)))
        runCurrent()

        assertEquals(1, api.verifyCalls)
        assertTrue(repo.entitlement.value.isPro)
    }

    @Test
    fun verificationFailuresNeverGrantNorAcknowledge() = runTest {
        val cases = listOf(
            apiError(422, "PURCHASE_INVALID") to BillingFailure.PURCHASE_INVALID,
            apiError(403, "PURCHASE_ACCOUNT_MISMATCH") to BillingFailure.OTHER_ACCOUNT,
            apiError(409, "PURCHASE_ALREADY_BOUND") to BillingFailure.ALREADY_BOUND,
            apiError(503, "BILLING_UNAVAILABLE", retryable = true) to BillingFailure.VERIFICATION_UNAVAILABLE,
            SyncApiException.Transport(IOException("offline")) to BillingFailure.VERIFICATION_UNAVAILABLE,
            SyncApiException.SessionRequired() to BillingFailure.SIGNED_OUT,
        )
        val repo = repository()
        runCurrent()
        cases.forEachIndexed { index, (error, expected) ->
            api.verifyAnswer = { _, _ -> throw error }
            client.updates.emit(PurchasesUpdate(BillingCode.OK, listOf(purchase("tok$index", BillingWireIds.MONTHLY))))
            runCurrent()

            assertEquals(PurchaseStatus.Failed(expected), repo.purchaseStatus.value)
            assertFalse(repo.entitlement.value.isPro)
        }
        assertTrue(client.acks.isEmpty())
    }

    // ── Restore and re-verification ──

    @Test
    fun restoreVerifiesEachOfTheAccountsPurchasesAndSkipsOtherAccounts() = runTest {
        val repo = repository()
        runCurrent()
        api.verifyTokens.clear()
        client.owned += purchase(TOKEN_A, BillingWireIds.MONTHLY)
        client.owned += purchase(TOKEN_B, BillingWireIds.ANNUAL)
        client.owned += purchase("other", BillingWireIds.ANNUAL, account = sha256Hex("user_2"))
        client.owned += purchase("pending", BillingWireIds.ANNUAL, state = PlayPurchaseState.PENDING)
        api.verifyAnswer = { productId, _ -> pro(productId) }

        val result = repo.restore()

        assertEquals(listOf(TOKEN_A, TOKEN_B), api.verifyTokens)
        assertTrue(result is RestoreResult.Restored)
        assertTrue(repo.entitlement.value.isPro)
    }

    @Test
    fun restoreWithNothingOrOnlyAnotherAccountsPurchase() = runTest {
        val repo = repository()
        runCurrent()

        assertEquals(RestoreResult.NothingToRestore, repo.restore())

        client.owned += purchase("other", BillingWireIds.ANNUAL, account = sha256Hex("user_2"))
        assertEquals(RestoreResult.Failed(BillingFailure.OTHER_ACCOUNT), repo.restore())

        client.owned.clear()
        client.owned += purchase("pending", BillingWireIds.ANNUAL, state = PlayPurchaseState.PENDING)
        assertEquals(RestoreResult.Pending, repo.restore())
    }

    @Test
    fun restoreReportsAPlayQueryFailure() = runTest {
        val repo = repository()
        runCurrent()
        client.purchaseQueryCodes += BillingCode.BILLING_UNAVAILABLE

        assertEquals(RestoreResult.Failed(BillingFailure.PLAY_UNAVAILABLE), repo.restore())
    }

    @Test
    fun startupReadsTheServerAndReverifiesExistingPurchases() = runTest {
        client.owned += purchase(TOKEN_A, BillingWireIds.MONTHLY)
        api.entitlementAnswer = { free() }
        api.verifyAnswer = { _, _ -> pro(BillingWireIds.MONTHLY) }

        val repo = repository()
        runCurrent()

        assertEquals(1, api.entitlementCalls)
        assertEquals(listOf(TOKEN_A), api.verifyTokens)
        assertTrue(repo.entitlement.value.isPro)
    }

    @Test
    fun foregroundOnlyReadsTheEntitlementAtMostOncePerInterval() = runTest {
        client.owned += purchase(TOKEN_A, BillingWireIds.MONTHLY)
        api.entitlementAnswer = { pro(BillingWireIds.MONTHLY) }
        api.verifyAnswer = { _, _ -> pro(BillingWireIds.MONTHLY) }
        val repo = repository()
        repo.onForeground() // ON_START before the first sync finished: that sync already covers it
        runCurrent()
        assertEquals(1, api.verifyCalls) // startup re-verifies
        assertEquals(1, api.entitlementCalls)

        repo.onForeground()
        runCurrent()
        assertEquals(1, api.entitlementCalls) // right after startup: throttled

        advanceTimeBy(MINUTE + 1)
        repo.onForeground()
        runCurrent()
        assertEquals(2, api.entitlementCalls)
        assertEquals(1, api.verifyCalls) // server says PRO: no POST
    }

    @Test
    fun foregroundReverifiesWhenTheServerSaysFreeWhilePlayOwnsAPurchase() = runTest {
        api.entitlementAnswer = { free() }
        val repo = repository()
        runCurrent()
        assertEquals(0, api.verifyCalls)

        client.owned += purchase(TOKEN_A, BillingWireIds.MONTHLY)
        api.verifyAnswer = { _, _ -> pro(BillingWireIds.MONTHLY) }
        advanceTimeBy(MINUTE + 1)
        repo.onForeground()
        runCurrent()

        assertEquals(listOf(TOKEN_A), api.verifyTokens)
        assertTrue(repo.entitlement.value.isPro)
    }

    @Test
    fun foregroundDoesNotPostAnotherAccountsOrAPendingPurchase() = runTest {
        api.entitlementAnswer = { free() }
        val repo = repository()
        runCurrent()
        client.owned += purchase("other", BillingWireIds.MONTHLY, account = sha256Hex("user_2"))
        client.owned += purchase("pending", BillingWireIds.ANNUAL, state = PlayPurchaseState.PENDING)

        advanceTimeBy(MINUTE + 1)
        repo.onForeground()
        runCurrent()

        assertEquals(2, api.entitlementCalls)
        assertEquals(0, api.verifyCalls)
    }

    @Test
    fun startIsIdempotent() = runTest {
        val repo = repository()
        repo.start()
        runCurrent()

        assertEquals(1, api.entitlementCalls)
    }

    @Test
    fun anAnswerIsDroppedOnceTheRootAccountLeftEvenBeforeTheFlowReEmits() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.entitlementGate = gate
        api.entitlementAnswer = { pro(BillingWireIds.MONTHLY) }
        val repo = repository()
        runCurrent()

        rootAccount = { null } // signed out in the root state; [account] has not re-emitted yet
        gate.complete(Unit)
        runCurrent()

        assertFalse(repo.entitlement.value.isPro)
        assertNull(cache.values[USER])
    }

    @Test
    fun signInReverifiesTheNewAccountsPurchases() = runTest {
        account.value = null
        val repo = repository()
        runCurrent()
        assertEquals(0, api.entitlementCalls)
        client.owned += purchase(TOKEN_A, BillingWireIds.MONTHLY)
        api.verifyAnswer = { _, _ -> pro(BillingWireIds.MONTHLY) }

        account.value = USER
        runCurrent()

        assertEquals(1, api.entitlementCalls)
        assertEquals(listOf(TOKEN_A), api.verifyTokens)
        assertTrue(repo.entitlement.value.isPro)
    }

    // ── Expiration and offline presentation ──

    @Test
    fun entitlementIsRefreshedWhenItsValidityEnds() = runTest {
        api.entitlementAnswer = { pro(BillingWireIds.MONTHLY, validUntil = BASE + HOUR) }
        val repo = repository()
        runCurrent()
        assertTrue(repo.entitlement.value.isPro)

        api.entitlementAnswer = { expired(BillingWireIds.MONTHLY, validUntil = BASE + HOUR) }
        advanceTimeBy(HOUR + 10_000)
        runCurrent()

        assertEquals(2, api.entitlementCalls)
        assertFalse(repo.entitlement.value.isPro)
        assertEquals(SubscriptionState.EXPIRED, repo.entitlement.value.state)
    }

    @Test
    fun renewalFoundAtExpiryKeepsProAndSchedulesTheNextCheck() = runTest {
        api.entitlementAnswer = { pro(BillingWireIds.MONTHLY, validUntil = BASE + HOUR) }
        val repo = repository()
        runCurrent()

        api.entitlementAnswer = { pro(BillingWireIds.MONTHLY, validUntil = BASE + 3 * HOUR) }
        advanceTimeBy(HOUR + 10_000)
        assertTrue(repo.entitlement.value.isPro)
        assertEquals(BASE + 3 * HOUR, repo.entitlement.value.validUntil)

        api.entitlementAnswer = { expired(BillingWireIds.MONTHLY, validUntil = BASE + 3 * HOUR) }
        advanceTimeBy(2 * HOUR + 10_000)
        assertFalse(repo.entitlement.value.isPro)
    }

    @Test
    fun offlineAtExpiryStopsPresentingPro() = runTest {
        api.entitlementAnswer = { pro(BillingWireIds.MONTHLY, validUntil = BASE + HOUR) }
        val repo = repository()
        runCurrent()

        api.entitlementAnswer = { throw SyncApiException.Transport(IOException("offline")) }
        advanceTimeBy(HOUR + 10_000)

        assertFalse(repo.entitlement.value.isPro)
        assertEquals(SubscriptionState.UNKNOWN, repo.entitlement.value.state)
    }

    @Test
    fun offlineShowsTheAccountsCachedEntitlementWithinItsValidity() = runTest {
        cache.values[USER] = cachedJson(pro(BillingWireIds.ANNUAL, validUntil = BASE + HOUR))
        api.entitlementAnswer = { throw SyncApiException.Transport(IOException("offline")) }
        client.purchaseQueryCodes += BillingCode.SERVICE_UNAVAILABLE

        val repo = repository()
        runCurrent()

        val entitlement = repo.entitlement.value
        assertTrue(entitlement.isPro)
        assertEquals(EntitlementSource.CACHED, entitlement.source)
        assertEquals(ProProduct.ANNUAL, entitlement.product)
    }

    @Test
    fun aCachedProPastItsValidityIsNotPresentedAsPro() = runTest {
        cache.values[USER] = cachedJson(pro(BillingWireIds.ANNUAL, validUntil = BASE - 1))
        api.entitlementAnswer = { throw SyncApiException.Transport(IOException("offline")) }

        val repo = repository()
        runCurrent()

        val entitlement = repo.entitlement.value
        assertFalse(entitlement.isPro)
        assertEquals(SubscriptionState.UNKNOWN, entitlement.state)
        assertEquals(EntitlementSource.CACHED, entitlement.source)
    }

    @Test
    fun aCorruptCacheIsIgnored() = runTest {
        cache.values[USER] = "{not json"
        api.entitlementAnswer = { throw SyncApiException.Transport(IOException("offline")) }

        val repo = repository()
        runCurrent()

        assertEquals(com.chrisgoac.gymlog.domain.billing.Entitlement.Unknown, repo.entitlement.value)
    }

    // ── Account switch ──

    @Test
    fun accountSwitchClearsTheEntitlementAndUsesTheNewAccountsCache() = runTest {
        api.entitlementAnswer = { pro(BillingWireIds.MONTHLY) }
        val repo = repository()
        runCurrent()
        assertTrue(repo.entitlement.value.isPro)

        cache.values["user_2"] = cachedJson(free())
        val gate = CompletableDeferred<Unit>()
        api.entitlementGate = gate
        account.value = "user_2"
        runCurrent()

        assertFalse(repo.entitlement.value.isPro)
        assertEquals(EntitlementSource.CACHED, repo.entitlement.value.source)
        assertEquals(PurchaseStatus.Idle, repo.purchaseStatus.value)
        gate.complete(Unit)

        account.value = null
        runCurrent()
        assertEquals(com.chrisgoac.gymlog.domain.billing.Entitlement.Unknown, repo.entitlement.value)
    }

    @Test
    fun aLateAnswerForThePreviousAccountIsDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.entitlementGate = gate
        api.entitlementAnswer = { pro(BillingWireIds.MONTHLY) }
        val repo = repository()
        runCurrent()

        // A restore started for user_1 is still waiting on the server when the account changes.
        api.verifyGate = gate
        client.owned += purchase(TOKEN_A, BillingWireIds.MONTHLY)
        api.verifyAnswer = { _, _ -> pro(BillingWireIds.MONTHLY) }
        val restore = async { repo.restore() }
        runCurrent()
        api.entitlementGate = null
        api.entitlementAnswer = { free() }
        account.value = "user_2"
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        restore.await()

        assertFalse(repo.entitlement.value.isPro)
        assertNull(cache.values[USER]) // nothing written for an account that is no longer current
    }

    // ── Misc ──

    @Test
    fun managementUriPointsToPlaySubscriptionsForTheProduct() = runTest {
        api.entitlementAnswer = { pro(BillingWireIds.ANNUAL) }
        val repo = repository()
        assertEquals("https://play.google.com/store/account/subscriptions?package=com.chrisgoac.gymlog", repo.managementUri())
        runCurrent()

        assertEquals(
            "https://play.google.com/store/account/subscriptions?sku=gymlog_pro_annual&package=com.chrisgoac.gymlog",
            repo.managementUri(),
        )
    }

    @Test
    fun entitlementReaderFollowsTheServerTier() = runTest {
        api.entitlementAnswer = { pro(BillingWireIds.MONTHLY) }
        val repo = repository()
        val reader = BillingEntitlementReader(repo)
        assertFalse(reader.observeIsPro().first())
        runCurrent()
        assertTrue(reader.observeIsPro().first())
        advanceUntilIdle()
    }

    // ── Fakes and helpers ──

    private class Launch(val activity: Any, val productId: String, val offerToken: String, val accountId: String, val replacing: String?)

    private class FakePlayClient(private val events: MutableList<String>) : PlayBillingClient {
        override val purchaseUpdates = MutableSharedFlow<PurchasesUpdate>(extraBufferCapacity = 16)
        val updates get() = purchaseUpdates
        var ready = false
        val connectResults = ArrayDeque<BillingCode>()
        var connectCalls = 0
        val products = mutableListOf(
            PlayProduct(BillingWireIds.ANNUAL, "offer-annual", "39,99 €", "P1Y"),
            PlayProduct(BillingWireIds.MONTHLY, "offer-monthly", "4,99 €", "P1M"),
        )
        val productCodes = ArrayDeque<BillingCode>()
        var productQueries = 0
        var lastQueriedIds: List<String> = emptyList()
        val owned = mutableListOf<PlayPurchase>()
        val purchaseQueryCodes = ArrayDeque<BillingCode>()
        var launchCode = BillingCode.OK
        var onLaunch: () -> Unit = {}
        val launches = mutableListOf<Launch>()
        val acks = mutableListOf<String>()

        override val isReady: Boolean get() = ready

        override suspend fun connect(): BillingCode {
            connectCalls++
            val code = connectResults.removeFirstOrNull() ?: BillingCode.OK
            ready = code == BillingCode.OK
            return code
        }

        override suspend fun queryProducts(productIds: List<String>): ProductsQuery {
            productQueries++
            lastQueriedIds = productIds
            val code = productCodes.removeFirstOrNull() ?: BillingCode.OK
            if (code == BillingCode.SERVICE_DISCONNECTED) ready = false
            return ProductsQuery(code, if (code == BillingCode.OK) products.toList() else emptyList())
        }

        override suspend fun queryActiveSubscriptions(): PurchasesQuery {
            val code = purchaseQueryCodes.removeFirstOrNull() ?: BillingCode.OK
            return PurchasesQuery(code, if (code == BillingCode.OK) owned.toList() else emptyList())
        }

        override fun launch(
            activity: Any,
            productId: String,
            offerToken: String,
            obfuscatedAccountId: String,
            replacingPurchaseToken: String?,
        ): BillingCode {
            launches += Launch(activity, productId, offerToken, obfuscatedAccountId, replacingPurchaseToken)
            onLaunch()
            return launchCode
        }

        override suspend fun acknowledge(purchaseToken: String): BillingCode {
            events += "ack:$purchaseToken"
            acks += purchaseToken
            return BillingCode.OK
        }
    }

    private class FakeBillingApi(private val events: MutableList<String>) : BillingApi {
        var entitlementAnswer: () -> EntitlementResponseDto = { free() }
        var verifyAnswer: (String, String) -> EntitlementResponseDto = { _, _ -> free() }
        var entitlementGate: CompletableDeferred<Unit>? = null
        var verifyGate: CompletableDeferred<Unit>? = null
        var entitlementCalls = 0
        var verifyCalls = 0
        val verifyTokens = mutableListOf<String>()

        override suspend fun verify(expectedUserId: String, productId: String, purchaseToken: String): EntitlementResponseDto {
            verifyCalls++
            verifyTokens += purchaseToken
            events += "verify:$purchaseToken"
            verifyGate?.await()
            return verifyAnswer(productId, purchaseToken)
        }

        override suspend fun entitlement(expectedUserId: String): EntitlementResponseDto {
            entitlementCalls++
            entitlementGate?.await()
            return entitlementAnswer()
        }
    }

    private class FakeCache : EntitlementCache {
        val values = HashMap<String, String>()
        override suspend fun read(userId: String): String? = values[userId]
        override suspend fun write(userId: String, json: String) {
            values[userId] = json
        }
    }

    private object BillingWireIds {
        const val MONTHLY = "gymlog_pro_monthly"
        const val ANNUAL = "gymlog_pro_annual"
    }

    private companion object {
        const val USER = "user_1"
        const val TOKEN_A = "token-a"
        const val TOKEN_B = "token-b"
        const val BASE = 1_767_225_600_000L
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        val ACTIVITY = Any()

        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        fun purchase(
            token: String,
            productId: String,
            state: PlayPurchaseState = PlayPurchaseState.PURCHASED,
            acknowledged: Boolean = true,
            account: String? = sha256Hex(USER),
        ) = PlayPurchase(token, listOf(productId), state, acknowledged, account)

        fun free() = EntitlementResponseDto("FREE", null, "NONE", null, BASE)

        fun pro(productId: String, validUntil: Long = BASE + 30 * 24 * HOUR) =
            EntitlementResponseDto("PRO", productId, "ACTIVE", validUntil, BASE)

        fun expired(productId: String, validUntil: Long) =
            EntitlementResponseDto("FREE", productId, "EXPIRED", validUntil, validUntil + 1)

        fun cachedJson(dto: EntitlementResponseDto): String =
            """{"tier":"${dto.tier}","productId":${dto.productId?.let { "\"$it\"" }},"state":"${dto.state}","validUntil":${dto.validUntil},"serverTime":${dto.serverTime}}"""

        fun apiError(status: Int, code: String, retryable: Boolean = false) =
            SyncApiException.Api(status, ApiErrorCode.Unknown, code, retryable, null, null, null, code)
    }
}
