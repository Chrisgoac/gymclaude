package com.chrisgoac.gymlog.account

import com.chrisgoac.gymlog.domain.account.LiveSubscription
import com.chrisgoac.gymlog.domain.account.LocalAccountDeletion
import com.chrisgoac.gymlog.network.AccountSignal
import com.chrisgoac.gymlog.network.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Where the device stands with an account deletion; anything but [None] keeps the product UI away. */
sealed interface AccountDeletionStatus {
    data object None : AccountDeletionStatus

    /** The account's local data is being removed. */
    data object Running : AccountDeletionStatus

    /** The local wipe (or the sign-out before it) failed; the mark stays and [AccountDeletionCoordinator.retry] resumes. */
    data object Failed : AccountDeletionStatus

    /**
     * Everything local is gone. [subscription]: a Play subscription the user still has to cancel in Play.
     * [serverConfirmed]: the server answered that it holds the deletion; false when the device wiped an account
     * whose deletion request had no confirmed answer and whose session then ended (see [AccountDeletionCoordinator]).
     */
    data class Done(val subscription: LiveSubscription?, val serverConfirmed: Boolean = true) : AccountDeletionStatus
}

/**
 * Durable, hash-only records (never raw ids):
 * - **intent**: a deletion request is about to be / was sent without a confirmed answer. Never wipes by itself.
 * - **pending** (confirmed): the server holds the deletion (or the evidence says so): the account must be wiped
 *   before anything else, also after process death.
 */
interface AccountDeletionMarks {
    suspend fun pending(): Set<String>
    suspend fun intents(): Set<String>
    suspend fun addIntent(hash: String)
    suspend fun removeIntent(hash: String)

    /** Pending hashes promoted without a server confirmation (only the wording of the final notice depends on it). */
    suspend fun unconfirmed(): Set<String>

    /** Intent → pending, atomically ([confirmed]: the server said it holds the deletion). Also adds a pending mark without an intent. */
    suspend fun promote(hash: String, confirmed: Boolean)

    /** Drops the pending mark (and its unconfirmed flag). */
    suspend fun remove(hash: String)
}

/**
 * The device side of account deletion.
 *
 * **Confirmed deletion** ([begin]: the server accepted, or any route answered `ACCOUNT_DELETING`):
 * 1. the pending mark is written BEFORE anything else, so after process death [start] resumes here instead of
 *    showing the product;
 * 2. the account's background work stops ([stopWork]);
 * 3. its session ends: once the identity provider has loaded ([auth] left Loading, [identityReady]), the account
 *    is signed out if it is the one on this device ([signOut], which drops the provider's stored credentials);
 * 4. [wipe] deletes exactly that account's database, files, caches and per-account stores (under the account's
 *    sync lock when its id is known in this process);
 * 5. the mark is removed and the hash remembered for this process: a later `SignedIn` of it is signed out again
 *    ([isWiped] keeps the root from opening it) and a duplicate [begin] is ignored.
 * A failure leaves the mark ([AccountDeletionStatus.Failed]); [retry] runs the same steps again.
 *
 * **Unconfirmed deletion** ([intend] before the request; the answer may be lost). The intent stays until evidence:
 * `ACCOUNT_DELETING` → confirmed; a 2xx of an ordinary route for the account → cleared; the account's session
 * expiring, being rejected, or ending without a deliberate sign-out (once the provider is loaded) → wiped as an
 * unconfirmed deletion. Startup never wipes on an intent alone and the DELETE is never re-sent automatically.
 */
class AccountDeletionCoordinator(
    private val marks: AccountDeletionMarks,
    private val hashOf: (userId: String) -> String,
    private val stopWork: suspend (hash: String) -> Unit,
    private val auth: StateFlow<AuthState>,
    /** True once the identity provider has loaded its stored session (so "no session" is real). */
    private val identityReady: () -> Boolean,
    /** Ends this device's session; true once there is none. */
    private val signOut: suspend () -> Boolean,
    /** [userId] is known when the deletion started in this process (the wipe then takes the account's sync lock). */
    private val wipe: suspend (hash: String, userId: String?) -> Unit,
    private val scope: CoroutineScope,
    private val sessionWaitMillis: Long = SESSION_WAIT_MILLIS,
) : LocalAccountDeletion {
    private val mutableStatus = MutableStateFlow<AccountDeletionStatus>(AccountDeletionStatus.None)
    val status: StateFlow<AccountDeletionStatus> = mutableStatus.asStateFlow()

    private val mutex = Mutex()
    private val intents: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val wiped: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val knownIds = ConcurrentHashMap<String, String>()
    @Volatile private var subscription: LiveSubscription? = null
    @Volatile private var serverConfirmed = false

    /**
     * Startup gate: loads the intents, starts watching the session and, if a confirmed deletion is pending, shows
     * it as Running and resumes it in the background (returns at once so the root can show the deleting state).
     */
    suspend fun start() {
        intents += marks.intents()
        scope.launch { watchSession() }
        val pending = marks.pending()
        if (pending.isNotEmpty()) {
            val unconfirmed = marks.unconfirmed()
            serverConfirmed = pending.any { it !in unconfirmed }
            mutableStatus.value = AccountDeletionStatus.Running
            scope.launch { runCleanup() }
        }
    }

    /** True when [userId]'s data was wiped by this process: the root must not open it again. */
    fun isWiped(userId: String): Boolean = hashOf(userId) in wiped

    override suspend fun intend(userId: String) {
        val hash = hashOf(userId)
        marks.addIntent(hash)
        intents += hash
    }

    override suspend fun withdraw(userId: String) {
        val hash = hashOf(userId)
        if (hash !in intents) return
        marks.removeIntent(hash)
        intents -= hash
    }

    override suspend fun begin(userId: String, subscription: LiveSubscription?) {
        if (subscription != null) this.subscription = subscription
        serverConfirmed = true
        promote(hashOf(userId), userId, confirmed = true)
    }

    /** What an API answer revealed about [userId]'s account (any thread). */
    fun onAccountSignal(userId: String, signal: AccountSignal) {
        val hash = hashOf(userId)
        when (signal) {
            AccountSignal.DELETING -> scope.launch { begin(userId, null) }
            AccountSignal.ACTIVE -> if (hash in intents) scope.launch { withdraw(userId) }
            AccountSignal.SESSION_REJECTED -> if (hash in intents) scope.launch { promote(hash, userId) }
        }
    }

    fun retry() {
        if (mutableStatus.value != AccountDeletionStatus.Failed) return
        mutableStatus.value = AccountDeletionStatus.Running
        scope.launch { runCleanup() }
    }

    /** The user read the final notice. Only a finished deletion can be dismissed. */
    fun acknowledge() {
        if (mutableStatus.value !is AccountDeletionStatus.Done) return
        subscription = null
        serverConfirmed = false
        mutableStatus.value = AccountDeletionStatus.None
    }

    private suspend fun promote(hash: String, userId: String?, confirmed: Boolean = false) {
        if (hash in wiped) return // already gone in this process: a late duplicate
        if (userId != null) knownIds[hash] = userId
        marks.promote(hash, confirmed)
        intents -= hash
        mutableStatus.value = AccountDeletionStatus.Running
        runCleanup()
    }

    private suspend fun watchSession() {
        auth.collect { state ->
            when (state) {
                is AuthState.SignedIn ->
                    // A wiped account's session restored late (provider loaded after the wipe): end it again.
                    if (hashOf(state.userId) in wiped) scope.launch { signOut() }
                is AuthState.SessionExpired -> {
                    val hash = hashOf(state.userId)
                    if (hash in intents) scope.launch { promote(hash, state.userId) }
                }
                // A deliberate sign-out withdraws its intent first, so what is left lost its session some other way.
                AuthState.SignedOut -> if (identityReady()) intents.toList().forEach { hash -> scope.launch { promote(hash, null) } }
                AuthState.Loading -> Unit
            }
        }
    }

    private suspend fun runCleanup() = mutex.withLock {
        try {
            val hashes = marks.pending()
            if (hashes.isEmpty()) {
                if (mutableStatus.value == AccountDeletionStatus.Running) mutableStatus.value = done()
                return@withLock
            }
            hashes.forEach { stopWork(it) }
            if (!endSession(hashes)) {
                mutableStatus.value = AccountDeletionStatus.Failed
                return@withLock
            }
            hashes.forEach { hash ->
                wipe(hash, knownIds[hash])
                wiped += hash
                marks.remove(hash)
            }
            mutableStatus.value = done()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableStatus.value = AccountDeletionStatus.Failed
        }
    }

    private fun done() = AccountDeletionStatus.Done(subscription, serverConfirmed)

    /**
     * Waits for the provider to load (never trusts "no session" before that), then signs out only if the session
     * on this device belongs to one of [hashes]: another account that is signed in is left alone.
     */
    private suspend fun endSession(hashes: Set<String>): Boolean {
        val state = withTimeoutOrNull(sessionWaitMillis) { auth.first { it != AuthState.Loading } } ?: return false
        if (!identityReady()) return false
        val owner = when (state) {
            is AuthState.SignedIn -> state.userId
            is AuthState.SessionExpired -> state.userId
            else -> null
        }
        if (owner == null || hashOf(owner) !in hashes) return true
        return signOut()
    }

    private companion object {
        const val SESSION_WAIT_MILLIS = 15_000L
    }
}
