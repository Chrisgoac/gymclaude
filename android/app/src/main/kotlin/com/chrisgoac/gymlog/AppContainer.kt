package com.chrisgoac.gymlog

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.chrisgoac.gymlog.auth.ClerkAuthGateway
import com.chrisgoac.gymlog.auth.ClerkAuthSessionProvider
import com.chrisgoac.gymlog.data.achievement.OfflineAchievementRepository
import com.chrisgoac.gymlog.data.backup.RoomBackupRepository
import com.chrisgoac.gymlog.domain.backup.BackupImportEffects
import com.chrisgoac.gymlog.domain.backup.BackupRepository
import com.chrisgoac.gymlog.navigation.BackupServices
import com.chrisgoac.gymlog.sync.ContentResolverBackupDocuments
import com.chrisgoac.gymlog.data.catalog.CatalogAsset
import com.chrisgoac.gymlog.domain.achievement.AchievementReconcileScheduler
import com.chrisgoac.gymlog.domain.achievement.AchievementRepository
import com.chrisgoac.gymlog.domain.achievement.ReconcileAchievementsUseCase
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.stats.InvalidatingHistoryRepository
import com.chrisgoac.gymlog.domain.stats.InvalidatingSyncRepository
import com.chrisgoac.gymlog.navigation.AchievementsServices
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import com.chrisgoac.gymlog.data.exercise.OfflineExerciseRepository
import com.chrisgoac.gymlog.data.routine.OfflineRoutineRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutLogRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutRepository
import com.chrisgoac.gymlog.domain.workout.FinishWorkoutUseCase
import com.chrisgoac.gymlog.domain.workout.HistoryRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutLogRepository
import com.chrisgoac.gymlog.data.body.OfflineBodyRepository
import com.chrisgoac.gymlog.domain.body.BodyRepository
import com.chrisgoac.gymlog.domain.math.WeightUnit
import com.chrisgoac.gymlog.data.stats.OfflineStatsRepository
import com.chrisgoac.gymlog.data.settings.OfflineGymRepository
import com.chrisgoac.gymlog.data.settings.OfflineSettingsRepository
import com.chrisgoac.gymlog.devicesettings.DataStoreDeviceSettings
import com.chrisgoac.gymlog.devicesettings.UserScopedPreferenceStores
import com.chrisgoac.gymlog.domain.settings.DeviceSettingsRepository
import com.chrisgoac.gymlog.domain.settings.GymRepository
import com.chrisgoac.gymlog.domain.settings.SettingsRepository
import com.chrisgoac.gymlog.domain.stats.InvalidatingSettingsRepository
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.io.File
import com.chrisgoac.gymlog.data.workout.OfflineHistoryRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutEditRepository
import com.chrisgoac.gymlog.domain.history.DefaultSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.stats.InvalidatingSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.stats.StatsInvalidation
import com.chrisgoac.gymlog.domain.stats.StatsRepository
import com.chrisgoac.gymlog.navigation.HistoryEditServices
import com.chrisgoac.gymlog.navigation.BodyServices
import com.chrisgoac.gymlog.navigation.ProgressServices
import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
import com.chrisgoac.gymlog.domain.time.DayRollover
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
import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.domain.toUiState
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
import com.chrisgoac.gymlog.network.ServerClock
import com.chrisgoac.gymlog.network.SyncApi
import com.chrisgoac.gymlog.network.createSyncApi
import com.chrisgoac.gymlog.network.createMediaApi
import com.chrisgoac.gymlog.data.media.OfflineMediaRepository
import com.chrisgoac.gymlog.domain.media.MediaRepository
import com.chrisgoac.gymlog.media.CameraCaptureTargets
import com.chrisgoac.gymlog.media.MediaUploadWorkerFactory
import com.chrisgoac.gymlog.media.MediaWorkEnvironment
import com.chrisgoac.gymlog.media.WorkManagerMediaScheduler
import com.chrisgoac.gymlog.model.media.MediaWorkOutcome
import com.chrisgoac.gymlog.sync.SyncReadiness
import androidx.work.DelegatingWorkerFactory
import kotlinx.coroutines.flow.filterNotNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import com.chrisgoac.gymlog.domain.SignOutUseCase
import com.chrisgoac.gymlog.navigation.SettingsServices
import com.chrisgoac.gymlog.domain.timer.RestTimerController
import com.chrisgoac.gymlog.sync.AppSyncEnvironment
import com.chrisgoac.gymlog.timer.RestTimerAccountWatcher
import com.chrisgoac.gymlog.timer.RestTimerDeletionHook
import com.chrisgoac.gymlog.timer.RestTimerNotifier
import com.chrisgoac.gymlog.timer.UserScopedRestTimerStore
import com.chrisgoac.gymlog.sync.AchievementTriggers
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID
import java.time.ZoneId

/** Manual DI root, one per process. */
class AppContainer(private val application: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val databaseManager = UserDatabaseManagerImpl(application)
    private val syncLocks = SyncLocks()
    private val restTimerNotifier = RestTimerNotifier(application)
    private fun currentUserHash(): String? =
        (rootStateMachine.state.value as? RootState.Ready)?.userId?.let(UserDatabaseManagerImpl::userHash)

    private val restTimerStore: UserScopedRestTimerStore = UserScopedRestTimerStore(application.filesDir, currentUserHash = ::currentUserHash)
    private val restTimerDeletionHook = RestTimerDeletionHook(restTimerStore::forget, restTimerNotifier::cancel, ::currentUserHash)

    // The hook releases the open DataStore before the directory goes; account changes are handled by RestTimerAccountWatcher.
    private val accountPrefs = UserScopedPreferenceStores(application.filesDir)
    private val accountCleaner = RoomLocalAccountCleaner(
        application, databaseManager, syncLocks,
        onLocalDataDeleted = { hash ->
            restTimerDeletionHook.onDeleted(hash)
            accountPrefs.forget(hash)
            mediaScheduler.cancelAll(hash)
        },
    )

    /** The account's durable rest timer (state per user, wake-up by [RestTimerNotifier]). */
    val restTimer = RestTimerController(restTimerStore, restTimerNotifier)

    /** Workout the user asked to open from the rest notification; consumed by the navigation shell. */
    val openWorkoutRequest = MutableStateFlow<String?>(null)

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
    private val offlineWorkouts =
        OfflineWorkoutRepository(databases, ::localMutationRunner, now = syncClock::nowMillis)
    val workoutRepository: WorkoutRepository = offlineWorkouts

    /** Set logging inside a workout (sets, order, notes, history for suggestions); local writes only. */
    val workoutLogRepository: WorkoutLogRepository = OfflineWorkoutLogRepository(databases, ::localMutationRunner)

    /** Stale-data events (SaveResult scopes) for derived data outside the Room stats queries (Coach snapshot, achievements). */
    val statsInvalidation: StatsInvalidation = StatsInvalidation()

    /** Finish-workout use case: completion write + rest-timer cleanup (the notification shares [restTimer]). */
    val finishWorkout: FinishWorkoutUseCase =
        FinishWorkoutUseCase(
            offlineWorkouts, offlineWorkouts, restTimer,
            onFinished = { statsInvalidation.publish(setOf(InvalidationScope.ACHIEVEMENTS)) },
        )

    /** Finished-workout history (filters, detail, change gym, delete) of the open account; writes are local (outbox). */
    val historyRepository: HistoryRepository = InvalidatingHistoryRepository(
        OfflineHistoryRepository(databases, ::localMutationRunner, exerciseRepository, routineRepository, now = syncClock::nowMillis),
        statsInvalidation,
    )

    /**
     * Full edit of a past workout: validation against the server clock, then ONE local mutation (conflict-checked);
     * each successful save publishes its invalidated scopes to [statsInvalidation].
     */
    val historyEditing: HistoryEditServices = HistoryEditServices(
        save = InvalidatingSaveWorkoutDraftUseCase(
            DefaultSaveWorkoutDraftUseCase(OfflineWorkoutEditRepository(databases, ::localMutationRunner), now = syncClock::nowMillis),
            statsInvalidation,
        ),
        now = syncClock::nowMillis,
    )

    /** Progress statistics of the open account (server-corrected clock, device time zone); read-only. */
    val statsRepository: StatsRepository =
        OfflineStatsRepository(databases, now = syncClock::nowMillis, zone = ZoneId::systemDefault)

    /**
     * Pro entitlement. TODO(Plan 5): replace with the Play Billing + server entitlement reader; until then
     * nobody is Pro, so the intelligent-report action shows locked.
     */
    val entitlements: EntitlementReader = object : EntitlementReader {
        override fun observeIsPro(): Flow<Boolean> = flowOf(false)
    }

    /** Progress screens: stats, entitlement, local-midnight ticker and the server-corrected clock in the device zone. */
    val progressServices: ProgressServices = ProgressServices(
        stats = statsRepository,
        entitlements = entitlements,
        ticks = DayRollover.ticks(syncClock::nowMillis, ZoneId::systemDefault),
        now = syncClock::nowMillis,
        zone = ZoneId::systemDefault,
    )

    /** Achievements (unlocked milestones, personal records) and their reconciliation; writes are local (outbox). */
    private val offlineAchievements = OfflineAchievementRepository(databases, ::localMutationRunner)
    val achievementRepository: AchievementRepository = offlineAchievements

    /** Unlocks / tombstones achievements from the history. Triggered by [startAchievementReconciliation], never by a screen. */
    val reconcileAchievements = ReconcileAchievementsUseCase(offlineAchievements, now = syncClock::nowMillis, zone = ZoneId::systemDefault)

    val achievementsServices: AchievementsServices = AchievementsServices(statsRepository, achievementRepository, ZoneId::systemDefault)

    /** Body measurements (+ the PWA's custom-metric setting) of the open account. */
    val bodyRepository: BodyRepository = OfflineBodyRepository(databases, ::localMutationRunner, now = syncClock::nowMillis)

    /** Synced PWA settings (goal, progression, volume goals) of the open account; writes are local (outbox). */
    /** Weekly-target changes publish ACHIEVEMENTS (non-local) so reconciliation re-checks the evidence. */
    val settingsRepository: SettingsRepository =
        InvalidatingSettingsRepository(OfflineSettingsRepository(databases, ::localMutationRunner), statsInvalidation)

    /**
     * Backup export/import of the open account (PWA file format). A committed import invalidates every derived
     * scope (achievement reconcile runs unlock/retain only: not a local edit) and asks for a sync.
     */
    val backupRepository: BackupRepository = RoomBackupRepository(
        databases, ::localMutationRunner, now = syncClock::nowMillis,
        onImported = BackupImportEffects(statsInvalidation, requestSync = { syncTriggers.onLocalMutation() }),
    )

    /** Gyms of the open account; writes are local (outbox). */
    val gymRepository: GymRepository = OfflineGymRepository(databases, ::localMutationRunner)

    /**
     * Theme, units, default gym, onboarding, notification answer and last filters: DataStore only, never the outbox.
     * Theme/units live with the device, the rest with the signed-in account under `users/<hash>/`.
     */
    val deviceSettings: DeviceSettingsRepository by lazy {
        val hash = rootStateMachine.state
            .map { (it as? RootState.Ready)?.userId?.let(UserDatabaseManagerImpl::userHash) }
            .stateIn(appScope, SharingStarted.Eagerly, currentUserHash())
        DataStoreDeviceSettings(deviceStore, accountPrefs, hash)
    }
    private val deviceStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            File(application.filesDir, "device_settings.preferences_pb")
        }
    }

    /** Body route; weights are always shown in kilograms (PWA parity; there is no unit setting). */
    val bodyServices: BodyServices by lazy {
        BodyServices(
            repository = bodyRepository,
            now = syncClock::nowMillis,
            zone = ZoneId::systemDefault,
            weightUnit = MutableStateFlow(WeightUnit.KG),
        )
    }

    private val syncApi: SyncApi = createSyncApi(BuildConfig.API_BASE_URL, authSession, serverClock)
    private val bootstrapRepository = BootstrapRepository(syncApi, syncLocks)

    // A pull that brought rows publishes the invalidation scopes (achievement reconciliation listens to them).
    val syncRepository: SyncRepository =
        InvalidatingSyncRepository(SyncRepositoryImpl(databases, syncApi, bootstrapRepository, syncLocks), statsInvalidation)

    private val retryGate = RetryAfterGate()
    private val syncScheduler = WorkManagerSyncScheduler({ WorkManager.getInstance(application) }, retryGate)

    // Before startupSweep: the cleaner's deletion hook (which the sweep may run) cancels queued photos through it.
    private val mediaScheduler by lazy {
        WorkManagerMediaScheduler({ WorkManager.getInstance(application) }, syncScheduler::isSuspended)
    }

    /** Camera capture URIs (FileProvider, no CAMERA permission) under the account's private directory. */
    val cameraCaptureTargets by lazy { CameraCaptureTargets(application) }

    private val offlineMedia = OfflineMediaRepository(
        databases = databases,
        api = createMediaApi(BuildConfig.API_BASE_URL, authSession, serverClock),
        filesRoot = application.filesDir,
        cacheRoot = application.cacheDir,
        openSource = { uri -> application.contentResolver.openInputStream(android.net.Uri.parse(uri)) },
        schedule = { hash, id -> mediaScheduler.schedule(hash, id) },
        locks = syncLocks,
        now = syncClock::nowMillis,
    )

    /** Private photos of the open account: offline queue + v1 media endpoints (never the outbox). */
    val mediaRepository: MediaRepository = offlineMedia

    // Manual syncs go through the scheduler too, so the Retry-After window and the one-run rule hold.
    val syncUseCases: SyncUseCases = DefaultSyncUseCases(
        syncRepository,
        syncScheduler,
        currentUserId = { (rootStateMachine.state.value as? RootState.Ready)?.userId },
    )

    /** What the user needs to know about sync, for the small indicator on History. */
    val syncUiState: Flow<SyncUiState> = syncUseCases.observeStatus().map { it.toUiState() }

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

    val rootStateMachine: RootStateMachine = RootStateMachine(authSession, databases, bootstrapRunner, appScope, clerkConfigured, startupGate = { startupSweep.await() })
        .also { it.start() }

    private val syncEnvironment = AppSyncEnvironment(rootStateMachine.state, syncRepository, syncScheduler, retryGate)

    /** Worker factory for WorkManager's on-demand initialization (see [GymLogApplication]): sync and photo uploads. */
    val workerFactory: DelegatingWorkerFactory = DelegatingWorkerFactory().apply {
        addFactory(SyncWorkerFactory(syncEnvironment))
        addFactory(
            MediaUploadWorkerFactory(
                object : MediaWorkEnvironment {
                    override suspend fun readiness(userHash: String): SyncReadiness = syncEnvironment.readiness(userHash)
                    override suspend fun process(userId: String, mediaId: String): MediaWorkOutcome = offlineMedia.process(userId, mediaId)
                },
            ),
        )
    }

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
        if (clerkConfigured) {
            SettingsServices(
                syncUseCases, signOutUseCase, gyms = gymRepository, training = settingsRepository, device = deviceSettings,
                backup = BackupServices(backupRepository, ContentResolverBackupDocuments(application.contentResolver), syncClock::nowMillis, ZoneId::systemDefault),
            )
        } else {
            null
        }
    }

    /**
     * Reconciles achievements after a finished workout, a history edit and a sync pull (all publish
     * [InvalidationScope.ACHIEVEMENTS]) and when an account becomes ready; debounced, and deferred while a workout is recorded.
     */
    private fun startAchievementReconciliation() {
        val workoutActive = offlineWorkouts.observeActiveWorkout().map { it != null }.distinctUntilChanged().catch { emit(false) }
        val scheduler = AchievementReconcileScheduler(
            statsInvalidation,
            AchievementTriggers.readyAccounts(rootStateMachine.state),
            AchievementTriggers.canRun(rootStateMachine.state, workoutActive),
            currentAccount = ::currentUserHash,
            reconcile = { allowTombstone -> reconcileAchievements(allowTombstone) },
            onError = { Log.w("GymLogAchievements", "Could not reconcile achievements", it) },
        )
        appScope.launch { scheduler.run() }
    }

    /** Re-schedules each account's queued photos when it becomes ready (process death, new sign-in). */
    private suspend fun resumeQueuedMedia() {
        rootStateMachine.state.map { (it as? RootState.Ready)?.userId }.distinctUntilChanged().filterNotNull().collect { userId ->
            try {
                val hash = UserDatabaseManagerImpl.userHash(userId)
                cameraCaptureTargets.sweep(hash)
                offlineMedia.pendingWork(userId).forEach { mediaScheduler.schedule(hash, it, resume = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("GymLogMedia", "Could not resume queued photos", e)
            }
        }
    }

    /** Starts the sync triggers: ready account, foreground, local mutations, connectivity (bootstrap retry). */
    fun startSync() {
        syncTriggers.start()
        startAchievementReconciliation()
        appScope.launch { RestTimerAccountWatcher(rootStateMachine.state, restTimer::detach).run() }
        appScope.launch { resumeQueuedMedia() }
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
