package com.chrisgoac.gymlog

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.chrisgoac.gymlog.NavigationShellChecks.fiveBottomDestinationsInOrder
import com.chrisgoac.gymlog.NavigationShellChecks.bodyScalesAndTabsStayLegibleAtTwoHundredPercentFont
import com.chrisgoac.gymlog.NavigationShellChecks.secondaryRouteOpensAndGoesBack
import com.chrisgoac.gymlog.NavigationShellChecks.selectedSemanticsFollowNavigation
import com.chrisgoac.gymlog.NavigationShellChecks.showShell
import com.chrisgoac.gymlog.NavigationShellChecks.tabStateIsPreserved
import com.chrisgoac.gymlog.NavigationShellChecks.touchTargetsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fiveBottomDestinations() = with(rule) { showShell(); fiveBottomDestinationsInOrder() }
    @Test fun selectedSemantics() = with(rule) { showShell(); selectedSemanticsFollowNavigation() }
    @Test fun tabStatePreserved() = with(rule) { showShell(); tabStateIsPreserved() }
    @Test fun touchTargets() = with(rule) { showShell(); touchTargetsAtLeast48dp() }
    @Test fun bodyScalesAndTabsLegibleAt200PercentFont() = with(rule) { showShell(fontScale = 2f); bodyScalesAndTabsStayLegibleAtTwoHundredPercentFont() }
    @Test fun secondaryRoutes() = with(rule) { showShell(); secondaryRouteOpensAndGoesBack() }
}
