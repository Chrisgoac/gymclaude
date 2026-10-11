package com.chrisgoac.gymlog.settings

import com.chrisgoac.gymlog.domain.BackgroundSyncScheduler
import com.chrisgoac.gymlog.domain.LocalAccountCleaner
import com.chrisgoac.gymlog.domain.OutboxDiagnostic
import com.chrisgoac.gymlog.domain.SignOutUseCase
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.domain.SyncStatus
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.domain.account.AccountDeletion
import com.chrisgoac.gymlog.domain.account.LiveSubscription
import com.chrisgoac.gymlog.domain.account.LocalAccountDeletion
import com.chrisgoac.gymlog.domain.account.ServerDeletionOutcome
import com.chrisgoac.gymlog.domain.auth.AuthFailure
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.auth.AuthSessionState
import com.chrisgoac.gymlog.domain.auth.AuthStep
import com.chrisgoac.gymlog.domain.auth.ReauthGateway
import com.chrisgoac.gymlog.domain.auth.ReauthStep
import com.chrisgoac.gymlog.domain.billing.BillingRepository
import com.chrisgoac.gymlog.domain.billing.Entitlement
import com.chrisgoac.gymlog.domain.billing.EntitlementSource
import com.chrisgoac.gymlog.domain.billing.ProProduct
import com.chrisgoac.gymlog.domain.billing.ProTier
import com.chrisgoac.gymlog.domain.billing.ProductCatalog
import com.chrisgoac.gymlog.domain.billing.PurchaseStatus
import com.chrisgoac.gymlog.domain.billing.RestoreResult
import com.chrisgoac.gymlog.domain.billing.SubscriptionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    // ── Sync / sign-out fakes ──
    private val status = MutableStateFlow(SyncStatus(false, 0, 0, true, null))
    private val events = mutableListOf<String>()
    private var unsynced = 0
    private val online = MutableStateFlow(true)

    private val sync = object : SyncUseCases {
        override fun observeStatus() = status
        override fun observeDiagnostics() = MutableStateFlow<List<OutboxDiagnostic>>(emptyList())
        override suspend fun requestSync() { events += "requestSync" }
    }

    private val auth = object : AuthGateway {
        override val state: StateFlow<AuthSessionState> = MutableStateFlow(AuthSessionState.SignedIn(USER))
        override val serviceUnavailable: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun signOut(): AuthStep { events += "signOut"; return AuthStep.Completed }
        override suspend fun startEmailCodeSignIn(email: String) = error("unused")
        override suspend fun verifyEmailCodeSignIn(code: String) = error("unused")
        override suspend fun signInWithPassword(email: String, password: String) = error("unused")
        override suspend fun startSignUp(email: String, password: String?) = error("unused")
        override suspend fun verifySignUp(code: String) = error("unused")
        override suspend fun resendSignUpCode() = error("unused")
        override suspend fun signInWithGoogle() = error("unused")
    }

    private val signOut = SignOutUseCase(
        currentUserId = { USER },
        scheduler = object : BackgroundSyncScheduler {
            override suspend fun ensurePeriodic(userId: String) { events += "periodic" }
            override suspend fun requestSync(userId: String, queueBehindRunning: Boolean) { events += "request" }
            override suspend fun cancel(userId: String) { events += "cancel" }
            override fun suspend(userId: String) { events += "suspend" }
            override fun resume(userId: String) { events += "resume" }
        },
        cleaner = object : LocalAccountCleaner {
            override suspend fun unsyncedChangeCount(userId: String) = unsynced
            override suspend fun markPendingDeletion(userId: String) { events += "mark" }
            override suspend fun confirmPendingDeletion(userId: String) { events += "confirm" }
            override suspend fun clearPendingDeletion(userId: String) { events += "clear" }
            override suspend fun deleteLocalData(userId: String) { events += "delete:$userId" }
        },
        auth = auth,
    )

    // ── Deletion fakes ──
    private val serverAnswers = ArrayDeque<ServerDeletionOutcome>()
    private val serverCalls = mutableListOf<Boolean>() // freshToken per call
    private val begun = mutableListOf<Pair<String, LiveSubscription?>>()
    private val intents = mutableSetOf<String>()
    private var serverGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val deletion = AccountDeletion(
        currentUserId = { USER },
        server = { userId, fresh ->
            assertEquals(USER, userId)
            serverCalls += fresh
            serverGate?.await()
            serverAnswers.removeFirst()
        },
        local = object : LocalAccountDeletion {
            override suspend fun intend(userId: String) { intents += userId }
            override suspend fun withdraw(userId: String) { intents -= userId }
            override suspend fun begin(userId: String, subscription: LiveSubscription?) {
                intents -= userId
                begun += userId to subscription
            }
        },
    )

    private val reauthSteps = ArrayDeque<ReauthStep>()
    private val reauthCalls = mutableListOf<String>()
    private val reauth = object : ReauthGateway {
        override suspend fun start(): ReauthStep { reauthCalls += "start"; return reauthSteps.removeFirst() }
        override suspend fun sendEmailCode(): ReauthStep { reauthCalls += "send"; return reauthSteps.removeFirst() }
        override suspend fun verifyEmailCode(code: String): ReauthStep { reauthCalls += "code:$code"; return reauthSteps.removeFirst() }
        override suspend fun verifyPassword(password: String): ReauthStep { reauthCalls += "password"; return reauthSteps.removeFirst() }
    }

    private val billing = FakeAccountBilling()

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(withBilling: Boolean = true): AccountViewModel =
        AccountViewModel(sync, signOut, deletion, reauth, billing.takeIf { withBilling }, online, syncWaitMillis = 30_000L)
            .also { vm -> backgroundScope.launch { vm.state.collect { } }; advanceUntilIdle() }

    private fun TestScope.signOutFlowTo(vm: AccountViewModel, block: AccountViewModel.() -> Unit) {
        vm.block()
        advanceUntilIdle()
    }

    // ── Sign-out branches ──

    @Test fun `nothing unsynced signs out and deletes exactly that account's data`() = runTest(dispatcher) {
        val vm = viewModel()
        signOutFlowTo(vm) { signOut() }
        assertEquals(SignOutPhase.Idle, vm.state.value.signOut)
        assertEquals(listOf("suspend", "cancel", "mark", "signOut", "confirm", "delete:$USER", "resume"), events)
    }

    @Test fun `pending changes ask first and only an explicit discard deletes them`() = runTest(dispatcher) {
        unsynced = 3
        val vm = viewModel()
        signOutFlowTo(vm) { signOut() }
        assertEquals(SignOutPhase.Confirm(3), vm.state.value.signOut)
        assertEquals(listOf("suspend"), events)

        signOutFlowTo(vm) { confirmDiscard() }
        assertEquals(SignOutPhase.Idle, vm.state.value.signOut)
        assertEquals(listOf("suspend", "cancel", "mark", "signOut", "confirm", "delete:$USER", "resume"), events)
    }

    @Test fun `staying signed in keeps the data and resumes sync`() = runTest(dispatcher) {
        unsynced = 3
        val vm = viewModel()
        signOutFlowTo(vm) { signOut() }
        signOutFlowTo(vm) { staySignedIn() }
        assertEquals(SignOutPhase.Idle, vm.state.value.signOut)
        assertEquals(listOf("suspend", "resume", "request"), events)
    }

    @Test fun `sync now drains the changes and then signs out by itself`() = runTest(dispatcher) {
        unsynced = 2
        status.value = SyncStatus(false, 2, 0, true, null)
        val vm = viewModel()
        signOutFlowTo(vm) { signOut() }

        vm.syncNowThenSignOut()
        runCurrent()
        assertEquals(SignOutPhase.Syncing, vm.state.value.signOut)
        assertEquals(listOf("suspend", "resume", "request"), events)

        status.value = SyncStatus(true, 2, 0, true, null)
        runCurrent()
        unsynced = 0
        status.value = SyncStatus(false, 0, 0, true, SyncResult.Success(2, 0, 0))
        runCurrent()

        assertEquals(SignOutPhase.Idle, vm.state.value.signOut)
        assertEquals(
            listOf("suspend", "resume", "request", "suspend", "cancel", "mark", "signOut", "confirm", "delete:$USER", "resume"),
            events,
        )
    }

    @Test fun `sync now that cannot send the changes offers the decision again`() = runTest(dispatcher) {
        unsynced = 2
        status.value = SyncStatus(false, 2, 0, true, null)
        val vm = viewModel()
        signOutFlowTo(vm) { signOut() }
        vm.syncNowThenSignOut()
        runCurrent()

        status.value = SyncStatus(true, 2, 0, true, null)
        runCurrent()
        status.value = SyncStatus(false, 2, 0, true, SyncResult.Retryable(null, 0))
        runCurrent()

        assertEquals(SignOutPhase.Confirm(2, syncFailed = true), vm.state.value.signOut)
        assertFalse(events.contains("signOut"))
    }

    @Test fun `an expired session during sync now keeps everything and asks again`() = runTest(dispatcher) {
        unsynced = 1
        status.value = SyncStatus(false, 1, 0, true, null)
        val vm = viewModel()
        signOutFlowTo(vm) { signOut() }
        vm.syncNowThenSignOut()
        runCurrent()

        status.value = SyncStatus(true, 1, 0, true, null)
        runCurrent()
        status.value = SyncStatus(false, 1, 0, true, SyncResult.SessionRequired(0))
        runCurrent()

        assertEquals(SignOutPhase.Confirm(1, syncFailed = true), vm.state.value.signOut)
        assertFalse(events.any { it.startsWith("delete") })
    }

    @Test fun `sync now that never finishes gives the decision back after the wait`() = runTest(dispatcher) {
        unsynced = 1
        status.value = SyncStatus(false, 1, 0, true, null)
        val vm = viewModel()
        signOutFlowTo(vm) { signOut() }
        vm.syncNowThenSignOut()
        advanceTimeBy(30_001L)
        advanceUntilIdle()
        assertEquals(SignOutPhase.Confirm(1, syncFailed = true), vm.state.value.signOut)
    }

    @Test fun `offline the sync now choice does nothing`() = runTest(dispatcher) {
        unsynced = 2
        online.value = false
        val vm = viewModel()
        signOutFlowTo(vm) { signOut() }
        assertFalse(vm.state.value.online)
        signOutFlowTo(vm) { syncNowThenSignOut() }
        assertEquals(SignOutPhase.Confirm(2), vm.state.value.signOut)
        assertEquals(listOf("suspend"), events)
    }

    @Test fun `leaving while sync now waits never signs out behind the user`() = runTest(dispatcher) {
        unsynced = 1
        status.value = SyncStatus(false, 1, 0, true, null)
        val vm = viewModel()
        signOutFlowTo(vm) { signOut() }
        vm.syncNowThenSignOut()
        runCurrent()
        vm.onAccountLeft()
        unsynced = 0
        status.value = SyncStatus(false, 0, 0, true, SyncResult.Success(1, 0, 0))
        advanceUntilIdle()
        assertEquals(SignOutPhase.Idle, vm.state.value.signOut)
        assertFalse(events.contains("signOut"))
    }

    @Test fun `sign-out is blocked while a deletion request is in flight`() = runTest(dispatcher) {
        serverGate = kotlinx.coroutines.CompletableDeferred()
        serverAnswers += ServerDeletionOutcome.Accepted(completed = true, subscription = null)
        val vm = viewModel()
        vm.onConfirmationChanged("ELIMINAR")
        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(DeletionPhase.Working, vm.state.value.deletion)
        assertFalse(vm.state.value.canSignOut)

        vm.signOut()
        advanceUntilIdle()
        assertTrue(events.isEmpty())

        serverGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(DeletionPhase.Started, vm.state.value.deletion)
        assertFalse(vm.state.value.canSignOut)
    }

    @Test fun `a second factor during reverification sends the user to the web page`() = runTest(dispatcher) {
        serverAnswers += ServerDeletionOutcome.ReauthRequired
        reauthSteps += ReauthStep.Choose(password = true, emailCodeTo = null)
        reauthSteps += ReauthStep.Failed(AuthFailure.Unsupported)
        val vm = viewModel()
        vm.onConfirmationChanged("ELIMINAR")
        vm.deleteAccount()
        advanceUntilIdle()
        vm.verifyReauthPassword("right")
        advanceUntilIdle()
        assertEquals(DeletionPhase.ReauthUnavailable, vm.state.value.deletion)
        assertTrue(begun.isEmpty())
    }

    @Test fun `an offline answer keeps the deletion intent and a reauth request clears it`() = runTest(dispatcher) {
        serverAnswers += ServerDeletionOutcome.Offline
        serverAnswers += ServerDeletionOutcome.ReauthRequired
        reauthSteps += ReauthStep.Choose(password = true, emailCodeTo = null)
        val vm = viewModel()
        vm.onConfirmationChanged("ELIMINAR")
        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(setOf(USER), intents)

        vm.deleteAccount()
        advanceUntilIdle()
        assertTrue(intents.isEmpty())
    }

    @Test fun `leaving the account screens while asking keeps everything`() = runTest(dispatcher) {
        unsynced = 3
        val vm = viewModel()
        signOutFlowTo(vm) { signOut() }
        signOutFlowTo(vm) { onAccountLeft() }
        assertEquals(SignOutPhase.Idle, vm.state.value.signOut)
        assertEquals(listOf("suspend", "resume"), events)
    }

    // ── Deletion ──

    @Test fun `deletion needs the exact typed confirmation and a connection`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onConfirmationChanged("eliminar")
        advanceUntilIdle()
        assertFalse(vm.state.value.canDelete)
        vm.deleteAccount()
        advanceUntilIdle()
        assertTrue(serverCalls.isEmpty())

        vm.onConfirmationChanged("ELIMINAR")
        advanceUntilIdle()
        assertTrue(vm.state.value.canDelete)

        online.value = false
        advanceUntilIdle()
        assertFalse(vm.state.value.canDelete)
        vm.deleteAccount()
        advanceUntilIdle()
        assertTrue(serverCalls.isEmpty())
    }

    @Test fun `an accepted deletion hands the account to the local wipe with the subscription notice`() = runTest(dispatcher) {
        val live = LiveSubscription(ProProduct.ANNUAL.productId, willRenew = true, validUntil = 9L, manageUrl = "https://play.example/manage")
        serverAnswers += ServerDeletionOutcome.Accepted(completed = true, subscription = live)
        val vm = viewModel()
        vm.onConfirmationChanged("ELIMINAR")
        vm.deleteAccount()
        advanceUntilIdle()

        assertEquals(DeletionPhase.Started, vm.state.value.deletion)
        assertEquals(listOf(false), serverCalls)
        assertEquals(listOf(USER to live), begun)
        assertFalse(vm.state.value.canDelete) // no second request once started
    }

    @Test fun `a deletion still in progress on the server also starts the local wipe`() = runTest(dispatcher) {
        serverAnswers += ServerDeletionOutcome.Accepted(completed = false, subscription = null)
        val vm = viewModel()
        vm.onConfirmationChanged("ELIMINAR")
        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(DeletionPhase.Started, vm.state.value.deletion)
        assertEquals(listOf(USER to null), begun)
    }

    @Test fun `an old sign-in is reverified with the password and the request repeated with a fresh token`() = runTest(dispatcher) {
        serverAnswers += ServerDeletionOutcome.ReauthRequired
        serverAnswers += ServerDeletionOutcome.Accepted(completed = true, subscription = null)
        reauthSteps += ReauthStep.Choose(password = true, emailCodeTo = "a***@example.com")
        reauthSteps += ReauthStep.Failed(AuthFailure.InvalidCredentials)
        reauthSteps += ReauthStep.Completed
        val vm = viewModel()
        vm.onConfirmationChanged("ELIMINAR")
        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(DeletionPhase.Reauth(password = true, emailCodeTo = "a***@example.com"), vm.state.value.deletion)
        assertTrue(begun.isEmpty())

        vm.verifyReauthPassword("wrong")
        advanceUntilIdle()
        assertEquals(AuthFailure.InvalidCredentials, (vm.state.value.deletion as DeletionPhase.Reauth).failure)

        vm.verifyReauthPassword("right")
        advanceUntilIdle()
        assertEquals(DeletionPhase.Started, vm.state.value.deletion)
        assertEquals(listOf(false, true), serverCalls)
        assertEquals(listOf("start", "password", "password"), reauthCalls)
        assertEquals(1, begun.size)
    }

    @Test fun `email code reverification sends a code and verifies it`() = runTest(dispatcher) {
        serverAnswers += ServerDeletionOutcome.ReauthRequired
        serverAnswers += ServerDeletionOutcome.Accepted(completed = true, subscription = null)
        reauthSteps += ReauthStep.Choose(password = false, emailCodeTo = "a***@example.com")
        reauthSteps += ReauthStep.CodeSent
        reauthSteps += ReauthStep.Completed
        val vm = viewModel()
        vm.onConfirmationChanged("ELIMINAR")
        vm.deleteAccount()
        advanceUntilIdle()

        vm.sendReauthCode()
        advanceUntilIdle()
        assertTrue((vm.state.value.deletion as DeletionPhase.Reauth).codeSent)

        vm.verifyReauthCode(" 123456 ")
        advanceUntilIdle()
        assertEquals(DeletionPhase.Started, vm.state.value.deletion)
        assertEquals(listOf("start", "send", "code:123456"), reauthCalls)
    }

    @Test fun `a server that still wants reauthentication after it fails instead of looping`() = runTest(dispatcher) {
        serverAnswers += ServerDeletionOutcome.ReauthRequired
        serverAnswers += ServerDeletionOutcome.ReauthRequired
        reauthSteps += ReauthStep.Completed // already fresh according to the provider
        val vm = viewModel()
        vm.onConfirmationChanged("ELIMINAR")
        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(DeletionPhase.Failed(DeletionError.RETRYABLE), vm.state.value.deletion)
        assertEquals(listOf(false, true), serverCalls)
        assertTrue(begun.isEmpty())
    }

    @Test fun `an account the app cannot reverify is sent to the web page`() = runTest(dispatcher) {
        serverAnswers += ServerDeletionOutcome.ReauthRequired
        reauthSteps += ReauthStep.Choose(password = false, emailCodeTo = null)
        val vm = viewModel()
        vm.onConfirmationChanged("ELIMINAR")
        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(DeletionPhase.ReauthUnavailable, vm.state.value.deletion)

        vm.cancelDeletion()
        advanceUntilIdle()
        assertEquals(DeletionPhase.Idle, vm.state.value.deletion)
        assertTrue(begun.isEmpty())
    }

    @Test fun `a failed request can be retried and nothing local is touched before the server accepts`() = runTest(dispatcher) {
        serverAnswers += ServerDeletionOutcome.Offline
        serverAnswers += ServerDeletionOutcome.Failed(retryable = true)
        serverAnswers += ServerDeletionOutcome.Accepted(completed = true, subscription = null)
        val vm = viewModel()
        vm.onConfirmationChanged("ELIMINAR")

        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(DeletionPhase.Failed(DeletionError.OFFLINE), vm.state.value.deletion)
        assertTrue(vm.state.value.canDelete)

        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(DeletionPhase.Failed(DeletionError.RETRYABLE), vm.state.value.deletion)
        assertTrue(begun.isEmpty())

        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(DeletionPhase.Started, vm.state.value.deletion)
        assertEquals(1, begun.size)
    }

    @Test fun `an expired session is explained and deletes nothing`() = runTest(dispatcher) {
        serverAnswers += ServerDeletionOutcome.SessionEnded
        val vm = viewModel()
        vm.onConfirmationChanged("ELIMINAR")
        vm.deleteAccount()
        advanceUntilIdle()
        assertEquals(DeletionPhase.Failed(DeletionError.SESSION_ENDED), vm.state.value.deletion)
        assertTrue(begun.isEmpty())
    }

    @Test fun `the subscription warning follows the server entitlement`() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(SubscriptionNotice.NONE, vm.state.value.subscription)

        billing.entitlement.value = Entitlement(ProTier.PRO, ProProduct.MONTHLY, SubscriptionState.ACTIVE, 10L, EntitlementSource.SERVER)
        advanceUntilIdle()
        assertEquals(SubscriptionNotice.RENEWING, vm.state.value.subscription)

        billing.entitlement.value = Entitlement(ProTier.PRO, ProProduct.MONTHLY, SubscriptionState.CANCELED, 10L, EntitlementSource.SERVER)
        advanceUntilIdle()
        assertEquals(SubscriptionNotice.NOT_RENEWING, vm.state.value.subscription)
        assertEquals(MANAGE, vm.managementUri())
    }

    @Test fun `without billing there is no warning beyond the general note`() = runTest(dispatcher) {
        val vm = viewModel(withBilling = false)
        assertEquals(SubscriptionNotice.NONE, vm.state.value.subscription)
        assertEquals(null, vm.managementUri())
    }

    private companion object {
        const val USER = "user_a"
        const val MANAGE = "https://play.google.com/store/account/subscriptions?sku=gymlog_pro_monthly&package=com.chrisgoac.gymlog"
    }

    private class FakeAccountBilling : BillingRepository {
        override val entitlement = MutableStateFlow(Entitlement.Unknown)
        override val products = MutableStateFlow<ProductCatalog>(ProductCatalog.Loading)
        override val purchaseStatus = MutableStateFlow<PurchaseStatus>(PurchaseStatus.Idle)
        override suspend fun loadProducts() = Unit
        override suspend fun launchPurchase(activity: Any, product: ProProduct): PurchaseStatus = PurchaseStatus.Idle
        override suspend fun restore(): RestoreResult = RestoreResult.NothingToRestore
        override suspend fun refresh() = Unit
        override fun managementUri() = MANAGE
    }
}

