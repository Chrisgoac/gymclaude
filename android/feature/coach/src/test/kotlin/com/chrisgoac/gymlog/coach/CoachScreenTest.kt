package com.chrisgoac.gymlog.coach

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.coach.CoachMessage
import com.chrisgoac.gymlog.domain.coach.CoachReportCategory
import com.chrisgoac.gymlog.domain.coach.CoachRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val DISCLAIMER = "Esto es orientación general de entrenamiento, no consejo médico. Ante dolor o dudas de salud, consulta a un profesional."

/** The Coach at 200% font: locked, conversation, streamed/incomplete turn, offline and the report dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp")
class CoachScreenTest {
    @get:Rule val rule = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = CoachActions(
        onBack = { log += "back" },
        onDraftChange = { log += "draft:$it" },
        onSend = { log += "send" },
        onSuggestion = { log += "suggest:$it" },
        onRetry = { log += "retry" },
        onDismissTurn = { log += "dismissTurn" },
        onDismissNotice = { log += "dismissNotice" },
        onReport = { log += "report:$it" },
        onReportCategory = { log += "category:$it" },
        onSubmitReport = { log += "submitReport" },
        onCloseReport = { log += "closeReport" },
    )

    private fun show(state: CoachUiState) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { GymLogTheme { CoachContent(state, actions) } }
        }
        rule.waitForIdle()
    }

    private val thread = listOf(
        CoachMessage("m1", CoachRole.USER, "¿Subo peso?", 1),
        CoachMessage("r1", CoachRole.ASSISTANT, "Sube 2,5 kg la próxima sesión.", 2),
    )

    private fun available(extra: (CoachUiState) -> CoachUiState = { it }) =
        extra(CoachUiState(access = CoachAccess.AVAILABLE, threadLoaded = true))

    private fun scrollTo(text: String) = rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    @Test fun `not Pro shows the explanation and no composer`() {
        show(CoachUiState(access = CoachAccess.LOCKED))
        rule.onNodeWithText("COACH PRO").assertIsDisplayed()
        rule.onNodeWithText("Necesitas una suscripción Pro activa", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Enviar").assertDoesNotExist()
        rule.onNodeWithText("Analiza mi semana").assertDoesNotExist()
    }

    @Test fun `empty thread offers the suggestions, the disclaimer and a big enough send`() {
        show(available())
        rule.onNodeWithText("PREGÚNTALE A TU COACH").assertIsDisplayed()
        CoachViewModel.SUGGESTED_PROMPTS.forEach { scrollTo(it); rule.onNodeWithContentDescription(it).assertIsEnabled() }
        rule.onNodeWithContentDescription("¿Por qué estoy estancado?").performClick()
        assertTrue("suggest:¿Por qué estoy estancado?" in log)
        scrollTo(DISCLAIMER)
        rule.onNodeWithText(DISCLAIMER).assertIsDisplayed()

        val send = rule.onNodeWithContentDescription("Enviar")
        send.assertIsNotEnabled()
        val bounds = send.getUnclippedBoundsInRoot()
        assertTrue("send is at least 48dp: $bounds", bounds.bottom - bounds.top >= 48.dp && bounds.right - bounds.left >= 48.dp)
    }

    @Test fun `messages are spoken with their author and answers can be reported`() {
        show(available { it.copy(messages = thread, draft = "otra pregunta") })
        rule.onNodeWithContentDescription("Tú: ¿Subo peso?").assertExists()
        rule.onNodeWithContentDescription("Coach: Sube 2,5 kg la próxima sesión.").assertExists()
        rule.onNodeWithContentDescription("Enviar").assertIsEnabled().performClick()
        rule.onAllNodesWithContentDescription("Reportar esta respuesta del coach").onFirst().performScrollTo().performClick()
        assertEquals(listOf("send", "report:r1"), log)
    }

    @Test fun `a streaming answer shows what arrived and a polite status`() {
        show(available { it.copy(messages = thread.take(1), turn = CoachTurn("m1", "r1", "Sube 2,5", TurnPhase.STREAMING)) })
        scrollTo("Sube 2,5")
        rule.onNodeWithText("El coach está escribiendo…").assertExists()
        rule.onNodeWithContentDescription("Enviar").assertIsNotEnabled()
        rule.onNodeWithText("Reintentar").assertDoesNotExist()
    }

    @Test fun `an incomplete answer is marked and offers retry and dismiss`() {
        show(available { it.copy(messages = thread.take(1), turn = CoachTurn("m1", "r1", "Sube", TurnPhase.INCOMPLETE, CoachFailure.INTERRUPTED)) })
        scrollTo("Respuesta incompleta")
        rule.onNodeWithText("Se cortó la conexión", substring = true).assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Reintentar"))
        rule.onNodeWithContentDescription("Reintentar").performClick()
        rule.onNodeWithContentDescription("Descartar").performClick()
        assertEquals(listOf("retry", "dismissTurn"), log)
    }

    @Test fun `a refused answer offers no retry`() {
        show(available { it.copy(turn = CoachTurn("m1", null, "", TurnPhase.INCOMPLETE, CoachFailure.REJECTED)) })
        scrollTo("Respuesta incompleta")
        rule.onNodeWithContentDescription("Reintentar").assertDoesNotExist()
        rule.onNodeWithContentDescription("Descartar").assertExists()
    }

    @Test fun `offline is announced and sending is disabled`() {
        show(available { it.copy(online = false, draft = "hola") })
        rule.onNodeWithText("Sin conexión", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Enviar").assertIsNotEnabled()
    }

    @Test fun `the report dialog lists the categories and needs one to submit`() {
        show(available { it.copy(messages = thread, report = CoachReportDraft("r1")) })
        rule.onNodeWithText("Reportar respuesta").assertIsDisplayed()
        listOf("Incorrecta", "Peligrosa", "Da consejo médico", "Ofensiva", "Otro motivo").forEach { rule.onNodeWithContentDescription(it).assertExists() }
        rule.onNodeWithContentDescription("Enviar reporte").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Da consejo médico").performClick()
        assertTrue("category:${CoachReportCategory.MEDICAL}" in log)
    }

    @Test fun `a failed report is announced in the dialog`() {
        show(available { it.copy(messages = thread, report = CoachReportDraft("r1", CoachReportCategory.OTHER, failure = CoachFailure.INTERRUPTED)) })
        rule.onNodeWithText("No se pudo enviar el reporte. Inténtalo de nuevo.").assertExists()
    }

    @Test fun `a chosen category is announced as selected and submit is enabled`() {
        show(available { it.copy(messages = thread, report = CoachReportDraft("r1", CoachReportCategory.HARMFUL)) })
        rule.onNodeWithContentDescription("Peligrosa, seleccionado").assertExists()
        rule.onNodeWithContentDescription("Enviar reporte").performScrollTo().assertIsEnabled().performClick()
        assertTrue("submitReport" in log)
    }
}
