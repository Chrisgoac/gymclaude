package com.chrisgoac.gymlog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GymLogApplication).container
        setContent {
            GymLogTheme {
                val rootState by container.rootStateMachine.state.collectAsState()
                GymLogRoot(
                    rootState,
                    authGateway = { container.authGateway },
                    onRetryBootstrap = container.rootStateMachine::retryBootstrap,
                )
            }
        }
    }
}
