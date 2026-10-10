package com.chrisgoac.gymlog.auth

import com.chrisgoac.gymlog.network.AuthState
import com.clerk.api.session.Session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ClerkAuthStateReducerTest {
    private val reducer = ClerkAuthStateReducer()

    private fun snapshot(userId: String? = null, phase: SessionPhase = SessionPhase.None, initialized: Boolean = true, failed: Boolean = false) =
        ClerkSnapshot(initialized = initialized, initializationFailed = failed, userId = userId, phase = phase)

    @Test fun uninitializedIsLoadingUnlessInitializationFailed() {
        assertEquals(AuthState.Loading, reducer.reduce(snapshot(initialized = false)))
        assertEquals(AuthState.SignedOut, reducer.reduce(snapshot(initialized = false, failed = true)))
    }

    @Test fun loadedUserWithoutAnActiveSessionStaysSignedOut() {
        assertEquals(AuthState.SignedOut, reducer.reduce(snapshot(userId = "user_1", phase = SessionPhase.Pending)))
        assertEquals(AuthState.SignedOut, reducer.reduce(snapshot(userId = null, phase = SessionPhase.Active)))
    }

    @Test fun activeSessionIsSignedIn() {
        assertEquals(AuthState.SignedIn("user_1"), reducer.reduce(snapshot("user_1", SessionPhase.Active)))
    }

    @Test fun sessionThatDisappearsWithoutSignOutIsExpired() {
        reducer.reduce(snapshot("user_1", SessionPhase.Active))
        assertEquals(AuthState.SessionExpired("user_1"), reducer.reduce(snapshot()))
    }

    @Test fun endedSessionIsExpiredForItsUser() {
        assertEquals(AuthState.SessionExpired("user_1"), reducer.reduce(snapshot("user_1", SessionPhase.Ended)))
    }

    @Test fun explicitSignOutIsSignedOutNotExpired() {
        reducer.reduce(snapshot("user_1", SessionPhase.Active))
        reducer.signOutRequested()
        assertEquals(AuthState.SignedOut, reducer.reduce(snapshot()))
        // And it is forgotten: a later empty snapshot is still just signed out.
        assertEquals(AuthState.SignedOut, reducer.reduce(snapshot()))
    }

    @Test fun failedSignOutKeepsExpiryDetection() {
        reducer.reduce(snapshot("user_1", SessionPhase.Active))
        reducer.signOutRequested()
        reducer.signOutAbandoned()
        assertEquals(AuthState.SessionExpired("user_1"), reducer.reduce(snapshot()))
    }

    @Test fun clerkStatusesMapToPhases() {
        assertEquals(SessionPhase.Active, SessionStatus.ACTIVE.toPhase())
        assertEquals(SessionPhase.Pending, SessionStatus.PENDING.toPhase())
        assertEquals(SessionPhase.Pending, SessionStatus.UNKNOWN.toPhase())
        listOf(
            SessionStatus.EXPIRED, SessionStatus.REVOKED, SessionStatus.ENDED,
            SessionStatus.ABANDONED, SessionStatus.REMOVED, SessionStatus.REPLACED,
        ).forEach { assertEquals(it.name, SessionPhase.Ended, it.toPhase()) }
        assertEquals(SessionPhase.None, (null as SessionStatus?).toPhase())
    }
}
