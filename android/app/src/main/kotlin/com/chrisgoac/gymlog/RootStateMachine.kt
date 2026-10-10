package com.chrisgoac.gymlog

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
import com.chrisgoac.gymlog.network.SyncApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** What the app shows at its root. Product navigation exists only in [Ready]. */
sealed interface RootState {
    data object Loading : RootState
    data object SignedOut : RootState

    /** [progress] is 0..1. */
    data class Bootstrapping(val userId: String, val progress: Float) : RootState
    data class Ready(val userId: String) : RootState

    /** The session expired; [userId]'s local data is kept until they sign in again. */
    data class ReauthenticationRequired(val userId: String) : RootState

    /**
     * Cannot continue: `config` (no publishable key) or `bootstrap`. [canRetry] is true when the
     * failure was transient (offline, server busy): [RootStateMachine.retryBootstrap] resumes it.
     */
    data class Fatal(val message: String, val canRetry: Boolean = false) : RootState
}

/**
 * Downloads a user's cloud data into their database, resuming from its checkpoint. Throws
 * [SyncApiException] on network/server failure (transient ones are retryable).
 */
fun interface BootstrapRunner {
    suspend fun run(userId: String, database: GymLogDatabase, onProgress: (Float) -> Unit)
}

/**
 * Turns the auth session into [RootState] (spec 12, roadmap global constraints):
 * - only an active session ([AuthState.SignedIn]) opens a database; anything else is signed out;
 * - an expired session keeps the user's database untouched and asks for re-authentication;
 * - bootstrap runs once per account per process; re-authenticating a bootstrapped account goes
 *   straight to [RootState.Ready];
 * - switching account (or signing out) closes the previous account's database first. Deleting
 *   local data is an explicit sign-out decision handled elsewhere, never done here.
 *
 * Not thread-safe by itself: run [scope] on a single thread (the app uses the main dispatcher).
 */
class RootStateMachine(
    private val auth: AuthSessionProvider,
    private val databases: UserDatabaseManager,
    private val bootstrap: BootstrapRunner,
    private val scope: CoroutineScope,
    configured: Boolean,
) {
    private val mutableState = MutableStateFlow<RootState>(if (configured) RootState.Loading else RootState.Fatal(CONFIG))
    val state: StateFlow<RootState> = mutableState.asStateFlow()

    private var started = false
    private var bootstrapJob: Job? = null
    private var bootstrappingUserId: String? = null
    private var bootstrappedUserId: String? = null

    fun start() {
        if (started || mutableState.value is RootState.Fatal) return
        started = true
        scope.launch { auth.state.collect(::onAuthState) }
    }

    private suspend fun onAuthState(authState: AuthState) {
        when (authState) {
            AuthState.Loading -> mutableState.value = RootState.Loading
            AuthState.SignedOut -> {
                cancelBootstrap()
                bootstrappedUserId = null
                databases.close()
                mutableState.value = RootState.SignedOut
            }
            is AuthState.SessionExpired -> {
                cancelBootstrap()
                mutableState.value = RootState.ReauthenticationRequired(authState.userId)
            }
            is AuthState.SignedIn -> onSignedIn(authState.userId)
        }
    }

    private suspend fun onSignedIn(userId: String) {
        val openUserId = databases.current.value?.userId
        if (openUserId != null && openUserId != userId) {
            cancelBootstrap()
            bootstrappedUserId = null
            databases.close()
        }
        when (userId) {
            bootstrappedUserId -> {
                databases.open(userId)
                mutableState.value = RootState.Ready(userId)
            }
            bootstrappingUserId -> Unit // Already running for this session.
            else -> startBootstrap(userId)
        }
    }

    private suspend fun startBootstrap(userId: String) {
        cancelBootstrap()
        bootstrappingUserId = userId
        mutableState.value = RootState.Bootstrapping(userId, 0f)
        // LAZY: the job must be registered as current before its body can run (immediate dispatchers).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            // A cancelled or superseded run must never publish state, even if the runner swallows
            // cancellation or fails because its database was closed underneath it.
            fun isCurrent() = self.isActive && bootstrapJob === self
            var database: GymLogDatabase? = null
            try {
                database = databases.open(userId)
                bootstrap.run(userId, database) { progress ->
                    if (isCurrent()) mutableState.value = RootState.Bootstrapping(userId, progress.coerceIn(0f, 1f))
                }
                if (isCurrent()) {
                    bootstrapJob = null
                    bootstrappingUserId = null
                    bootstrappedUserId = userId
                    mutableState.value = RootState.Ready(userId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled // cancelBootstrap() already reset the bookkeeping.
            } catch (failure: Exception) {
                if (isCurrent()) {
                    val transient = failure.isTransientSyncFailure()
                    // A transient failure on an account restored before (e.g. a replacement
                    // bootstrap after INVALID_CURSOR) keeps the local copy usable; background sync
                    // resumes the download from its checkpoint.
                    val restoredBefore = transient && database?.let { hasCompletedBootstrap(it) } == true
                    if (!isCurrent()) return@launch
                    bootstrapJob = null
                    bootstrappingUserId = null
                    if (restoredBefore) {
                        bootstrappedUserId = userId
                        mutableState.value = RootState.Ready(userId)
                    } else {
                        mutableState.value = RootState.Fatal(BOOTSTRAP, canRetry = transient)
                    }
                }
            }
        }
        bootstrapJob = job
        job.start()
    }

    /** Re-runs a bootstrap that failed transiently, for the account that is still signed in. */
    fun retryBootstrap() {
        val failed = mutableState.value
        val authState = auth.state.value
        if (failed !is RootState.Fatal || !failed.canRetry || authState !is AuthState.SignedIn) return
        scope.launch { startBootstrap(authState.userId) }
    }

    /** The incremental cursor exists only once a bootstrap has completed on this database. */
    private suspend fun hasCompletedBootstrap(database: GymLogDatabase): Boolean = try {
        database.syncDao().cursor() != null
    } catch (_: IllegalStateException) {
        false // Closed underneath us (sign-out/switch): treat as never restored.
    }

    private fun Exception.isTransientSyncFailure(): Boolean = when (this) {
        is SyncApiException.Transport, is SyncApiException.Malformed -> true
        is SyncApiException.Api -> retryable
        else -> false
    }

    /** Cancels any running bootstrap and waits for it, so it is gone before its database is closed. */
    private suspend fun cancelBootstrap() {
        val job = bootstrapJob
        bootstrapJob = null
        bootstrappingUserId = null
        job?.cancelAndJoin()
    }

    private companion object {
        const val CONFIG = "config"
        const val BOOTSTRAP = "bootstrap"
    }
}
