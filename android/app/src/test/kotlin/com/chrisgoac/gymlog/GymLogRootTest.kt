package com.chrisgoac.gymlog

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GymLogRootTest {
    @get:Rule val rule = createComposeRule()

    private fun show(state: RootState, onRetry: () -> Unit = {}) = rule.setContent {
        GymLogTheme { GymLogRoot(state, authGateway = { error("not shown") }, onRetryBootstrap = onRetry) }
    }

    @Test fun retryableBootstrapFailureShowsReintentar() {
        var retries = 0
        show(RootState.Fatal("bootstrap", canRetry = true)) { retries++ }
        rule.onNodeWithText("Reintentar").performClick()
        assertEquals(1, retries)
    }

    @Test fun nonRetryableFailureHasNoRetryAction() {
        show(RootState.Fatal("bootstrap"))
        rule.onAllNodesWithText("Reintentar").assertCountEquals(0)
    }
}
