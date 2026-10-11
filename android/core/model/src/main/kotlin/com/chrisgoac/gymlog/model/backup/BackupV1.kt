package com.chrisgoac.gymlog.model.backup

import com.chrisgoac.gymlog.model.AchievementRecord
import com.chrisgoac.gymlog.model.BodyMetricRecord
import com.chrisgoac.gymlog.model.CoachMessageRecord
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.ExercisePhotoRecord
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedExerciseRecord
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.MesocycleRecord
import com.chrisgoac.gymlog.model.ProgressPhotoRecord
import com.chrisgoac.gymlog.model.RoutineExerciseRecord
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.SyncRecord
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingRecord
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.longOrNull
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// The PWA's backup file (lib/repositories/backup.ts `BackupFile`), so a file exported on either
// platform imports on the other:
//   { "app": "gymlog", "version": 12, "exportedAt": <epoch ms>, "data": { "<table>": [record…] × 14 } }
// Records are the sync wire records. The PWA stores `userId` on most rows (null until first push);
// export writes `"userId": null` there and import ignores whatever userId a file carries: the rows
// always belong to the account whose database is open. Nothing else leaves the device: no outbox,
// no serverVersion, no device settings, no tokens. Catalog exercises (the PWA's `seed-…` rows, which
// its export includes) are never imported: they are APK assets on Android.

/**
 * A decoded backup. [schemaVersion] is the file's `version`; [entities] its `data`. [presentTables] are
 * the tables whose key the file's `data` actually has: an older file lacks some, and a missing table
 * means "not in this backup", never "empty" (a replace leaves those tables alone).
 */
data class BackupV1(
    val schemaVersion: Int,
    val exportedAt: Long,
    val entities: BackupEntities,
    val presentTables: Set<SyncTable> = BackupCodec.TABLE_ORDER.toSet(),
)

/** The 14 synchronized tables of a backup, in the PWA's `data` key order. Missing tables (older files) are empty. */
data class BackupEntities(
    val gyms: List<GymRecord> = emptyList(),
    val exercises: List<ExerciseRecord> = emptyList(),
    val routines: List<RoutineRecord> = emptyList(),
    val routineExercises: List<RoutineExerciseRecord> = emptyList(),
    val workoutSessions: List<WorkoutSessionRecord> = emptyList(),
    val loggedExercises: List<LoggedExerciseRecord> = emptyList(),
    val loggedSets: List<LoggedSetRecord> = emptyList(),
    val exercisePhotos: List<ExercisePhotoRecord> = emptyList(),
    val userSettings: List<UserSettingRecord> = emptyList(),
    val coachMessages: List<CoachMessageRecord> = emptyList(),
    val bodyMetrics: List<BodyMetricRecord> = emptyList(),
    val progressPhotos: List<ProgressPhotoRecord> = emptyList(),
    val mesocycles: List<MesocycleRecord> = emptyList(),
    val achievements: List<AchievementRecord> = emptyList(),
) {
    /** Every table with its rows, in [BackupCodec.TABLE_ORDER]. */
    fun byTable(): List<Pair<SyncTable, List<SyncRecord>>> = BackupCodec.TABLE_ORDER.map { it to rows(it) }

    fun rows(table: SyncTable): List<SyncRecord> = when (table) {
        SyncTable.GYMS -> gyms
        SyncTable.EXERCISES -> exercises
        SyncTable.ROUTINES -> routines
        SyncTable.ROUTINE_EXERCISES -> routineExercises
        SyncTable.WORKOUT_SESSIONS -> workoutSessions
        SyncTable.LOGGED_EXERCISES -> loggedExercises
        SyncTable.LOGGED_SETS -> loggedSets
        SyncTable.EXERCISE_PHOTOS -> exercisePhotos
        SyncTable.USER_SETTINGS -> userSettings
        SyncTable.COACH_MESSAGES -> coachMessages
        SyncTable.BODY_METRICS -> bodyMetrics
        SyncTable.PROGRESS_PHOTOS -> progressPhotos
        SyncTable.MESOCYCLES -> mesocycles
        SyncTable.ACHIEVEMENTS -> achievements
    }

    val size: Int get() = BackupCodec.TABLE_ORDER.sumOf { rows(it).size }

    companion object {
        /** Builds the entities from rows of any table (each row lands in its own table's list). */
        fun of(rows: Map<SyncTable, List<SyncRecord>>): BackupEntities {
            fun <R : SyncRecord> list(table: SyncTable): List<R> {
                @Suppress("UNCHECKED_CAST")
                return rows[table].orEmpty() as List<R>
            }
            return BackupEntities(
                gyms = list(SyncTable.GYMS),
                exercises = list(SyncTable.EXERCISES),
                routines = list(SyncTable.ROUTINES),
                routineExercises = list(SyncTable.ROUTINE_EXERCISES),
                workoutSessions = list(SyncTable.WORKOUT_SESSIONS),
                loggedExercises = list(SyncTable.LOGGED_EXERCISES),
                loggedSets = list(SyncTable.LOGGED_SETS),
                exercisePhotos = list(SyncTable.EXERCISE_PHOTOS),
                userSettings = list(SyncTable.USER_SETTINGS),
                coachMessages = list(SyncTable.COACH_MESSAGES),
                bodyMetrics = list(SyncTable.BODY_METRICS),
                progressPhotos = list(SyncTable.PROGRESS_PHOTOS),
                mesocycles = list(SyncTable.MESOCYCLES),
                achievements = list(SyncTable.ACHIEVEMENTS),
            )
        }
    }
}

/** Why a file cannot be imported. Thrown before anything is written. */
sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Not a GymLog backup (`app` is not "gymlog", or there is no `data` object). */
    class NotABackup : BackupException("Not a GymLog backup")

    /** Written by a newer app ([version] > [BackupCodec.CURRENT_VERSION]) or with a nonsense version. */
    class UnsupportedVersion(val version: String) : BackupException("Unsupported backup version $version")

    /** Bigger than [maxBytes]; reading stopped there. */
    class TooLarge(val maxBytes: Long) : BackupException("Backup larger than $maxBytes bytes")

    /** Not valid JSON, or a record that does not decode as its table's wire record. */
    class Corrupt(detail: String, cause: Throwable? = null) : BackupException("Corrupt backup: $detail", cause)

    /** Records the server would reject (id format, U+0000, non-finite numbers, timestamps, duplicates). */
    class InvalidRecords(val problems: List<String>) : BackupException("Invalid backup records: ${problems.take(5).joinToString("; ")}")

    /**
     * [count] records carry an `updatedAt`/`deletedAt` later than the (server-corrected) clock plus the
     * allowed skew: imported, they would beat every future edit under last-writer-wins.
     */
    class FutureTimestamps(val count: Int) : BackupException("$count backup records are dated in the future")

    /** No account database is open. */
    class NoAccount : BackupException("No account is open")

    /** The file was read for another account (or database) than the one open now. */
    class AccountChanged : BackupException("The open account changed since the file was read")

    /** A workout is being recorded; importing now could replace or tombstone it under the user's hands. */
    class ActiveWorkout : BackupException("Finish or discard the active workout before importing")
}

/** A decoded file plus how many catalog exercises it carried (dropped, never imported). */
data class DecodedBackup(val backup: BackupV1, val skippedCatalogExercises: Int)

/** Reads and writes [BackupV1] in the PWA's JSON format, straight from/to streams. */
@OptIn(ExperimentalSerializationApi::class)
object BackupCodec {
    const val APP = "gymlog"

    /** The PWA's current `BackupFile.version`. Older versions (fewer tables) import; newer ones are refused. */
    const val CURRENT_VERSION = 12

    /** Files above this are refused while reading (a real history is a few MB). */
    const val MAX_BYTES: Long = 20L * 1024 * 1024

    private const val CATALOG_ID_PREFIX = "seed-"

    /** The PWA's `data` key order. */
    val TABLE_ORDER: List<SyncTable> = listOf(
        SyncTable.GYMS, SyncTable.EXERCISES, SyncTable.ROUTINES, SyncTable.ROUTINE_EXERCISES,
        SyncTable.WORKOUT_SESSIONS, SyncTable.LOGGED_EXERCISES, SyncTable.LOGGED_SETS, SyncTable.EXERCISE_PHOTOS,
        SyncTable.USER_SETTINGS, SyncTable.COACH_MESSAGES, SyncTable.BODY_METRICS, SyncTable.PROGRESS_PHOTOS,
        SyncTable.MESOCYCLES, SyncTable.ACHIEVEMENTS,
    )

    /** PWA tables whose rows carry `userId` (lib/db/types.ts); the child tables do not. */
    val TABLES_WITH_USER_ID: Set<SyncTable> =
        TABLE_ORDER.toSet() - setOf(SyncTable.ROUTINE_EXERCISES, SyncTable.LOGGED_EXERCISES, SyncTable.LOGGED_SETS)

    /** Fields inside records the app does not know (userId, PWA-only extras) are ignored. */
    private val readJson: Json = Json(from = ContractJson) { ignoreUnknownKeys = true }

    fun write(backup: BackupV1, sink: OutputStream) {
        val data = JsonObject(
            backup.entities.byTable().associate { (table, rows) ->
                table.wire to JsonArray(rows.map { row -> encodeRow(table, row) })
            },
        )
        val file = JsonObject(
            linkedMapOf(
                "app" to JsonPrimitive(APP),
                "version" to JsonPrimitive(backup.schemaVersion),
                "exportedAt" to JsonPrimitive(backup.exportedAt),
                "data" to data,
            ),
        )
        ContractJson.encodeToStream(JsonElement.serializer(), file, sink)
        sink.flush()
    }

    /**
     * Parses [source] (stops past [maxBytes]) and checks the header before decoding any record, so
     * a newer format is reported as such rather than as a decoding error. Catalog exercises are
     * dropped here. Record-level rules (ids, NUL, finite numbers…) are checked by the importer.
     */
    fun read(source: InputStream, maxBytes: Long = MAX_BYTES): DecodedBackup {
        val tree = try {
            readJson.decodeFromStream(JsonElement.serializer(), LimitedInputStream(source, maxBytes))
        } catch (e: Exception) {
            if (e.causes().any { it is LimitExceeded }) throw BackupException.TooLarge(maxBytes)
            throw BackupException.Corrupt("not valid JSON", e)
        }
        val root = tree as? JsonObject ?: throw BackupException.NotABackup()
        if ((root["app"] as? JsonPrimitive)?.takeIf { it.isString }?.content != APP) throw BackupException.NotABackup()
        val data = root["data"] as? JsonObject ?: throw BackupException.NotABackup()
        val versionElement = root["version"] as? JsonPrimitive
        val version = versionElement?.takeIf { !it.isString }?.longOrNull
        if (version == null || version !in 1..CURRENT_VERSION.toLong()) {
            throw BackupException.UnsupportedVersion(versionElement?.contentOrNull ?: "missing")
        }
        val exportedAt = (root["exportedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: throw BackupException.Corrupt("exportedAt is not an integer")

        var skippedCatalog = 0
        val present = TABLE_ORDER.filterTo(LinkedHashSet()) { table -> data[table.wire].let { it != null && it !is JsonNull } }
        val rows = TABLE_ORDER.associateWith { table ->
            val element = data[table.wire] ?: JsonNull
            if (element is JsonNull) return@associateWith emptyList()
            val array = element as? JsonArray ?: throw BackupException.Corrupt("data.${table.wire} is not an array")
            val kept = if (table == SyncTable.EXERCISES) {
                array.filter { row -> !isCatalog(row) }.also { skippedCatalog = array.size - it.size }
            } else {
                array
            }
            try {
                readJson.decodeFromJsonElement(ListSerializer(serializerFor(table)), JsonArray(kept))
            } catch (e: SerializationException) {
                throw BackupException.Corrupt("data.${table.wire}: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw BackupException.Corrupt("data.${table.wire}: ${e.message}", e)
            }
        }
        return DecodedBackup(BackupV1(version.toInt(), exportedAt, BackupEntities.of(rows), present), skippedCatalog)
    }

    /** The PWA keeps the catalog in `exercises` (`seed-…` ids, esPersonalizado false) and exports it. */
    private fun isCatalog(row: JsonElement): Boolean {
        val obj = row as? JsonObject ?: return false // a malformed row fails typed decoding instead
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
        val custom = (obj["esPersonalizado"] as? JsonPrimitive)?.booleanOrNull
        return id?.startsWith(CATALOG_ID_PREFIX) == true || custom == false
    }

    private fun encodeRow(table: SyncTable, row: SyncRecord): JsonObject {
        @Suppress("UNCHECKED_CAST")
        val encoded = ContractJson.encodeToJsonElement(serializerFor(table) as KSerializer<SyncRecord>, row) as JsonObject
        return if (table in TABLES_WITH_USER_ID) JsonObject(encoded + ("userId" to JsonNull)) else encoded
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(table: SyncTable): KSerializer<SyncRecord> = when (table) {
        SyncTable.GYMS -> GymRecord.serializer()
        SyncTable.EXERCISES -> ExerciseRecord.serializer()
        SyncTable.ROUTINES -> RoutineRecord.serializer()
        SyncTable.ROUTINE_EXERCISES -> RoutineExerciseRecord.serializer()
        SyncTable.WORKOUT_SESSIONS -> WorkoutSessionRecord.serializer()
        SyncTable.LOGGED_EXERCISES -> LoggedExerciseRecord.serializer()
        SyncTable.LOGGED_SETS -> LoggedSetRecord.serializer()
        SyncTable.EXERCISE_PHOTOS -> ExercisePhotoRecord.serializer()
        SyncTable.USER_SETTINGS -> UserSettingRecord.serializer()
        SyncTable.COACH_MESSAGES -> CoachMessageRecord.serializer()
        SyncTable.BODY_METRICS -> BodyMetricRecord.serializer()
        SyncTable.PROGRESS_PHOTOS -> ProgressPhotoRecord.serializer()
        SyncTable.MESOCYCLES -> MesocycleRecord.serializer()
        SyncTable.ACHIEVEMENTS -> AchievementRecord.serializer()
    } as KSerializer<SyncRecord>

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }

    private class LimitExceeded : IOException("backup size limit exceeded")

    /** Fails with [LimitExceeded] as soon as more than [limit] bytes have been read. */
    private class LimitedInputStream(source: InputStream, private val limit: Long) : FilterInputStream(source) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count(n.toLong())
            return n
        }

        override fun skip(n: Long): Long = super.skip(n).also { count(it) }

        private fun count(n: Long) {
            count += n
            if (count > limit) throw LimitExceeded()
        }
    }
}
