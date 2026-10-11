package com.chrisgoac.gymlog.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.BackgroundSyncScheduler
import com.chrisgoac.gymlog.domain.LocalAccountCleaner
import com.chrisgoac.gymlog.domain.OutboxDiagnostic
import com.chrisgoac.gymlog.domain.SignOutUseCase
import com.chrisgoac.gymlog.domain.SyncStatus
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.domain.account.AccountDeletion
import com.chrisgoac.gymlog.domain.account.ServerDeletionOutcome
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.auth.AuthSessionState
import com.chrisgoac.gymlog.domain.auth.AuthStep
import com.chrisgoac.gymlog.domain.auth.ReauthGateway
import com.chrisgoac.gymlog.domain.auth.ReauthStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Account and privacy screens: the three sign-out choices, offline restriction, typed confirmation, links. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp")
class AccountScreenTest {
    @get:Rule val rule = createComposeRule()

    private val online = MutableStateFlow(true)
    private val opened = mutableListOf<String>()
    private val serverCalls = mutableListOf<Boolean>()
    private val links = AccountLinks(
        privacyPolicy = "https://gym.example/privacy",
        accountDeletion = "https://gym.example/delete-account",
        subscriptions = { "https://play.google.com/store/account/subscriptions?package=com.chrisgoac.gymlog" },
    )

    private val sync = object : SyncUseCases {
        override fun observeStatus() = MutableStateFlow(SyncStatus(false, 2, 0, true, null))
        override fun observeDiagnostics() = MutableStateFlow<List<OutboxDiagnostic>>(emptyList())
        override suspend fun requestSync() = Unit
    }

    private val signOut = SignOutUseCase(
        currentUserId = { "u" },
        scheduler = object : BackgroundSyncScheduler {
            override suspend fun ensurePeriodic(userId: String) = Unit
            override suspend fun requestSync(userId: String, queueBehindRunning: Boolean) = Unit
            override suspend fun cancel(userId: String) = Unit
            override fun suspend(userId: String) = Unit
            override fun resume(userId: String) = Unit
        },
        cleaner = object : LocalAccountCleaner {
            override suspend fun unsyncedChangeCount(userId: String) = 2
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

    private val reauth = object : ReauthGateway {
        override suspend fun start(): ReauthStep = ReauthStep.Choose(password = true, emailCodeTo = "a***@example.com")
        override suspend fun sendEmailCode(): ReauthStep = ReauthStep.CodeSent
        override suspend fun verifyEmailCode(code: String): ReauthStep = ReauthStep.Completed
        override suspend fun verifyPassword(password: String): ReauthStep = ReauthStep.Completed
    }

    private fun showAccount(): AccountViewModel {
        val vm = AccountViewModel(
            sync, signOut,
            AccountDeletion({ "u" }, { _, fresh -> serverCalls += fresh; ServerDeletionOutcome.ReauthRequired }, { _, _ -> }),
            reauth, billing = null, online = online,
        )
        rule.setContent {
            GymLogTheme { AccountScreen(vm, links, onBack = {}, onPrivacy = {}, onOpenUri = { opened += it }) }
        }
        rule.waitForIdle()
        return vm
    }

    @Test fun signOutWithPendingChangesOffersSyncStayAndAnExplicitDiscard() {
        showAccount()
        rule.onNodeWithText("Cerrar sesión").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Sincronizar ahora").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("Seguir con la sesión abierta").assertIsDisplayed()
        rule.onNodeWithText("Descartar cambios pendientes y cerrar sesión").assertIsDisplayed()
    }

    @Test fun offlineSyncNowIsDisabledAndExplained() {
        online.value = false
        showAccount()
        rule.onNodeWithText("Cerrar sesión").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Sincronizar ahora").assertIsNotEnabled()
        rule.onNodeWithText("Sin conexión: no se puede sincronizar ahora", substring = true).assertIsDisplayed()
    }

    @Test fun deletionNeedsTheTypedWordThenAsksForAFreshSignIn() {
        showAccount()
        rule.onNodeWithText("Eliminar la cuenta no cancela una suscripción de Google Play", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Eliminar cuenta para siempre").performScrollTo().assertIsNotEnabled()

        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("ELIMINAR")
        rule.waitForIdle()
        rule.onNodeWithText("Eliminar cuenta para siempre").performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()

        assertEquals(listOf(false), serverCalls)
        rule.onNodeWithText("Confirmar con contraseña").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Enviar código a a***@example.com").performScrollTo().assertIsDisplayed()
    }

    @Test fun manageSubscriptionOpensPlay() {
        showAccount()
        rule.onNodeWithText("Gestionar suscripción en Google Play").performScrollTo().performClick()
        assertEquals(listOf(links.subscriptions()), opened)
    }

    @Test fun privacyLinksOpenThePolicyPlayAndTheWebDeletionPage() {
        rule.setContent { GymLogTheme { PrivacyScreen(links, onBack = {}, onOpenUri = { opened += it }) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Abrir: Política de privacidad").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Abrir: Suscripciones de Google Play").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Abrir: Eliminar la cuenta desde la web").performScrollTo().performClick()
        rule.onNodeWithText("correo de contacto del desarrollador", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(listOf(links.privacyPolicy, links.subscriptions(), links.accountDeletion), opened)
    }
}
