package com.chrisgoac.gymlog.settings

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val OFFERS = ProductCatalog.Available(
    listOf(
        ProductOffer(ProProduct.MONTHLY, "4,99 €", "P1M"),
        ProductOffer(ProProduct.ANNUAL, "39,99 €", "P1Y"),
    ),
)

private fun entitlement(tier: ProTier, state: SubscriptionState, product: ProProduct? = ProProduct.MONTHLY, validUntil: Long? = 1_000L) =
    Entitlement(tier, product, state, validUntil, EntitlementSource.SERVER)

private class FakeBilling : BillingRepository {
    override val entitlement = MutableStateFlow(Entitlement.Unknown)
    override val products = MutableStateFlow<ProductCatalog>(ProductCatalog.Loading)
    override val purchaseStatus = MutableStateFlow<PurchaseStatus>(PurchaseStatus.Idle)
    var loadedProducts = 0
    var refreshed = 0
    val launched = mutableListOf<Pair<Any, ProProduct>>()
    var restoreResult: RestoreResult = RestoreResult.NothingToRestore
    var restoreGate: CompletableDeferred<Unit>? = null
    var restores = 0
    var loadFailure: Exception? = null
    var refreshFailure: Exception? = null

    override suspend fun loadProducts() {
        loadedProducts++
        loadFailure?.let { throw it }
        products.value = OFFERS
    }
    override suspend fun launchPurchase(activity: Any, product: ProProduct): PurchaseStatus {
        launched += activity to product
        purchaseStatus.value = PurchaseStatus.AwaitingPlay(product)
        return purchaseStatus.value
    }
    override suspend fun restore(): RestoreResult {
        restores++
        restoreGate?.await()
        return restoreResult
    }
    override suspend fun refresh() {
        refreshed++
        refreshFailure?.let { throw it }
    }
    override fun managementUri() = "https://play.google.com/store/account/subscriptions?package=com.chrisgoac.gymlog"
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProViewModelTest {
    private val billing = FakeBilling()
    private val online = MutableStateFlow(true)
    private val dispatcher = UnconfinedTestDispatcher()
    private val activity = Any()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(scope: kotlinx.coroutines.CoroutineScope): ProViewModel =
        ProViewModel(billing, online).also { vm -> scope.launch { vm.state.collect { } } }

    @Test fun `opening the screen loads Play's offers and re-reads the entitlement`() = runTest(dispatcher) {
        val vm = viewModel(backgroundScope)
        assertEquals(1, billing.loadedProducts)
        assertEquals(1, billing.refreshed)
        assertEquals(OFFERS, vm.state.value.catalog)
        assertEquals("4,99 €", vm.state.value.offer(ProProduct.MONTHLY)?.formattedPrice)
        assertEquals("P1Y", vm.state.value.offer(ProProduct.ANNUAL)?.billingPeriod)
    }

    @Test fun `an unexpected billing error while opening the screen does not crash and the state still renders`() = runTest(dispatcher) {
        billing.loadFailure = IllegalStateException("play library")
        billing.refreshFailure = IllegalStateException("unexpected")
        val vm = viewModel(backgroundScope)
        assertEquals(1, billing.loadedProducts)
        assertEquals(ProductCatalog.Loading, vm.state.value.catalog)
        assertEquals(Entitlement.Unknown, vm.state.value.entitlement)

        vm.reloadProducts() // the retry is guarded too
        assertEquals(2, billing.loadedProducts)
    }

    @Test fun `a failed product load still re-reads the entitlement`() = runTest(dispatcher) {
        billing.loadFailure = IllegalStateException("play library")
        viewModel(backgroundScope)
        assertEquals(1, billing.refreshed)
    }

    @Test fun `the plan is derived only from the server entitlement`() {
        val cases = listOf(
            Entitlement.Unknown to PlanStatus.Unverified,
            entitlement(ProTier.FREE, SubscriptionState.NONE, null, null) to PlanStatus.Free,
            entitlement(ProTier.PRO, SubscriptionState.ACTIVE) to PlanStatus.Active(ProProduct.MONTHLY, 1_000L),
            entitlement(ProTier.PRO, SubscriptionState.GRACE) to PlanStatus.Grace(1_000L),
            entitlement(ProTier.PRO, SubscriptionState.CANCELED) to PlanStatus.CanceledUntil(ProProduct.MONTHLY, 1_000L),
            entitlement(ProTier.FREE, SubscriptionState.PAUSED) to PlanStatus.Paused,
            entitlement(ProTier.FREE, SubscriptionState.EXPIRED) to PlanStatus.Ended(1_000L),
            entitlement(ProTier.FREE, SubscriptionState.CANCELED) to PlanStatus.Ended(1_000L),
        )
        cases.forEach { (input, expected) -> assertEquals(expected, PlanStatus.of(input)) }
        assertTrue(PlanStatus.of(entitlement(ProTier.PRO, SubscriptionState.CANCELED)).isPro)
        assertTrue(PlanStatus.of(entitlement(ProTier.PRO, SubscriptionState.GRACE)).isPro)
        assertFalse(PlanStatus.of(entitlement(ProTier.FREE, SubscriptionState.PAUSED)).isPro)
        assertTrue(PlanStatus.of(entitlement(ProTier.FREE, SubscriptionState.EXPIRED)).hasSubscription)
        assertFalse(PlanStatus.Free.hasSubscription)
        assertFalse(PlanStatus.Unverified.hasSubscription)
    }

    @Test fun `a purchase hands the activity and product to billing`() = runTest(dispatcher) {
        val vm = viewModel(backgroundScope)
        vm.purchase(activity, ProProduct.ANNUAL)
        assertEquals(listOf(activity to ProProduct.ANNUAL), billing.launched)
        assertTrue(vm.state.value.purchase is PurchaseStatus.AwaitingPlay)
    }

    @Test fun `purchase and restore are blocked offline`() = runTest(dispatcher) {
        val vm = viewModel(backgroundScope)
        online.value = false
        assertFalse(vm.state.value.canTransact)
        vm.purchase(activity, ProProduct.MONTHLY)
        vm.restorePurchases()
        assertTrue(billing.launched.isEmpty())
        assertEquals(0, billing.restores)
        online.value = true
        assertTrue(vm.state.value.canTransact)
    }

    @Test fun `a second purchase or a restore cannot start while one is in flight`() = runTest(dispatcher) {
        val vm = viewModel(backgroundScope)
        vm.purchase(activity, ProProduct.MONTHLY)
        assertTrue(vm.state.value.busy)
        vm.purchase(activity, ProProduct.ANNUAL)
        vm.restorePurchases()
        assertEquals(1, billing.launched.size)
        assertEquals(0, billing.restores)

        billing.purchaseStatus.value = PurchaseStatus.Verifying
        assertFalse(vm.state.value.canTransact)
        billing.purchaseStatus.value = PurchaseStatus.Pending
        assertTrue("a pending payment does not freeze the buttons", vm.state.value.canTransact)
    }

    @Test fun `restore reports its outcome and blocks a second restore meanwhile`() = runTest(dispatcher) {
        val vm = viewModel(backgroundScope)
        billing.restoreGate = CompletableDeferred()
        vm.restorePurchases()
        assertEquals(RestorePhase.Working, vm.state.value.restore)
        assertFalse(vm.state.value.canTransact)
        vm.restorePurchases()
        assertEquals(1, billing.restores)
        billing.restoreGate!!.complete(Unit)
        assertEquals(RestorePhase.NothingToRestore, vm.state.value.restore)

        val outcomes = listOf(
            RestoreResult.Restored(entitlement(ProTier.PRO, SubscriptionState.ACTIVE)) to RestorePhase.Restored,
            RestoreResult.Pending to RestorePhase.Pending,
            RestoreResult.Failed(BillingFailure.NETWORK) to RestorePhase.Failed(BillingFailure.NETWORK),
        )
        billing.restoreGate = null
        outcomes.forEach { (result, phase) ->
            billing.restoreResult = result
            vm.restorePurchases()
            assertEquals(phase, vm.state.value.restore)
        }
    }

    @Test fun `a plan already owned for another account surfaces as SUBSCRIBED_ELSEWHERE`() = runTest(dispatcher) {
        val vm = viewModel(backgroundScope)
        billing.purchaseStatus.value = PurchaseStatus.Failed(BillingFailure.SUBSCRIBED_ELSEWHERE)
        assertEquals(PurchaseStatus.Failed(BillingFailure.SUBSCRIBED_ELSEWHERE), vm.state.value.purchase)
        assertTrue("the manage link is the way out", vm.managementUri().startsWith("https://play.google.com/"))
    }

    @Test fun `a finished purchase from before the screen opened is not repeated`() = runTest(dispatcher) {
        billing.purchaseStatus.value = PurchaseStatus.Failed(BillingFailure.NETWORK)
        val vm = viewModel(backgroundScope)
        assertEquals(PurchaseStatus.Idle, vm.state.value.purchase)
        vm.purchase(activity, ProProduct.MONTHLY)
        billing.purchaseStatus.value = PurchaseStatus.Failed(BillingFailure.NETWORK)
        assertEquals(PurchaseStatus.Failed(BillingFailure.NETWORK), vm.state.value.purchase)
        vm.dismissNotice()
        assertEquals(PurchaseStatus.Idle, vm.state.value.purchase)
    }

    @Test fun `dismissing clears a restore message but never an unfinished one`() = runTest(dispatcher) {
        val vm = viewModel(backgroundScope)
        billing.restoreResult = RestoreResult.Pending
        vm.restorePurchases()
        vm.dismissNotice()
        assertEquals(RestorePhase.Idle, vm.state.value.restore)

        vm.purchase(activity, ProProduct.MONTHLY)
        vm.dismissNotice()
        assertTrue(vm.state.value.purchase is PurchaseStatus.AwaitingPlay)
    }

    @Test fun `the switch plan is offered on the other product while Pro`() = runTest(dispatcher) {
        val vm = viewModel(backgroundScope)
        billing.entitlement.value = entitlement(ProTier.PRO, SubscriptionState.ACTIVE, ProProduct.MONTHLY)
        assertEquals(ProProduct.MONTHLY, vm.state.value.entitlement.product)
        assertTrue(vm.state.value.plan.isPro)
        vm.purchase(activity, ProProduct.ANNUAL) // billing decides it is a replacement; the screen just asks
        assertEquals(ProProduct.ANNUAL, billing.launched.single().second)
    }

    @Test fun `a failing connectivity flow does not block purchases`() = runTest(dispatcher) {
        val failing = kotlinx.coroutines.flow.flow<Boolean> { throw IllegalStateException("boom") }
        val vm = ProViewModel(billing, failing).also { backgroundScope.launch { it.state.collect { } } }
        assertTrue(vm.state.value.online)
    }
}
