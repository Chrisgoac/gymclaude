package com.chrisgoac.gymlog.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.sync.SyncLocks
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.database.entity.SyncCursorEntity
import com.chrisgoac.gymlog.network.AuthState
import com.chrisgoac.gymlog.sync.RoomLocalAccountCleaner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Two accounts on one device: deleting A wipes exactly A's database, files and caches; B is untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountDeletionIsolationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = UserDatabaseManagerImpl(context) { it.allowMainThreadQueries() }
    private val forgotten = mutableListOf<String>()
    private val locks = SyncLocks()
    private val cleaner = RoomLocalAccountCleaner(context, databases, locks, onLocalDataDeleted = { forgotten += it })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val marks = DataStoreAccountDeletionMarks(File(context.filesDir, "account_deletion_test.preferences_pb"), scope)
    private val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(USER_A))

    @After fun tearDown() = runBlocking {
        databases.closeAndDelete(USER_A)
        databases.closeAndDelete(USER_B)
        scope.cancel()
    }

    private fun hash(userId: String) = UserDatabaseManagerImpl.userHash(userId)

    private fun privateFiles(userId: String): List<File> {
        val files = File(context.filesDir, "users/${hash(userId)}").apply { mkdirs() }
        val cache = File(context.cacheDir, "users/${hash(userId)}").apply { mkdirs() }
        return listOf(File(files, "photo.jpg").apply { writeText("x") }, File(cache, "thumb.jpg").apply { writeText("y") })
    }

    private fun coordinator() = AccountDeletionCoordinator(
        marks = marks,
        hashOf = ::hash,
        stopWork = {},
        auth = auth,
        identityReady = { true },
        signOut = { auth.value = AuthState.SignedOut; true },
        wipe = cleaner::deleteAccountData,
        scope = scope,
    )

    @Test fun deletingOneAccountKeepsTheOtherIntact() = runBlocking {
        databases.open(USER_B).syncDao().saveCursor(SyncCursorEntity(cursor = "b", updatedAt = 1))
        databases.close()
        val bFiles = privateFiles(USER_B)
        databases.open(USER_A).syncDao().saveCursor(SyncCursorEntity(cursor = "a", updatedAt = 1))
        val aFiles = privateFiles(USER_A)

        val coordinator = coordinator()
        coordinator.begin(USER_A, null)

        assertEquals(AccountDeletionStatus.Done(null), coordinator.status.value)
        assertTrue(databases.databaseFiles(USER_A).none { it.exists() })
        assertTrue(aFiles.none { it.exists() })
        assertEquals(listOf(hash(USER_A)), forgotten)
        assertTrue(marks.pending().isEmpty())

        assertTrue(bFiles.all { it.exists() })
        assertEquals("b", databases.open(USER_B).syncDao().cursor()?.cursor)
    }

    @Test fun theWipeWaitsForAnInFlightRunOfTheAccountAndNothingSurvivesIt() = runBlocking {
        databases.open(USER_A).syncDao().saveCursor(SyncCursorEntity(cursor = "a", updatedAt = 1))
        val running = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // A sync/photo run of A holding the account's lock, still writing into A's private directory.
        val run = scope.launch {
            locks.forUser(USER_A).withLock {
                running.complete(Unit)
                release.await()
                File(context.filesDir, "users/${hash(USER_A)}/late.tmp").apply { parentFile?.mkdirs() }.writeText("late")
            }
        }
        running.await()

        val coordinator = coordinator()
        val deletion = scope.async { coordinator.begin(USER_A, null) }
        delay(200)
        assertTrue("the database is not deleted under the running work", databases.databaseFiles(USER_A).any { it.exists() })

        release.complete(Unit)
        run.join()
        deletion.await()

        assertFalse(File(context.filesDir, "users/${hash(USER_A)}").exists())
        assertTrue(databases.databaseFiles(USER_A).none { it.exists() })
        assertEquals(AccountDeletionStatus.Done(null), coordinator.status.value)
    }

    @Test fun theMarksAreDurableUntilTheirTransitions() = runBlocking {
        marks.addIntent(hash(USER_A))
        assertEquals(setOf(hash(USER_A)), marks.intents())
        assertTrue(marks.pending().isEmpty())
        marks.promote(hash(USER_A), confirmed = false)
        assertEquals(setOf(hash(USER_A)), marks.unconfirmed())
        assertEquals(setOf(hash(USER_A)), marks.pending())
        assertTrue(marks.intents().isEmpty())
        marks.remove(hash(USER_A))
        assertTrue(marks.pending().isEmpty())
        assertTrue(marks.unconfirmed().isEmpty())
    }

    private companion object {
        const val USER_A = "user_iso_a"
        const val USER_B = "user_iso_b"
    }
}
