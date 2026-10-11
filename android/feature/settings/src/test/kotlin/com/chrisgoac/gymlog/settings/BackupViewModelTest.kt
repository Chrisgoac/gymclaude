package com.chrisgoac.gymlog.settings

import com.chrisgoac.gymlog.domain.backup.BackupRepository
import com.chrisgoac.gymlog.domain.backup.ExportResult
import com.chrisgoac.gymlog.domain.backup.ImportMode
import com.chrisgoac.gymlog.domain.backup.ImportResult
import com.chrisgoac.gymlog.domain.backup.PreparedImport
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import com.chrisgoac.gymlog.model.backup.BackupEntities
import com.chrisgoac.gymlog.model.backup.BackupException
import com.chrisgoac.gymlog.model.backup.BackupV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId

private class FakeBackups : BackupRepository {
    var prepareError: Exception? = null
    val imports = mutableListOf<ImportMode>()
    override suspend fun export(sink: OutputStream): ExportResult = ExportResult(7).also { sink.write(1) }
    override suspend fun prepare(source: InputStream): PreparedImport {
        prepareError?.let { throw it }
        val sessions = listOf(
            WorkoutSessionRecord(RecordId("s1"), 1, null, fecha = 1),
            WorkoutSessionRecord(RecordId("s2"), 1, 2, fecha = 1),
        )
        return PreparedImport(BackupV1(12, 99, BackupEntities(workoutSessions = sessions)), skippedCatalogExercises = 3, accountId = "u1")
    }
    override suspend fun import(prepared: PreparedImport, mode: ImportMode): ImportResult {
        imports += mode
        return ImportResult(written = 2, deleted = if (mode == ImportMode.Merge) 0 else 4, unchanged = 0, skippedCatalogExercises = 3)
    }
}

private class FakeDocuments(private val failWrite: Boolean = false) : BackupDocuments {
    val deleted = mutableListOf<String>()
    override fun delete(uri: String) { deleted += uri }
    override fun openForWrite(uri: String): OutputStream = if (failWrite) throw IOException("denied") else ByteArrayOutputStream()
    override fun openForRead(uri: String): InputStream = ByteArrayInputStream(ByteArray(0))
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val backups = FakeBackups()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(documents: BackupDocuments = FakeDocuments()) =
        BackupViewModel(backups, documents, now = { 1_788_000_000_000L }, zone = { ZoneId.of("Europe/Madrid") }, io = dispatcher)

    @Test fun `file name follows the PWA in the local date`() {
        assertEquals("gymlog-backup-2026-08-29.json", vm().suggestedFileName())
    }

    @Test fun `export reports the row count, a cancelled picker does nothing, a write failure is typed`() {
        val model = vm()
        model.exportTo(null)
        assertEquals(BackupPhase.Idle, model.phase.value)
        model.exportTo("content://doc")
        assertEquals(BackupPhase.Exported(7), model.phase.value)
        val documents = FakeDocuments(failWrite = true)
        val failing = vm(documents)
        failing.exportTo("content://doc")
        assertEquals(BackupPhase.Failed(BackupFailure.WriteFailed), failing.phase.value)
        assertEquals(listOf("content://doc"), documents.deleted) // no partial file left behind
    }

    @Test fun `merge imports straight after the preview`() {
        val model = vm()
        model.importFrom("content://doc")
        val ready = model.phase.value as BackupPhase.Ready
        assertEquals(BackupPreview(totalRows = 2, workouts = 1, skippedCatalogExercises = 3, exportedAt = 99), ready.preview)
        assertEquals(ImportMode.Merge, ready.mode)
        assertTrue(backups.imports.isEmpty()) // nothing written before the user chooses
        model.startImport()
        assertEquals(listOf(ImportMode.Merge), backups.imports)
        assertTrue(model.phase.value is BackupPhase.Imported)
    }

    @Test fun `replace needs an explicit confirmation and can be backed out of`() {
        val model = vm()
        model.importFrom("content://doc")
        model.selectMode(ImportMode.ReplaceAfterConfirmation)
        model.startImport()
        assertTrue(model.phase.value is BackupPhase.ConfirmReplace)
        assertTrue(backups.imports.isEmpty())
        model.cancel()
        assertEquals(ImportMode.ReplaceAfterConfirmation, (model.phase.value as BackupPhase.Ready).mode)
        model.startImport()
        model.confirmReplace()
        assertEquals(listOf(ImportMode.ReplaceAfterConfirmation), backups.imports)
        assertEquals(4, (model.phase.value as BackupPhase.Imported).result.deleted)
    }

    @Test fun `refused files map to typed failures`() {
        val cases = mapOf(
            BackupException.NotABackup() to BackupFailure.NotABackup,
            BackupException.UnsupportedVersion("13") to BackupFailure.NewerVersion,
            BackupException.TooLarge(1) to BackupFailure.TooLarge,
            BackupException.Corrupt("x") to BackupFailure.Corrupt,
            BackupException.InvalidRecords(listOf("x")) to BackupFailure.InvalidRecords,
            BackupException.FutureTimestamps(3) to BackupFailure.FutureDates,
            BackupException.AccountChanged() to BackupFailure.AccountChanged,
            BackupException.ActiveWorkout() to BackupFailure.ActiveWorkout,
            IOException("gone") to BackupFailure.ReadFailed,
        )
        cases.forEach { (error, failure) ->
            backups.prepareError = error
            val model = vm()
            model.importFrom("content://doc")
            assertEquals(BackupPhase.Failed(failure), model.phase.value)
        }
        assertTrue(backups.imports.isEmpty())
    }
}
