package com.chrisgoac.gymlog

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
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

    /** Unrecoverable: `config` (no publishable key) or `bootstrap`. */
    data class Fatal(val message: String) : RootState
}

/** Downloads a user's cloud data into their freshly opened database (Task 7). */
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
            try {
                val database = databases.open(userId)
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
            } catch (_: Exception) {
                if (isCurrent()) {
                    // TODO(Task 7): distinguish retryable (offline) failures and resume from the checkpoint.
                    bootstrapJob = null
                    bootstrappingUserId = null
                    mutableState.value = RootState.Fatal(BOOTSTRAP)
                }
            }
        }
        bootstrapJob = job
        job.start()
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
