package com.chrisgoac.gymlog.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.billing.BillingFailure
import com.chrisgoac.gymlog.domain.billing.Entitlement
import com.chrisgoac.gymlog.domain.billing.EntitlementSource
import com.chrisgoac.gymlog.domain.billing.ProProduct
import com.chrisgoac.gymlog.domain.billing.ProTier
import com.chrisgoac.gymlog.domain.billing.ProductCatalog
import com.chrisgoac.gymlog.domain.billing.ProductOffer
import com.chrisgoac.gymlog.domain.billing.PurchaseStatus
import com.chrisgoac.gymlog.domain.billing.SubscriptionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

private val SCROLL = SemanticsMatcher("vertical scroll container") { SemanticsProperties.VerticalScrollAxisRange in it.config }
private val UTC = ZoneOffset.UTC
private val NOV_1 = LocalDate.of(2026, 11, 1).atStartOfDay(UTC).toInstant().toEpochMilli()

private val OFFERS = ProductCatalog.Available(
    listOf(ProductOffer(ProProduct.MONTHLY, "4,99 €", "P1M"), ProductOffer(ProProduct.ANNUAL, "39,99 €", "P1Y")),
)

private fun entitlement(tier: ProTier, state: SubscriptionState, product: ProProduct? = ProProduct.MONTHLY, until: Long? = NOV_1) =
    Entitlement(tier, product, state, until, EntitlementSource.SERVER)

/** The Pro screen and Settings card at 200% font: what Pro unlocks, localized prices, billing disclosure, states, disabled states. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp")
class ProScreenTest {
    @get:Rule val rule = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = ProActions(
        onBack = { log += "back" },
        onSubscribe = { log += "subscribe:${it.productId}" },
        onRestore = { log += "restore" },
        onManage = { log += "manage" },
        onReloadProducts = { log += "reload" },
        onDismissNotice = { log += "dismiss" },
    )

    private fun show(state: ProUiState, fontScale: Float = 2f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GymLogTheme { ProContent(state, actions, UTC) }
            }
        }
        rule.waitForIdle()
    }

    private fun free(extra: (ProUiState) -> ProUiState = { it }) =
        extra(ProUiState(entitlement = entitlement(ProTier.FREE, SubscriptionState.NONE, null, null), catalog = OFFERS))

    private fun pro(state: SubscriptionState = SubscriptionState.ACTIVE, product: ProProduct = ProProduct.MONTHLY, extra: (ProUiState) -> ProUiState = { it }) =
        extra(ProUiState(entitlement = entitlement(ProTier.PRO, state, product), catalog = OFFERS))

    private fun scrollTo(text: String, substring: Boolean = true) {
        rule.onNode(SCROLL).performScrollToNode(hasText(text, substring = substring))
    }

    @Test fun `Free shows exactly what Pro unlocks and that everything else stays free`() {
        show(free())
        scrollTo("QUÉ INCLUYE PRO", false)
        rule.onNodeWithText("Coach con IA", substring = true).assertExists()
        rule.onNodeWithText("Mesociclos con IA", substring = true).assertExists()
        rule.onNodeWithText("Informe inteligente", substring = true).assertExists()
        rule.onNodeWithText("LO DEMÁS SIGUE SIENDO GRATIS").assertExists()
        rule.onNodeWithText("tus datos siguen siendo tuyos", substring = true).assertExists()
        rule.onNodeWithText("Plan gratuito", substring = true).assertExists()
    }

    @Test fun `both plans show Play's localized price and period with the renewal disclosure`() {
        show(free())
        scrollTo("4,99 € al mes")
        rule.onNodeWithText("4,99 € al mes").assertIsDisplayed()
        rule.onNodeWithText("Se renueva automáticamente cada mes hasta que canceles en Google Play.").assertExists()
        scrollTo("39,99 € al año")
        rule.onNodeWithText("39,99 € al año").assertIsDisplayed()
        rule.onNodeWithText("Se renueva automáticamente cada año hasta que canceles en Google Play.").assertExists()
    }

    @Test fun `the no-trial wording is explicit and nothing promises a free trial`() {
        show(free())
        scrollTo("Sin periodo de prueba")
        rule.onNodeWithText("Sin periodo de prueba", substring = true).assertIsDisplayed()
        rule.onNodeWithText("prueba gratis", substring = true, ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText("días gratis", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test fun `prices are read in full by TalkBack with the plan name and period`() {
        show(free())
        scrollTo("Suscribirse", false)
        rule.onNodeWithContentDescription("Suscribirse al Plan mensual, 4,99 € al mes").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Suscribirse al Plan anual, 39,99 € al año").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("subscribe:gymlog_pro_monthly", "subscribe:gymlog_pro_annual"), log)
    }

    @Test fun `buttons are at least 48dp at 200 percent font`() {
        show(free())
        listOf("Suscribirse al Plan mensual, 4,99 € al mes", "Restaurar compras", "Gestionar en Google Play").forEach { description ->
            val bounds = rule.onNodeWithContentDescription(description).performScrollTo().getUnclippedBoundsInRoot()
            assertTrue("$description is at least 48dp: $bounds", bounds.bottom - bounds.top >= 48.dp && bounds.right - bounds.left >= 48.dp)
        }
    }

    @Test fun `Pro on the monthly plan keeps it as current and offers a switch to the annual one`() {
        show(pro())
        rule.onNodeWithText("GymLog Pro · plan mensual. Se renueva el 1 de noviembre de 2026", substring = true).assertExists()
        rule.onNodeWithContentDescription("Plan mensual, tu plan actual").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Cambiar al Plan anual, 39,99 € al año").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("subscribe:gymlog_pro_annual"), log)
        rule.onNodeWithText("Google Play ajusta el cobro", substring = true).assertExists()
    }

    @Test fun `grace paused canceled and ended each say what happens next`() {
        show(pro(SubscriptionState.GRACE))
        rule.onNodeWithText("GymLog Pro activo hasta el 1 de noviembre de 2026. No pudimos cobrar el último pago: arréglalo en Google Play.").assertExists()
    }

    @Test fun `canceled Pro says until when and that it will not renew`() {
        show(pro(SubscriptionState.CANCELED))
        rule.onNodeWithText("GymLog Pro hasta el 1 de noviembre de 2026. No se renovará.").assertExists()
    }

    @Test fun `paused says no Pro until resumed in Play and that data stays`() {
        show(ProUiState(entitlement = entitlement(ProTier.FREE, SubscriptionState.PAUSED), catalog = OFFERS))
        rule.onNodeWithText("Sin Pro hasta que la reanudes allí. Tus datos siguen disponibles.", substring = true).assertExists()
    }

    @Test fun `an expired subscription says so and keeps data available`() {
        show(ProUiState(entitlement = entitlement(ProTier.FREE, SubscriptionState.EXPIRED), catalog = OFFERS))
        rule.onNodeWithText("Tu suscripción Pro terminó el 1 de noviembre de 2026. Tus datos siguen disponibles.").assertExists()
        rule.onNodeWithContentDescription("Suscribirse al Plan mensual, 4,99 € al mes").performScrollTo().assertIsEnabled()
    }

    @Test fun `an unverified plan asks nothing of the user and keeps data available`() {
        show(ProUiState(entitlement = Entitlement.Unknown, catalog = OFFERS))
        rule.onNodeWithText("No hemos podido comprobar tu plan", substring = true).assertExists()
    }

    @Test fun `offline disables purchase and restore, says why, and manage stays available`() {
        show(free { it.copy(online = false) })
        rule.onNodeWithText("Sin conexión", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Suscribirse al Plan mensual, 4,99 € al mes").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Suscribirse al Plan anual, 39,99 € al año").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Restaurar compras").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Gestionar en Google Play").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("manage"), log)
    }

    @Test fun `while the Play sheet or verification is open nothing else can start`() {
        show(free { it.copy(purchase = PurchaseStatus.AwaitingPlay(ProProduct.MONTHLY)) })
        rule.onNodeWithText("Completa la compra en Google Play.").assertExists()
        rule.onNodeWithContentDescription("Suscribirse al Plan anual, 39,99 € al año").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Restaurar compras").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `verifying and pending explain themselves and Pro is not promised before it is granted`() {
        show(free { it.copy(purchase = PurchaseStatus.Verifying) })
        rule.onNodeWithText("Verificando tu compra…").assertExists()
    }

    @Test fun `a pending payment says Pro starts when it completes and stays dismissible`() {
        show(free { it.copy(purchase = PurchaseStatus.Pending) })
        rule.onNodeWithText("pendiente en Google Play", substring = true).assertExists()
        rule.onNodeWithText("no se te dará acceso", substring = true).assertExists()
        rule.onNodeWithContentDescription("Entendido").performScrollTo().performClick()
        assertEquals(listOf("dismiss"), log)
    }

    @Test fun `a plan owned for another account explains and points to Play`() {
        show(free { it.copy(purchase = PurchaseStatus.Failed(BillingFailure.SUBSCRIBED_ELSEWHERE)) })
        rule.onNodeWithText("no está vinculada a esta cuenta de GymLog", substring = true).assertExists()
        rule.onNodeWithText("gestiónala en Google Play", substring = true).assertExists()
    }

    @Test fun `nothing to restore explains why a purchase without an account link cannot be restored`() {
        show(free { it.copy(restore = RestorePhase.NothingToRestore) })
        rule.onNodeWithText("sin pasar por la app", substring = true).assertExists()
        rule.onNodeWithText("no podemos restaurarla aquí", substring = true).assertExists()
    }

    @Test fun `restore calls back when allowed`() {
        show(free())
        rule.onNodeWithContentDescription("Restaurar compras").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("restore"), log)
    }

    @Test fun `unavailable Play or products show a reason and no purchase buttons`() {
        show(free { it.copy(catalog = ProductCatalog.Unavailable(BillingFailure.PLAY_UNAVAILABLE)) })
        rule.onNodeWithText("Google Play no está disponible", substring = true).assertExists()
        rule.onNodeWithContentDescription("Suscribirse al Plan mensual, 4,99 € al mes").assertDoesNotExist()
        rule.onNodeWithText("Reintentar").assertDoesNotExist()
    }

    @Test fun `a network failure loading prices offers a retry`() {
        show(free { it.copy(catalog = ProductCatalog.Unavailable(BillingFailure.NETWORK)) })
        rule.onNodeWithContentDescription("Reintentar").performScrollTo().performClick()
        assertEquals(listOf("reload"), log)
    }

    @Test fun `unexpected billing periods still read as a price`() {
        show(free { it.copy(catalog = ProductCatalog.Available(listOf(ProductOffer(ProProduct.MONTHLY, "4,99 €", "P3M")))) })
        rule.onNodeWithText("4,99 € por periodo de facturación").assertExists()
    }

    @Test fun `the subscription card shows plan and date and links to Pro and to Play`() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                GymLogTheme { SubscriptionCard(PlanStatus.Active(ProProduct.ANNUAL, NOV_1), onOpenPro = { log += "open" }, onManage = { log += "manage" }, zone = UTC) }
            }
        }
        rule.onNodeWithText("GymLog Pro · plan anual. Se renueva el 1 de noviembre de 2026").assertExists()
        rule.onNodeWithContentDescription("Ver GymLog Pro").performClick()
        rule.onNodeWithContentDescription("Gestionar en Google Play").performClick()
        assertEquals(listOf("open", "manage"), log)
    }

    @Test fun `the subscription card hides the Play link when there is no subscription`() {
        rule.setContent { GymLogTheme { SubscriptionCard(PlanStatus.Free, onOpenPro = {}, onManage = {}) } }
        rule.onNodeWithText("Plan gratuito", substring = true).assertExists()
        rule.onNodeWithContentDescription("Gestionar en Google Play").assertDoesNotExist()
        rule.onNodeWithContentDescription("Ver GymLog Pro").assertIsEnabled()
    }
}
