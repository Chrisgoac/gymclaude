package com.chrisgoac.gymlog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.navigation.GymLogApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Shared by the JVM (Robolectric) NavigationShellTest and the device NavigationSmokeTest. */
internal object NavigationShellChecks {
    val tabLabels = listOf("Entrenar", "Rutinas", "Ejercicios", "Progreso", "Historial")

    fun ComposeContentTestRule.showShell(fontScale: Float = 1f, darkTheme: Boolean = false) {
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                GymLogTheme(darkTheme = darkTheme) { GymLogApp() }
            }
        }
    }

    private fun ComposeContentTestRule.tab(label: String): SemanticsNodeInteraction =
        onNode(isSelectable() and hasText(label))

    fun ComposeContentTestRule.fiveBottomDestinationsInOrder() {
        onAllNodes(isSelectable()).assertCountEquals(5)
        val lefts = tabLabels.map { label ->
            val node = tab(label)
            node.assertIsDisplayed()
            node.fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals("tabs must be laid out in spec order", lefts.sorted(), lefts)
    }

    fun ComposeContentTestRule.selectedSemanticsFollowNavigation() {
        tab("Entrenar").assertIsSelected()
        (tabLabels - "Entrenar").forEach { tab(it).assertIsNotSelected() }
        tab("Progreso").performClick()
        tab("Progreso").assertIsSelected()
        tab("Entrenar").assertIsNotSelected()
        onNodeWithText("PROGRESO").assertIsDisplayed()
    }

    fun ComposeContentTestRule.tabStateIsPreserved() {
        onNode(hasScrollAction()).performScrollToNode(hasText("Entrenar · fila 30"))
        onNodeWithText("Entrenar · fila 30").assertIsDisplayed()
        tab("Rutinas").performClick()
        onNodeWithText("RUTINAS").assertIsDisplayed()
        tab("Entrenar").performClick()
        // Restored back stack keeps the scrolled position instead of resetting to the top.
        onNodeWithText("Entrenar · fila 30").assertIsDisplayed()
    }

    fun ComposeContentTestRule.touchTargetsAtLeast48dp() {
        val clickable = onAllNodes(hasClickAction())
        val count = clickable.fetchSemanticsNodes().size
        assertTrue("expected tabs + header action + cards to be clickable", count >= 7)
        repeat(count) { index ->
            clickable[index].assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }

    fun ComposeContentTestRule.readableAtTwoHundredPercentFont() {
        onNodeWithText("ENTRENAR").assertIsDisplayed()
        onNodeWithText("Entrenar · fila 1").performScrollToNodeIfNeeded()
        onAllNodes(isSelectable()).assertCountEquals(5)
        tabLabels.forEach { label ->
            val node = tab(label)
            node.assertIsDisplayed()
            node.assertHeightIsAtLeast(48.dp)
            val results = mutableListOf<TextLayoutResult>()
            val textNode = onNode(hasText(label) and isSelectable()).fetchSemanticsNode()
            val child = textNode.children.firstOrNull() ?: textNode
            child.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            assertTrue("tab label '$label' should be measured", results.isNotEmpty())
            val layout = results.first()
            assertFalse("tab label '$label' is clipped vertically at 200% font", layout.didOverflowHeight)
            assertTrue("tab label '$label' is wider than its tab", layout.size.width <= node.fetchSemanticsNode().size.width)
            assertEquals("tab label '$label' must stay on one line", 1, results.first().lineCount)
        }
    }

    fun ComposeContentTestRule.secondaryRouteOpensAndGoesBack() {
        onNodeWithText("AJUSTES").performClick()
        onNodeWithText("AJUSTES").assertIsDisplayed()
        onNodeWithText("ATRÁS").performClick()
        onNodeWithText("ENTRENAR").assertIsDisplayed()
        onNodeWithText("Coach Pro").performClick()
        onNodeWithText("COACH PRO").assertIsDisplayed()
    }

    private fun SemanticsNodeInteraction.performScrollToNodeIfNeeded() = assertIsDisplayed()
}
