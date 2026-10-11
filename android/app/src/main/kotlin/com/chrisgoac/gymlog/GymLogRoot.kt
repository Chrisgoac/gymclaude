package com.chrisgoac.gymlog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chrisgoac.gymlog.auth.AuthScreen
import com.chrisgoac.gymlog.auth.AuthViewModel
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.timer.RestTimerController
import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.domain.workout.FinishWorkoutUseCase
import com.chrisgoac.gymlog.domain.workout.HistoryRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutLogRepository
import kotlinx.coroutines.flow.Flow
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.navigation.GymLogApp
import com.chrisgoac.gymlog.navigation.HistoryEditServices
import com.chrisgoac.gymlog.navigation.AchievementsServices
import com.chrisgoac.gymlog.navigation.BodyServices
import com.chrisgoac.gymlog.navigation.AiPlanServices
import com.chrisgoac.gymlog.navigation.CoachServices
import com.chrisgoac.gymlog.navigation.MediaServices
import com.chrisgoac.gymlog.navigation.ProgressServices
import com.chrisgoac.gymlog.navigation.SettingsServices
import kotlin.math.roundToInt

/** Renders [RootState]: product navigation ([GymLogApp]) exists only once the user is [RootState.Ready]. */
@Composable
fun GymLogRoot(
    state: RootState,
    authGateway: () -> AuthGateway,
    onRetryBootstrap: () -> Unit = {},
    /** Account deletion: retry a failed local wipe, dismiss the final notice, open Play's subscription page. */
    onRetryDeletion: () -> Unit = {},
    onDeletionAcknowledged: () -> Unit = {},
    onOpenUri: (String) -> Unit = {},
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
    aiPlans: AiPlanServices? = null,
    syncState: Flow<SyncUiState>? = null,
    openWorkoutRequest: String? = null,
    onOpenWorkoutHandled: () -> Unit = {},
) {
    when (state) {
        RootState.Loading -> CenteredStatus { HierroLoadingState(stringResource(R.string.root_loading)) }
        RootState.SignedOut -> AuthRoute(authGateway, notice = null)
        is RootState.ReauthenticationRequired -> AuthRoute(authGateway, notice = stringResource(R.string.root_reauth))
        is RootState.Bootstrapping -> CenteredStatus {
            HierroLoadingState(stringResource(R.string.root_bootstrapping, (state.progress * 100).roundToInt()))
        }
        is RootState.Ready -> AccountScope(state.userId) {
            GymLogApp(
                settings = settings,
                exercises = exercises,
                routines = routines,
                workouts = workouts,
                workoutLog = workoutLog,
                restTimer = restTimer,
                finishWorkout = finishWorkout,
                history = history,
                historyEditing = historyEditing,
                progress = progress,
                body = body,
                achievements = achievements,
                media = media,
                coach = coach,
                aiPlans = aiPlans,
                syncState = syncState,
                openWorkoutRequest = openWorkoutRequest,
                onOpenWorkoutHandled = onOpenWorkoutHandled,
            )
        }
        is RootState.DeletingAccount -> CenteredStatus {
            if (state.failed) {
                HierroEmptyState(
                    title = stringResource(R.string.root_deleting_failed_title),
                    message = stringResource(R.string.root_deleting_failed_message),
                    actionLabel = stringResource(R.string.root_retry),
                    onAction = onRetryDeletion,
                )
            } else {
                HierroLoadingState(stringResource(R.string.root_deleting))
            }
        }
        is RootState.AccountDeleted -> CenteredStatus {
            HierroCard {
                HierroText(stringResource(R.string.root_deleted_title).uppercase(), style = HierroTheme.typography.title)
                HierroText(stringResource(R.string.root_deleted_message), color = HierroTheme.colors.textMuted)
                val subscription = state.subscription?.takeIf { it.willRenew }
                if (subscription != null) {
                    HierroText(stringResource(R.string.root_deleted_subscription), color = HierroTheme.colors.error)
                    HierroButton(stringResource(R.string.root_deleted_manage), { onOpenUri(subscription.manageUrl) })
                }
                HierroButton(
                    stringResource(R.string.root_deleted_continue),
                    onDeletionAcknowledged,
                    variant = if (subscription != null) HierroButtonVariant.Secondary else HierroButtonVariant.Primary,
                )
            }
        }
        is RootState.Fatal -> CenteredStatus {
            val config = state.message == "config"
            HierroEmptyState(
                title = stringResource(if (config) R.string.root_fatal_config_title else R.string.root_fatal_title),
                message = stringResource(
                    when {
                        config -> R.string.root_fatal_config_message
                        state.canRetry -> R.string.root_fatal_retry_message
                        else -> R.string.root_fatal_message
                    },
                ),
                actionLabel = if (state.canRetry) stringResource(R.string.root_retry) else null,
                onAction = if (state.canRetry) onRetryBootstrap else null,
            )
        }
    }
}

@Composable
private fun AuthRoute(authGateway: () -> AuthGateway, notice: String?) {
    // Activity-scoped: survives the screen leaving and re-entering composition.
    val viewModel = viewModel { AuthViewModel(authGateway()) }
    AuthScreen(viewModel, notice = notice)
}

/**
 * Gives the signed-in shell a [ViewModelStoreOwner] of its own, keyed by [userId]: ViewModels
 * created inside (Settings, navigation back stack state) never outlive the account. The store lives
 * in an activity-scoped holder so it survives rotation, and is cleared when the account changes or
 * the shell leaves composition for any other root state (sign-out, expiry).
 */
@Composable
private fun AccountScope(userId: String, content: @Composable () -> Unit) {
    val holder = viewModel { AccountStoreHolder() }
    key(userId) {
        val owner = remember(holder) { holder.ownerFor(userId) }
        val activity = LocalActivity.current
        DisposableEffect(owner) {
            // Leaving composition because of a configuration change keeps the store (rotation).
            onDispose { if (activity?.isChangingConfigurations != true) holder.release(userId) }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
    }
}

/** Activity-scoped: holds the [ViewModelStore] of the account whose shell is showing. */
internal class AccountStoreHolder : ViewModel() {
    private var userId: String? = null
    private var store: ViewModelStore? = null

    fun ownerFor(userId: String): ViewModelStoreOwner {
        if (this.userId != userId) {
            store?.clear()
            this.userId = userId
            store = ViewModelStore()
        }
        val current = checkNotNull(store)
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = current
        }
    }

    fun release(userId: String) {
        if (this.userId != userId) return
        store?.clear()
        store = null
        this.userId = null
    }

    override fun onCleared() {
        store?.clear()
    }
}

@Composable
private fun CenteredStatus(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(HierroTheme.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.Center) { content() }
    }
}
