package com.chrisgoac.gymlog.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.RootState
import com.chrisgoac.gymlog.data.sync.BootstrapRepository
import com.chrisgoac.gymlog.data.sync.SyncLocks
import com.chrisgoac.gymlog.data.sync.SyncRepositoryImpl
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.database.entity.BootstrapCheckpointEntity
import com.chrisgoac.gymlog.database.entity.BootstrapState
import com.chrisgoac.gymlog.database.entity.SyncCursorEntity
import com.chrisgoac.gymlog.domain.SyncRepository
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.PullResponse
import com.chrisgoac.gymlog.model.PushRequest
import com.chrisgoac.gymlog.model.PushResponse
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.network.SyncApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SignOutIsolationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = UserDatabaseManagerImpl(context)
    private val locks = SyncLocks()

    /** A server whose push stays in flight until the test lets it answer. */
    private class SuspendedPushApi : SyncApi {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun push(request: PushRequest): PushResponse {
            entered.complete(Unit)
            release.await()
            return PushResponse(request.mutations.map { it.mutationId }, emptyList())
        }

        override suspend fun pull(cursor: String?, limit: Int) = PullResponse(emptyList(), cursor ?: "c0", hasMore = false)
    }

    @After fun tearDown() = runBlocking { databases.close() }

    @Test fun deletionWaitsForAnInFlightSyncAndThenRemovesEveryFile() = runBlocking {
        val api = SuspendedPushApi()
        val repository = SyncRepositoryImpl(databases, api, BootstrapRepository(api, locks), locks)
        val cleaner = RoomLocalAccountCleaner(context, databases, locks)

        val db = databases.open("user_1")
        db.syncDao().saveBootstrapCheckpoint(BootstrapCheckpointEntity(state = BootstrapState.COMPLETED, lastCursor = "c1", pagesApplied = 1, updatedAt = 1))
        db.syncDao().saveCursor(SyncCursorEntity(cursor = "c1", updatedAt = 1))
        RoomLocalMutationRunner(db, DeviceId("dev")).run(
            LocalMutation {
                put(
                    ExerciseRecord(
                        id = RecordId("ex-1"), updatedAt = 5_000, deletedAt = null, nombre = "Press", grupoMuscular = "pecho",
                        equipamiento = "barra", tipo = "compuesto", notas = null, esPersonalizado = true,
                    ),
                )
            },
        )

        val sync = async(Dispatchers.Default) { repository.syncOnce() }
        api.entered.await()
        val delete = async(Dispatchers.Default) { cleaner.deleteLocalData("user_1") }

        delay(500)
        assertFalse("deletion must wait for the run", delete.isCompleted)
        assertTrue(databases.databaseFiles("user_1").first().exists())

        api.release.complete(Unit)
        assertTrue(sync.await() is SyncResult.Success)
        delete.await()

        assertTrue(databases.databaseFiles("user_1").none { it.exists() })
        assertEquals(null, databases.current.value)
    }

    @Test fun anInterruptedDeletionIsFinishedByTheStartupSweepWithoutRawIdsOnDisk() = runBlocking {
        val before = RoomLocalAccountCleaner(context, databases, locks)
        databases.open("user_1").syncDao().cursor() // forces the file to exist
        before.markPendingDeletion("user_1")
        databases.close() // "crash" after the mark, before the files were deleted
        val stored = context.getSharedPreferences("gymlog.pending-deletion", Context.MODE_PRIVATE).getStringSet("hashes", null)
        assertEquals(setOf(UserDatabaseManagerImpl.userHash("user_1")), stored)
        assertTrue(databases.databaseFiles("user_1").first().exists())

        RoomLocalAccountCleaner(context, databases, locks).sweepPendingDeletions()

        assertTrue(databases.databaseFiles("user_1").none { it.exists() })
        assertTrue(context.getSharedPreferences("gymlog.pending-deletion", Context.MODE_PRIVATE).getStringSet("hashes", null).orEmpty().isEmpty())
    }

    @Test fun aSuccessfulDeletionClearsItsMark() = runBlocking {
        val cleaner = RoomLocalAccountCleaner(context, databases, locks)
        databases.open("user_1")
        cleaner.markPendingDeletion("user_1")
        cleaner.deleteLocalData("user_1")
        assertTrue(context.getSharedPreferences("gymlog.pending-deletion", Context.MODE_PRIVATE).getStringSet("hashes", null).orEmpty().isEmpty())
    }

    @Test fun nothingSyncsWhileSignOutIsInProgress() = runBlocking {
        val scheduler = WorkManagerSyncScheduler({ error("no WorkManager needed") }, RetryAfterGate())
        val repository = object : SyncRepository {
            override fun observeStatus() = error("unused")
            override fun observeDiagnostics() = error("unused")
            override suspend fun syncOnce(): SyncResult = error("unused")
        }
        val environment = AppSyncEnvironment(MutableStateFlow<RootState>(RootState.Ready("u")), repository, scheduler, RetryAfterGate())
        val hash = UserDatabaseManagerImpl.userHash("u")

        assertEquals(SyncReadiness.Ready, environment.readiness(hash))
        scheduler.suspend("u")
        assertEquals(SyncReadiness.NotYet, environment.readiness(hash))
        scheduler.resume("u")
        assertEquals(SyncReadiness.Ready, environment.readiness(hash))
    }
}
