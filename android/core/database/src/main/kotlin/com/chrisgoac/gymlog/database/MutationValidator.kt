package com.chrisgoac.gymlog.database

import com.chrisgoac.gymlog.model.AchievementRecord
import com.chrisgoac.gymlog.model.BodyMetricRecord
import com.chrisgoac.gymlog.model.CoachMessageRecord
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.ExercisePhotoRecord
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedExerciseRecord
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.MAX_OPERATIONS_PER_MUTATION
import com.chrisgoac.gymlog.model.MesocycleRecord
import com.chrisgoac.gymlog.model.ProgressPhotoRecord
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RoutineExerciseRecord
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.SyncRecord
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingRecord
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A local mutation the server would reject with a schema error; nothing was written. */
class InvalidLocalMutationException(val problems: List<String>) :
    IllegalArgumentException("Invalid local mutation: ${problems.joinToString("; ")}")

/**
 * Client-side copy of the push rules the server enforces (contracts/v1/README.md, Data model and
 * Limits). The contract asks clients to validate before enqueueing, so a schema error is a client
 * bug caught at write time rather than a poison mutation that must be quarantined later.
 * Int columns are int32 by construction (Kotlin `Int`).
 */
object MutationValidator {
    private val RECORD_ID = Regex("^[A-Za-z0-9._:-]{1,128}$")

    // Zod 4 `z.uuid()`: RFC 9562 version 1-8 and variant 10xx, plus the nil and max UUIDs.
    private val UUID = Regex(
        "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}" +
            "|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$",
    )

    /** JavaScript Number.MAX_SAFE_INTEGER: the upper bound of every wire timestamp. */
    const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L

    private const val CATALOG_ID_PREFIX = "seed-"

    fun problems(mutation: PushMutation): List<String> = buildList {
        if (!UUID.matches(mutation.mutationId.value)) add("mutationId is not a UUID")
        if (mutation.deviceId.value.length !in 1..128) add("deviceId must be 1..128 chars")
        val operations = mutation.operations
        if (operations.size !in 1..MAX_OPERATIONS_PER_MUTATION) {
            add("a mutation needs 1..$MAX_OPERATIONS_PER_MUTATION operations, got ${operations.size}")
        }
        var encodable = true // non-finite doubles cannot be JSON-encoded for the NUL scan
        val seen = HashSet<Pair<SyncTable, String>>()
        operations.forEachIndexed { index, operation ->
            val record = operation.record
            val where = "operations[$index] ${operation.table.wire}/${record.id.value}"
            if (!seen.add(operation.table to record.id.value)) add("$where: duplicate (table, id)")
            if (!RECORD_ID.matches(record.id.value)) add("$where: id must match ^[A-Za-z0-9._:-]{1,128}$")
            if (operation.table == SyncTable.EXERCISES && record.id.value.startsWith(CATALOG_ID_PREFIX)) {
                add("$where: catalog exercises are never pushed")
            }
            timestamps(record).forEach { (name, value) ->
                if (value != null && value !in 0..MAX_SAFE_INTEGER) add("$where: $name out of 0..2^53-1")
            }
            decimals(record).forEach { (name, value) ->
                if (value != null && !value.isFinite()) {
                    add("$where: $name is not finite")
                    encodable = false
                }
            }
            val base = operation.baseServerVersion
            if (base != null && base < 0) add("$where: baseServerVersion is negative")
        }
        // Every string value and object key, record fields and JSON values alike (and deviceId).
        if (encodable) {
            val json = ContractJson.encodeToJsonElement(PushMutation.serializer(), mutation)
            if (containsNul(json)) add("a string or JSON key contains U+0000")
        }
    }

    fun requireValid(mutation: PushMutation) {
        val found = problems(mutation)
        if (found.isNotEmpty()) throw InvalidLocalMutationException(found)
    }

    private fun containsNul(element: JsonElement): Boolean = when (element) {
        is JsonNull -> false
        is JsonPrimitive -> element.isString && '\u0000' in element.content
        is JsonArray -> element.any(::containsNul)
        is JsonObject -> element.entries.any { (key, value) -> '\u0000' in key || containsNul(value) }
    }

    private fun timestamps(record: SyncRecord): List<Pair<String, Long?>> {
        val common = listOf("updatedAt" to record.updatedAt, "deletedAt" to record.deletedAt)
        return common + when (record) {
            is WorkoutSessionRecord -> listOf("fecha" to record.fecha)
            is BodyMetricRecord -> listOf("fecha" to record.fecha)
            is ProgressPhotoRecord -> listOf("fecha" to record.fecha)
            is CoachMessageRecord -> listOf("createdAt" to record.createdAt)
            is MesocycleRecord -> listOf("fechaInicio" to record.fechaInicio)
            is AchievementRecord -> listOf("fechaDesbloqueo" to record.fechaDesbloqueo)
            is ExerciseRecord, is RoutineRecord, is RoutineExerciseRecord, is LoggedExerciseRecord,
            is LoggedSetRecord, is GymRecord, is ExercisePhotoRecord, is UserSettingRecord -> emptyList()
        }
    }

    private fun decimals(record: SyncRecord): List<Pair<String, Double?>> = when (record) {
        is ExerciseRecord -> listOf("incrementoKg" to record.incrementoKg)
        is LoggedSetRecord -> listOf("peso" to record.peso)
        is BodyMetricRecord -> listOf("valor" to record.valor)
        else -> emptyList()
    }
}
