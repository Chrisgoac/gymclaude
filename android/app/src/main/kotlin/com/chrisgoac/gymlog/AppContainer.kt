package com.chrisgoac.gymlog

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.chrisgoac.gymlog.auth.ClerkAuthGateway
import com.chrisgoac.gymlog.auth.ClerkAuthSessionProvider
import com.chrisgoac.gymlog.data.sync.BootstrapRepository
import com.chrisgoac.gymlog.data.sync.ServerOffsetClock
import com.chrisgoac.gymlog.data.sync.SyncLocks
import com.chrisgoac.gymlog.data.sync.SyncRepositoryImpl
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.LocalMutationScope
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.domain.DefaultSyncUseCases
import com.chrisgoac.gymlog.domain.SyncClock
import com.chrisgoac.gymlog.domain.SyncRepository
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
import com.chrisgoac.gymlog.network.ServerClock
import com.chrisgoac.gymlog.network.SyncApi
import com.chrisgoac.gymlog.network.createSyncApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import com.chrisgoac.gymlog.domain.SignOutUseCase
import com.chrisgoac.gymlog.navigation.SettingsServices
import com.chrisgoac.gymlog.sync.AppSyncEnvironment
import com.chrisgoac.gymlog.sync.BootstrapAutoRetry
import com.chrisgoac.gymlog.sync.ConnectivityMonitor
import com.chrisgoac.gymlog.sync.NotifyingLocalMutationRunner
import com.chrisgoac.gymlog.sync.RetryAfterGate
import com.chrisgoac.gymlog.sync.RoomLocalAccountCleaner
import com.chrisgoac.gymlog.sync.SyncTriggers
import com.chrisgoac.gymlog.sync.SyncWorkerFactory
import com.chrisgoac.gymlog.sync.WorkManagerSyncScheduler
import com.clerk.api.Clerk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Manual DI root, one per process. */
class AppContainer(private val application: Application) {
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

    /** Stable per installation (contract `deviceId`, 1..128 chars); never derived from the account. */
    private val deviceId: DeviceId by lazy { installationDeviceId(application) }

    /**
     * Local writes of [database] (the open account's): domain rows + outbox in one transaction,
     * stamped through [LocalMutationScope.nextTimestamp] with [syncClock].
     */
    fun localMutationRunner(database: GymLogDatabase): LocalMutationRunner =
        NotifyingLocalMutationRunner(
            RoomLocalMutationRunner(database, deviceId, clock = syncClock::nowMillis),
            onCommitted = syncTriggers::onLocalMutation,
        )

    private val syncApi: SyncApi = createSyncApi(BuildConfig.API_BASE_URL, authSession, serverClock)
    private val syncLocks = SyncLocks()
    private val bootstrapRepository = BootstrapRepository(syncApi, syncLocks)

    val syncRepository: SyncRepository = SyncRepositoryImpl(databases, syncApi, bootstrapRepository, syncLocks)
    val syncUseCases: SyncUseCases = DefaultSyncUseCases(syncRepository)

    private val retryGate = RetryAfterGate()
    private val syncScheduler = WorkManagerSyncScheduler({ WorkManager.getInstance(application) }, retryGate)

    private val bootstrapRunner = BootstrapRunner { userId, database, onProgress ->
        bootstrapRepository.run(userId, database, onProgress)
    }

    val rootStateMachine = RootStateMachine(authSession, databases, bootstrapRunner, appScope, clerkConfigured)
        .also { it.start() }

    /** Worker factory for WorkManager's on-demand initialization (see [GymLogApplication]). */
    val syncWorkerFactory = SyncWorkerFactory(
        AppSyncEnvironment(rootStateMachine.state, syncUseCases, syncScheduler, retryGate),
    )

    private val syncTriggers = SyncTriggers(appScope, rootStateMachine.state, syncScheduler)

    private val signOutUseCase by lazy {
        SignOutUseCase(
            currentUserId = { (rootStateMachine.state.value as? RootState.Ready)?.userId },
            scheduler = syncScheduler,
            cleaner = RoomLocalAccountCleaner(application, databases),
            auth = authGateway,
        )
    }

    /** Null when Clerk is not configured: the signed-in shell is unreachable then. */
    val settingsServices: SettingsServices? by lazy {
        if (clerkConfigured) SettingsServices(syncUseCases, signOutUseCase) else null
    }

    /** Starts the sync triggers: ready account, foreground, local mutations, connectivity (bootstrap retry). */
    fun startSync() {
        syncTriggers.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) syncTriggers.onForeground() },
        )
        val connectivity = ConnectivityMonitor(application)
        appScope.launch { BootstrapAutoRetry(rootStateMachine.state, connectivity.online, rootStateMachine::retryBootstrap).run() }
    }

    private companion object {
        fun installationDeviceId(application: Application): DeviceId {
            val prefs = application.getSharedPreferences("gymlog.installation", Context.MODE_PRIVATE)
            val existing = prefs.getString("deviceId", null)
            if (existing != null) return DeviceId(existing)
            val created = "android-" + UUID.randomUUID()
            prefs.edit { putString("deviceId", created) }
            return DeviceId(created)
        }

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
