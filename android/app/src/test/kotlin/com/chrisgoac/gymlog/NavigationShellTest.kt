package com.chrisgoac.gymlog

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.chrisgoac.gymlog.NavigationShellChecks.fiveBottomDestinationsInOrder
import com.chrisgoac.gymlog.NavigationShellChecks.readableAtTwoHundredPercentFont
import com.chrisgoac.gymlog.NavigationShellChecks.secondaryRouteOpensAndGoesBack
import com.chrisgoac.gymlog.NavigationShellChecks.selectedSemanticsFollowNavigation
import com.chrisgoac.gymlog.NavigationShellChecks.showShell
import com.chrisgoac.gymlog.NavigationShellChecks.tabStateIsPreserved
import com.chrisgoac.gymlog.NavigationShellChecks.touchTargetsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class NavigationShellTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fiveBottomDestinations() = with(rule) { showShell(); fiveBottomDestinationsInOrder() }
    @Test fun selectedSemantics() = with(rule) { showShell(); selectedSemanticsFollowNavigation() }
    @Test fun tabStatePreserved() = with(rule) { showShell(); tabStateIsPreserved() }
    @Test fun touchTargets() = with(rule) { showShell(); touchTargetsAtLeast48dp() }
    @Test fun readableAt200PercentFont() = with(rule) { showShell(fontScale = 2f); readableAtTwoHundredPercentFont() }
    @Test fun darkThemeRenders() = with(rule) { showShell(darkTheme = true); fiveBottomDestinationsInOrder() }
    @Test fun secondaryRoutes() = with(rule) { showShell(); secondaryRouteOpensAndGoesBack() }
}
