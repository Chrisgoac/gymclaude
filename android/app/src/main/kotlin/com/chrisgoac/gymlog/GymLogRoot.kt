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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chrisgoac.gymlog.auth.AuthScreen
import com.chrisgoac.gymlog.auth.AuthViewModel
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.navigation.GymLogApp
import com.chrisgoac.gymlog.navigation.SettingsServices
import kotlin.math.roundToInt

/** Renders [RootState]: product navigation ([GymLogApp]) exists only once the user is [RootState.Ready]. */
@Composable
fun GymLogRoot(
    state: RootState,
    authGateway: () -> AuthGateway,
    onRetryBootstrap: () -> Unit = {},
    settings: SettingsServices? = null,
) {
    when (state) {
        RootState.Loading -> CenteredStatus { HierroLoadingState(stringResource(R.string.root_loading)) }
        RootState.SignedOut -> AuthRoute(authGateway, notice = null)
        is RootState.ReauthenticationRequired -> AuthRoute(authGateway, notice = stringResource(R.string.root_reauth))
        is RootState.Bootstrapping -> CenteredStatus {
            HierroLoadingState(stringResource(R.string.root_bootstrapping, (state.progress * 100).roundToInt()))
        }
        is RootState.Ready -> GymLogApp(settings = settings)
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
