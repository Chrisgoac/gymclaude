package com.chrisgoac.gymlog.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.entity.BootstrapCheckpointEntity
import com.chrisgoac.gymlog.database.entity.BootstrapState
import com.chrisgoac.gymlog.database.entity.OutboxStatus
import com.chrisgoac.gymlog.database.entity.SyncCursorEntity
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.MutationId
import com.chrisgoac.gymlog.model.PullResponse
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncRecord
import com.chrisgoac.gymlog.model.SyncTable
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OutboxTransactionTest {
    private lateinit var db: GymLogDatabase
    private lateinit var store: RecordStore
    private val device = DeviceId("device-test")
    private var nextId = 0

    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private fun runner(mutationId: () -> MutationId = { MutationId(uuid(++nextId)) }) =
        RoomLocalMutationRunner(db, device, clock = { 1_000L }, newMutationId = mutationId)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GymLogDatabase::class.java).build()
        store = RecordStore(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun outboxMutations(): List<PushMutation> =
        db.syncDao().allOutbox().map { ContractJson.decodeFromString(PushMutation.serializer(), it.payloadJson) }

    private suspend fun assertNothingWritten(vararg records: SyncRecord) {
        assertTrue("outbox must stay empty", db.syncDao().allOutbox().isEmpty())
        records.forEach { assertNull("${it.id} must not be written", store.find(it.table, it.id)) }
    }

    @Test
    fun oneLogicalHistoryEditIsOneOutboxRowWithOrderedOperations() = runTest {
        // The session was already synced: its known serverVersion must travel as baseServerVersion.
        store.put(SampleRecords.session(), serverVersion = 7)
        val edited = listOf(
            SampleRecords.session(updatedAt = SampleRecords.T + 10),
            SampleRecords.loggedExercise("le-1"),
            SampleRecords.loggedSet("ls-1", peso = 82.5),
            SampleRecords.loggedSet("ls-2", peso = 85.0),
            SampleRecords.loggedExercise("le-2"),
        )

        val result = runner().run(LocalMutation { edited.forEach { put(it) }; "saved" })

        assertEquals("saved", result)
        val rows = db.syncDao().allOutbox()
        assertEquals(1, rows.size)
        assertEquals(OutboxStatus.PENDING, rows.single().status)
        assertEquals(0, rows.single().attemptCount)
        assertEquals(device.value, rows.single().deviceId)
        val pushed = outboxMutations().single()
        assertEquals(rows.single().mutationId, pushed.mutationId.value)
        assertEquals(edited, pushed.operations.map { it.record })
        assertEquals(listOf(7L, null, null, null, null), pushed.operations.map { it.baseServerVersion })
        // Local write keeps the last server version seen; only a pull changes it.
        assertEquals(7L, store.find(SyncTable.WORKOUT_SESSIONS, RecordId("ws-1"))?.serverVersion)
        assertEquals(edited.first(), store.find(SyncTable.WORKOUT_SESSIONS, RecordId("ws-1"))?.record)
    }

    @Test
    fun failedOutboxInsertRollsBackDomainRows() = runTest {
        val fixed = runner { MutationId(uuid(1)) }
        fixed.run(LocalMutation { put(SampleRecords.gym("gy-1")) })

        val second = listOf(SampleRecords.session("ws-9"), SampleRecords.loggedSet("ls-9"))
        try {
            // Same mutationId: the outbox insert hits the primary key after the rows were upserted.
            fixed.run(LocalMutation { second.forEach { put(it) } })
            fail("duplicate mutationId must fail")
        } catch (expected: Exception) {
            // SQLiteConstraintException from the outbox insert.
        }

        second.forEach { assertNull(store.find(it.table, it.id)) }
        assertEquals(1, db.syncDao().allOutbox().size)
        assertEquals(listOf(SampleRecords.gym("gy-1")), outboxMutations().single().operations.map { it.record })
    }

    @Test
    fun failureInsideTheMutationRollsBackEverything() = runTest {
        try {
            runner().run(LocalMutation<Unit> {
                put(SampleRecords.session())
                error("boom")
            })
            fail("must throw")
        } catch (expected: IllegalStateException) {
        }
        assertNothingWritten(SampleRecords.session())
    }

    @Test
    fun tombstonesAreRetainedNotDeleted() = runTest {
        runner().run(LocalMutation { put(SampleRecords.exercise("ex-7")) })
        runner().run(LocalMutation {
            val live = find(SyncTable.EXERCISES, RecordId("ex-7")) as ExerciseRecord
            put(live.copy(updatedAt = SampleRecords.T + 1, deletedAt = SampleRecords.T + 1))
        })

        val row = db.exerciseDao().findById("ex-7")
        assertEquals(SampleRecords.T + 1, row?.deletedAt)
        assertEquals(listOf("ex-7"), db.exerciseDao().getAll().map { it.id })
        assertTrue(db.exerciseDao().observeLive().first().isEmpty())
        val delete = outboxMutations().last().operations.single().record
        assertEquals(SampleRecords.T + 1, delete.deletedAt)
        assertEquals(2, db.syncDao().allOutbox().size)
    }

    @Test
    fun findSeesThisMutationsOwnPuts() = runTest {
        runner().run(LocalMutation {
            put(SampleRecords.gym("gy-3"))
            assertEquals(SampleRecords.gym("gy-3"), find(SyncTable.GYMS, RecordId("gy-3")))
        })
    }

    @Test
    fun outboxIsFifoAndQuarantineDoesNotBlockLaterRows() = runTest {
        val r = runner()
        r.run(LocalMutation { put(SampleRecords.gym("gy-a")) })
        r.run(LocalMutation { put(SampleRecords.gym("gy-b")) })
        r.run(LocalMutation { put(SampleRecords.gym("gy-c")) })
        val all = db.syncDao().allOutbox()
        assertEquals(listOf(1L, 2L, 3L), all.map { it.sequence })

        db.syncDao().quarantineOutbox(all[1].mutationId, "RECORD_ID_CONFLICT")
        db.syncDao().recordOutboxFailure(all[0].mutationId, "RATE_LIMITED")

        val pending = db.syncDao().pendingOutbox(limit = 20)
        assertEquals(listOf(all[0].mutationId, all[2].mutationId), pending.map { it.mutationId })
        assertEquals(1, pending.first().attemptCount)
        assertEquals("RATE_LIMITED", pending.first().lastErrorCode)
        assertEquals(1, db.syncDao().outboxCount(OutboxStatus.QUARANTINED))
        assertEquals(2, db.syncDao().observeOutboxCount(OutboxStatus.PENDING).first())

        db.syncDao().deleteOutbox(listOf(all[0].mutationId))
        r.run(LocalMutation { put(SampleRecords.gym("gy-d")) })
        assertEquals(listOf(2L, 3L, 4L), db.syncDao().allOutbox().map { it.sequence })
    }

    @Test
    fun validationRejectsWhatTheServerWouldRejectAndWritesNothing() = runTest {
        val nul = "a\u0000b"
        val cases: List<Pair<String, List<SyncRecord>>> = listOf(
            "bad id" to listOf(SampleRecords.gym("bad id")),
            "id too long" to listOf(SampleRecords.gym("x".repeat(129))),
            "NUL in text" to listOf(SampleRecords.gym().copy(nombre = nul)),
            "NUL in JSON key" to listOf(
                SampleRecords.mesocycle().copy(
                    progresion = JsonObject(mapOf(nul to JsonNull)),
                ),
            ),
            "negative timestamp" to listOf(SampleRecords.session().copy(fecha = -1)),
            "negative deletedAt" to listOf(SampleRecords.gym().copy(deletedAt = -5)),
            "timestamp over 2^53-1" to listOf(SampleRecords.gym().copy(updatedAt = MutationValidator.MAX_SAFE_INTEGER + 1)),
            "duplicate table/id" to listOf(SampleRecords.gym("gy-1"), SampleRecords.gym("gy-1")),
            "51 operations" to (1..51).map { SampleRecords.gym("gy-$it") },
            "no operations" to emptyList(),
            "catalog exercise" to listOf(SampleRecords.exercise("seed-press-banca")),
            "non-finite weight" to listOf(SampleRecords.loggedSet(peso = Double.NaN)),
        )
        cases.forEach { (name, records) ->
            try {
                runner().run(LocalMutation { records.forEach { put(it) } })
                fail("$name must be rejected")
            } catch (expected: InvalidLocalMutationException) {
                assertTrue("$name: ${expected.problems}", expected.problems.isNotEmpty())
            }
            assertNothingWritten(*records.toTypedArray())
        }
    }

    @Test
    fun validationRejectsNonUuidMutationIdAndBadDeviceId() = runTest {
        try {
            runner { MutationId("not-a-uuid") }.run(LocalMutation { put(SampleRecords.gym()) })
            fail("non-UUID mutationId must be rejected")
        } catch (expected: InvalidLocalMutationException) {
        }
        try {
            RoomLocalMutationRunner(db, DeviceId(""), newMutationId = { MutationId(uuid(5)) })
                .run(LocalMutation { put(SampleRecords.gym()) })
            fail("empty deviceId must be rejected")
        } catch (expected: InvalidLocalMutationException) {
        }
        assertNothingWritten(SampleRecords.gym())
    }

    @Test
    fun exactlyFiftyOperationsIsAccepted() = runTest {
        runner().run(LocalMutation { (1..50).forEach { put(SampleRecords.gym("gy-$it")) } })
        assertEquals(50, outboxMutations().single().operations.size)
    }

    @Test
    fun everyFixtureRecordSurvivesRoomWithItsServerVersion() = runTest {
        val text = File(System.getProperty("gymlog.fixturesDir"), "bootstrap-all-entities.json").readText()
        val changes = ContractJson.decodeFromString(PullResponse.serializer(), text).changes
        changes.forEach { store.put(it.record, it.serverVersion) }
        changes.forEach { change ->
            assertEquals(StoredRecord(change.record, change.serverVersion), store.find(change.table, change.record.id))
        }
        SampleRecords.all().forEach { record ->
            store.put(record, null)
            assertEquals(StoredRecord(record, null), store.find(record.table, record.id))
        }
    }

    @Test
    fun cursorAndBootstrapCheckpointPersist() = runTest {
        val sync = db.syncDao()
        assertNull(sync.cursor())
        assertNull(sync.bootstrapCheckpoint())
        sync.saveCursor(SyncCursorEntity(cursor = "MTAw", updatedAt = 1))
        sync.saveCursor(SyncCursorEntity(cursor = "MjAw", updatedAt = 2))
        assertEquals("MjAw", sync.cursor()?.cursor)

        sync.saveBootstrapCheckpoint(BootstrapCheckpointEntity(state = BootstrapState.IN_PROGRESS, lastCursor = "MTAw", pagesApplied = 1, updatedAt = 1))
        val inProgress = sync.bootstrapCheckpoint()!!
        assertEquals(false, inProgress.completed)
        assertEquals("MTAw", inProgress.lastCursor)
        sync.saveBootstrapCheckpoint(inProgress.copy(state = BootstrapState.COMPLETED, lastCursor = "MjAw", pagesApplied = 2))
        assertEquals(true, sync.observeBootstrapCheckpoint().first()?.completed)
    }
}
