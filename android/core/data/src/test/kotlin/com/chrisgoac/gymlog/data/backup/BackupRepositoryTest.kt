package com.chrisgoac.gymlog.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RecordStore
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.backup.ImportMode
import com.chrisgoac.gymlog.domain.backup.PreparedImport
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.MAX_OPERATIONS_PER_MUTATION
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import com.chrisgoac.gymlog.model.backup.BackupCodec
import com.chrisgoac.gymlog.model.backup.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRepositoryTest {
    private var now = 1_800_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = newDb()
    private val other = newDb()
    private var imported = 0

    private fun newDb() = Room.inMemoryDatabaseBuilder(context, GymLogDatabase::class.java).allowMainThreadQueries().build()

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private fun runnerOf(database: GymLogDatabase): LocalMutationRunner = RoomLocalMutationRunner(database, DeviceId("test-device"), clock = { now })

    private fun repository(
        database: GymLogDatabase = db,
        batchSize: Int = 250,
        runnerFor: (GymLogDatabase) -> LocalMutationRunner = ::runnerOf,
    ) = RoomBackupRepository(FakeManager(database), runnerFor, now = { now }, onImported = { imported++ }, io = Dispatchers.Unconfined, batchSize = batchSize)

    @After fun tearDown() {
        db.close()
        other.close()
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("backup/$name")) { "missing fixture $name" }.readBytes()

    private fun outbox(database: GymLogDatabase = db): List<PushMutation> =
        database.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContractJson.decodeFromString(PushMutation.serializer(), cursor.getString(0))) }
        }

    private fun rowCount(database: GymLogDatabase = db): Int = listOf(
        "exercises", "routines", "routine_exercises", "workout_sessions", "logged_exercises", "logged_sets", "gyms",
        "exercise_photos", "user_settings", "coach_messages", "body_metrics", "progress_photos", "mesocycles", "achievements",
    ).sumOf { table -> database.query("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }

    private suspend fun RoomBackupRepository.prepareBytes(bytes: ByteArray): PreparedImport = prepare(ByteArrayInputStream(bytes))

    private suspend fun RoomBackupRepository.exportBytes(): ByteArray = ByteArrayOutputStream().also { export(it) }.toByteArray()

    private suspend fun putGym(id: String, name: String, database: GymLogDatabase = db) {
        runnerOf(database).run(LocalMutation { put(GymRecord(RecordId(id), nextTimestamp(SyncTable.GYMS, RecordId(id)), null, name, 0, false)) })
        now += 1000
    }

    // --- PWA fixtures (ported from lib/repositories/backup.test.ts) ---

    @Test fun `PWA backup imports every table, skips the catalog and refreshes updatedAt so it syncs`() = runTest {
        val repo = repository()
        val prepared = repo.prepareBytes(fixture("pwa-backup-v12.json"))
        assertEquals(1, prepared.skippedCatalogExercises)

        val result = repo.import(prepared, ImportMode.Merge)

        // 16 file rows - 1 catalog = 15; the tombstone ls2 of a set this device never had is skipped.
        assertEquals(14, result.written)
        assertEquals(1, result.unchanged)
        assertEquals(14, rowCount())
        assertEquals(1, imported)
        val store = RecordStore(db)
        assertEquals("Gold's", (store.find(SyncTable.GYMS, RecordId("g1"))!!.record as GymRecord).nombre)
        assertNull(store.find(SyncTable.EXERCISES, RecordId("seed-press-banca")))
        assertNull(store.find(SyncTable.LOGGED_SETS, RecordId("ls2")))
        assertTrue(store.find(SyncTable.ROUTINES, RecordId("r1"))!!.record.updatedAt >= now)
        assertTrue(store.find(SyncTable.ACHIEVEMENTS, RecordId("ab1"))!!.record.updatedAt >= now)
        // Every written row is in the outbox, in mutations the server accepts (<= 50 operations).
        val operations = outbox().flatMap { it.operations }
        assertEquals(14, operations.size)
        assertTrue(outbox().all { it.operations.size <= MAX_OPERATIONS_PER_MUTATION })
    }

    @Test fun `older PWA file with missing tables imports`() = runTest {
        val repo = repository()
        val result = repo.import(repo.prepareBytes(fixture("pwa-backup-v6.json")), ImportMode.Merge)
        assertEquals(2, result.written)
        assertTrue(RecordStore(db).find(SyncTable.ROUTINE_EXERCISES, RecordId("re1"))!!.record.updatedAt >= now)
    }

    @Test fun `foreign userId is stripped and the export has the PWA shape`() = runTest {
        val repo = repository()
        repo.import(repo.prepareBytes(fixture("pwa-backup-v12.json")), ImportMode.Merge)
        val exported = repo.exportBytes()
        val text = exported.decodeToString()
        assertFalse(text.contains("user_foreign"))
        assertFalse(text.contains("pwaOnlyField"))

        val pwa = Json.parseToJsonElement(fixture("pwa-backup-v12.json").decodeToString()).jsonObject
        val android = Json.parseToJsonElement(text).jsonObject
        assertEquals(pwa.keys, android.keys)
        assertEquals("gymlog", android["app"]!!.jsonPrimitive.content)
        assertEquals(BackupCodec.CURRENT_VERSION.toString(), android["version"]!!.jsonPrimitive.content)
        assertEquals(now.toString(), android["exportedAt"]!!.jsonPrimitive.content)
        val pwaData = pwa["data"]!!.jsonObject
        val androidData = android["data"]!!.jsonObject
        assertEquals(pwaData.keys, androidData.keys)
        pwaData.forEach { (table, rows) ->
            val exportedById = androidData[table]!!.jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
            rows.jsonArray.map { it.jsonObject }.forEach { row ->
                val id = row["id"]!!.jsonPrimitive.content
                val out = exportedById[id]?.jsonObject ?: return@forEach // catalog and never-held tombstone
                // Every field the PWA wrote comes back (PWA-only extras aside); userId as null.
                assertTrue("$table/$id keys ${out.keys}", (row.keys - "pwaOnlyField").all { it in out.keys })
                if ("userId" in row) assertEquals(JsonNull, out["userId"]) else assertFalse("userId" in out)
            }
        }
        assertEquals(1, androidData["exercises"]!!.jsonArray.size) // custom only, never the catalog
    }

    @Test fun `export never carries server metadata, outbox or secrets`() = runTest {
        RecordStore(db).put(GymRecord(RecordId("g1"), 10, null, "Box", 0, false), serverVersion = 987654321)
        putGym("g2", "Casa")
        val text = repository().exportBytes().decodeToString()
        listOf("serverVersion", "987654321", "mutationId", "deviceId", "test-device", "baseServerVersion", "token", "payloadJson")
            .forEach { assertFalse("export contains $it", text.contains(it)) }
        assertEquals(2, Json.parseToJsonElement(text).jsonObject["data"]!!.jsonObject["gyms"]!!.jsonArray.size)
    }

    // --- Round trip ---

    @Test fun `Android to Android round trip restores every row`() = runTest {
        putGym("g1", "Gold's")
        runnerOf(db).run(
            LocalMutation {
                put(WorkoutSessionRecord(RecordId("s1"), nextTimestamp(SyncTable.WORKOUT_SESSIONS, RecordId("s1")), null, null, null, "g1", 5, 60, "nota"))
                put(LoggedSetRecord(RecordId("x1"), nextTimestamp(SyncTable.LOGGED_SETS, RecordId("x1")), now, "le1", 0, 50.25, 5, true))
            },
        )
        val bytes = repository().exportBytes()
        val target = repository(other)
        val result = target.import(target.prepareBytes(bytes), ImportMode.Merge)

        assertEquals(2, result.written) // the tombstone x1 never existed on the target
        val source = RecordStore(db)
        val restored = RecordStore(other)
        listOf(SyncTable.GYMS to "g1", SyncTable.WORKOUT_SESSIONS to "s1").forEach { (table, id) ->
            val a = source.find(table, RecordId(id))!!.record
            val b = restored.find(table, RecordId(id))!!.record
            assertEquals(a.toString().replaceStamps(), b.toString().replaceStamps())
        }
    }

    // --- Refused files: typed error, nothing written ---

    @Test fun `corrupt, foreign, newer and invalid files are refused before any write`() = runTest {
        putGym("g0", "Existing")
        val before = rowCount() to outbox().size
        val repo = repository()
        suspend fun expect(bytes: String, check: (BackupException) -> Boolean) {
            try {
                repo.import(repo.prepareBytes(bytes.encodeToByteArray()), ImportMode.ReplaceAfterConfirmation)
                fail("accepted: $bytes")
            } catch (e: BackupException) {
                assertTrue("${e::class.simpleName}: ${e.message}", check(e))
            }
        }
        expect("{ not json") { it is BackupException.Corrupt }
        expect("""{"app":"otra-cosa","version":12,"exportedAt":1,"data":{}}""") { it is BackupException.NotABackup }
        expect("""{"app":"gymlog","version":12,"exportedAt":1}""") { it is BackupException.NotABackup }
        expect("[]") { it is BackupException.NotABackup }
        expect("""{"app":"gymlog","version":13,"exportedAt":1,"data":{}}""") { it is BackupException.UnsupportedVersion }
        expect("""{"app":"gymlog","version":"12","exportedAt":1,"data":{}}""") { it is BackupException.UnsupportedVersion }
        expect(withGyms("""{"id":"g1","nombre":"x","orden":"cero","archivada":false,"updatedAt":1,"deletedAt":null}""")) { it is BackupException.Corrupt }
        expect(withGyms("""{"id":"g1","nombre":"x","orden":3000000000,"archivada":false,"updatedAt":1,"deletedAt":null}""")) { it is BackupException.Corrupt }
        expect(withGyms("""{"id":"bad id","nombre":"x","orden":0,"archivada":false,"updatedAt":1,"deletedAt":null}""")) { it is BackupException.InvalidRecords }
        expect(withGyms("""{"id":"g1","nombre":"x\u0000","orden":0,"archivada":false,"updatedAt":1,"deletedAt":null}""")) { it is BackupException.InvalidRecords }
        expect(withGyms("""{"id":"g1","nombre":"x","orden":0,"archivada":false,"updatedAt":9007199254740992,"deletedAt":null}""")) { it is BackupException.InvalidRecords }
        expect(withGyms("""{"id":"g1","nombre":"a","orden":0,"archivada":false,"updatedAt":1,"deletedAt":null},{"id":"g1","nombre":"b","orden":0,"archivada":false,"updatedAt":1,"deletedAt":null}""")) {
            it is BackupException.InvalidRecords && it.problems.any { p -> "more than once" in p }
        }
        val infinite = """{"app":"gymlog","version":12,"exportedAt":1,"data":{"loggedSets":[{"id":"s","loggedExerciseId":"le","orden":0,"peso":1e400,"reps":1,"updatedAt":1,"deletedAt":null}]}}"""
        expect(infinite) { it is BackupException.InvalidRecords || it is BackupException.Corrupt }
        assertEquals(before, rowCount() to outbox().size)
        assertEquals(0, imported)
    }

    @Test fun `a file over the size cap is refused while streaming`() = runTest {
        val repo = repository()
        val endless = object : InputStream() {
            private var first = true
            override fun read(): Int = if (first) { first = false; '['.code } else ' '.code
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                var start = off
                if (first) { b[off] = '['.code.toByte(); first = false; start++ }
                b.fill(' '.code.toByte(), start, off + len)
                return len
            }
        }
        try {
            repo.prepare(endless)
            fail("accepted an endless file")
        } catch (e: BackupException.TooLarge) {
            assertEquals(BackupCodec.MAX_BYTES, e.maxBytes)
        }
        assertEquals(0, rowCount())
    }

    // --- Modes and idempotency ---

    @Test fun `merge twice adds no outbox mutation the second time`() = runTest {
        val repo = repository()
        val prepared = repo.prepareBytes(fixture("pwa-backup-v12.json"))
        repo.import(prepared, ImportMode.Merge)
        val outboxAfterFirst = outbox().size
        now += 60_000
        val second = repo.import(repo.prepareBytes(fixture("pwa-backup-v12.json")), ImportMode.Merge)
        assertEquals(0, second.written)
        assertEquals(outboxAfterFirst, outbox().size)
    }

    @Test fun `merge keeps newer local rows and takes newer file rows`() = runTest {
        putGym("g1", "Local nuevo") // stamped 1_800_000_000_000 > file 1_700_000_000_000
        putGym("r-old", "x")
        val repo = repository()
        repo.import(repo.prepareBytes(fixture("pwa-backup-v12.json")), ImportMode.Merge)
        assertEquals("Local nuevo", (RecordStore(db).find(SyncTable.GYMS, RecordId("g1"))!!.record as GymRecord).nombre)

        // A file row newer than the stored one wins.
        val newer = withGyms("""{"id":"g1","nombre":"Del fichero","orden":0,"archivada":false,"updatedAt":${now + 10_000},"deletedAt":null}""")
        repo.import(repo.prepareBytes(newer.encodeToByteArray()), ImportMode.Merge)
        assertEquals("Del fichero", (RecordStore(db).find(SyncTable.GYMS, RecordId("g1"))!!.record as GymRecord).nombre)
        assertNull(RecordStore(db).find(SyncTable.GYMS, RecordId("r-old"))!!.record.deletedAt) // merge never deletes
    }

    @Test fun `replace writes the file and tombstones the rest, idempotently`() = runTest {
        putGym("g-local", "Solo aquí")
        putGym("g1", "Local nuevo")
        val repo = repository()
        val result = repo.import(repo.prepareBytes(fixture("pwa-backup-v12.json")), ImportMode.ReplaceAfterConfirmation)
        assertEquals(1, result.deleted)
        val store = RecordStore(db)
        assertTrue(store.find(SyncTable.GYMS, RecordId("g-local"))!!.record.deletedAt != null)
        assertEquals("Gold's", (store.find(SyncTable.GYMS, RecordId("g1"))!!.record as GymRecord).nombre) // file wins even if older
        val outboxAfter = outbox().size

        now += 60_000
        val again = repo.import(repo.prepareBytes(fixture("pwa-backup-v12.json")), ImportMode.ReplaceAfterConfirmation)
        assertEquals(0, again.written)
        assertEquals(0, again.deleted)
        assertEquals(outboxAfter, outbox().size)
    }

    @Test fun `a big import is one transaction of bounded mutations`() = runTest {
        val sets = (0 until 260).joinToString(",") { i ->
            """{"id":"ls$i","loggedExerciseId":"le1","orden":$i,"peso":20,"reps":10,"updatedAt":1,"deletedAt":null}"""
        }
        val file = """{"app":"gymlog","version":12,"exportedAt":1,"data":{"loggedSets":[$sets]}}"""
        val repo = repository(batchSize = 100)
        repo.import(repo.prepareBytes(file.encodeToByteArray()), ImportMode.Merge)
        assertEquals(260, rowCount())
        assertEquals(260, outbox().sumOf { it.operations.size })
        assertTrue(outbox().all { it.operations.size <= MAX_OPERATIONS_PER_MUTATION })
    }

    @Test fun `a failure midway rolls back every row and outbox mutation`() = runTest {
        putGym("g0", "Existing")
        val before = rowCount() to outbox().size
        var runs = 0
        val failing: (GymLogDatabase) -> LocalMutationRunner = { database ->
            val real = runnerOf(database)
            object : LocalMutationRunner {
                override suspend fun <T> run(mutation: LocalMutation<T>): T {
                    val result = real.run(mutation)
                    if (++runs == 3) error("disk full")
                    return result
                }
            }
        }
        val repo = repository(batchSize = 2, runnerFor = failing)
        val prepared = repo.prepareBytes(fixture("pwa-backup-v12.json"))
        try {
            repo.import(prepared, ImportMode.ReplaceAfterConfirmation)
            fail("import should have failed")
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }
        assertEquals(3, runs)
        assertEquals(before, rowCount() to outbox().size)
        assertEquals("Existing", (RecordStore(db).find(SyncTable.GYMS, RecordId("g0"))!!.record as GymRecord).nombre)
        assertNull(RecordStore(db).find(SyncTable.GYMS, RecordId("g0"))!!.record.deletedAt)
        assertEquals(0, imported)
    }

    private fun withGyms(rows: String) = """{"app":"gymlog","version":12,"exportedAt":1,"data":{"gyms":[$rows]}}"""

    private fun String.replaceStamps() = replace(Regex("(updatedAt|deletedAt)=\\d+"), "$1=0")
}
