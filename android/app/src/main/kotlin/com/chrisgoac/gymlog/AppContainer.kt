package com.chrisgoac.gymlog

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.chrisgoac.gymlog.auth.ClerkAuthGateway
import com.chrisgoac.gymlog.auth.ClerkAuthSessionProvider
import com.chrisgoac.gymlog.data.catalog.CatalogAsset
import com.chrisgoac.gymlog.data.exercise.OfflineExerciseRepository
import com.chrisgoac.gymlog.data.routine.OfflineRoutineRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
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
import com.chrisgoac.gymlog.sync.CatalogSeedingUserDatabaseManager
import com.chrisgoac.gymlog.sync.ConnectivityMonitor
import com.chrisgoac.gymlog.sync.NotifyingLocalMutationRunner
import com.chrisgoac.gymlog.sync.ReconcilingUserDatabaseManager
import com.chrisgoac.gymlog.sync.RetryAfterGate
import com.chrisgoac.gymlog.sync.RoomLocalAccountCleaner
import com.chrisgoac.gymlog.sync.ServerClockStore
import com.chrisgoac.gymlog.sync.SyncTriggers
import com.chrisgoac.gymlog.sync.SyncWorkerFactory
import com.chrisgoac.gymlog.sync.WorkManagerSyncScheduler
import com.clerk.api.Clerk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID

/** Manual DI root, one per process. */
class AppContainer(private val application: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val databaseManager = UserDatabaseManagerImpl(application)
    private val syncLocks = SyncLocks()
    private val accountCleaner = RoomLocalAccountCleaner(application, databaseManager, syncLocks)

    /** Every open first finishes a pending sign-out deletion of that account (see [RoomLocalAccountCleaner.reconcile]). */
    val databases: UserDatabaseManager = CatalogSeedingUserDatabaseManager(
        ReconcilingUserDatabaseManager(databaseManager, accountCleaner::reconcile),
        loadAsset = { application.assets.open(CATALOG_ASSET).use(CatalogAsset::read) },
    )

    /** False when `clerk.publishableKey` is missing from local.properties or Clerk rejects it. */
    private val clerkConfigured: Boolean = initializeClerk(application, BuildConfig.CLERK_PUBLISHABLE_KEY)

    private val clerkSession: ClerkAuthSessionProvider? = if (clerkConfigured) ClerkAuthSessionProvider(appScope) else null

    val authSession: AuthSessionProvider = clerkSession ?: UnconfiguredAuthSession

    /** Only reachable from the auth screens, which a misconfigured app never shows. */
    val authGateway: AuthGateway by lazy {
        ClerkAuthGateway(checkNotNull(clerkSession) { "Clerk is not configured" }, appScope)
    }

    /**
     * Offset to the server clock, learned from every sync response `Date` header and persisted, so
     * writes made offline right after a restart are already stamped with the last known offset.
     */
    private val serverClock = ServerClockStore(application).let { store ->
        ServerClock(initialOffsetMillis = store.seedOffset(), onObserved = store::save)
    }

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

    /** Catalog + custom exercises of the open account; writes are local (outbox) and never wait for HTTP. */
    val exerciseRepository: ExerciseRepository = OfflineExerciseRepository(databases, ::localMutationRunner)

    /** Routines (ordered exercises, rotation) of the open account; writes are local (outbox) and never wait for HTTP. */
    val routineRepository: RoutineRepository = OfflineRoutineRepository(databases, ::localMutationRunner, exerciseRepository)

    /** Workout sessions (active workout, start flows, weekly summary) of the open account; writes are local (outbox). */
    val workoutRepository: WorkoutRepository =
        OfflineWorkoutRepository(databases, ::localMutationRunner, exerciseRepository, now = syncClock::nowMillis)

    private val syncApi: SyncApi = createSyncApi(BuildConfig.API_BASE_URL, authSession, serverClock)
    private val bootstrapRepository = BootstrapRepository(syncApi, syncLocks)

    val syncRepository: SyncRepository = SyncRepositoryImpl(databases, syncApi, bootstrapRepository, syncLocks)

    private val retryGate = RetryAfterGate()
    private val syncScheduler = WorkManagerSyncScheduler({ WorkManager.getInstance(application) }, retryGate)

    // Manual syncs go through the scheduler too, so the Retry-After window and the one-run rule hold.
    val syncUseCases: SyncUseCases = DefaultSyncUseCases(
        syncRepository,
        syncScheduler,
        currentUserId = { (rootStateMachine.state.value as? RootState.Ready)?.userId },
    )

    /**
     * Finishes sign-out deletions a crash interrupted. The root state machine waits for it, so a
     * quick sign-in by the marked user can never open a database this sweep would then delete.
     */
    private val startupSweep = appScope.async {
        try {
            accountCleaner.sweepPendingDeletions()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w("GymLogSignOut", "Could not finish a pending local-data deletion", e) // retried at the next open of that account
        }
    }

    private val bootstrapRunner = BootstrapRunner { userId, database, onProgress ->
        bootstrapRepository.run(userId, database, onProgress)
    }

    val rootStateMachine = RootStateMachine(authSession, databases, bootstrapRunner, appScope, clerkConfigured, startupGate = { startupSweep.await() })
        .also { it.start() }

    /** Worker factory for WorkManager's on-demand initialization (see [GymLogApplication]). */
    val syncWorkerFactory = SyncWorkerFactory(
        AppSyncEnvironment(rootStateMachine.state, syncRepository, syncScheduler, retryGate),
    )

    private val syncTriggers = SyncTriggers(appScope, rootStateMachine.state, syncScheduler)

    private val signOutUseCase by lazy {
        SignOutUseCase(
            currentUserId = { (rootStateMachine.state.value as? RootState.Ready)?.userId },
            scheduler = syncScheduler,
            cleaner = accountCleaner,
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
        const val CATALOG_ASSET = "exercises-v1.json"

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
    override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String? = null
}
