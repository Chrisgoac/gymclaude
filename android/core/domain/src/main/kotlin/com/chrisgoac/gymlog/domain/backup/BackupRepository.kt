package com.chrisgoac.gymlog.domain.backup

import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.stats.StatsInvalidation
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.backup.BackupV1
import java.io.InputStream
import java.io.OutputStream

/** How an import treats what is already on the device. */
enum class ImportMode {
    /**
     * Last writer wins per row, and never deletes: a live file row is written, keeping its own
     * `updatedAt`, only when the row is new or the file's copy is newer; file tombstones are ignored
     * and rows that are not in the file stay. Re-importing changes nothing.
     */
    Merge,

    /**
     * Within the tables the file has, the file becomes the account's data: every differing file row
     * is written (freshly stamped, so it wins), every live row missing from the file is deleted
     * (tombstoned, so the deletion syncs). Tables the file lacks are untouched. Only after the user confirmed it.
     */
    ReplaceAfterConfirmation,
}

/** A file that was read and validated completely; nothing has been written yet. */
class PreparedImport(
    val backup: BackupV1,
    /** Catalog exercises the file carried (the PWA exports its catalog); never imported. */
    val skippedCatalogExercises: Int,
    /** The account it was read for; importing into any other one is refused. */
    val accountId: String,
) {
    val rowsByTable: Map<SyncTable, Int> = backup.entities.byTable().associate { (table, rows) -> table to rows.size }
    val totalRows: Int = backup.entities.size
}

data class ImportResult(
    /** Rows written from the file (new or changed). */
    val written: Int,
    /** Live rows the replace tombstoned (missing from the file, or a tombstone in it). */
    val deleted: Int,
    /** File rows left as they were (older than, or equal to, what the device holds). */
    val unchanged: Int,
    val skippedCatalogExercises: Int,
)

data class ExportResult(val rows: Int)

/**
 * Backup export/import of the open account in the PWA's file format ([BackupV1]). Every failure
 * before writing is a [com.chrisgoac.gymlog.model.backup.BackupException].
 */
interface BackupRepository {
    /** Writes the account's synchronized data (live rows and tombstones, no catalog, no server or device data). */
    suspend fun export(sink: OutputStream): ExportResult

    /** Reads and validates the whole file for the open account; the database is not touched. */
    suspend fun prepare(source: InputStream): PreparedImport

    /**
     * Applies [prepared] in ONE transaction (rows + their outbox mutations): it all commits or
     * nothing does. Refused while a workout is being recorded or if the open account is not the one
     * [prepared] was read for. Derived data is recomputed and a sync requested afterwards.
     */
    suspend fun import(prepared: PreparedImport, mode: ImportMode): ImportResult
}

/**
 * What a committed import triggers: every derived scope is stale (stats, Coach, achievements), but as
 * a NON-local edit, so achievement reconciliation only unlocks/retains; and a sync is requested.
 */
class BackupImportEffects(
    private val invalidation: StatsInvalidation,
    private val requestSync: () -> Unit,
) : () -> Unit {
    override fun invoke() {
        invalidation.publish(InvalidationScope.entries.toSet(), localEdit = false)
        requestSync()
    }
}
