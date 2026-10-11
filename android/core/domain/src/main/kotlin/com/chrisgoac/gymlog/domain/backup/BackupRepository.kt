package com.chrisgoac.gymlog.domain.backup

import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.backup.BackupV1
import java.io.InputStream
import java.io.OutputStream

/** How an import treats what is already on the device. */
enum class ImportMode {
    /**
     * Last writer wins per row: a file row replaces the stored one only when its `updatedAt` is
     * newer (or the row is new); rows that are not in the file stay. Re-importing changes nothing.
     */
    Merge,

    /**
     * The file becomes the account's data: every file row is written, every live row missing from
     * the file is deleted (tombstoned, so the deletion syncs). Only after the user confirmed it.
     */
    ReplaceAfterConfirmation,
}

/** A file that was read and validated completely; nothing has been written yet. */
class PreparedImport(
    val backup: BackupV1,
    /** Catalog exercises the file carried (the PWA exports its catalog); never imported. */
    val skippedCatalogExercises: Int,
) {
    val rowsByTable: Map<SyncTable, Int> = backup.entities.byTable().associate { (table, rows) -> table to rows.size }
    val totalRows: Int = backup.entities.size
}

data class ImportResult(
    /** Rows written from the file (new or changed). */
    val written: Int,
    /** Live rows the replace deleted because the file does not have them. */
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

    /** Reads and validates the whole file; the database is not touched. */
    suspend fun prepare(source: InputStream): PreparedImport

    /**
     * Applies [prepared] in ONE transaction (rows + their outbox mutations): it all commits or
     * nothing does. Derived data is recomputed and a sync requested afterwards.
     */
    suspend fun import(prepared: PreparedImport, mode: ImportMode): ImportResult
}
