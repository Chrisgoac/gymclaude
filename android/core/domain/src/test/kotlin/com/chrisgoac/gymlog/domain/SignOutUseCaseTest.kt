package com.chrisgoac.gymlog.domain

import com.chrisgoac.gymlog.domain.auth.AuthFailure
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.auth.AuthSessionState
import com.chrisgoac.gymlog.domain.auth.AuthStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SignOutUseCaseTest {
    private val events = mutableListOf<String>()

    private val scheduler = object : BackgroundSyncScheduler {
        override suspend fun ensurePeriodic(userId: String) { events += "periodic:$userId" }
        override suspend fun requestSync(userId: String) { events += "request:$userId" }
        override suspend fun cancel(userId: String) { events += "cancel:$userId" }
    }

    private fun cleaner(unsynced: Int, failDelete: Boolean = false) = object : LocalAccountCleaner {
        override suspend fun unsyncedChangeCount(userId: String): Int { events += "count:$userId"; return unsynced }
        override suspend fun deleteLocalData(userId: String) {
            events += "delete:$userId"
            if (failDelete) error("disk")
        }
    }

    private fun auth(result: AuthStep = AuthStep.Completed) = object : AuthGateway {
        override val state: StateFlow<AuthSessionState> = MutableStateFlow(AuthSessionState.SignedIn("u"))
        override val serviceUnavailable: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun signOut(): AuthStep { events += "signOut"; return result }
        override suspend fun startEmailCodeSignIn(email: String) = error("unused")
        override suspend fun verifyEmailCodeSignIn(code: String) = error("unused")
        override suspend fun signInWithPassword(email: String, password: String) = error("unused")
        override suspend fun startSignUp(email: String, password: String?) = error("unused")
        override suspend fun verifySignUp(code: String) = error("unused")
        override suspend fun resendSignUpCode() = error("unused")
        override suspend fun signInWithGoogle() = error("unused")
    }

    private fun useCase(unsynced: Int, authResult: AuthStep = AuthStep.Completed, failDelete: Boolean = false) =
        SignOutUseCase({ "u" }, scheduler, cleaner(unsynced, failDelete), auth(authResult))

    @Test fun withNothingUnsyncedItCancelsWorkSignsOutAndDeletes() = runTest {
        assertEquals(SignOutOutcome.SignedOut, useCase(0).begin())
        assertEquals(listOf("cancel:u", "count:u", "signOut", "delete:u"), events)
    }

    @Test fun withUnsyncedChangesItAsksAndDeletesNothing() = runTest {
        assertEquals(SignOutOutcome.ConfirmationRequired(4), useCase(4).begin())
        assertEquals(listOf("cancel:u", "count:u"), events)
    }

    @Test fun confirmingDiscardsTheChanges() = runTest {
        val useCase = useCase(4)
        useCase.begin()
        assertEquals(SignOutOutcome.SignedOut, useCase.confirmDiscard())
        assertEquals(listOf("cancel:u", "count:u", "signOut", "delete:u"), events)
    }

    @Test fun decliningResumesSyncAndKeepsEverything() = runTest {
        val useCase = useCase(4)
        useCase.begin()
        useCase.cancel()
        assertEquals(listOf("cancel:u", "count:u", "periodic:u", "request:u"), events)
        assertEquals(SignOutOutcome.Failed, useCase.confirmDiscard()) // nothing is awaiting any more
    }

    @Test fun failedSignOutKeepsDataAndResumesSync() = runTest {
        val outcome = useCase(0, authResult = AuthStep.Failed(AuthFailure.Network)).begin()
        assertEquals(SignOutOutcome.Failed, outcome)
        assertEquals(listOf("cancel:u", "count:u", "signOut", "periodic:u", "request:u"), events)
    }

    @Test fun deleteFailureIsReported() = runTest {
        assertEquals(SignOutOutcome.Failed, useCase(0, failDelete = true).begin())
    }
}
