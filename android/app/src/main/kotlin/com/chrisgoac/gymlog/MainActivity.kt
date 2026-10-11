package com.chrisgoac.gymlog

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.settings.DeviceSettings
import com.chrisgoac.gymlog.domain.settings.ThemeMode
import com.chrisgoac.gymlog.timer.RestTimerNotifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GymLogApplication).container
        takeWorkoutRequest(intent)
        // One flow for the activity: calling observe() inside the composition would build a new flow,
        // and restart its collection, on every recomposition.
        val deviceSettings = container.deviceSettings.observe()
        setContent {
            val device by deviceSettings.collectAsState(DeviceSettings())
            GymLogTheme(
                darkTheme = when (device.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
            ) {
                val rootState by container.rootStateMachine.state.collectAsState()
                GymLogRoot(
                    rootState,
                    authGateway = { container.authGateway },
                    onRetryBootstrap = container.rootStateMachine::retryBootstrap,
                    settings = container.settingsServices,
                    exercises = container.exerciseRepository,
                    routines = container.routineRepository,
                    workouts = container.workoutRepository,
                    workoutLog = container.workoutLogRepository,
                    restTimer = container.restTimer,
                    finishWorkout = container.finishWorkout,
                    history = container.historyRepository,
                    historyEditing = container.historyEditing,
                    progress = container.progressServices,
                    body = container.bodyServices,
                    achievements = container.achievementsServices,
                    media = container.mediaServices,
                    syncState = container.syncUiState,
                    openWorkoutRequest = container.openWorkoutRequest.collectAsState().value,
                    onOpenWorkoutHandled = { container.openWorkoutRequest.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeWorkoutRequest(intent)
    }

    /** The rest notification opens the workout it was raised for. */
    private fun takeWorkoutRequest(intent: Intent?) {
        val sessionId = intent?.getStringExtra(RestTimerNotifier.EXTRA_OPEN_WORKOUT) ?: return
        intent.removeExtra(RestTimerNotifier.EXTRA_OPEN_WORKOUT)
        (application as GymLogApplication).container.openWorkoutRequest.value = sessionId
    }
}
