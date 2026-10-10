package com.chrisgoac.gymlog.domain

import com.chrisgoac.gymlog.domain.auth.AuthFailure
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.auth.AuthSessionState
import com.chrisgoac.gymlog.domain.auth.AuthStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SignOutUseCaseTest {
    private val events = mutableListOf<String>()

    private val scheduler = object : BackgroundSyncScheduler {
        override suspend fun ensurePeriodic(userId: String) { events += "periodic:$userId" }
        override suspend fun requestSync(userId: String, queueBehindRunning: Boolean) { events += "request:$userId" }
        override suspend fun cancel(userId: String) { events += "cancel:$userId" }
        override fun suspend(userId: String) { events += "suspend:$userId" }
        override fun resume(userId: String) { events += "resume:$userId" }
    }

    private val marks = mutableSetOf<String>()
    private var failClear = false

    private fun cleaner(unsynced: Int, failDelete: Boolean = false, failCount: Boolean = false, cancelDelete: Boolean = false) = object : LocalAccountCleaner {
        override suspend fun unsyncedChangeCount(userId: String): Int {
            events += "count:$userId"
            if (failCount) error("not the open database")
            return unsynced
        }
        override suspend fun markPendingDeletion(userId: String) { events += "mark:$userId"; marks += userId }
        override suspend fun confirmPendingDeletion(userId: String) { events += "confirm:$userId" }
        override suspend fun clearPendingDeletion(userId: String) {
            events += "clear:$userId"
            if (failClear) error("prefs")
            marks -= userId
        }
        override suspend fun deleteLocalData(userId: String) {
            events += "delete:$userId"
            if (!failDelete && !cancelDelete) marks -= userId
            if (cancelDelete) throw kotlinx.coroutines.CancellationException("scope gone")
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

    private fun useCase(
        unsynced: Int,
        authResult: AuthStep = AuthStep.Completed,
        failDelete: Boolean = false,
        failCount: Boolean = false,
        cancelDelete: Boolean = false,
    ) = SignOutUseCase({ "u" }, scheduler, cleaner(unsynced, failDelete, failCount, cancelDelete), auth(authResult))

    private val fullSignOut = listOf("suspend:u", "count:u", "cancel:u", "mark:u", "signOut", "confirm:u", "delete:u", "resume:u")

    @Test fun withNothingUnsyncedItRecordsDeletionSignsOutAndDeletes() = runTest {
        assertEquals(SignOutOutcome.SignedOut, useCase(0).begin())
        assertEquals(fullSignOut, events)
    }

    @Test fun withUnsyncedChangesItSuspendsTriggersAsksAndTouchesNothingElse() = runTest {
        assertEquals(SignOutOutcome.ConfirmationRequired(4), useCase(4).begin())
        assertEquals(listOf("suspend:u", "count:u"), events)
    }

    @Test fun confirmingCancelsWorkAndDiscardsTheChanges() = runTest {
        val useCase = useCase(4)
        useCase.begin()
        assertEquals(SignOutOutcome.SignedOut, useCase.confirmDiscard())
        assertEquals(fullSignOut, events)
    }

    @Test fun decliningResumesTriggersAndNeverCancelsWork() = runTest {
        val useCase = useCase(4)
        useCase.begin()
        useCase.cancel()
        assertEquals(listOf("suspend:u", "count:u", "resume:u", "request:u"), events)
        assertEquals(SignOutOutcome.Failed, useCase.confirmDiscard()) // nothing is awaiting any more
    }

    @Test fun abandoningTheConfirmationResumesTriggersAndKeepsEverything() = runTest {
        val useCase = useCase(4)
        useCase.begin()
        useCase.abandon()
        useCase.abandon() // idempotent
        assertEquals(listOf("suspend:u", "count:u", "resume:u"), events)
    }

    @Test fun failedSignOutForgetsTheDeletionMarkKeepsDataAndResumesSync() = runTest {
        val outcome = useCase(0, authResult = AuthStep.Failed(AuthFailure.Network)).begin()
        assertEquals(SignOutOutcome.Failed, outcome)
        assertEquals(
            listOf("suspend:u", "count:u", "cancel:u", "mark:u", "signOut", "clear:u", "resume:u", "periodic:u", "request:u", "resume:u"),
            events,
        )
    }

    @Test fun deleteFailureIsReportedAndTheMarkSurvivesForTheNextStartSweep() = runTest {
        assertEquals(SignOutOutcome.Failed, useCase(0, failDelete = true).begin())
        assertEquals(false, events.contains("clear:u"))
        assertEquals("resume:u", events.last())
    }

    @Test fun cancellingTheCallerMidSignOutStillFinishesAndLeavesNoStrandedMark() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slowAuth = object : AuthGateway by auth() {
            override suspend fun signOut(): AuthStep { events += "signOut"; gate.await(); return AuthStep.Completed }
        }
        val useCase = SignOutUseCase({ "u" }, scheduler, cleaner(0), slowAuth)
        val job = launch { useCase.begin() }
        runCurrent()
        assertEquals(setOf("u"), marks) // mark written, sign-out in flight

        job.cancel() // the screen went away
        gate.complete(Unit)
        job.join()

        assertEquals(fullSignOut, events)
        assertEquals(emptySet<String>(), marks)
    }

    @Test fun failedSignOutLeavesNoMark() = runTest {
        useCase(0, authResult = AuthStep.Failed(AuthFailure.Network)).begin()
        assertEquals(emptySet<String>(), marks)
    }

    @Test fun failedSignOutWhoseClearAlsoFailsIsStillReportedAndNeverConfirmsTheMark() = runTest {
        failClear = true
        assertEquals(SignOutOutcome.Failed, useCase(0, authResult = AuthStep.Failed(AuthFailure.Network)).begin())
        assertEquals(false, events.contains("confirm:u")) // only an intent remains; it never deletes
        assertEquals(false, events.contains("delete:u"))
    }

    @Test fun anUnreadableOutboxAbortsWithoutCancellingAnything() = runTest {
        assertEquals(SignOutOutcome.Failed, useCase(0, failCount = true).begin())
        assertEquals(listOf("suspend:u", "count:u", "resume:u"), events)
    }

    @Test fun defaultUseCasesRouteSyncRequestsThroughTheScheduler() = runTest {
        val repository = object : SyncRepository {
            override fun observeStatus() = throw NotImplementedError()
            override suspend fun syncOnce(): SyncResult = error("a manual sync must not bypass the scheduler")
            override fun observeDiagnostics() = throw NotImplementedError()
        }
        DefaultSyncUseCases(repository, scheduler) { "u" }.requestSync()
        assertEquals(listOf("request:u"), events)
    }
}
