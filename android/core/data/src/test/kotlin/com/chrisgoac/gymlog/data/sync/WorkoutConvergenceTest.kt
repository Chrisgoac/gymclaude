package com.chrisgoac.gymlog.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.exercise.OfflineExerciseRepository
import com.chrisgoac.gymlog.data.routine.OfflineRoutineRepository
import com.chrisgoac.gymlog.data.workout.OfflineHistoryRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutEditRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutLogRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutRepository
import com.chrisgoac.gymlog.database.GYMLOG_MIGRATIONS
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RecordStore
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.domain.history.DefaultSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.LoggedExerciseDraft
import com.chrisgoac.gymlog.domain.history.LoggedSetDraft
import com.chrisgoac.gymlog.domain.history.SaveResult
import com.chrisgoac.gymlog.domain.history.WorkoutDraft
import com.chrisgoac.gymlog.domain.routine.RoutineExerciseInput
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.workout.FinishResult
import com.chrisgoac.gymlog.domain.workout.FinishWorkoutUseCase
import com.chrisgoac.gymlog.domain.workout.SetChange
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncRecord
import com.chrisgoac.gymlog.model.SyncTable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Phase 3 gate (Task 9): two independent devices of the same account, each with its own file
 * database, outbox and sync stack, converge through [FakeSyncServer] after offline workout work.
 * Everything below the [com.chrisgoac.gymlog.network.SyncApi] seam is production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkoutConvergenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = T0
    private var ids = 0
    private val devices = mutableListOf<Device>()

    @After
    fun tearDown() {
        devices.forEach { it.close() }
        devices.forEach { context.deleteDatabase(it.fileName) }
    }

    private fun nextId() = "00000000-0000-4000-8000-%012d".format(++ids)

    private class Manager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase(USER, db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    /** One installation: its own Room FILE, device id, outbox and sync stack. */
    private inner class Device(val fileName: String, val deviceId: String, val link: FakeSyncServer.Link) {
        val db: GymLogDatabase = Room.databaseBuilder(context, GymLogDatabase::class.java, fileName)
            .addMigrations(*GYMLOG_MIGRATIONS).allowMainThreadQueries().build()
        private val manager = Manager(db)
        private val runner = RoomLocalMutationRunner(db, DeviceId(deviceId), clock = { now })
        private val runnerFor = { _: GymLogDatabase -> runner }
        private val exercises = OfflineExerciseRepository(manager, runnerFor)
        val routines = OfflineRoutineRepository(manager, runnerFor, exercises, newId = ::nextId)
        val workouts = OfflineWorkoutRepository(manager, runnerFor, exercises, now = { now }, newId = ::nextId)
        val log = OfflineWorkoutLogRepository(manager, runnerFor, newId = ::nextId)
        val history = OfflineHistoryRepository(manager, runnerFor, exercises, routines, now = { now })
        val finish = FinishWorkoutUseCase(workouts, workouts)
        val save = DefaultSaveWorkoutDraftUseCase(OfflineWorkoutEditRepository(manager, runnerFor)) { now }
        private val locks = SyncLocks()
        val sync = SyncRepositoryImpl(manager, link, BootstrapRepository(link, locks, clock = { now }), locks, clock = { now })

        suspend fun seedCatalog() = db.catalogDao().upsertAll(
            listOf(
                CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null),
                CatalogExerciseEntity("seed-remo", "Remo con barra", "espalda", "barra", "compuesto", null, null, null),
                CatalogExerciseEntity("seed-squat", "Sentadilla", "cuadriceps", "barra", "compuesto", null, null, null),
            ),
        )

        suspend fun addGym(id: String) = runner.run(
            LocalMutation { put(GymRecord(RecordId(id), nextTimestamp(SyncTable.GYMS, RecordId(id)), null, "Gym $id", 0, false)) },
        )

        suspend fun detail(sessionId: String): WorkoutAggregate = history.observeDetail(sessionId).first()!!.workout

        suspend fun pendingOutbox(): Int = db.syncDao().allOutbox().size

        /** Every synced record (tombstones included), as wire records. */
        suspend fun snapshot(): Map<Pair<SyncTable, String>, SyncRecord> {
            val store = RecordStore(db)
            return buildMap {
                ROOM_TABLES.forEach { (table, name) ->
                    val rowIds = db.query("SELECT id FROM $name", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                    rowIds.forEach { id -> put(table to id, store.find(table, RecordId(id))!!.record) }
                }
            }
        }

        fun close() = db.close()
    }

    private fun device(fileName: String, deviceId: String, link: FakeSyncServer.Link) =
        Device(fileName, deviceId, link).also { devices += it }

    /** Process death: the old stack is dropped and a new one opens the same database file. */
    private fun kill(device: Device): Device {
        device.close()
        devices -= device
        return device(device.fileName, device.deviceId, device.link)
    }

    private fun success(result: SyncResult) = assertTrue("expected success, was $result", result is SyncResult.Success)

    @Test
    fun offlineRoutineWorkoutAndHistoryEditConvergeOnASecondDevice() = runTest {
        val server = FakeSyncServer(now = { now }, pageSize = 4) // small pages exercise cursor pagination
        var a = device("convergence-a.db", "device-a", server.Link(USER))
        a.seedCatalog()

        // ---- Device A, offline from the first write ----
        a.addGym("g1")
        a.addGym("g2")
        val routine = a.routines.create(
            RoutineInput("Push", "", listOf(RoutineExerciseInput("k-press", "seed-press", "Press"), RoutineExerciseInput("k-remo", "seed-remo", "Remo"))),
        )
        now += MINUTE
        val sessionId = a.workouts.startRoutineWorkout(routine.id, "g1").sessionId
        val (press, remo) = a.workouts.observeActiveWorkout().first()!!.exercises.map { it.id }
        val pressSets = listOf(60.0, 62.5, 65.0).map { weight -> now += MINUTE; a.log.addSet(press, weight, 8, warmup = false) }
        now += MINUTE
        a.log.moveSet(pressSets[2], -2) // reorder: heaviest set first
        val remoSets = listOf(50.0, 70.0).map { weight -> now += MINUTE; a.log.addSet(remo, weight, 10, warmup = false) }
        a.log.updateSet(remoSets[1], SetChange(reps = 9))

        // The app is killed mid-workout and reopened over the same file.
        a = kill(a)
        val resumed = a.workouts.observeActiveWorkout().first()!!
        assertEquals(sessionId, resumed.session.id)
        assertEquals(listOf(pressSets[2], pressSets[0], pressSets[1]), resumed.exercises[0].sets.map { it.id })
        assertEquals(listOf(0, 1, 2), resumed.exercises[0].sets.map { it.position })

        now += 30 * MINUTE
        assertTrue(a.finish(sessionId, "Buena sesión") is FinishResult.Finished)
        assertNull(a.workouts.observeActiveWorkout().first())

        // Historical edit: date, gym, an added exercise, a removed exercise, set values and order.
        now += DAY
        val original = a.detail(sessionId)
        val newStart = original.session.startedAt - 2 * DAY
        val squat = nextId()
        val squatSet = nextId()
        val draft = WorkoutDraft.from(original)
            .copy(startedAt = Instant.ofEpochMilli(newStart), gymId = "g2", notes = "Editada")
            .withSet(pressSets[0]) { it.copy(weight = "61") }
            .removeSet(pressSets[1])
            .moveSet(pressSets[0], -1)
            .removeExercise(remo)
            .let { it.copy(exercises = it.exercises + LoggedExerciseDraft(squat, "seed-squat", "Sentadilla", listOf(LoggedSetDraft(squatSet, "100", "5", false)))) }
        assertTrue(a.save(original, draft) is SaveResult.Saved)
        val edited = a.detail(sessionId)
        assertTrue("nothing reached the server while offline", server.applications.isEmpty())
        assertTrue(a.pendingOutbox() > 0)

        // A sync attempt while still offline keeps every mutation queued.
        assertTrue(a.sync.syncOnce(USER) is SyncResult.Retryable)
        val queued = a.pendingOutbox()

        // ---- Reconnect: the first delivery is applied but its response is lost ----
        a.link.online = true
        a.link.loseNextPushResponse = true
        assertTrue(a.sync.syncOnce(USER) is SyncResult.Retryable)
        val firstBody = a.link.pushBodies.single()

        // The worker runs again and delivers the same mutations a second time.
        success(a.sync.syncOnce(USER))
        assertEquals("the retry resends the exact bytes", firstBody, a.link.pushBodies[1])
        assertEquals(0, a.pendingOutbox())
        assertEquals(queued, server.applications.size)
        assertTrue("each mutation applied once on the server", server.applications.values.all { it == 1 })
        assertTrue(server.replays >= 1)
        // The replayed batch wrote no journal entries (only the not-yet-sent rest did).
        val replayedIds = Regex("\"mutationId\":\"([^\"]+)\"").findAll(firstBody).map { it.groupValues[1] }.toSet()
        assertTrue(replayedIds.isNotEmpty() && replayedIds.all { server.applications[it] == 1 })

        // Delivering the identical request once more is pure replay.
        val journalBefore = server.journalSize
        server.push(firstBody)
        assertEquals(journalBefore, server.journalSize)
        assertTrue(server.applications.values.all { it == 1 })

        // ---- Device B: a fresh install of the same account bootstraps from the server ----
        val b = device("convergence-b.db", "device-b", server.Link(USER))
        b.seedCatalog()
        b.link.online = true
        success(b.sync.syncOnce(USER))

        val serverState = server.snapshot()
        val stateA = a.snapshot()
        val stateB = b.snapshot()
        assertEquals(serverState, stateA)
        assertEquals(stateA, stateB)
        assertEquals(edited, b.detail(sessionId))
        assertEquals(a.detail(sessionId), b.detail(sessionId))

        // Tombstones reached B: the removed set and the removed exercise with its sets.
        listOf(pressSets[1], remoSets[0], remoSets[1]).forEach { id ->
            assertNotNull("set $id is a tombstone on B", stateB.getValue(SyncTable.LOGGED_SETS to id).deletedAt)
        }
        assertNotNull(stateB.getValue(SyncTable.LOGGED_EXERCISES to remo).deletedAt)
        val bWorkout = b.detail(sessionId)
        assertEquals(newStart, bWorkout.session.startedAt)
        assertEquals("g2", bWorkout.session.gymId)
        assertEquals(listOf(press, squat), bWorkout.exercises.map { it.id })
        assertEquals(listOf(pressSets[0], pressSets[2]), bWorkout.exercises[0].sets.map { it.id })
        assertEquals(listOf(61.0, 65.0), bWorkout.exercises[0].sets.map { it.weight })
        // orden values are compact and identical on both devices.
        assertEquals(listOf(0, 1), bWorkout.exercises[0].sets.map { it.position })
    }

    @Test
    fun concurrentOfflineEditsOfTheSameSetConvergeToTheLwwWinnerInEitherSyncOrder() = runTest {
        for (aSyncsFirst in listOf(true, false)) {
            for (sameInstant in listOf(false, true)) {
                concurrentEdit(aSyncsFirst, sameInstant)
            }
        }
    }

    private suspend fun concurrentEdit(aSyncsFirst: Boolean, sameInstant: Boolean) {
        val label = "aSyncsFirst=$aSyncsFirst sameInstant=$sameInstant"
        val server = FakeSyncServer(now = { now }, pageSize = 3)
        val suffix = "${if (aSyncsFirst) "a" else "b"}${if (sameInstant) "t" else "d"}"
        val a = device("concurrent-a-$suffix.db", "device-a", server.Link(USER))
        val b = device("concurrent-b-$suffix.db", "device-b", server.Link(USER))
        listOf(a, b).forEach { it.seedCatalog(); it.link.online = true }

        // A logs and finishes a workout; both devices sync to the same state.
        a.addGym("g1")
        val sessionId = a.workouts.startFreeWorkout("g1").sessionId
        val press = a.log.addExercise(sessionId, "seed-press")
        val set = a.log.addSet(press, 60.0, 8, warmup = false)
        now += 30 * MINUTE
        a.finish(sessionId, null)
        success(a.sync.syncOnce(USER))
        success(b.sync.syncOnce(USER))
        assertEquals(label, a.snapshot(), b.snapshot())

        // Both go offline and edit the same set from the history editor.
        a.link.online = false
        b.link.online = false
        now += MINUTE
        val fromA = a.detail(sessionId)
        assertTrue(a.save(fromA, WorkoutDraft.from(fromA).withSet(set) { it.copy(reps = "9") }) is SaveResult.Saved)
        if (!sameInstant) now += MINUTE
        val fromB = b.detail(sessionId)
        assertTrue(b.save(fromB, WorkoutDraft.from(fromB).withSet(set) { it.copy(reps = "11") }) is SaveResult.Saved)
        val aSet = a.snapshot().getValue(SyncTable.LOGGED_SETS to set) as LoggedSetRecord
        val bSet = b.snapshot().getValue(SyncTable.LOGGED_SETS to set) as LoggedSetRecord
        if (sameInstant) assertEquals(label, aSet.updatedAt, bSet.updatedAt) else assertTrue(label, bSet.updatedAt > aSet.updatedAt)
        // Later updatedAt wins; on a tie the greater deviceId ("device-b") does. Either way: B.
        val winner = bSet

        a.link.online = true
        b.link.online = true
        val (first, second) = if (aSyncsFirst) a to b else b to a
        success(first.sync.syncOnce(USER))
        success(second.sync.syncOnce(USER))
        success(first.sync.syncOnce(USER))

        val serverState = server.snapshot()
        assertEquals(label, winner, serverState.getValue(SyncTable.LOGGED_SETS to set))
        assertEquals(label, serverState, a.snapshot())
        assertEquals(label, serverState, b.snapshot())
        assertEquals(label, 11, a.detail(sessionId).exercises.single().sets.single().reps)
        assertEquals(label, 0, a.pendingOutbox() + b.pendingOutbox())
    }

    private companion object {
        const val USER = "user_convergence"
        const val T0 = 1_767_225_600_000L
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE

        val ROOM_TABLES = listOf(
            SyncTable.EXERCISES to "exercises",
            SyncTable.ROUTINES to "routines",
            SyncTable.ROUTINE_EXERCISES to "routine_exercises",
            SyncTable.WORKOUT_SESSIONS to "workout_sessions",
            SyncTable.LOGGED_EXERCISES to "logged_exercises",
            SyncTable.LOGGED_SETS to "logged_sets",
            SyncTable.GYMS to "gyms",
            SyncTable.EXERCISE_PHOTOS to "exercise_photos",
            SyncTable.USER_SETTINGS to "user_settings",
            SyncTable.COACH_MESSAGES to "coach_messages",
            SyncTable.BODY_METRICS to "body_metrics",
            SyncTable.PROGRESS_PHOTOS to "progress_photos",
            SyncTable.MESOCYCLES to "mesocycles",
            SyncTable.ACHIEVEMENTS to "achievements",
        )
    }
}
