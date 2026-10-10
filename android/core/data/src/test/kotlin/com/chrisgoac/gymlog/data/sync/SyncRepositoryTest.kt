package com.chrisgoac.gymlog.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RecordStore
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.BootstrapCheckpointEntity
import com.chrisgoac.gymlog.database.entity.BootstrapState
import com.chrisgoac.gymlog.database.entity.OutboxMutationEntity
import com.chrisgoac.gymlog.database.entity.OutboxStatus
import com.chrisgoac.gymlog.database.entity.SyncCursorEntity
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.model.ApiErrorCode
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.ExerciseChange
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.MutationId
import com.chrisgoac.gymlog.model.PullResponse
import com.chrisgoac.gymlog.model.PushRequest
import com.chrisgoac.gymlog.model.PushResponse
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.network.ServerClock
import com.chrisgoac.gymlog.network.SyncApi
import com.chrisgoac.gymlog.network.SyncApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: GymLogDatabase
    private lateinit var databases: FakeDatabases
    private val api = FakeSyncApi()
    private val locks = SyncLocks()
    private var nextMutation = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, GymLogDatabase::class.java).build()
        databases = FakeDatabases(OpenUserDatabase(USER, db))
    }

    @After
    fun tearDown() = db.close()

    private fun repository(applier: PullPageApplier = PullPageApplier()) =
        SyncRepositoryImpl(databases, api, BootstrapRepository(api, locks, applier), locks, applier)

    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    /** Enqueues a local edit of exercise [id] through the real outbox writer; returns its mutationId. */
    private suspend fun enqueue(id: String, nombre: String = "local $id", notas: String? = null, database: GymLogDatabase = db): String {
        val mutationId = uuid(++nextMutation)
        RoomLocalMutationRunner(database, DeviceId("dev-1"), newMutationId = { MutationId(mutationId) })
            .run(LocalMutation { put(exercise(id, updatedAt = T + nextMutation, nombre = nombre, notas = notas)) })
        return mutationId
    }

    private suspend fun markBootstrapped(cursor: String, database: GymLogDatabase = db) {
        database.syncDao().saveBootstrapCheckpoint(BootstrapCheckpointEntity(state = BootstrapState.COMPLETED, lastCursor = cursor, pagesApplied = 1, updatedAt = T))
        database.syncDao().saveCursor(SyncCursorEntity(cursor = cursor, updatedAt = T))
    }

    private suspend fun stored(id: String, database: GymLogDatabase = db) = database.exerciseDao().findById(id)

    private suspend fun outboxStatus(mutationId: String) = db.syncDao().allOutbox().single { it.mutationId == mutationId }

    // ---- Push ----

    @Test
    fun duplicatePushReplayDeletesTheOutboxRowOnceAndResendsIdenticalBytes() = runTest {
        markBootstrapped("c1")
        val a = enqueue("ex-a")
        // The request reaches the server but the response is lost.
        api.onPush = { throw SyncApiException.Transport(IOException("timeout")) }
        assertEquals(SyncResult.Retryable(null, 0), repository().syncOnce())
        assertEquals(1, outboxStatus(a).attemptCount)

        // The retry is replayed by the server: the id comes back (here even twice) as accepted.
        api.onPush = { request -> PushResponse(request.mutations.map { it.mutationId } + request.mutations.map { it.mutationId }, emptyList()) }
        val result = repository().syncOnce()

        assertEquals(SyncResult.Success(pushed = 1, pulled = 0, quarantined = 0), result)
        assertTrue(db.syncDao().allOutbox().isEmpty())
        val bodies = api.pushRequests.map { ContractJson.encodeToString(PushRequest.serializer(), it) }
        assertEquals("the retry must resend the exact same bytes", bodies[0], bodies[1])
    }

    @Test
    fun sessionRequiredPausesWithoutDeletingOrQuarantining() = runTest {
        markBootstrapped("c1")
        val a = enqueue("ex-a")
        val b = enqueue("ex-b")
        api.onPush = { throw SyncApiException.SessionRequired("HTTP 401") }

        assertEquals(SyncResult.SessionRequired(0), repository().syncOnce())

        val rows = db.syncDao().allOutbox()
        assertEquals(listOf(a, b), rows.map { it.mutationId })
        assertTrue(rows.all { it.status == OutboxStatus.PENDING && it.attemptCount == 0 && it.lastErrorCode == null })
        assertTrue("no pull after a 401", api.pullCursors.isEmpty())
    }

    @Test
    fun retryableErrorsSurfaceRetryAfterAndKeepTheRowsPending() = runTest {
        markBootstrapped("c1")
        val a = enqueue("ex-a")
        api.onPush = { throw apiError("RATE_LIMITED", status = 429, retryable = true, retryAfterSeconds = 17) }
        assertEquals(SyncResult.Retryable(retryAfterMillis = 17_000, quarantined = 0), repository().syncOnce())

        api.onPush = { throw apiError("WRITE_RACE", status = 409, retryable = true) }
        assertEquals(SyncResult.Retryable(retryAfterMillis = null, quarantined = 0), repository().syncOnce())

        val row = outboxStatus(a)
        assertEquals(OutboxStatus.PENDING, row.status)
        assertEquals(2, row.attemptCount)
        assertEquals("WRITE_RACE", row.lastErrorCode)
        assertTrue(api.pullCursors.isEmpty())
    }

    @Test
    fun quarantinedMutationDoesNotBlockLaterRows() = runTest {
        markBootstrapped("c1")
        val a = enqueue("ex-a")
        val b = enqueue("ex-b")
        val c = enqueue("ex-c")
        api.onPush = { request ->
            if (request.mutations.any { it.mutationId.value == b }) throw apiError("RECORD_ID_CONFLICT", status = 409, mutationId = b)
            accept(request)
        }

        val result = repository().syncOnce()

        assertEquals(SyncResult.Success(pushed = 2, pulled = 0, quarantined = 1), result)
        assertEquals(listOf(listOf(a, b, c), listOf(a, c)), api.pushedIds())
        val left = db.syncDao().allOutbox().single()
        assertEquals(b, left.mutationId)
        assertEquals(OutboxStatus.QUARANTINED, left.status)
        assertEquals("RECORD_ID_CONFLICT", left.lastErrorCode)

        // Quarantined rows are never resent.
        api.pushRequests.clear()
        repository().syncOnce()
        assertTrue(api.pushRequests.isEmpty())
    }

    @Test
    fun invalidRequestFieldPathQuarantinesTheMutationAtThatBatchIndex() = runTest {
        markBootstrapped("c1")
        val a = enqueue("ex-a")
        val b = enqueue("ex-b")
        api.onPush = { request ->
            if (request.mutations.size == 2) throw apiError("INVALID_REQUEST", details = mapOf("mutations.1.operations.0.record.nombre" to listOf("Required")))
            accept(request)
        }

        assertEquals(SyncResult.Success(pushed = 1, pulled = 0, quarantined = 1), repository().syncOnce())
        assertEquals(listOf(listOf(a, b), listOf(a)), api.pushedIds())
        assertEquals(OutboxStatus.QUARANTINED, outboxStatus(b).status)
    }

    @Test
    fun unattributableSchemaErrorIsolatesThePoisonMutation() = runTest {
        markBootstrapped("c1")
        val a = enqueue("ex-a")
        val b = enqueue("ex-b")
        val c = enqueue("ex-c")
        // Zod's flatten() keeps only the top-level key: no index, no mutationId.
        api.onPush = { request ->
            if (request.mutations.any { it.mutationId.value == b }) throw apiError("INVALID_REQUEST", details = mapOf("mutations" to listOf("Invalid input")))
            accept(request)
        }

        assertEquals(SyncResult.Success(pushed = 2, pulled = 0, quarantined = 1), repository().syncOnce())
        assertEquals(listOf(listOf(a, b, c), listOf(a), listOf(b), listOf(c)), api.pushedIds())
        assertEquals(OutboxStatus.QUARANTINED, outboxStatus(b).status)
    }

    @Test
    fun requestLevelErrorsFailWithoutQuarantiningTheOutbox() = runTest {
        markBootstrapped("c1")
        val a = enqueue("ex-a")
        api.onPush = { throw apiError("UNSUPPORTED_MEDIA_TYPE", status = 415) }

        assertEquals(SyncResult.Failed("UNSUPPORTED_MEDIA_TYPE", 0), repository().syncOnce())
        assertEquals(OutboxStatus.PENDING, outboxStatus(a).status)
    }

    @Test
    fun pushesFifoInBatchesOfAtMostTwenty() = runTest {
        markBootstrapped("c1")
        val ids = (1..25).map { enqueue("ex-$it") }
        api.onPush = ::accept

        assertEquals(SyncResult.Success(pushed = 25, pulled = 0, quarantined = 0), repository().syncOnce())
        assertEquals(listOf(ids.take(20), ids.drop(20)), api.pushedIds())
    }

    @Test
    fun batchesStayUnderTheBodyBudget() = runTest {
        markBootstrapped("c1")
        val big = "x".repeat(400_000)
        val ids = (1..4).map { enqueue("ex-$it", notas = big) }
        api.onPush = ::accept

        repository().syncOnce()

        assertEquals(listOf(ids.take(2), ids.drop(2)), api.pushedIds())
        api.pushRequests.forEach { request ->
            val bytes = ContractJson.encodeToString(PushRequest.serializer(), request).toByteArray().size
            assertTrue("$bytes bytes", bytes <= SyncRepositoryImpl.PUSH_BODY_BUDGET_BYTES)
        }
    }

    // ---- Pull ----

    @Test
    fun pullsUntilHasMoreIsFalseSavingTheCursorAfterEachPage() = runTest {
        markBootstrapped("c1")
        api.onPull = { cursor ->
            when (cursor) {
                "c1" -> page("c2", hasMore = true, change("ex-1", 11))
                "c2" -> page("c3", hasMore = false, change("ex-2", 12))
                else -> error("unexpected cursor $cursor")
            }
        }

        assertEquals(SyncResult.Success(pushed = 0, pulled = 2, quarantined = 0), repository().syncOnce())
        assertEquals(listOf<String?>("c1", "c2"), api.pullCursors)
        assertEquals("c3", db.syncDao().cursor()?.cursor)
        assertEquals(12L, stored("ex-2")?.serverVersion)
    }

    @Test
    fun interruptedPageApplyAdvancesNeitherRowsNorCursor() = runTest {
        markBootstrapped("c1")
        api.onPull = { page("c2", hasMore = false, change("ex-1", 11), change("ex-2", 12), change("ex-3", 13)) }
        val failing = PullPageApplier { index -> if (index == 1) throw IllegalStateException("crash mid-page") }

        try {
            repository(failing).syncOnce()
            fail("the failure must propagate")
        } catch (_: IllegalStateException) {
        }

        assertNull(stored("ex-1"))
        assertNull(stored("ex-2"))
        assertEquals("c1", db.syncDao().cursor()?.cursor)

        // The next run reapplies the same page from the same cursor.
        assertEquals(SyncResult.Success(0, 3, 0), repository().syncOnce())
        assertEquals(listOf<String?>("c1", "c1"), api.pullCursors)
        assertNotNull(stored("ex-3"))
    }

    @Test
    fun invalidCursorRunsAReplacementBootstrapThatIsOnlyCompleteAfterTheLastPage() = runTest {
        markBootstrapped("stale")
        RecordStore(db).put(exercise("ex-old", nombre = "old server copy"), serverVersion = 3)
        var stateDuringDownload: BootstrapState? = null
        api.onPull = { cursor ->
            when (cursor) {
                "stale" -> throw apiError("INVALID_CURSOR")
                null -> page("p1", hasMore = true, change("ex-old", 21, nombre = "fresh"))
                "p1" -> {
                    stateDuringDownload = db.syncDao().bootstrapCheckpoint()?.state
                    page("p2", hasMore = false, change("ex-new", 22))
                }
                else -> error("unexpected cursor $cursor")
            }
        }

        val result = repository().syncOnce()

        assertEquals(SyncResult.Success(0, 2, 0), result)
        assertEquals(listOf("stale", null, "p1"), api.pullCursors)
        assertEquals(BootstrapState.IN_PROGRESS, stateDuringDownload)
        val checkpoint = db.syncDao().bootstrapCheckpoint()!!
        assertEquals(BootstrapState.COMPLETED, checkpoint.state)
        assertEquals("p2", checkpoint.lastCursor)
        assertEquals("p2", db.syncDao().cursor()?.cursor)
        assertEquals("fresh", stored("ex-old")?.nombre)
        assertEquals(21L, stored("ex-old")?.serverVersion)
        assertNotNull(stored("ex-new"))
    }

    @Test
    fun pendingEditNewerThanThePulledRecordSurvivesThePull() = runTest {
        markBootstrapped("c1")
        // The user edits ex-1 offline while the pull is in flight.
        api.onPull = {
            enqueue("ex-1", nombre = "offline edit")
            page("c2", hasMore = false, change("ex-1", 30, nombre = "server"), change("ex-2", 31))
        }

        assertEquals(SyncResult.Success(pushed = 0, pulled = 1, quarantined = 0), repository().syncOnce())

        assertEquals("offline edit", stored("ex-1")?.nombre)
        assertNull("the pending row keeps its last known serverVersion", stored("ex-1")?.serverVersion)
        assertEquals(31L, stored("ex-2")?.serverVersion)
        assertEquals("the cursor still advances", "c2", db.syncDao().cursor()?.cursor)
    }

    @Test
    fun bootstrapSkipsPendingRecordsButAdoptsTheServerCopyOfQuarantinedOnes() = runTest {
        enqueue("ex-pending", nombre = "local pending")
        val poison = enqueue("ex-poison", nombre = "local poison")
        db.syncDao().quarantineOutbox(poison, "INVALID_REFERENCE")
        api.onPull = {
            page("p1", hasMore = false, change("ex-pending", 40), change("ex-poison", 41, nombre = "server"), change("ex-2", 42))
        }

        BootstrapRepository(api, locks).run(USER, db)

        assertEquals("local pending", stored("ex-pending")?.nombre)
        assertEquals("server", stored("ex-poison")?.nombre)
        assertEquals(41L, stored("ex-poison")?.serverVersion)
        assertNotNull(stored("ex-2"))
    }

    @Test
    fun pendingEditOlderThanThePulledRecordAdoptsTheServerCopy() = runTest {
        markBootstrapped("c1")
        var mutationId = ""
        api.onPull = {
            mutationId = enqueue("ex-1", nombre = "offline edit") // updatedAt T + 1
            page("c2", hasMore = false, change("ex-1", 30, nombre = "server", updatedAt = T + 100))
        }

        repository().syncOnce()

        assertEquals("server", stored("ex-1")?.nombre)
        assertEquals(T + 100, stored("ex-1")?.updatedAt)
        assertEquals(30L, stored("ex-1")?.serverVersion)
        assertEquals("the edit stays queued for the server to resolve", OutboxStatus.PENDING, outboxStatus(mutationId).status)
    }

    @Test
    fun writeAfterSeeingAServerVersionWinsEvenWithADeviceClockBehind() = runTest {
        markBootstrapped("c1")
        val v2 = T + 5_000
        api.onPull = { page("c2", hasMore = false, change("ex-1", 7, updatedAt = v2)) }
        repository().syncOnce()

        // Device clock far behind and no server offset learned yet.
        val skewed = ServerOffsetClock(ServerClock { 1_000L })
        val runner = RoomLocalMutationRunner(db, DeviceId("dev-1"), clock = skewed::nowMillis, newMutationId = { MutationId(uuid(99)) })
        val stamped = runner.run(
            LocalMutation {
                val now = nextTimestamp(SyncTable.EXERCISES, RecordId("ex-1"))
                put(exercise("ex-1", updatedAt = now, nombre = "edited"))
                now
            },
        )

        assertEquals(v2 + 1, stamped)
        assertEquals(v2 + 1, stored("ex-1")?.updatedAt)
        // A correct clock ahead of the stored version is used as is.
        val ahead = RoomLocalMutationRunner(db, DeviceId("dev-1"), clock = { v2 + 60_000 }, newMutationId = { MutationId(uuid(98)) })
        assertEquals(v2 + 60_000, ahead.run(LocalMutation { nextTimestamp(SyncTable.EXERCISES, RecordId("ex-1")).also { put(exercise("ex-1", updatedAt = it)) } }))
    }

    @Test
    fun corruptOutboxPayloadIsQuarantinedAndDoesNotBlockTheQueue() = runTest {
        markBootstrapped("c1")
        val dao = db.syncDao()
        dao.insertOutbox(
            OutboxMutationEntity(
                mutationId = uuid(50), deviceId = "dev-1", payloadJson = "{not json", createdAt = T,
                attemptCount = 0, lastErrorCode = null, sequence = dao.nextOutboxSequence(), status = OutboxStatus.PENDING,
            ),
        )
        val good = enqueue("ex-good")
        api.onPush = ::accept

        assertEquals(SyncResult.Success(pushed = 1, pulled = 0, quarantined = 1), repository().syncOnce())
        assertEquals(listOf(listOf(good)), api.pushedIds())
        val corrupt = outboxStatus(uuid(50))
        assertEquals(OutboxStatus.QUARANTINED, corrupt.status)
        assertEquals(SyncRepositoryImpl.CORRUPT_PAYLOAD, corrupt.lastErrorCode)
    }

    @Test
    fun pushThatAcceptsNothingRecordsTheFailureAndStops() = runTest {
        markBootstrapped("c1")
        val a = enqueue("ex-a")
        api.onPush = { PushResponse(emptyList(), emptyList()) }

        assertEquals(SyncResult.Retryable(null, 0), repository().syncOnce())
        assertEquals(1, api.pushRequests.size)
        val row = outboxStatus(a)
        assertEquals(OutboxStatus.PENDING, row.status)
        assertEquals(1, row.attemptCount)
        assertEquals("NONE_ACCEPTED", row.lastErrorCode)
    }

    @Test
    fun otherPullErrorsKeepTheCursor() = runTest {
        markBootstrapped("c1")
        api.onPull = { throw apiError("INVALID_REQUEST") }
        assertEquals(SyncResult.Failed("INVALID_REQUEST", 0), repository().syncOnce())

        api.onPull = { throw apiError("RATE_LIMITED", status = 429, retryable = true, retryAfterSeconds = 5) }
        assertEquals(SyncResult.Retryable(5_000, 0), repository().syncOnce())

        assertEquals("c1", db.syncDao().cursor()?.cursor)
        assertEquals(BootstrapState.COMPLETED, db.syncDao().bootstrapCheckpoint()?.state)
    }

    @Test
    fun statusIsPerUser() = runTest {
        markBootstrapped("c1")
        val repo = repository()
        repo.syncOnce()
        assertEquals(SyncResult.Success(0, 0, 0), repo.observeStatus().first().lastResult)

        val other = Room.inMemoryDatabaseBuilder(context, GymLogDatabase::class.java).build()
        try {
            databases.state.value = OpenUserDatabase("user_other", other)
            val status = repo.observeStatus().first()
            assertNull("another account never sees this account's result", status.lastResult)
            assertEquals(false, status.restoreComplete)
        } finally {
            other.close()
        }
    }

    // ---- Bootstrap ----

    @Test
    fun bootstrapRestartsFromScratchWhenTheCheckpointCursorIsInvalid() = runTest {
        db.syncDao().saveBootstrapCheckpoint(BootstrapCheckpointEntity(state = BootstrapState.IN_PROGRESS, lastCursor = "p1", pagesApplied = 3, updatedAt = T))
        api.onPull = { cursor ->
            when (cursor) {
                "p1" -> throw apiError("INVALID_CURSOR")
                null -> page("q1", hasMore = false, change("ex-1", 1))
                else -> error("unexpected cursor $cursor")
            }
        }

        BootstrapRepository(api, locks).run(USER, db)

        assertEquals(listOf("p1", null), api.pullCursors)
        val checkpoint = db.syncDao().bootstrapCheckpoint()!!
        assertTrue(checkpoint.completed)
        assertEquals(1, checkpoint.pagesApplied)
        assertEquals("q1", db.syncDao().cursor()?.cursor)
    }

    @Test
    fun invalidCursorFromAnEmptyCursorIsNotRetriedForever() = runTest {
        api.onPull = { throw apiError("INVALID_CURSOR") }
        try {
            BootstrapRepository(api, locks).run(USER, db)
            fail("must propagate")
        } catch (e: SyncApiException.Api) {
            assertEquals(ApiErrorCode.INVALID_CURSOR, e.code)
        }
        assertEquals(listOf<String?>(null), api.pullCursors)
    }


    @Test
    fun bootstrapAppliesEveryTableAndTombstoneAndMarksReady() = runTest {
        api.onPull = { cursor ->
            assertNull(cursor)
            ContractJson.decodeFromString(PullResponse.serializer(), fixture("bootstrap-all-entities.json"))
        }
        val progress = mutableListOf<Float>()

        BootstrapRepository(api, locks).run(USER, db) { progress += it }

        val checkpoint = db.syncDao().bootstrapCheckpoint()!!
        assertTrue(checkpoint.completed)
        assertEquals(checkpoint.lastCursor, db.syncDao().cursor()?.cursor)
        assertEquals(1f, progress.last())
        val tombstone = stored("bootstrap-exercises-tombstone")!!
        assertNotNull(tombstone.deletedAt)
        assertEquals(1002L, tombstone.serverVersion)
        assertNotNull(db.achievementDao().findById("bootstrap-achievements-tombstone")?.deletedAt)

        // Completed: running again downloads nothing.
        api.pullCursors.clear()
        BootstrapRepository(api, locks).run(USER, db)
        assertTrue(api.pullCursors.isEmpty())
    }

    @Test
    fun bootstrapResumesFromTheCheckpointAfterProcessDeath() = runTest {
        val name = "bootstrap-resume.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, GymLogDatabase::class.java, name).build()
        api.onPull = { cursor ->
            when (cursor) {
                null -> page("p1", hasMore = true, change("ex-1", 1))
                "p1" -> throw SyncApiException.Transport(IOException("connection lost"))
                else -> error("unexpected cursor $cursor")
            }
        }
        try {
            BootstrapRepository(api, locks).run(USER, first)
            fail("bootstrap must fail on page 2")
        } catch (_: SyncApiException.Transport) {
        }
        val partial = first.syncDao().bootstrapCheckpoint()!!
        assertEquals(BootstrapState.IN_PROGRESS, partial.state)
        assertEquals("p1", partial.lastCursor)
        assertNull("partial data never seeds the incremental cursor", first.syncDao().cursor())
        first.close()

        // "Process death": a fresh database handle and a fresh repository on the same file.
        val second = Room.databaseBuilder(context, GymLogDatabase::class.java, name).build()
        try {
            api.pullCursors.clear()
            api.onPull = { cursor ->
                assertEquals("p1", cursor)
                page("p2", hasMore = false, change("ex-2", 2))
            }
            val progress = mutableListOf<Float>()
            BootstrapRepository(api, SyncLocks()).run(USER, second) { progress += it }

            assertEquals(listOf<String?>("p1"), api.pullCursors)
            assertTrue(second.syncDao().bootstrapCheckpoint()!!.completed)
            assertEquals(2, second.syncDao().bootstrapCheckpoint()!!.pagesApplied)
            assertNotNull(stored("ex-1", second))
            assertNotNull(stored("ex-2", second))
            assertTrue("progress starts from the resumed page", progress.first() > 0f)
            assertEquals(1f, progress.last())
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun syncOnceResumesAnUnfinishedBootstrapInsteadOfPullingIncrementally() = runTest {
        db.syncDao().saveBootstrapCheckpoint(BootstrapCheckpointEntity(state = BootstrapState.IN_PROGRESS, lastCursor = "p1", pagesApplied = 1, updatedAt = T))
        api.onPull = { page("p2", hasMore = false, change("ex-2", 2)) }

        assertEquals(SyncResult.Success(0, 1, 0), repository().syncOnce())
        assertEquals(listOf<String?>("p1"), api.pullCursors)
        assertTrue(db.syncDao().bootstrapCheckpoint()!!.completed)
        assertEquals("p2", db.syncDao().cursor()?.cursor)
    }

    @Test
    fun runsAreSerializedPerUser() = runTest {
        markBootstrapped("c1")
        val gate = CompletableDeferred<Unit>()
        val entered = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        var calls = 0
        api.onPull = { cursor ->
            entered[calls++].complete(Unit)
            gate.await()
            page(cursor!!, hasMore = false)
        }
        val repo = repository()
        val first = async(Dispatchers.Default) { repo.syncOnce() }
        withContext(Dispatchers.Default) { entered[0].await() }
        val second = async(Dispatchers.Default) { repo.syncOnce() }

        // Room runs on its own threads: give the second run real time to (wrongly) reach the server.
        val overlapped = withContext(Dispatchers.Default) { withTimeoutOrNull(500) { entered[1].await() } }
        assertNull("a second run must wait for the first", overlapped)

        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, api.pullCursors.size)
    }

    @Test
    fun noOpenDatabaseMeansNoSession() = runTest {
        databases.state.value = null
        assertEquals(SyncResult.SessionRequired(0), repository().syncOnce())
    }

    // ---- Helpers ----

    private fun fixture(name: String) = File(System.getProperty("gymlog.fixturesDir"), name).readText()

    private fun accept(request: PushRequest) = PushResponse(request.mutations.map { it.mutationId }, emptyList())

    private fun page(cursor: String, hasMore: Boolean, vararg changes: ExerciseChange) = PullResponse(changes.toList(), cursor, hasMore)

    private fun change(id: String, serverVersion: Long, nombre: String = "server $id", updatedAt: Long = T) =
        ExerciseChange(exercise(id, updatedAt = updatedAt, nombre = nombre), serverVersion)

    private fun apiError(
        code: String,
        status: Int = 400,
        retryable: Boolean = false,
        mutationId: String? = null,
        details: Map<String, List<String>>? = null,
        retryAfterSeconds: Long? = null,
    ) = SyncApiException.Api(status, ApiErrorCode.fromWire(code), code, retryable, retryAfterSeconds, mutationId, details, "test")

    private class FakeDatabases(open: OpenUserDatabase?) : UserDatabaseManager {
        val state = MutableStateFlow(open)
        override val current: StateFlow<OpenUserDatabase?> = state
        override suspend fun open(userId: String): GymLogDatabase = error("not used")
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private class FakeSyncApi : SyncApi {
        val pushRequests = mutableListOf<PushRequest>()
        val pullCursors = mutableListOf<String?>()
        var onPush: suspend (PushRequest) -> PushResponse = { error("unexpected push") }
        var onPull: suspend (String?) -> PullResponse = { cursor -> PullResponse(emptyList(), cursor ?: "c0", hasMore = false) }

        fun pushedIds(): List<List<String>> = pushRequests.map { request -> request.mutations.map { it.mutationId.value } }

        override suspend fun push(request: PushRequest): PushResponse {
            pushRequests += request
            return onPush(request)
        }

        override suspend fun pull(cursor: String?, limit: Int): PullResponse {
            pullCursors += cursor
            return onPull(cursor)
        }
    }

    private companion object {
        const val USER = "user_test"
        const val T = 1_767_225_600_000L

        fun exercise(id: String, updatedAt: Long = T, nombre: String = "Press $id", notas: String? = null) = ExerciseRecord(
            id = RecordId(id), updatedAt = updatedAt, deletedAt = null, nombre = nombre, grupoMuscular = "pecho",
            equipamiento = "barra", tipo = "compuesto", notas = notas, esPersonalizado = true,
        )
    }
}
