package com.chrisgoac.gymlog

import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.BackgroundSyncScheduler
import com.chrisgoac.gymlog.domain.LocalAccountCleaner
import com.chrisgoac.gymlog.domain.OutboxDiagnostic
import com.chrisgoac.gymlog.domain.SignOutUseCase
import com.chrisgoac.gymlog.domain.SyncStatus
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.domain.ai.IntelligentReport
import com.chrisgoac.gymlog.domain.ai.IntelligentReportRepository
import com.chrisgoac.gymlog.domain.ai.MesocycleDraft
import com.chrisgoac.gymlog.domain.ai.MesocycleParams
import com.chrisgoac.gymlog.domain.ai.MesocycleRepository
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.auth.AuthSessionState
import com.chrisgoac.gymlog.domain.auth.AuthStep
import com.chrisgoac.gymlog.domain.billing.BillingRepository
import com.chrisgoac.gymlog.domain.billing.Entitlement
import com.chrisgoac.gymlog.domain.billing.EntitlementSource
import com.chrisgoac.gymlog.domain.billing.ProProduct
import com.chrisgoac.gymlog.domain.billing.ProTier
import com.chrisgoac.gymlog.domain.billing.ProductCatalog
import com.chrisgoac.gymlog.domain.billing.ProductOffer
import com.chrisgoac.gymlog.domain.billing.PurchaseStatus
import com.chrisgoac.gymlog.domain.billing.RestoreResult
import com.chrisgoac.gymlog.domain.billing.SubscriptionState
import com.chrisgoac.gymlog.domain.coach.CoachMessage
import com.chrisgoac.gymlog.domain.coach.CoachReportCategory
import com.chrisgoac.gymlog.domain.coach.CoachRepository
import com.chrisgoac.gymlog.domain.coach.CoachStreamUpdate
import com.chrisgoac.gymlog.domain.routine.Routine
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.routine.RoutineSummary
import com.chrisgoac.gymlog.navigation.AiPlanServices
import com.chrisgoac.gymlog.navigation.CoachServices
import com.chrisgoac.gymlog.navigation.GymLogApp
import com.chrisgoac.gymlog.navigation.GymLogDestination
import com.chrisgoac.gymlog.navigation.ProgressServices
import com.chrisgoac.gymlog.navigation.SettingsServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.ZoneId

private val ZONE = ZoneId.of("Europe/Madrid")

/** A Play-less billing whose account's Pro has expired: the server says FREE/EXPIRED, Play offers both plans. */
private class ExpiredBilling : BillingRepository {
    override val entitlement: StateFlow<Entitlement> =
        MutableStateFlow(Entitlement(ProTier.FREE, ProProduct.MONTHLY, SubscriptionState.EXPIRED, 1_700_000_000_000L, EntitlementSource.SERVER))
    override val products: StateFlow<ProductCatalog> = MutableStateFlow(
        ProductCatalog.Available(listOf(ProductOffer(ProProduct.MONTHLY, "4,99 €", "P1M"), ProductOffer(ProProduct.ANNUAL, "39,99 €", "P1Y"))),
    )
    override val purchaseStatus: StateFlow<PurchaseStatus> = MutableStateFlow(PurchaseStatus.Idle)
    override suspend fun loadProducts() = Unit
    override suspend fun launchPurchase(activity: Any, product: ProProduct): PurchaseStatus = PurchaseStatus.Idle
    override suspend fun restore(): RestoreResult = RestoreResult.NothingToRestore
    override suspend fun refresh() = Unit
    override fun managementUri() = "https://play.google.com/store/account/subscriptions"
}

private class ExpiredRoutines : RoutineRepository {
    override fun observeRoutines() = MutableStateFlow(listOf(RoutineSummary("r1", "Push", null, false, 2, listOf("Press de banca"))))
    override fun observeRoutine(id: String): Flow<Routine?> = MutableStateFlow(null)
    override fun observeNextRoutineId(): Flow<String?> = MutableStateFlow("r1")
    override suspend fun create(input: RoutineInput): Routine = error("unused")
    override suspend fun update(id: String, input: RoutineInput): Routine = error("unused")
    override suspend fun reorder(idsInOrder: List<String>) = Unit
    override suspend fun setArchived(id: String, archived: Boolean) = Unit
    override suspend fun delete(id: String) = Unit
}

private class UnusedCoach : CoachRepository {
    override fun observeThread(): Flow<List<CoachMessage>> = flowOf(emptyList())
    override fun send(clientMessageId: String, text: String): Flow<CoachStreamUpdate> = emptyFlow()
    override suspend fun report(responseId: String, category: CoachReportCategory, context: String?) = Unit
}

private class UnusedMesocycles : MesocycleRepository {
    override suspend fun generate(clientRequestId: String, params: MesocycleParams): MesocycleDraft = error("Free never generates")
    override suspend fun save(draft: MesocycleDraft): String = error("Free never saves")
}

private class UnusedReports : IntelligentReportRepository {
    override suspend fun generate(): IntelligentReport = error("Free never requests a report")
    override fun lastReport(): IntelligentReport? = null
}

private val SYNC = object : SyncUseCases {
    override fun observeStatus() = MutableStateFlow(SyncStatus(false, 0, 0, true, null))
    override fun observeDiagnostics() = MutableStateFlow<List<OutboxDiagnostic>>(emptyList())
    override suspend fun requestSync() = Unit
}

private val SIGN_OUT = SignOutUseCase(
    currentUserId = { "u" },
    scheduler = object : BackgroundSyncScheduler {
        override suspend fun ensurePeriodic(userId: String) = Unit
        override suspend fun requestSync(userId: String, queueBehindRunning: Boolean) = Unit
        override suspend fun cancel(userId: String) = Unit
        override fun suspend(userId: String) = Unit
        override fun resume(userId: String) = Unit
    },
    cleaner = object : LocalAccountCleaner {
        override suspend fun unsyncedChangeCount(userId: String) = 0
        override suspend fun markPendingDeletion(userId: String) = Unit
        override suspend fun confirmPendingDeletion(userId: String) = Unit
        override suspend fun clearPendingDeletion(userId: String) = Unit
        override suspend fun deleteLocalData(userId: String) = Unit
    },
    auth = object : AuthGateway {
        override val state: StateFlow<AuthSessionState> = MutableStateFlow(AuthSessionState.SignedIn("u"))
        override val serviceUnavailable: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun signOut(): AuthStep = AuthStep.Completed
        override suspend fun startEmailCodeSignIn(email: String) = error("unused")
        override suspend fun verifyEmailCodeSignIn(code: String) = error("unused")
        override suspend fun signInWithPassword(email: String, password: String) = error("unused")
        override suspend fun startSignUp(email: String, password: String?) = error("unused")
        override suspend fun verifySignUp(code: String) = error("unused")
        override suspend fun resendSignUpCode() = error("unused")
        override suspend fun signInWithGoogle() = error("unused")
    },
)

/**
 * An account whose Pro expired: every Free screen is still reachable and shows its data, and each locked AI entry
 * point leads to the Pro screen instead of dead-ending.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class ProPaywallNavigationTest {
    @get:Rule val rule = createComposeRule()

    private var nav: NavHostController? = null

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        rule.waitForIdle()
    }

    private fun showExpiredAccount() {
        val entitlements = ProgFakeEntitlements(false)
        rule.setContent {
            GymLogTheme {
                val controller = rememberNavController()
                nav = controller
                GymLogApp(
                    navController = controller,
                    settings = SettingsServices(SYNC, SIGN_OUT, billing = ExpiredBilling()),
                    workouts = ProgGyms(),
                    routines = ExpiredRoutines(),
                    exercises = ProgFakeExercises(listOf(progExercise("e1", "Press banca"))),
                    progress = ProgressServices(ProgFakeStats(), entitlements, flowOf(Unit), { 1_786_000_000_000L }, { ZONE }),
                    coach = CoachServices(UnusedCoach(), entitlements, flowOf(true)),
                    aiPlans = AiPlanServices(UnusedMesocycles(), UnusedReports(), entitlements, flowOf(true)) { ZONE },
                )
            }
        }
        idle()
    }

    private fun assertAt(route: kotlin.reflect.KClass<out GymLogDestination>) {
        assertTrue("expected $route but at ${nav!!.currentDestination}", nav!!.currentDestination!!.hasRoute(route))
    }

    private fun assertOnProScreen() {
        assertAt(GymLogDestination.Pro::class)
        rule.onNodeWithText("Tu suscripción Pro terminó", substring = true).assertExists()
        rule.onNodeWithText("4,99 € al mes").performScrollTo().assertExists()
        rule.onNodeWithText("Sin periodo de prueba", substring = true).performScrollTo().assertExists()
    }

    @Test fun theLockedCoachCardOpensThePaywall() {
        showExpiredAccount()
        rule.onNodeWithContentDescription("Coach, Solo Pro").performScrollTo().assertIsEnabled().performClick()
        idle()
        assertOnProScreen()
    }

    @Test fun theLockedMesocycleActionOpensThePaywallAndTheRoutinesStayVisible() {
        showExpiredAccount()
        rule.onNodeWithText("Rutinas").performClick()
        idle()
        rule.onNodeWithText("Push").assertExists()
        rule.onNodeWithText("Mesociclo con IA · Pro").assertIsEnabled().performClick()
        idle()
        assertOnProScreen()
    }

    @Test fun theLockedReportActionOpensThePaywallAndProgressStaysReadable() {
        showExpiredAccount()
        rule.onNodeWithText("Progreso").performClick()
        idle()
        assertAt(GymLogDestination.Progress::class)
        rule.onNodeWithText("Aún no hay progreso que mostrar", ignoreCase = true).assertExists()
        rule.onNodeWithContentDescription("Informe inteligente. Requiere Pro").performScrollTo().assertIsEnabled().performClick()
        idle()
        assertOnProScreen()
    }

    private fun lockedScreenOffersThePaywall(destination: GymLogDestination) {
        showExpiredAccount()
        rule.runOnUiThread { nav!!.navigate(destination) }
        idle()
        rule.onNodeWithText("Ver GymLog Pro").assertIsEnabled().performClick()
        idle()
        assertOnProScreen()
    }

    @Test fun theLockedCoachScreenOffersThePaywall() = lockedScreenOffersThePaywall(GymLogDestination.Coach)

    @Test fun theLockedMesocycleScreenOffersThePaywall() = lockedScreenOffersThePaywall(GymLogDestination.MesocycleGenerator)

    @Test fun theLockedReportScreenOffersThePaywall() = lockedScreenOffersThePaywall(GymLogDestination.IntelligentReport)

    @Test fun everyFreeTabStillOpensAfterExpiry() {
        showExpiredAccount()
        rule.onNodeWithText("Push").assertExists()
        rule.onNodeWithText("Ejercicios").performClick()
        idle()
        rule.onNodeWithText("Press banca").assertExists()
        rule.onNodeWithText("Rutinas").performClick()
        idle()
        rule.onNodeWithText("Push").assertExists()
        rule.onNodeWithText("Progreso").performClick()
        idle()
        assertAt(GymLogDestination.Progress::class)
        rule.onNodeWithText("Historial").performClick()
        idle()
        assertTrue(nav!!.currentDestination != null)
    }

    @Test fun settingsShowsTheSubscriptionAndOpensThePaywall() {
        showExpiredAccount()
        rule.runOnUiThread { nav!!.navigate(GymLogDestination.Settings) }
        idle()
        rule.onNodeWithText("SUSCRIPCIÓN").assertExists()
        rule.onNodeWithText("Tu suscripción Pro terminó", substring = true).assertExists()
        rule.onNodeWithContentDescription("Ver GymLog Pro").performScrollTo().performClick()
        idle()
        assertOnProScreen()
    }
}
