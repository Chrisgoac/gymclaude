package com.chrisgoac.gymlog

import android.app.Application
import com.chrisgoac.gymlog.auth.ClerkAuthGateway
import com.chrisgoac.gymlog.auth.ClerkAuthSessionProvider
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
import com.clerk.api.Clerk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Manual DI root, one per process. */
class AppContainer(application: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val databases: UserDatabaseManager = UserDatabaseManagerImpl(application)

    /** False when `clerk.publishableKey` is missing from local.properties or Clerk rejects it. */
    private val clerkConfigured: Boolean = initializeClerk(application, BuildConfig.CLERK_PUBLISHABLE_KEY)

    private val clerkSession: ClerkAuthSessionProvider? = if (clerkConfigured) ClerkAuthSessionProvider(appScope) else null

    val authSession: AuthSessionProvider = clerkSession ?: UnconfiguredAuthSession

    /** Only reachable from the auth screens, which a misconfigured app never shows. */
    val authGateway: AuthGateway by lazy {
        ClerkAuthGateway(checkNotNull(clerkSession) { "Clerk is not configured" }, appScope)
    }

    val rootStateMachine = RootStateMachine(authSession, databases, PendingBootstrapRunner, appScope, clerkConfigured)
        .also { it.start() }

    private companion object {
        fun initializeClerk(application: Application, publishableKey: String): Boolean {
            if (publishableKey.isBlank()) return false
            return try {
                Clerk.initialize(application, publishableKey)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }
}

private object UnconfiguredAuthSession : AuthSessionProvider {
    override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedOut)
    override suspend fun accessToken(): String? = null
}

/** TODO(Task 7): replace with the real paged bootstrap (pull all pages, apply catalog, checkpoint). */
private val PendingBootstrapRunner = BootstrapRunner { _, _, onProgress -> onProgress(1f) }
