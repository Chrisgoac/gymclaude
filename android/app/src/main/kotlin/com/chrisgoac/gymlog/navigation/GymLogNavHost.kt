package com.chrisgoac.gymlog.navigation

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
import androidx.compose.runtime.getValue
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
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroTab
import com.chrisgoac.gymlog.designsystem.component.HierroTabBar
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.R

/** Five-tab shell. Route content is smoke-only; real feature screens replace it in later tasks. */
@Composable
fun GymLogApp(navController: NavHostController = rememberNavController()) {
    val colors = HierroTheme.colors
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            GymLogNavHost(navController, Modifier.widthIn(max = 720.dp).fillMaxSize())
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
private fun GymLogNavHost(navController: NavHostController, modifier: Modifier) {
    NavHost(navController, startDestination = GymLogGraph.Train, modifier = modifier) {
        navigation<GymLogGraph.Train>(startDestination = GymLogDestination.Train) {
            composable<GymLogDestination.Train> {
                SmokeScreen(GymLogDestination.Train, navController, showSecondaryCards = true)
            }
        }
        navigation<GymLogGraph.Routines>(startDestination = GymLogDestination.Routines) {
            composable<GymLogDestination.Routines> { SmokeScreen(GymLogDestination.Routines, navController) }
        }
        navigation<GymLogGraph.Exercises>(startDestination = GymLogDestination.Exercises) {
            composable<GymLogDestination.Exercises> { SmokeScreen(GymLogDestination.Exercises, navController) }
        }
        navigation<GymLogGraph.Progress>(startDestination = GymLogDestination.Progress) {
            composable<GymLogDestination.Progress> { SmokeScreen(GymLogDestination.Progress, navController) }
        }
        navigation<GymLogGraph.History>(startDestination = GymLogDestination.History) {
            composable<GymLogDestination.History> { SmokeScreen(GymLogDestination.History, navController) }
        }
        composable<GymLogDestination.Coach> { SmokeScreen(GymLogDestination.Coach, navController, secondary = true) }
        composable<GymLogDestination.Body> { SmokeScreen(GymLogDestination.Body, navController, secondary = true) }
        composable<GymLogDestination.Achievements> { SmokeScreen(GymLogDestination.Achievements, navController, secondary = true) }
        composable<GymLogDestination.Settings> { SmokeScreen(GymLogDestination.Settings, navController, secondary = true) }
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
