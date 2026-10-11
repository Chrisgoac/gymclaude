package com.chrisgoac.gymlog.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.chrisgoac.gymlog.domain.timer.RestTimerController
import com.chrisgoac.gymlog.timer.NotificationPermissionPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutLogRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.history.HistoryDetailScreen
import com.chrisgoac.gymlog.history.HistoryDetailViewModel
import com.chrisgoac.gymlog.history.HistoryEditorScreen
import com.chrisgoac.gymlog.history.HistoryEditorViewModel
import com.chrisgoac.gymlog.domain.history.SaveWorkoutDraftUseCase
import androidx.activity.compose.BackHandler
import com.chrisgoac.gymlog.history.HistoryListScreen
import com.chrisgoac.gymlog.history.HistoryListViewModel
import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.domain.workout.FinishWorkoutUseCase
import com.chrisgoac.gymlog.domain.workout.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.chrisgoac.gymlog.train.ActiveWorkoutChoice
import com.chrisgoac.gymlog.train.ActiveWorkoutScreen
import com.chrisgoac.gymlog.timer.TrackWorkoutScreenVisibility
import com.chrisgoac.gymlog.train.WorkoutViewModel
import com.chrisgoac.gymlog.train.StartWorkoutUseCase
import com.chrisgoac.gymlog.train.TrainEvent
import com.chrisgoac.gymlog.train.TrainHomeScreen
import com.chrisgoac.gymlog.train.TrainHomeViewModel
import com.chrisgoac.gymlog.routines.MesocycleGeneratorScreen
import com.chrisgoac.gymlog.routines.MesocycleGeneratorViewModel
import com.chrisgoac.gymlog.routines.RoutineEditorScreen
import com.chrisgoac.gymlog.routines.RoutineEditorViewModel
import com.chrisgoac.gymlog.routines.RoutineListScreen
import com.chrisgoac.gymlog.routines.RoutineListViewModel
import androidx.lifecycle.createSavedStateHandle
import com.chrisgoac.gymlog.exercises.ExerciseDetailScreen
import com.chrisgoac.gymlog.exercises.ExerciseDetailViewModel
import com.chrisgoac.gymlog.exercises.ExerciseEditorScreen
import com.chrisgoac.gymlog.exercises.ExerciseEditorViewModel
import com.chrisgoac.gymlog.exercises.ExerciseListScreen
import com.chrisgoac.gymlog.exercises.ExerciseListViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chrisgoac.gymlog.domain.SignOutUseCase
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.settings.BackupDocuments
import com.chrisgoac.gymlog.settings.BackupScreen
import com.chrisgoac.gymlog.settings.BackupViewModel
import com.chrisgoac.gymlog.domain.backup.BackupRepository
import com.chrisgoac.gymlog.settings.GymsScreen
import com.chrisgoac.gymlog.settings.GymsViewModel
import com.chrisgoac.gymlog.settings.TrainingSettingsScreen
import com.chrisgoac.gymlog.settings.TrainingSettingsViewModel
import com.chrisgoac.gymlog.domain.settings.DeviceSettingsRepository
import com.chrisgoac.gymlog.domain.settings.GymRepository
import com.chrisgoac.gymlog.domain.settings.SettingsRepository
import com.chrisgoac.gymlog.settings.SettingsScreen
import com.chrisgoac.gymlog.settings.SettingsUiState
import com.chrisgoac.gymlog.settings.SettingsViewModel
import com.chrisgoac.gymlog.settings.SyncDiagnosticsScreen
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroTab
import com.chrisgoac.gymlog.designsystem.component.HierroTabBar
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.R
import androidx.lifecycle.createSavedStateHandle
import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
import com.chrisgoac.gymlog.domain.coach.CoachRepository
import com.chrisgoac.gymlog.coach.CoachScreen
import com.chrisgoac.gymlog.coach.CoachViewModel
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.stats.StatsFilter
import com.chrisgoac.gymlog.domain.stats.StatsRepository
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.achievements.AchievementsScreen
import com.chrisgoac.gymlog.achievements.AchievementsViewModel
import com.chrisgoac.gymlog.domain.achievement.AchievementRepository
import com.chrisgoac.gymlog.body.BodyScreen
import com.chrisgoac.gymlog.body.BodyViewModel
import com.chrisgoac.gymlog.body.BodyClock
import com.chrisgoac.gymlog.domain.body.BodyRepository
import com.chrisgoac.gymlog.domain.media.MediaRepository
import com.chrisgoac.gymlog.domain.media.PhotoCaptureTargets
import com.chrisgoac.gymlog.exercises.ExercisePhotosViewModel
import com.chrisgoac.gymlog.progress.PhotoClock
import com.chrisgoac.gymlog.progress.PhotoGalleryViewModel
import com.chrisgoac.gymlog.progress.ProgressGalleryScreen
import com.chrisgoac.gymlog.domain.math.WeightUnit
import kotlinx.coroutines.flow.StateFlow
import com.chrisgoac.gymlog.progress.ExerciseProgressScreen
import com.chrisgoac.gymlog.progress.IntelligentReportScreen
import com.chrisgoac.gymlog.progress.IntelligentReportViewModel
import com.chrisgoac.gymlog.domain.ai.IntelligentReportRepository
import com.chrisgoac.gymlog.domain.ai.MesocycleRepository
import com.chrisgoac.gymlog.progress.ExerciseProgressViewModel
import com.chrisgoac.gymlog.progress.ProgressClock
import com.chrisgoac.gymlog.progress.ProgressScreen
import com.chrisgoac.gymlog.progress.ProgressViewModel
import java.time.ZoneId

/** What the Settings routes need; null keeps them as smoke screens (shell tests). */
class SettingsServices(
    val sync: SyncUseCases,
    val signOut: SignOutUseCase,
    val gyms: GymRepository? = null,
    val training: SettingsRepository? = null,
    val device: DeviceSettingsRepository? = null,
    val backup: BackupServices? = null,
)

/** Backup export/import: the repository, the SAF document opener and the clock/zone for the file name and dates. */
class BackupServices(val repository: BackupRepository, val documents: BackupDocuments, val now: () -> Long, val zone: () -> ZoneId)

/** What the history editor needs: the atomic save and the server-corrected clock (future-date check). */
class HistoryEditServices(val save: SaveWorkoutDraftUseCase, val now: () -> Long)

/**
 * What the Progress routes need: derived statistics, the Pro entitlement (report action), the day-rollover
 * ticker and the server-corrected clock + device zone the numbers are computed with.
 */
class ProgressServices(
    val stats: StatsRepository,
    val entitlements: EntitlementReader,
    val ticks: Flow<Unit>,
    val now: () -> Long,
    val zone: () -> ZoneId,
) {
    fun clock(): ProgressClock = ProgressClock(now(), zone())
}

/**
 * What the Body route needs: the repository, the server-corrected clock + device zone and the weight display unit
 * (kilograms until the synced units setting exists, Plan 4 Task 6).
 */
class BodyServices(
    val repository: BodyRepository,
    val now: () -> Long,
    val zone: () -> ZoneId,
    val weightUnit: StateFlow<WeightUnit>,
) {
    fun clock(): BodyClock = BodyClock(now(), zone())
}

/**
 * What the private-photo screens need: the offline media repository, camera capture slots (no CAMERA
 * permission) and the server-corrected clock + device zone the photo dates are chosen in.
 */
class MediaServices(
    val repository: MediaRepository,
    val captureTargets: PhotoCaptureTargets,
    val now: () -> Long,
    val zone: () -> ZoneId,
) {
    fun clock(): PhotoClock = PhotoClock(now(), zone())
}

/**
 * What the Coach route needs: the thread + streamed turns, the Pro entitlement (presentation only: the
 * server decides) and connectivity (the Coach needs the network and never queues a question).
 */
class CoachServices(
    val repository: CoachRepository,
    val entitlements: EntitlementReader,
    val online: Flow<Boolean>,
)

/**
 * What the Pro planning routes need: AI mesocycle drafts, the intelligent report, the Pro entitlement
 * (presentation only) and connectivity (both need the network), plus the device zone report dates are shown in.
 */
class AiPlanServices(
    val mesocycles: MesocycleRepository,
    val reports: IntelligentReportRepository,
    val entitlements: EntitlementReader,
    val online: Flow<Boolean>,
    val zone: () -> ZoneId,
)

/** What the Achievements route needs: the streak (stats), unlocked milestones + records, and the device zone dates are shown in. */
class AchievementsServices(
    val stats: StatsRepository,
    val achievements: AchievementRepository,
    val zone: () -> ZoneId,
)

/** Five-tab shell. Route content is smoke-only except Settings; real feature screens replace it in later tasks. */
@Composable
fun GymLogApp(
    navController: NavHostController = rememberNavController(),
    settings: SettingsServices? = null,
    exercises: ExerciseRepository? = null,
    routines: RoutineRepository? = null,
    workouts: WorkoutRepository? = null,
    workoutLog: WorkoutLogRepository? = null,
    restTimer: RestTimerController? = null,
    finishWorkout: FinishWorkoutUseCase? = null,
    history: HistoryRepository? = null,
    historyEditing: HistoryEditServices? = null,
    progress: ProgressServices? = null,
    body: BodyServices? = null,
    achievements: AchievementsServices? = null,
    media: MediaServices? = null,
    coach: CoachServices? = null,
    syncState: Flow<SyncUiState>? = null,
    openWorkoutRequest: String? = null,
    aiPlans: AiPlanServices? = null,
    onOpenWorkoutHandled: () -> Unit = {},
) {
    val colors = HierroTheme.colors
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // The rest notification asks for its workout; Missing/other-account sessions end in the screen's own "not found" state.
    LaunchedEffect(openWorkoutRequest) {
        openWorkoutRequest?.let {
            navController.navigate(GymLogDestination.ActiveWorkout(it)) { launchSingleTop = true }
            onOpenWorkoutHandled()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            GymLogNavHost(navController, Modifier.widthIn(max = 720.dp).fillMaxSize(), settings, exercises, routines, workouts, workoutLog, restTimer, finishWorkout, history, historyEditing, progress, body, achievements, media, coach, syncState, aiPlans)
        }
        HierroTabBar {
            BottomDestinations.forEach { item ->
                val selected = currentDestination?.hierarchy?.any { it.hasRoute(item.graph::class) } == true
                HierroTab(
                    label = stringResource(item.start.title),
                    selected = selected,
                    onClick = {
                        navController.navigate(item.graph) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
        Spacer(Modifier.fillMaxWidth().background(colors.surface).windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun GymLogNavHost(
    navController: NavHostController,
    modifier: Modifier,
    settings: SettingsServices?,
    exercises: ExerciseRepository?,
    routines: RoutineRepository?,
    workouts: WorkoutRepository?,
    workoutLog: WorkoutLogRepository?,
    restTimer: RestTimerController?,
    finishWorkout: FinishWorkoutUseCase?,
    history: HistoryRepository?,
    historyEditing: HistoryEditServices?,
    progress: ProgressServices?,
    body: BodyServices?,
    achievements: AchievementsServices?,
    media: MediaServices?,
    coach: CoachServices?,
    syncState: Flow<SyncUiState>?,
    aiPlans: AiPlanServices?,
) {
    // Scoped to the account's shell (see GymLogRoot) so Settings and its diagnostics share one state
    // and it survives navigation and rotation, but never another account.
    // One Train ViewModel per account shell: the Train tab and the Routines tab's "Empezar" both start
    // workouts through it, so they share the gym selection and the one-active-workout guard.
    val trainViewModel = if (workouts != null && routines != null) {
        viewModel {
            TrainHomeViewModel(
                workouts, routines, StartWorkoutUseCase(workouts),
                defaultGymId = settings?.device?.observe()?.map { it.defaultGymId } ?: flowOf(null),
            )
        }
    } else {
        null
    }
    LaunchedEffect(trainViewModel) {
        trainViewModel?.eventFlow?.collect { event ->
            when (event) {
                is TrainEvent.OpenWorkout -> navController.navigate(GymLogDestination.ActiveWorkout(event.sessionId)) { launchSingleTop = true }
                // The error card lives on the Train tab: take the user there if the start came from elsewhere.
                is TrainEvent.StartFailed -> if (navController.currentDestination?.hierarchy?.any { it.hasRoute(GymLogGraph.Train::class) } != true) {
                    navController.navigate(GymLogGraph.Train) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    }
    val settingsViewModel = settings?.let { services -> viewModel { SettingsViewModel(services.sync, services.signOut) } }
    // An unanswered sign-out confirmation survives Settings -> diagnostics and rotation (it lives in
    // the ViewModel); it is dropped, and sync resumes, once the user is on any other destination.
    // The back stack is null until the graph is set (also right after a rotation): that is "not
    // known yet", not "left Settings", or a pending confirmation would be dropped on rotation.
    val destination by navController.currentBackStackEntryAsState()
    val inSettings: Boolean? = destination?.destination?.let {
        it.hasRoute(GymLogDestination.Settings::class) || it.hasRoute(GymLogDestination.SyncDiagnostics::class)
    }
    LaunchedEffect(inSettings) { if (inSettings == false) settingsViewModel?.onSettingsLeft() }

    Box(modifier) {
        NavHost(navController, startDestination = GymLogGraph.Train, modifier = Modifier.fillMaxSize()) {
            navigation<GymLogGraph.Train>(startDestination = GymLogDestination.Train) {
                composable<GymLogDestination.Train> {
                    if (trainViewModel == null) {
                        SmokeScreen(GymLogDestination.Train, navController, showSecondaryCards = true)
                    } else {
                        // Presentation only: the Coach card opens for Pro accounts; the server re-checks every request.
                        val isPro by remember(coach) { coach?.entitlements?.observeIsPro()?.catch { emit(false) } ?: flowOf(false) }.collectAsState(false)
                        TrainHomeScreen(
                            trainViewModel,
                            onOpenWorkout = { navController.navigate(GymLogDestination.ActiveWorkout(it)) { launchSingleTop = true } },
                            onOpenBody = { navController.navigate(GymLogDestination.Body) },
                            onOpenAchievements = { navController.navigate(GymLogDestination.Achievements) },
                            onSettings = { navController.navigate(GymLogDestination.Settings) },
                            onOpenCoach = if (coach != null && isPro) {
                                { navController.navigate(GymLogDestination.Coach) { launchSingleTop = true } }
                            } else {
                                null
                            },
                        )
                    }
                }
                composable<GymLogDestination.ActiveWorkout> { entry ->
                    val sessionId = entry.toRoute<GymLogDestination.ActiveWorkout>().sessionId
                    if (workouts == null || workoutLog == null || exercises == null || routines == null) {
                        SmokeScreen(GymLogDestination.Train, navController, secondary = true)
                    } else {
                        // Typed drafts live in the entry's SavedStateHandle (process death); the session itself is in Room.
                        // The first rest timer asks for the notification permission (API 33+), in context.
                        val context = LocalContext.current
                        val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                        val onRestStarted by rememberUpdatedState<(Int?) -> Unit> { rest ->
                            if (rest != null && rest > 0 && NotificationPermissionPrompt.consumePrompt(context)) {
                                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        val viewModel = viewModel(key = sessionId) {
                            WorkoutViewModel(
                                sessionId, workouts, workoutLog, exercises, routines, createSavedStateHandle(),
                                onSetCompleted = { onRestStarted(it) },
                                restTimer = restTimer,
                                finishWorkout = finishWorkout,
                            )
                        }
                        // While this entry is started, the rest timer's end is announced here, not by notification.
                        TrackWorkoutScreenVisibility(entry)
                        ActiveWorkoutScreen(viewModel, onBack = { navController.popBackStack() })
                    }
                }
            }
            navigation<GymLogGraph.Routines>(startDestination = GymLogDestination.Routines) {
                composable<GymLogDestination.Routines> {
                    if (routines == null) {
                        SmokeScreen(GymLogDestination.Routines, navController)
                    } else {
                        val viewModel = viewModel { RoutineListViewModel(routines) }
                        // Presentation only: the server re-checks Pro on every AI request.
                        val aiPlansIsPro by remember(aiPlans) { aiPlans?.entitlements?.observeIsPro()?.catch { emit(false) } ?: flowOf(false) }.collectAsState(false)
                        RoutineListScreen(
                            viewModel,
                            onOpen = { navController.navigate(GymLogDestination.RoutineEditor(it)) },
                            onCreate = { navController.navigate(GymLogDestination.RoutineEditor()) },
                            onStartRoutine = { trainViewModel?.startRoutine(it) },
                            onSettings = { navController.navigate(GymLogDestination.Settings) },
                            onGenerateMesocycle = aiPlans?.let { { navController.navigate(GymLogDestination.MesocycleGenerator) { launchSingleTop = true } } },
                            mesocycleIsPro = aiPlansIsPro,
                        )
                    }
                }
                composable<GymLogDestination.RoutineEditor> { entry ->
                    val id = entry.toRoute<GymLogDestination.RoutineEditor>().id
                    if (routines == null || exercises == null) {
                        SmokeScreen(GymLogDestination.Routines, navController, secondary = true)
                    } else {
                        // The draft is kept in the entry's SavedStateHandle, so it survives process death.
                        val viewModel = viewModel { RoutineEditorViewModel(routines, exercises, createSavedStateHandle(), id) }
                        RoutineEditorScreen(
                            viewModel,
                            onBack = { navController.popBackStack() },
                            onDone = { navController.popBackStack() },
                        )
                    }
                }
            }
            navigation<GymLogGraph.Exercises>(startDestination = GymLogDestination.Exercises) {
                composable<GymLogDestination.Exercises> {
                    if (exercises == null) {
                        SmokeScreen(GymLogDestination.Exercises, navController)
                    } else {
                        val viewModel = viewModel { ExerciseListViewModel(exercises) }
                        ExerciseListScreen(
                            viewModel,
                            onOpen = { navController.navigate(GymLogDestination.ExerciseDetail(it)) },
                            onCreate = { navController.navigate(GymLogDestination.ExerciseEditor()) },
                            onSettings = { navController.navigate(GymLogDestination.Settings) },
                        )
                    }
                }
                composable<GymLogDestination.ExerciseDetail> { entry ->
                    val id = entry.toRoute<GymLogDestination.ExerciseDetail>().id
                    if (exercises == null) {
                        SmokeScreen(GymLogDestination.Exercises, navController, secondary = true)
                    } else {
                        val viewModel = viewModel { ExerciseDetailViewModel(exercises, id) }
                        val photos = media?.let { services ->
                            viewModel(key = "photos:$id") { ExercisePhotosViewModel(services.repository, services.captureTargets, id, createSavedStateHandle()) }
                        }
                        ExerciseDetailScreen(
                            viewModel,
                            onBack = { navController.popBackStack() },
                            onEdit = { navController.navigate(GymLogDestination.ExerciseEditor(it)) },
                            photos = photos,
                        )
                    }
                }
                composable<GymLogDestination.ExerciseEditor> { entry ->
                    val id = entry.toRoute<GymLogDestination.ExerciseEditor>().id
                    if (exercises == null) {
                        SmokeScreen(GymLogDestination.Exercises, navController, secondary = true)
                    } else {
                        val viewModel = viewModel { ExerciseEditorViewModel(exercises, id) }
                        ExerciseEditorScreen(
                            viewModel,
                            onBack = { navController.popBackStack() },
                            onSaved = { savedId ->
                                if (id == null) {
                                    // A new exercise replaces the editor with its detail page.
                                    navController.navigate(GymLogDestination.ExerciseDetail(savedId)) {
                                        popUpTo<GymLogDestination.ExerciseEditor> { inclusive = true }
                                    }
                                } else {
                                    navController.popBackStack()
                                }
                            },
                        )
                    }
                }
            }
            navigation<GymLogGraph.Progress>(startDestination = GymLogDestination.Progress) {
                composable<GymLogDestination.Progress> {
                    if (progress == null || exercises == null || workouts == null) {
                        SmokeScreen(GymLogDestination.Progress, navController)
                    } else {
                        val viewModel = viewModel {
                            ProgressViewModel(
                                progress.stats, workouts, exercises, progress.entitlements,
                                createSavedStateHandle(), progress.ticks, progress::clock,
                            )
                        }
                        ProgressScreen(
                            viewModel = viewModel,
                            onOpenExercise = { id, filter ->
                                navController.navigate(GymLogDestination.ProgressExercise(id, filter.period.wire, filter.gym.gymId ?: GymFilter.ALL_WIRE))
                            },
                            onOpenReport = aiPlans?.let { { navController.navigate(GymLogDestination.IntelligentReport) { launchSingleTop = true } } },
                            onOpenBody = body?.let { { navController.navigate(GymLogDestination.Body) { launchSingleTop = true } } },
                            onOpenAchievements = achievements?.let { { navController.navigate(GymLogDestination.Achievements) { launchSingleTop = true } } },
                            onOpenPhotos = media?.let { { navController.navigate(GymLogDestination.ProgressPhotos()) { launchSingleTop = true } } },
                        )
                    }
                }
                composable<GymLogDestination.ProgressExercise> { entry ->
                    val args = entry.toRoute<GymLogDestination.ProgressExercise>()
                    if (progress == null || exercises == null || workouts == null) {
                        SmokeScreen(GymLogDestination.Progress, navController, secondary = true)
                    } else {
                        val viewModel = viewModel(key = args.exerciseId) {
                            ExerciseProgressViewModel(
                                args.exerciseId, progress.stats, exercises, workouts,
                                createSavedStateHandle(), progress.ticks, progress::clock,
                                StatsFilter(Period.fromWire(args.period) ?: Period.THREE_MONTHS, GymFilter.fromWire(args.gym)),
                            )
                        }
                        ExerciseProgressScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                    }
                }
            }
            navigation<GymLogGraph.History>(startDestination = GymLogDestination.History) {
                composable<GymLogDestination.History> {
                    if (history == null || workouts == null) {
                        SmokeScreen(GymLogDestination.History, navController)
                    } else {
                        // Shell-scoped: filters and the loaded window survive opening a detail and rotation.
                        val viewModel = viewModel { HistoryListViewModel(history, workouts, syncState ?: emptyFlow()) }
                        HistoryListScreen(
                            viewModel,
                            onOpen = { navController.navigate(GymLogDestination.HistoryDetail(it)) },
                            onSettings = { navController.navigate(GymLogDestination.Settings) },
                        )
                    }
                }
                composable<GymLogDestination.HistoryDetail> { entry ->
                    val sessionId = entry.toRoute<GymLogDestination.HistoryDetail>().sessionId
                    if (history == null || workouts == null) {
                        SmokeScreen(GymLogDestination.History, navController, secondary = true)
                    } else {
                        val viewModel = viewModel(key = sessionId) { HistoryDetailViewModel(history, workouts, sessionId) }
                        HistoryDetailScreen(
                            viewModel,
                            onBack = { navController.popBackStack() },
                            onEdit = if (historyEditing != null && exercises != null) {
                                { navController.navigate(GymLogDestination.HistoryEditor(sessionId)) { launchSingleTop = true } }
                            } else {
                                null
                            },
                        )
                    }
                }
                composable<GymLogDestination.HistoryEditor> { entry ->
                    val sessionId = entry.toRoute<GymLogDestination.HistoryEditor>().sessionId
                    if (history == null || workouts == null || exercises == null || historyEditing == null) {
                        SmokeScreen(GymLogDestination.History, navController, secondary = true)
                    } else {
                        // The draft (and the original it came from) live in the entry's SavedStateHandle: rotation and
                        // process death keep the edit; nothing is written until "Guardar cambios".
                        val viewModel = viewModel(key = "edit:$sessionId") {
                            HistoryEditorViewModel(
                                sessionId, history, workouts, exercises, historyEditing.save, createSavedStateHandle(), now = historyEditing.now,
                            )
                        }
                        // System back asks before discarding changes, like the Cancel action.
                        BackHandler { viewModel.requestExit() }
                        HistoryEditorScreen(viewModel, onExit = { navController.popBackStack() })
                    }
                }
            }
            composable<GymLogDestination.Coach> {
                if (coach == null) {
                    SmokeScreen(GymLogDestination.Coach, navController, secondary = true)
                } else {
                    // Entry-scoped: the turn survives rotation; leaving the Coach stops it, and the unanswered
                    // question is offered for retry (same message id) when the Coach is opened again.
                    val viewModel = viewModel { CoachViewModel(coach.repository, coach.entitlements, coach.online, createSavedStateHandle()) }
                    CoachScreen(viewModel, onBack = { navController.popBackStack() })
                }
            }
            composable<GymLogDestination.MesocycleGenerator> {
                if (aiPlans == null) {
                    SmokeScreen(GymLogDestination.Routines, navController, secondary = true)
                } else {
                    // Entry-scoped: the draft survives rotation; the pending request id survives process death.
                    val viewModel = viewModel { MesocycleGeneratorViewModel(aiPlans.mesocycles, aiPlans.entitlements, aiPlans.online, createSavedStateHandle()) }
                    MesocycleGeneratorScreen(viewModel, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
                }
            }
            composable<GymLogDestination.IntelligentReport> {
                if (aiPlans == null) {
                    SmokeScreen(GymLogDestination.Progress, navController, secondary = true)
                } else {
                    val viewModel = viewModel { IntelligentReportViewModel(aiPlans.reports, aiPlans.entitlements, aiPlans.online) }
                    IntelligentReportScreen(viewModel, aiPlans.zone, onBack = { navController.popBackStack() })
                }
            }
            composable<GymLogDestination.Body> {
                if (body == null) {
                    SmokeScreen(GymLogDestination.Body, navController, secondary = true)
                } else {
                    val viewModel = viewModel { BodyViewModel(body.repository, createSavedStateHandle(), body::clock, body.weightUnit) }
                    BodyScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onOpenPhotos = media?.let { { navController.navigate(GymLogDestination.ProgressPhotos()) { launchSingleTop = true } } },
                        onAddPhoto = media?.let { { navController.navigate(GymLogDestination.ProgressPhotos(capture = true)) { launchSingleTop = true } } },
                    )
                }
            }
            composable<GymLogDestination.ProgressPhotos> { entry ->
                if (media == null) {
                    SmokeScreen(GymLogDestination.Progress, navController, secondary = true)
                } else {
                    val startWithCapture = entry.toRoute<GymLogDestination.ProgressPhotos>().capture
                    val viewModel = viewModel { PhotoGalleryViewModel(media.repository, media.captureTargets, media::clock, createSavedStateHandle()) }
                    // Opens the new-photo form once per visit (rotation keeps it in the ViewModel, not re-opened here).
                    var opened by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(startWithCapture) {
                        if (startWithCapture && !opened) {
                            opened = true
                            viewModel.onAddPhoto()
                        }
                    }
                    ProgressGalleryScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }
            }
            composable<GymLogDestination.Achievements> {
                if (achievements == null) {
                    SmokeScreen(GymLogDestination.Achievements, navController, secondary = true)
                } else {
                    val viewModel = viewModel { AchievementsViewModel(achievements.stats, achievements.achievements, achievements.zone) }
                    AchievementsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }
            }
            composable<GymLogDestination.Settings> {
                if (settingsViewModel == null) {
                    SmokeScreen(GymLogDestination.Settings, navController, secondary = true)
                } else {
                    SettingsScreen(
                        settingsViewModel,
                        onBack = { navController.popBackStack() },
                        onSyncDetails = { navController.navigate(GymLogDestination.SyncDiagnostics) },
                        onGyms = settings?.takeIf { it.gyms != null && it.device != null }?.let { { navController.navigate(GymLogDestination.Gyms) } },
                        onTraining = settings?.takeIf { it.training != null && it.device != null }?.let { { navController.navigate(GymLogDestination.TrainingSettings) } },
                        onBackup = settings?.backup?.let { { navController.navigate(GymLogDestination.Backup) } },
                    )
                }
            }
            composable<GymLogDestination.Gyms> {
                val gyms = settings?.gyms
                val device = settings?.device
                if (gyms == null || device == null) {
                    SmokeScreen(GymLogDestination.Gyms, navController, secondary = true)
                } else {
                    GymsScreen(viewModel { GymsViewModel(gyms, device) }, onBack = { navController.popBackStack() })
                }
            }
            composable<GymLogDestination.TrainingSettings> {
                val training = settings?.training
                val device = settings?.device
                if (training == null || device == null || routines == null) {
                    SmokeScreen(GymLogDestination.TrainingSettings, navController, secondary = true)
                } else {
                    TrainingSettingsScreen(viewModel { TrainingSettingsViewModel(training, device, routines) }, onBack = { navController.popBackStack() })
                }
            }
            composable<GymLogDestination.Backup> {
                val backup = settings?.backup
                if (backup == null) {
                    SmokeScreen(GymLogDestination.Backup, navController, secondary = true)
                } else {
                    BackupScreen(
                        viewModel { BackupViewModel(backup.repository, backup.documents, backup.now, backup.zone) },
                        onBack = { navController.popBackStack() },
                        zone = backup.zone,
                    )
                }
            }
            composable<GymLogDestination.SyncDiagnostics> {
                val diagnostics by (settingsViewModel?.state ?: EmptySettingsState).collectAsState()
                SyncDiagnosticsScreen(diagnostics.diagnostics, onBack = { navController.popBackStack() })
            }
        }
        // "Tienes un entrenamiento en curso": over whichever tab (Train or Routines) started.
        trainViewModel?.let { ActiveWorkoutChoice(it) }
    }
}

/** Accessible route label + scrollable rows (lets tests prove scroll/tab state survives tab switches). */
@Composable
private fun SmokeScreen(
    destination: GymLogDestination,
    navController: NavHostController,
    secondary: Boolean = false,
    showSecondaryCards: Boolean = false,
) {
    val title = stringResource(destination.title)
    Column(Modifier.fillMaxSize()) {
        HierroScreenHeader(title) {
            if (secondary) {
                HierroHeaderAction(stringResource(R.string.nav_back), onClick = { navController.popBackStack() })
            } else {
                HierroHeaderAction(stringResource(R.string.nav_settings), onClick = { navController.navigate(GymLogDestination.Settings) })
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showSecondaryCards) {
                items(listOf(GymLogDestination.Coach, GymLogDestination.Body, GymLogDestination.Achievements)) { target ->
                    val label = stringResource(target.title)
                    HierroCard(onClick = { navController.navigate(target) }) {
                        HierroText(label, style = HierroTheme.typography.title)
                    }
                }
            }
            items((1..SMOKE_ROWS).toList()) { index ->
                HierroCard { HierroText(stringResource(R.string.smoke_row, title, index)) }
            }
        }
    }
}

const val SMOKE_ROWS = 40

private val EmptySettingsState = kotlinx.coroutines.flow.MutableStateFlow(SettingsUiState())
