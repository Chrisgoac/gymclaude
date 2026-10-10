package com.chrisgoac.gymlog.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.sync.BootstrapRepository
import com.chrisgoac.gymlog.data.sync.SyncLocks
import com.chrisgoac.gymlog.data.sync.SyncRepositoryImpl
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.database.entity.BootstrapCheckpointEntity
import com.chrisgoac.gymlog.database.entity.BootstrapState
import com.chrisgoac.gymlog.database.entity.SyncCursorEntity
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.PullResponse
import com.chrisgoac.gymlog.model.PushRequest
import com.chrisgoac.gymlog.model.PushResponse
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
import com.chrisgoac.gymlog.network.SyncApi
import com.chrisgoac.gymlog.network.SyncApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A sync run belongs to the account it was started for, even if the user switches account meanwhile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountBindingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = UserDatabaseManagerImpl(context)
    private val locks = SyncLocks()

    private class SwitchableAuth : AuthSessionProvider {
        override val state = MutableStateFlow<AuthState>(AuthState.SignedIn(USER_A))
        override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String? =
            if (state.value == AuthState.SignedIn(expectedUserId)) "token-of-$expectedUserId" else null
    }

    /** Asks the session for a token exactly like the real client does, and records what would go on the wire. */
    private class TokenRecordingApi(private val auth: AuthSessionProvider) : SyncApi {
        val sentTokens = mutableListOf<String>()
        var beforePush: suspend () -> Unit = {}

        private suspend fun token(userId: String): String =
            auth.accessToken(userId)?.also { sentTokens += it } ?: throw SyncApiException.SessionRequired("No access token")

        override suspend fun push(expectedUserId: String, request: PushRequest): PushResponse {
            beforePush()
            token(expectedUserId)
            return PushResponse(request.mutations.map { it.mutationId }, emptyList())
        }

        override suspend fun pull(expectedUserId: String, cursor: String?, limit: Int): PullResponse {
            token(expectedUserId)
            return PullResponse(emptyList(), cursor ?: "c0", hasMore = false)
        }
    }

    @After fun tearDown() = runBlocking {
        databases.close()
        listOf(USER_A, USER_B).forEach { databases.closeAndDelete(it) }
    }

    private suspend fun prepare(userId: String): GymLogDatabase {
        val db = databases.open(userId)
        db.syncDao().saveBootstrapCheckpoint(BootstrapCheckpointEntity(state = BootstrapState.COMPLETED, lastCursor = "c1", pagesApplied = 1, updatedAt = 1))
        db.syncDao().saveCursor(SyncCursorEntity(cursor = "c1", updatedAt = 1))
        RoomLocalMutationRunner(db, DeviceId("dev")).run(
            LocalMutation {
                put(
                    ExerciseRecord(
                        id = RecordId("ex-$userId"), updatedAt = 5_000, deletedAt = null, nombre = "Press", grupoMuscular = "pecho",
                        equipamiento = "barra", tipo = "compuesto", notas = null, esPersonalizado = true,
                    ),
                )
            },
        )
        return db
    }

    @Test fun aRunForAAfterSwitchingToBNeverSendsBsTokenNorTouchesBsDatabase() = runBlocking {
        val auth = SwitchableAuth()
        val api = TokenRecordingApi(auth)
        val repository = SyncRepositoryImpl(databases, api, BootstrapRepository(api, locks), locks)
        prepare(USER_A)
        auth.state.value = AuthState.SignedIn(USER_B)
        val dbB = prepare(USER_B) // closes A's database

        val result = repository.syncOnce(USER_A)

        assertEquals(SyncResult.Failed(SyncRepositoryImpl.ACCOUNT_MISMATCH, 0), result)
        assertTrue(api.sentTokens.isEmpty())
        val outboxB = dbB.syncDao().allOutbox().single()
        assertEquals(0, outboxB.attemptCount)
    }

    @Test fun aRunForAInFlightDuringTheSwitchToBNeverSendsBsToken() = runBlocking {
        val auth = SwitchableAuth()
        val api = TokenRecordingApi(auth)
        val repository = SyncRepositoryImpl(databases, api, BootstrapRepository(api, locks), locks)
        prepare(USER_A)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforePush = {
            entered.complete(Unit)
            release.await()
        }

        val run = async(Dispatchers.Default) { repository.syncOnce(USER_A) }
        entered.await()
        auth.state.value = AuthState.SignedIn(USER_B) // the user switches account mid-run
        val dbB = prepare(USER_B)
        api.beforePush = {}
        release.complete(Unit)

        assertEquals(SyncResult.SessionRequired(0), run.await())
        assertTrue("got ${api.sentTokens}", api.sentTokens.none { it.endsWith(USER_B) })
        assertEquals(0, dbB.syncDao().allOutbox().single().attemptCount)
    }

    private companion object {
        const val USER_A = "user_a"
        const val USER_B = "user_b"
    }
}
