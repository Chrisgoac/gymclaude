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
import androidx.compose.ui.test.onRoot
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

    /**
     * At 200% system font scale the screen CONTENT scales fully and stays unclipped. The tab bar
     * deliberately caps at 1.3x (five labels share the width), so for tabs we only assert they stay
     * legible: one line, not clipped, inside their tab.
     */
    fun ComposeContentTestRule.bodyScalesAndTabsStayLegibleAtTwoHundredPercentFont() {
        onNodeWithText("ENTRENAR").assertIsDisplayed()
        // Body: a card's text is displayed, measured at the doubled size, and not clipped.
        val body = onNodeWithText("Entrenar · fila 1")
        body.assertIsDisplayed()
        val bodyLayout = textLayout(body)
        assertFalse("body text clipped vertically at 200% font", bodyLayout.didOverflowHeight)
        // Every glyph is laid out inside the card: the node fits within the root horizontally.
        val bounds = body.fetchSemanticsNode().boundsInRoot
        val rootWidth = onRoot().fetchSemanticsNode().size.width
        assertTrue("body text extends past the screen at 200% font", bounds.left >= 0f && bounds.right <= rootWidth)
        // Body is 16sp: at 2x its laid-out line must be at least 16sp * 2 tall in px (it really scaled).
        val density = bodyLayout.layoutInput.density
        assertTrue("body text did not scale to 200%", density.fontScale == 2f)
        val minLinePx = 16 * density.fontScale * density.density
        assertTrue("body line too short for 2x scale", bodyLayout.size.height >= minLinePx)
        // Tabs (capped at 1.3x by design): legible and contained.
        onAllNodes(isSelectable()).assertCountEquals(5)
        tabLabels.forEach { label ->
            val node = tab(label)
            node.assertIsDisplayed()
            node.assertHeightIsAtLeast(48.dp)
            val layout = textLayout(onNode(hasText(label) and isSelectable()))
            assertFalse("tab label '$label' is clipped", layout.didOverflowHeight)
            assertTrue("tab label '$label' is wider than its tab", layout.size.width <= node.fetchSemanticsNode().size.width)
            assertEquals("tab label '$label' must stay on one line", 1, layout.lineCount)
        }
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val semantics = node.fetchSemanticsNode()
        val target = if (SemanticsActions.GetTextLayoutResult in semantics.config) semantics else semantics.children.first()
        target.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertTrue("text should be measured", results.isNotEmpty())
        return results.first()
    }

    fun ComposeContentTestRule.secondaryRouteOpensAndGoesBack() {
        onNodeWithText("AJUSTES").performClick()
        onNodeWithText("AJUSTES").assertIsDisplayed()
        onNodeWithText("ATRÁS").performClick()
        onNodeWithText("ENTRENAR").assertIsDisplayed()
        onNodeWithText("Coach Pro").performClick()
        onNodeWithText("COACH PRO").assertIsDisplayed()
    }
}
