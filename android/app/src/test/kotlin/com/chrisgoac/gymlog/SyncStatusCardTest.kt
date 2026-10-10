package com.chrisgoac.gymlog

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.OutboxDiagnostic
import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.settings.SyncDiagnosticsScreen
import com.chrisgoac.gymlog.settings.SyncStatusCard
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
class SyncStatusCardTest {
    @get:Rule val rule = createComposeRule()

    private fun card(state: SyncUiState, onSync: () -> Unit = {}, onDetails: () -> Unit = {}) = rule.setContent {
        GymLogTheme { SyncStatusCard(state, onSyncNow = onSync, onDetails = onDetails) }
    }

    @Test fun syncedShowsNoSyncNowButton() {
        card(SyncUiState.Synced)
        rule.onNodeWithText("Todo sincronizado").assertExists()
        rule.onAllNodesWithText("Sincronizar ahora").assertCountEquals(0)
        rule.onNodeWithText("Ver detalles").assertExists()
    }

    @Test fun pendingShowsCountAndAllowsSyncNow() {
        var syncs = 0
        card(SyncUiState.Pending(2), onSync = { syncs++ })
        rule.onNodeWithText("2 cambios pendientes de enviar").assertExists()
        rule.onNodeWithText("Sincronizar ahora").performClick()
        assertEquals(1, syncs)
    }

    @Test fun offlineSingularAndPlural() {
        card(SyncUiState.Offline(1))
        rule.onNodeWithText("Sin conexión con el servidor. 1 cambio guardado en este dispositivo.").assertExists()
    }

    @Test fun sessionRequiredAsksToSignInAndOffersNoSync() {
        card(SyncUiState.SessionRequired)
        rule.onNodeWithText("Tu sesión ha caducado. Inicia sesión de nuevo para seguir sincronizando.").assertExists()
        rule.onAllNodesWithText("Sincronizar ahora").assertCountEquals(0)
    }

    @Test fun sessionRejectedExplainsAndOffersRetry() {
        var syncs = 0
        card(SyncUiState.SessionRejected, onSync = { syncs++ })
        rule.onNodeWithText("El servidor rechazó la sesión", substring = true).assertExists()
        rule.onAllNodesWithText("Sincronizar ahora").assertCountEquals(0)
        rule.onNodeWithText("Reintentar").performClick()
        assertEquals(1, syncs)
    }

    @Test fun needsActionLinksToDetails() {
        var opened = 0
        card(SyncUiState.NeedsAction(3), onDetails = { opened++ })
        rule.onNodeWithText("El servidor ha rechazado 3 cambios. Revísalos en los detalles.").assertExists()
        rule.onNodeWithText("Ver detalles").performClick()
        assertEquals(1, opened)
    }

    @Test fun diagnosticsShowIdsTablesAttemptsAndErrorsOnly() {
        rule.setContent {
            GymLogTheme {
                SyncDiagnosticsScreen(
                    listOf(OutboxDiagnostic("1a2b3c4d", listOf("exercises", "routines"), 2, "INVALID_REQUEST", rejected = true)),
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText("Cambio 1a2b3c4d").assertExists()
        rule.onNodeWithText("Tablas: exercises, routines").assertExists()
        rule.onNodeWithText("Intentos: 2").assertExists()
        rule.onNodeWithText("Último error: INVALID_REQUEST").assertExists()
        rule.onNodeWithText("Rechazado").assertExists()
    }

    @Test fun emptyDiagnosticsExplainThereIsNothingPending() {
        rule.setContent { GymLogTheme { SyncDiagnosticsScreen(emptyList(), onBack = {}) } }
        rule.onNodeWithText("Sin cambios pendientes".uppercase()).assertExists()
    }
}
