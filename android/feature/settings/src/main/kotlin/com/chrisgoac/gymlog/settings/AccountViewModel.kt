package com.chrisgoac.gymlog.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.SignOutOutcome
import com.chrisgoac.gymlog.domain.SignOutUseCase
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.domain.account.AccountDeletion
import com.chrisgoac.gymlog.domain.account.AccountDeletionOutcome
import com.chrisgoac.gymlog.domain.auth.AuthFailure
import com.chrisgoac.gymlog.domain.auth.ReauthGateway
import com.chrisgoac.gymlog.domain.auth.ReauthStep
import com.chrisgoac.gymlog.domain.billing.BillingRepository
import com.chrisgoac.gymlog.domain.billing.SubscriptionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where a sign-out attempt stands. */
sealed interface SignOutPhase {
    data object Idle : SignOutPhase
    data object Working : SignOutPhase

    /**
     * [unsyncedChanges] would be lost: waiting for the user's decision (sync now, stay signed in, or discard).
     * [syncFailed]: a "sync now" just ran and these changes are still not on the server.
     */
    data class Confirm(val unsyncedChanges: Int, val syncFailed: Boolean = false) : SignOutPhase

    /** "Sync now" is running; sign-out continues by itself once the changes are on the server. */
    data object Syncing : SignOutPhase
    data object Failed : SignOutPhase
}

/** What deleting the account is waiting for. */
sealed interface DeletionPhase {
    data object Idle : DeletionPhase
    data object Working : DeletionPhase

    /**
     * The server wants a recent sign-in: confirm the identity, then the request is repeated by itself.
     * [codeSent]: an email code is on its way; [failure] explains the last failed attempt.
     */
    data class Reauth(
        val password: Boolean,
        val emailCodeTo: String?,
        val codeSent: Boolean = false,
        val failure: AuthFailure? = null,
    ) : DeletionPhase

    /** This app cannot confirm the identity of this account: the web page (account-deletion URL) can. */
    data object ReauthUnavailable : DeletionPhase

    data class Failed(val reason: DeletionError) : DeletionPhase

    /** Accepted by the server: the app is leaving the product and wiping the account's local data. */
    data object Started : DeletionPhase
}

enum class DeletionError { OFFLINE, SESSION_ENDED, RETRYABLE, PERMANENT }

/** What Play says about the subscription, for the warning shown before deleting. */
enum class SubscriptionNotice {
    /** Nothing known to be live (free, or ended): only the general note. */
    NONE,

    /** Active (or in grace) and renewing: deleting the account does NOT stop the charges. */
    RENEWING,

    /** Paid period running but it will not renew, or paused in Play. */
    NOT_RENEWING,
}

data class AccountUiState(
    val online: Boolean = true,
    val signOut: SignOutPhase = SignOutPhase.Idle,
    val confirmation: String = "",
    val deletion: DeletionPhase = DeletionPhase.Idle,
    val subscription: SubscriptionNotice = SubscriptionNotice.NONE,
) {
    val canDelete: Boolean
        get() = online && confirmation.trim() == AccountDeletion.CONFIRMATION &&
            (deletion is DeletionPhase.Idle || deletion is DeletionPhase.Failed)

    /** No sign-out while a deletion request is in flight or accepted: it would race the deletion's own cleanup. */
    val canSignOut: Boolean
        get() = deletion != DeletionPhase.Working && deletion != DeletionPhase.Started
}

/**
 * Account controls: sign-out (resolving unsynced changes first) and account deletion (typed confirmation,
 * recent sign-in, server request; the app's root takes over once the server accepts it).
 */
class AccountViewModel(
    private val sync: SyncUseCases,
    private val signOutUseCase: SignOutUseCase,
    private val deletion: AccountDeletion,
    private val reauth: ReauthGateway,
    private val billing: BillingRepository? = null,
    online: kotlinx.coroutines.flow.Flow<Boolean> = flowOf(true),
    /** How long "sync now" may run before the decision is offered again. */
    private val syncWaitMillis: Long = SYNC_WAIT_MILLIS,
) : ViewModel() {
    private val local = MutableStateFlow(AccountUiState())
    private var syncThenSignOut: Job? = null

    val state: StateFlow<AccountUiState> = combine(
        local,
        online,
        billing?.entitlement?.map { it.state.toNotice() } ?: flowOf(SubscriptionNotice.NONE),
    ) { state, isOnline, notice -> state.copy(online = isOnline, subscription = notice) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AccountUiState())

    /** Play's subscription page (with the product when known), or null when billing is not wired. */
    fun managementUri(): String? = billing?.managementUri()

    // ── Sign-out ──

    fun signOut() = runSignOut { signOutUseCase.begin() }

    /** Explicit, destructive: the unsynced changes are thrown away with the account's local data. */
    fun confirmDiscard() = runSignOut { signOutUseCase.confirmDiscard() }

    /** Keep the changes and the session: sync resumes. */
    fun staySignedIn() {
        viewModelScope.launch {
            signOutUseCase.cancel()
            setSignOut(SignOutPhase.Idle)
        }
    }

    /**
     * Sync the pending changes, then sign out by itself if nothing is left. Needs a connection (the button is
     * disabled offline). If changes remain (offline, rejected, session problem) the decision is offered again.
     */
    fun syncNowThenSignOut() {
        if (local.value.signOut !is SignOutPhase.Confirm || !state.value.online || !local.value.canSignOut) return
        setSignOut(SignOutPhase.Syncing)
        syncThenSignOut = viewModelScope.launch {
            signOutUseCase.cancel() // resumes triggers and asks for a sync
            var sawRun = false
            withTimeoutOrNull(syncWaitMillis) {
                sync.observeStatus().first { status ->
                    if (status.running) sawRun = true
                    !status.running && (status.pendingMutations == 0 || (sawRun && status.lastResult !is SyncResult.Success))
                }
            }
            when (val outcome = signOutUseCase.begin()) {
                SignOutOutcome.SignedOut -> setSignOut(SignOutPhase.Idle)
                is SignOutOutcome.ConfirmationRequired -> setSignOut(SignOutPhase.Confirm(outcome.unsyncedChanges, syncFailed = true))
                SignOutOutcome.Failed -> setSignOut(SignOutPhase.Failed)
            }
        }
    }

    /**
     * The account screens went away: an unanswered confirmation is dropped and sync resumes; a "sync now, then
     * sign out" still waiting stops waiting (the user did not watch it finish, so it never signs out behind them).
     */
    fun onAccountLeft() {
        when (local.value.signOut) {
            is SignOutPhase.Confirm -> {
                signOutUseCase.abandon()
                setSignOut(SignOutPhase.Idle)
            }
            SignOutPhase.Syncing -> {
                syncThenSignOut?.cancel()
                syncThenSignOut = null
                setSignOut(SignOutPhase.Idle) // triggers were already resumed by "sync now"
            }
            else -> Unit
        }
    }

    override fun onCleared() {
        signOutUseCase.abandon()
    }

    private fun runSignOut(action: suspend () -> SignOutOutcome) {
        val phase = local.value.signOut
        if (phase == SignOutPhase.Working || phase == SignOutPhase.Syncing || !local.value.canSignOut) return
        setSignOut(SignOutPhase.Working)
        viewModelScope.launch {
            setSignOut(
                when (val outcome = action()) {
                    SignOutOutcome.SignedOut -> SignOutPhase.Idle
                    is SignOutOutcome.ConfirmationRequired -> SignOutPhase.Confirm(outcome.unsyncedChanges)
                    SignOutOutcome.Failed -> SignOutPhase.Failed
                },
            )
        }
    }

    private fun setSignOut(phase: SignOutPhase) {
        local.value = local.value.copy(signOut = phase)
    }

    // ── Account deletion ──

    fun onConfirmationChanged(text: String) {
        local.value = local.value.copy(confirmation = text.take(MAX_CONFIRMATION))
    }

    fun deleteAccount() {
        // The latest typed text and phase (local), with the last known connectivity.
        if (!local.value.copy(online = state.value.online).canDelete) return
        requestDeletion(afterReauth = false)
    }

    fun sendReauthCode() = reauthStep { reauth.sendEmailCode() }

    fun verifyReauthCode(code: String) = reauthStep { reauth.verifyEmailCode(code.trim()) }

    fun verifyReauthPassword(password: String) = reauthStep { reauth.verifyPassword(password) }

    /** Leaves the identity check without deleting anything. */
    fun cancelDeletion() {
        if (local.value.deletion is DeletionPhase.Reauth || local.value.deletion is DeletionPhase.ReauthUnavailable) {
            setDeletion(DeletionPhase.Idle)
        }
    }

    private fun requestDeletion(afterReauth: Boolean) {
        setDeletion(DeletionPhase.Working)
        viewModelScope.launch {
            when (val outcome = deletion.request(local.value.confirmation, afterReauth)) {
                AccountDeletionOutcome.Started -> setDeletion(DeletionPhase.Started)
                AccountDeletionOutcome.ReauthRequired ->
                    if (afterReauth) setDeletion(DeletionPhase.Failed(DeletionError.RETRYABLE)) else startReauth()
                AccountDeletionOutcome.ConfirmationMismatch -> setDeletion(DeletionPhase.Idle)
                AccountDeletionOutcome.Offline -> setDeletion(DeletionPhase.Failed(DeletionError.OFFLINE))
                AccountDeletionOutcome.SessionEnded -> setDeletion(DeletionPhase.Failed(DeletionError.SESSION_ENDED))
                is AccountDeletionOutcome.Failed ->
                    setDeletion(DeletionPhase.Failed(if (outcome.retryable) DeletionError.RETRYABLE else DeletionError.PERMANENT))
            }
        }
    }

    private suspend fun startReauth() {
        setDeletion(
            when (val step = reauth.start()) {
                is ReauthStep.Choose ->
                    if (step.password || step.emailCodeTo != null) DeletionPhase.Reauth(step.password, step.emailCodeTo)
                    else DeletionPhase.ReauthUnavailable
                ReauthStep.Completed -> return requestDeletion(afterReauth = true)
                ReauthStep.CodeSent -> DeletionPhase.Reauth(password = false, emailCodeTo = null, codeSent = true)
                is ReauthStep.Failed ->
                    if (step.reason == AuthFailure.Unsupported) DeletionPhase.ReauthUnavailable
                    else DeletionPhase.Failed(if (step.reason == AuthFailure.Network) DeletionError.OFFLINE else DeletionError.RETRYABLE)
            },
        )
    }

    private fun reauthStep(action: suspend () -> ReauthStep) {
        val current = local.value.deletion as? DeletionPhase.Reauth ?: return
        setDeletion(DeletionPhase.Working)
        viewModelScope.launch {
            when (val step = action()) {
                ReauthStep.Completed -> requestDeletion(afterReauth = true)
                ReauthStep.CodeSent -> setDeletion(current.copy(codeSent = true, failure = null))
                is ReauthStep.Choose -> setDeletion(current.copy(failure = null))
                // A step this app cannot do (second factor, another account's session): the web page can.
                is ReauthStep.Failed ->
                    if (step.reason == AuthFailure.Unsupported) setDeletion(DeletionPhase.ReauthUnavailable)
                    else setDeletion(current.copy(failure = step.reason))
            }
        }
    }

    private fun setDeletion(phase: DeletionPhase) {
        local.value = local.value.copy(deletion = phase)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SYNC_WAIT_MILLIS = 60_000L
        const val MAX_CONFIRMATION = 32
    }
}

private fun SubscriptionState.toNotice(): SubscriptionNotice = when (this) {
    SubscriptionState.ACTIVE, SubscriptionState.GRACE -> SubscriptionNotice.RENEWING
    SubscriptionState.CANCELED, SubscriptionState.PAUSED -> SubscriptionNotice.NOT_RENEWING
    SubscriptionState.NONE, SubscriptionState.EXPIRED, SubscriptionState.UNKNOWN -> SubscriptionNotice.NONE
}
