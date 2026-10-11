package com.chrisgoac.gymlog.progress

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.media.MediaRejection
import com.chrisgoac.gymlog.domain.media.MediaUiState
import com.chrisgoac.gymlog.domain.media.PhotoMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

private val AUG_12 = LocalDate.of(2026, 8, 12)
private val CLOCK = PhotoClock(dayMillis(2026, 8, 12) + 10 * 3_600_000L, MADRID)

private fun photo(id: String, date: LocalDate, angle: String = "frente", state: MediaUiState = MediaUiState.Available("/none/$id.jpg"), note: String? = null, concealed: Boolean = false) =
    ProgressPhotoUi(id, date.atStartOfDay(MADRID).toInstant().toEpochMilli(), date, angle, note, state, concealed)

private class Calls {
    val log = mutableListOf<String>()
}

private fun actions(calls: Calls = Calls()) = PhotoActions(
    onBack = { calls.log += "back" },
    onAdd = { calls.log += "add" },
    onRetryLoad = { calls.log += "retryLoad" },
    onDismissMessage = { calls.log += "dismiss" },
    onRequestImage = { calls.log += "request:$it" },
    onRetryImage = { calls.log += "retryImage:$it" },
    onOpen = { calls.log += "open:$it" },
    onReveal = { calls.log += "reveal:$it" },
    onCloseViewer = { calls.log += "closeViewer" },
    onAskDelete = { calls.log += "askDelete:$it" },
    onCancelDelete = { calls.log += "cancelDelete" },
    onConfirmDelete = { calls.log += "confirmDelete" },
    onRetryUpload = { calls.log += "retryUpload:$it" },
    onCompare = { calls.log += "compare" },
    onCloseCompare = { calls.log += "closeCompare" },
    onCompareAngle = { calls.log += "angle:$it" },
    onCompareA = { calls.log += "a:$it" },
    onCompareB = { calls.log += "b:$it" },
    onSwap = { calls.log += "swap" },
    onDraftAngle = { calls.log += "draftAngle:$it" },
    onDraftDate = { calls.log += "draftDate:$it" },
    onDraftNote = { calls.log += "draftNote:$it" },
    onCancelDraft = { calls.log += "cancelDraft" },
    onPickPhoto = { calls.log += "pick" },
    onTakePhoto = { calls.log += "camera" },
)

/** Gallery, capture form, viewer and comparison at 200% font: text alternatives, state rendering, touch targets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp")
class PhotoScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun show(fontScale: Float = 2f, content: @Composable () -> Unit) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { GymLogTheme { content() } }
        }
        rule.waitForIdle()
    }

    private fun ready(vararg photos: ProgressPhotoUi, extra: (PhotoGalleryUiState) -> PhotoGalleryUiState = { it }): PhotoGalleryUiState {
        val days = photos.groupBy { it.date }.map { PhotoDay(it.key, it.value) }.sortedByDescending { it.date }
        return extra(PhotoGalleryUiState(status = PhotoGalleryStatus.Ready, days = days, clock = CLOCK, canCompare = false))
    }

    // ---- Gallery: every state has a text alternative ----

    @Test fun everyMediaStateIsRenderedAsTextAndSpokenWithItsDateAndAngle() {
        val cases = listOf(
            photo("local", AUG_12, "frente", MediaUiState.LocalPending("/none/local.jpg")) to "Guardada en este dispositivo, pendiente de subir",
            photo("up", AUG_12, "lado", MediaUiState.Uploading(null, "/none/up.jpg")) to "Subiendo",
            photo("ok", AUG_12.minusDays(1), "espalda", MediaUiState.Available("/none/ok.jpg")) to "",
            photo("loading", AUG_12.minusDays(2), "frente", MediaUiState.Available(null)) to "Cargando foto",
            photo("off", AUG_12.minusDays(3), "frente", MediaUiState.RemoteUnavailableOffline) to "Sin conexión: esta foto aún no está guardada en este dispositivo",
            photo("retry", AUG_12.minusDays(4), "lado", MediaUiState.RetryableFailure("/none/r.jpg", "timeout")) to "No se pudo subir ahora; se reintentará sola",
            photo("rej", AUG_12.minusDays(5), "espalda", MediaUiState.Rejected(MediaRejection.TOO_LARGE, "/none/j.jpg")) to "Rechazada: la foto es demasiado grande",
        )
        show { PhotoGalleryContent(ready(*cases.map { it.first }.toTypedArray()), actions()) }
        cases.forEach { (photo, status) ->
            val angle = mapOf("frente" to "Frente", "lado" to "Lado", "espalda" to "Espalda").getValue(photo.angle)
            val date = PhotoFormat.longDate(photo.date)
            val description = "$angle, $date. $status".trim()
            rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(description))
            rule.onNodeWithContentDescription(description).assertExists()
        }
    }

    @Test fun datesAreHeadingsAndTilesOpenTheViewer() {
        val calls = Calls()
        show { PhotoGalleryContent(ready(photo("a", AUG_12, note = "mañana"), photo("b", AUG_12.minusDays(30), "lado")), actions(calls)) }
        rule.onNodeWithText("12 DE AGOSTO DE 2026").assertIsDisplayed()
        rule.onNodeWithText("13 DE JULIO DE 2026").assertIsDisplayed()
        rule.onNodeWithContentDescription("Frente, 12 de agosto de 2026. Nota: mañana.").performClick()
        assertTrue("open:a" in calls.log)
    }

    @Test fun anAccessibleEmptyStateAndTheMainActionsMeetTheTouchTargetAtDoubleFont() {
        val calls = Calls()
        show { PhotoGalleryContent(ready(), actions(calls)) }
        rule.onNodeWithText("Aún no hay fotos".uppercase()).assertIsDisplayed()
        val add = rule.onNodeWithContentDescription("Añadir foto")
        add.assertIsEnabled()
        val bounds = add.getUnclippedBoundsInRoot()
        assertTrue("add is at least 48dp: $bounds", bounds.bottom - bounds.top >= 48.dp && bounds.right - bounds.left >= 48.dp)
        add.performClick()
        assertEquals(listOf("add"), calls.log.filter { it == "add" })
    }

    @Test fun compareIsOfferedOnlyWhenThereAreTwoPhotosOfAnAngle() {
        show { PhotoGalleryContent(ready(photo("a", AUG_12), extra = { it.copy(canCompare = false) }), actions()) }
        rule.onNodeWithContentDescription("Comparar fotos").assertDoesNotExist()
    }

    @Test fun compareButtonShowsWhenAvailable() {
        val calls = Calls()
        show { PhotoGalleryContent(ready(photo("a", AUG_12), photo("b", AUG_12.minusDays(9)), extra = { it.copy(canCompare = true) }), actions(calls)) }
        rule.onNodeWithContentDescription("Comparar fotos").performClick()
        assertTrue("compare" in calls.log)
    }

    @Test fun loadFailureOffersRetryAndKeepsTheReassurance() {
        val calls = Calls()
        show { PhotoGalleryContent(PhotoGalleryUiState(status = PhotoGalleryStatus.Failed), actions(calls)) }
        rule.onNodeWithText("Tus fotos siguen guardadas", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Reintentar").performClick()
        assertTrue("retryLoad" in calls.log)
    }

    @Test fun concealedTilesSayTheyAreHiddenAndATapRevealsBeforeAnotherOpens() {
        val calls = Calls()
        show { PhotoGalleryContent(ready(photo("a", AUG_12, concealed = true)), actions(calls)) }
        rule.onNodeWithContentDescription("Frente, 12 de agosto de 2026. Foto oculta, toca para mostrar").performClick()
        assertEquals(listOf("reveal:a"), calls.log.filter { it.startsWith("reveal") || it.startsWith("open") })
    }

    @Test fun concealedComparisonSidesAreRevealedOneByOne() {
        val calls = Calls()
        val hiddenOld = older.copy(concealed = true)
        val cmp = comparison.copy(series = listOf(hiddenOld, newer), a = hiddenOld, b = newer)
        show { PhotoGalleryContent(ready(hiddenOld, newer, extra = { it.copy(comparing = true, comparison = cmp) }), actions(calls)) }
        rule.onNodeWithContentDescription("Foto A: foto de Frente del 1 de junio de 2026. Foto oculta, toca para mostrar").performClick()
        rule.onNodeWithContentDescription("Foto B: foto de Frente del 12 de agosto de 2026.", substring = true).assertExists()
        assertEquals(listOf("reveal:old"), calls.log.filter { it.startsWith("reveal") })
    }

    @Test fun aFailingUploadOffersRetryInTheViewer() {
        val calls = Calls()
        val viewing = photo("f", AUG_12, state = MediaUiState.RetryableFailure("/none/f.jpg", "timeout"))
        show { PhotoGalleryContent(ready(viewing, extra = { it.copy(viewing = viewing) }), actions(calls)) }
        rule.onNodeWithContentDescription("Reintentar subida").performClick()
        assertTrue("retryUpload:f" in calls.log)
    }

    // ---- Capture ----

    @Test fun captureFormAsksForAngleDateAndNoteBeforeTheSourceAndOffersBothSources() {
        val calls = Calls()
        val draft = CaptureDraft("lado", AUG_12)
        show { PhotoGalleryContent(ready(extra = { it.copy(draft = draft) }), actions(calls)) }
        rule.onNode(hasText("Lado") and isSelectable()).assertIsSelected()
        rule.onNode(hasText("Espalda") and isSelectable()).performClick()
        rule.onNodeWithContentDescription("Fecha: 12 de agosto de 2026. Cambiar fecha").assertExists()
        rule.onNodeWithContentDescription("Elegir de la galería").performClick()
        rule.onNodeWithContentDescription("Hacer una foto").performClick()
        assertEquals(listOf("draftAngle:espalda", "pick", "camera"), calls.log)
    }

    @Test fun refusedImagesExplainWhyInsideTheForm() {
        show { PhotoGalleryContent(ready(extra = { it.copy(draft = CaptureDraft("frente", AUG_12), message = PhotoMessage.Unsupported) }), actions()) }
        rule.onNodeWithText("Ese tipo de archivo no se admite", substring = true).assertIsDisplayed()
    }

    @Test fun whileSavingTheFormCannotBeChangedOrCancelled() {
        show { PhotoGalleryContent(ready(extra = { it.copy(draft = CaptureDraft("frente", AUG_12, busy = true)) }), actions()) }
        rule.onNodeWithContentDescription("Hacer una foto").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Cancelar").assertIsNotEnabled()
    }

    // ---- Viewer ----

    @Test fun viewerShowsNoteAndTheActionsOfAnAvailablePhoto() {
        val calls = Calls()
        val viewing = photo("a", AUG_12, "espalda", note = "después de entrenar")
        show { PhotoGalleryContent(ready(viewing, extra = { it.copy(viewing = viewing) }), actions(calls)) }
        rule.onNodeWithText("Espalda · 12 de agosto de 2026").assertIsDisplayed()
        rule.onNodeWithText("Nota: después de entrenar").assertIsDisplayed()
        rule.onNodeWithContentDescription("Eliminar foto").performClick()
        assertEquals("askDelete:a", calls.log.last())
        rule.onNodeWithContentDescription("Reintentar subida").assertDoesNotExist()
    }

    @Test fun viewerOfAnUncachedPhotoOfflineExplainsAndOffersRetry() {
        val calls = Calls()
        val viewing = photo("a", AUG_12, state = MediaUiState.RemoteUnavailableOffline)
        show { PhotoGalleryContent(ready(viewing, extra = { it.copy(viewing = viewing) }), actions(calls)) }
        rule.onNodeWithText("Sin conexión: esta foto aún no está guardada en este dispositivo").assertIsDisplayed()
        rule.onNodeWithContentDescription("Reintentar cargar la foto").performClick()
        assertTrue("retryImage:a" in calls.log)
    }

    @Test fun viewerOfARefusedUploadOffersRetryAndDiscard() {
        val calls = Calls()
        val viewing = photo("r", AUG_12, state = MediaUiState.Rejected(MediaRejection.UNSUPPORTED, "/none/r.jpg"))
        show { PhotoGalleryContent(ready(viewing, extra = { it.copy(viewing = viewing) }), actions(calls)) }
        rule.onNodeWithText("Rechazada: tipo de imagen no admitido").assertIsDisplayed()
        rule.onNodeWithContentDescription("Reintentar subida").performClick()
        rule.onNodeWithContentDescription("Descartar foto").performClick()
        assertEquals(listOf("retryUpload:r", "askDelete:r"), calls.log.filter { it.startsWith("retryUpload") || it.startsWith("askDelete") })
        rule.onNodeWithContentDescription("Eliminar foto").assertDoesNotExist()
    }

    @Test fun deletingNeedsAnExplicitConfirmation() {
        val calls = Calls()
        val target = photo("a", AUG_12, "lado")
        show { PhotoGalleryContent(ready(target, extra = { it.copy(viewing = target, pendingDelete = target) }), actions(calls)) }
        rule.onNodeWithText("¿Eliminar esta foto?").assertIsDisplayed()
        rule.onNodeWithText("Se borrará la foto de Lado del 12 de agosto de 2026", substring = true).assertIsDisplayed()
        assertTrue("confirmDelete" !in calls.log)
        rule.onNodeWithContentDescription("Eliminar", useUnmergedTree = false).performClick()
        assertTrue("confirmDelete" in calls.log)
    }

    // ---- Comparison ----

    private val older = photo("old", LocalDate.of(2026, 6, 1), "frente")
    private val newer = photo("new", AUG_12, "frente")
    private val comparison = ComparisonUi(listOf("frente", "lado"), "frente", listOf(older, newer), older, newer)

    @Test fun comparisonLabelsEachSideWithItsDateAndAngleAtDoubleFont() {
        show { PhotoGalleryContent(ready(older, newer, extra = { it.copy(comparing = true, comparison = comparison) }), actions()) }
        rule.onNodeWithContentDescription("Foto A: foto de Frente del 1 de junio de 2026.", substring = true).assertExists()
        rule.onNodeWithContentDescription("Foto B: foto de Frente del 12 de agosto de 2026.", substring = true).assertExists()
        rule.onNodeWithText("1 de junio de 2026").assertIsDisplayed()
        rule.onNodeWithText("12 de agosto de 2026").assertIsDisplayed()
        rule.onNodeWithText("FOTO A").assertIsDisplayed()
        rule.onNodeWithText("FOTO B").assertIsDisplayed()
    }

    @Test fun comparisonControlsSwapAndChooseAndSpeakTheirSelection() {
        val calls = Calls()
        show { PhotoGalleryContent(ready(older, newer, extra = { it.copy(comparing = true, comparison = comparison) }), actions(calls)) }
        rule.onNodeWithContentDescription("Intercambiar A y B").also {
            val bounds = it.getUnclippedBoundsInRoot()
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }.performClick()
        rule.onNode(hasText("Lado") and isSelectable()).performClick() // angle choice
        rule.onNode(hasText("12 de agosto de 2026, Frente") and isSelectable() and androidx.compose.ui.test.isSelected()).assertExists()
        assertEquals(listOf("swap", "angle:lado"), calls.log.filter { it == "swap" || it.startsWith("angle") })
    }

    @Test fun comparisonNeedsTwoPhotosAndSaysSo() {
        show { PhotoGalleryContent(ready(older, extra = { it.copy(comparing = true, comparison = null) }), actions()) }
        rule.onNodeWithText("Para comparar necesitas al menos dos fotos del mismo ángulo.").assertIsDisplayed()
    }

    @Test fun comparisonShowsOfflineAndPendingSidesAsText() {
        val offline = older.copy(state = MediaUiState.RemoteUnavailableOffline)
        val pending = newer.copy(state = MediaUiState.LocalPending(null))
        val cmp = comparison.copy(series = listOf(offline, pending), a = offline, b = pending)
        show { PhotoGalleryContent(ready(offline, pending, extra = { it.copy(comparing = true, comparison = cmp) }), actions()) }
        // The visible status line repeats what the side's description already speaks.
        rule.onNodeWithContentDescription("Foto A: foto de Frente del 1 de junio de 2026. Sin conexión", substring = true).assertExists()
    }
}
