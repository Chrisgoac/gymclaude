package com.chrisgoac.gymlog

import android.app.Application
import com.chrisgoac.gymlog.auth.ClerkAuthGateway
import com.chrisgoac.gymlog.auth.ClerkAuthSessionProvider
import com.chrisgoac.gymlog.data.sync.BootstrapRepository
import com.chrisgoac.gymlog.data.sync.ServerOffsetClock
import com.chrisgoac.gymlog.data.sync.SyncLocks
import com.chrisgoac.gymlog.data.sync.SyncRepositoryImpl
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.domain.DefaultSyncUseCases
import com.chrisgoac.gymlog.domain.SyncClock
import com.chrisgoac.gymlog.domain.SyncRepository
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
import com.chrisgoac.gymlog.network.ServerClock
import com.chrisgoac.gymlog.network.SyncApi
import com.chrisgoac.gymlog.network.createSyncApi
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

    /** Offset to the server clock, learned from every sync response `Date` header. */
    private val serverClock = ServerClock()

    /** Stamps updatedAt/deletedAt of local writes (device now + server offset). */
    val syncClock: SyncClock = ServerOffsetClock(serverClock)

    private val syncApi: SyncApi = createSyncApi(BuildConfig.API_BASE_URL, authSession, serverClock)
    private val syncLocks = SyncLocks()
    private val bootstrapRepository = BootstrapRepository(syncApi, syncLocks)

    val syncRepository: SyncRepository = SyncRepositoryImpl(databases, syncApi, bootstrapRepository, syncLocks)
    val syncUseCases: SyncUseCases = DefaultSyncUseCases(syncRepository)

    private val bootstrapRunner = BootstrapRunner { userId, database, onProgress ->
        bootstrapRepository.run(userId, database, onProgress)
    }

    val rootStateMachine = RootStateMachine(authSession, databases, bootstrapRunner, appScope, clerkConfigured)
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
