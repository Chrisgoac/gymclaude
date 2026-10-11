package com.chrisgoac.gymlog

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import com.chrisgoac.gymlog.body.BodyClock
import com.chrisgoac.gymlog.body.BodyScreen
import com.chrisgoac.gymlog.body.BodyViewModel
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.body.BodyMeasurement
import com.chrisgoac.gymlog.domain.body.BodyMetrics
import com.chrisgoac.gymlog.domain.body.BodyRepository
import com.chrisgoac.gymlog.domain.body.CustomBodyMetric
import com.chrisgoac.gymlog.domain.body.CustomMetricCheck
import com.chrisgoac.gymlog.domain.body.InvalidCustomMetricException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

private val MADRID = ZoneId.of("Europe/Madrid")
private val TODAY = LocalDate.of(2026, 8, 12)
private val NOW = TODAY.atTime(10, 0).atZone(MADRID).toInstant().toEpochMilli()
private fun dayAt(offset: Long) = TODAY.minusDays(offset).atStartOfDay(MADRID).toInstant().toEpochMilli()

private val VERTICAL_LIST = SemanticsMatcher("vertical scroll container") { SemanticsProperties.VerticalScrollAxisRange in it.config }

private class BodyTestRepository : BodyRepository {
    val rows = MutableStateFlow<List<BodyMeasurement>>(emptyList())
    val customs = MutableStateFlow<List<CustomBodyMetric>>(emptyList())
    val writes = mutableListOf<String>()
    private var seq = 0

    fun seed(type: String, value: Double, at: Long) {
        rows.value = rows.value + BodyMeasurement("m${++seq}", type, value, at, seq.toLong())
    }

    override fun observeCustomMetrics(): Flow<List<CustomBodyMetric>> = customs
    override fun observeMeasuredTypes(): Flow<Set<String>> = rows.map { list -> list.map { it.type }.toSet() }
    override fun observeMeasurements(type: String): Flow<List<BodyMeasurement>> =
        rows.map { list -> list.filter { it.type == type }.sortedWith(compareBy({ it.at }, { it.updatedAt })) }

    override suspend fun addMeasurement(type: String, value: Double, at: Long): String {
        writes += "add:$type:$value"
        seed(type, value, at)
        return "m$seq"
    }

    override suspend fun updateMeasurement(id: String, value: Double, at: Long) {
        writes += "update:$id:$value"
        rows.value = rows.value.map { if (it.id == id) it.copy(value = value, at = at) else it }
    }

    override suspend fun deleteMeasurement(id: String) {
        writes += "delete:$id"
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun addCustomMetric(name: String, unit: String): CustomBodyMetric =
        when (val check = BodyMetrics.checkCustom(name, unit, customs.value)) {
            is CustomMetricCheck.Invalid -> throw InvalidCustomMetricException(check.problem)
            is CustomMetricCheck.Existing -> check.metric
            is CustomMetricCheck.New -> check.metric.also { customs.value = customs.value + it }
        }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class BodyScreensTest {
    @get:Rule val rule = createComposeRule()

    private val repo = BodyTestRepository()

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        rule.waitForIdle()
    }

    private fun show(fontScale: Float = 2f, onBack: () -> Unit = {}, onOpenPhotos: (() -> Unit)? = null, onAddPhoto: (() -> Unit)? = null) {
        val viewModel = BodyViewModel(repo, SavedStateHandle(), { BodyClock(NOW, MADRID) })
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GymLogTheme { BodyScreen(viewModel, onBack = onBack, onOpenPhotos = onOpenPhotos, onAddPhoto = onAddPhoto) }
            }
        }
        idle()
    }

    private fun scrollTo(text: String) {
        rule.onNode(VERTICAL_LIST).performScrollToNode(hasText(text))
    }

    private fun trained() {
        repo.seed("peso", 82.0, dayAt(40))
        repo.seed("peso", 80.5, dayAt(1))
    }

    @Test fun emptyStateOffersTheFirstReadingAndLeadsToThePrivatePhotoGallery() {
        val calls = mutableListOf<String>()
        show(onOpenPhotos = { calls += "open" }, onAddPhoto = { calls += "add" })
        rule.onNodeWithText("Aún no hay mediciones de peso", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("Añadir medición").assertIsDisplayed()
        scrollTo("Añadir foto de progreso")
        rule.onNodeWithText("Añadir foto de progreso").assertIsEnabled().performClick()
        scrollTo("Ver fotos de progreso")
        rule.onNodeWithText("Ver fotos de progreso").assertIsEnabled().performClick()
        assertEquals(listOf("add", "open"), calls)
    }

    @Test fun withoutMediaServicesThePhotoSectionIsHiddenInsteadOfDisabled() {
        show()
        rule.onNodeWithText("Ver fotos de progreso").assertDoesNotExist()
        rule.onNodeWithText("Añadir foto de progreso").assertDoesNotExist()
    }

    @Test fun summaryRowsAreOneStopEachAndSayTheDirectionInWords() {
        trained()
        show()
        rule.onNodeWithContentDescription("Última medición: 80,5 kilogramos", substring = true).assertExists()
        rule.onNodeWithContentDescription("Cambio frente a la anterior: Baja 1,5 kilogramos").assertExists()
        rule.onNodeWithContentDescription("Cambio en el periodo: Baja 1,5 kilogramos", substring = true).assertExists()
    }

    @Test fun theChartHasASpokenSummaryAndATableAlternative() {
        trained()
        show()
        scrollTo("Ver como tabla")
        rule.onNodeWithContentDescription("Evolución de Peso, 2 puntos", substring = true).assertExists()
        rule.onNodeWithText("Ver como tabla").performClick()
        idle()
        rule.onNodeWithText("Ocultar tabla").assertExists()
        assertTrue(rule.onAllNodesWithContentDescription("80,5 kg", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun chipsLetTheirRoleSpeakTheSelectionInsteadOfAddingAWord() {
        trained()
        show()
        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        rule.onNode(selected and hasText("Peso")).assertExists()
        rule.onNode(selected and hasText("Cintura")).assertDoesNotExist()
        rule.onAllNodesWithContentDescription("seleccionado", substring = true).assertCountEquals(0)
    }

    @Test fun historyActionsNameTheReadingTheyAffect() {
        trained()
        show()
        scrollTo("HISTORIAL")
        assertEquals(2, rule.onAllNodesWithContentDescription("Editar la medición del", substring = true).fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithContentDescription("Borrar la medición del", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun theEditorRejectsPermissiveNumbersThenSavesAValidOne() {
        show()
        rule.onNodeWithText("Añadir medición").performClick()
        idle()
        rule.onNode(hasSetTextAction()).performTextInput("Infinity")
        rule.onNodeWithText("Guardar").performClick()
        idle()
        rule.onNodeWithText("Escribe solo un número, por ejemplo 80,5.").assertExists()
        assertTrue(repo.writes.isEmpty())

        rule.onNode(hasSetTextAction()).performTextReplacement("80,5")
        rule.onNodeWithText("Guardar").performClick()
        idle()
        assertEquals(listOf("add:peso:80.5"), repo.writes)
    }

    @Test fun savingAValidValueWritesOnceAndReturnsToTheList() {
        show()
        rule.onNodeWithText("Añadir medición").performClick()
        idle()
        rule.onNode(hasSetTextAction()).performTextInput("80,5")
        rule.onNodeWithText("Guardar").performClick()
        idle()
        assertEquals(listOf("add:peso:80.5"), repo.writes)
        rule.onNodeWithText("Añadir medición").assertIsDisplayed()
        rule.onNodeWithContentDescription("Última medición: 80,5 kilogramos", substring = true).assertExists()
    }

    @Test fun cancellingTheEditorWritesNothing() {
        show()
        rule.onNodeWithText("Añadir medición").performClick()
        idle()
        rule.onNode(hasSetTextAction()).performTextInput("80")
        rule.onNodeWithText("Cancelar").performClick()
        idle()
        assertTrue(repo.writes.isEmpty())
        rule.onNodeWithText("Añadir medición").assertIsDisplayed()
    }

    @Test fun theDatePickerOpensAndDismisses() {
        show()
        rule.onNodeWithText("Añadir medición").performClick()
        idle()
        rule.onNodeWithContentDescription("Cambiar fecha, ", substring = true).performClick()
        idle()
        rule.onNodeWithText("Aceptar").assertExists()
    }

    @Test fun deletingAsksForConfirmationFirst() {
        trained()
        show()
        scrollTo("HISTORIAL")
        rule.onAllNodesWithContentDescription("Borrar la medición del", substring = true).onFirst().performClick()
        idle()
        rule.onNodeWithText("¿Borrar esta medición?").assertIsDisplayed()
        assertTrue(repo.writes.isEmpty())
        rule.onNodeWithContentDescription("Borrar").performClick()
        idle()
        assertEquals(1, repo.writes.size)
        assertTrue(repo.writes.single().startsWith("delete:"))
        assertFalse(rule.onAllNodesWithText("¿Borrar esta medición?").fetchSemanticsNodes().isNotEmpty())
    }
}
