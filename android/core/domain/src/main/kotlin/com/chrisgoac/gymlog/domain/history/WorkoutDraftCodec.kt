package com.chrisgoac.gymlog.domain.history

import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.domain.workout.WorkoutSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Text form of an editor's draft and of the original it was opened from, for saved state (so both
 * survive process death: the original must not be re-read, or a newer synced version would hide a
 * conflict). Decoding returns null for anything unreadable.
 */
object WorkoutDraftCodec {
    fun encode(draft: WorkoutDraft): String = buildJsonObject {
        put("sessionId", draft.sessionId)
        put("startedAt", draft.startedAt.toEpochMilli())
        put("gymId", draft.gymId)
        put("notes", draft.notes)
        put(
            "exercises",
            JsonArray(
                draft.exercises.map { exercise ->
                    buildJsonObject {
                        put("id", exercise.id)
                        put("exerciseId", exercise.exerciseId)
                        put("exerciseName", exercise.exerciseName)
                        put(
                            "sets",
                            JsonArray(
                                exercise.sets.map { set ->
                                    buildJsonObject {
                                        put("id", set.id)
                                        put("weight", set.weight)
                                        put("reps", set.reps)
                                        put("warmup", set.warmup)
                                    }
                                },
                            ),
                        )
                    }
                },
            ),
        )
    }.toString()

    fun decodeDraft(text: String?): WorkoutDraft? = decode(text) { root ->
        WorkoutDraft(
            sessionId = root.string("sessionId")!!,
            startedAt = Instant.ofEpochMilli(root.getValue("startedAt").jsonPrimitive.long),
            gymId = root.string("gymId"),
            notes = root.string("notes").orEmpty(),
            exercises = root.getValue("exercises").jsonArray.map { element ->
                val exercise = element.jsonObject
                LoggedExerciseDraft(
                    id = exercise.string("id")!!,
                    exerciseId = exercise.string("exerciseId")!!,
                    exerciseName = exercise.string("exerciseName"),
                    sets = exercise.getValue("sets").jsonArray.map { setElement ->
                        val set = setElement.jsonObject
                        LoggedSetDraft(set.string("id")!!, set.string("weight")!!, set.string("reps")!!, set.getValue("warmup").jsonPrimitive.boolean)
                    },
                )
            },
        )
    }

    fun encode(aggregate: WorkoutAggregate): String = buildJsonObject {
        val session = aggregate.session
        put("id", session.id)
        put("routineId", session.routineId)
        put("gymId", session.gymId)
        put("startedAt", session.startedAt)
        put("durationSeconds", session.durationSeconds)
        put("notes", session.notes)
        put("updatedAt", session.updatedAt)
        put(
            "exercises",
            JsonArray(
                aggregate.exercises.map { exercise ->
                    buildJsonObject {
                        put("id", exercise.id)
                        put("exerciseId", exercise.exerciseId)
                        put("exerciseName", exercise.exerciseName)
                        put("position", exercise.position)
                        put("updatedAt", exercise.updatedAt)
                        put(
                            "sets",
                            JsonArray(
                                exercise.sets.map { set ->
                                    buildJsonObject {
                                        put("id", set.id)
                                        put("position", set.position)
                                        put("weight", set.weight)
                                        put("reps", set.reps)
                                        put("warmup", set.warmup)
                                        put("updatedAt", set.updatedAt)
                                    }
                                },
                            ),
                        )
                    }
                },
            ),
        )
    }.toString()

    fun decodeAggregate(text: String?): WorkoutAggregate? = decode(text) { root ->
        WorkoutAggregate(
            session = WorkoutSession(
                id = root.string("id")!!,
                routineId = root.string("routineId"),
                gymId = root.string("gymId"),
                startedAt = root.getValue("startedAt").jsonPrimitive.long,
                durationSeconds = root["durationSeconds"]?.jsonPrimitive?.intOrNull,
                notes = root.string("notes"),
                updatedAt = root.getValue("updatedAt").jsonPrimitive.long,
            ),
            exercises = root.getValue("exercises").jsonArray.map { element ->
                val exercise = element.jsonObject
                WorkoutExercise(
                    id = exercise.string("id")!!,
                    exerciseId = exercise.string("exerciseId")!!,
                    exerciseName = exercise.string("exerciseName"),
                    position = exercise.getValue("position").jsonPrimitive.int,
                    sets = exercise.getValue("sets").jsonArray.map { setElement ->
                        val set = setElement.jsonObject
                        WorkoutSet(
                            id = set.string("id")!!,
                            position = set.getValue("position").jsonPrimitive.int,
                            weight = set.getValue("weight").jsonPrimitive.double,
                            reps = set.getValue("reps").jsonPrimitive.int,
                            warmup = set.getValue("warmup").jsonPrimitive.boolean,
                            updatedAt = set.getValue("updatedAt").jsonPrimitive.long,
                        )
                    },
                    updatedAt = exercise.getValue("updatedAt").jsonPrimitive.long,
                )
            },
        )
    }

    private fun <T> decode(text: String?, read: (JsonObject) -> T): T? {
        if (text == null) return null
        return try {
            read(Json.parseToJsonElement(text).jsonObject)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NullPointerException) {
            null
        } catch (_: NoSuchElementException) {
            null
        }
    }

    private fun JsonObject.string(key: String): String? = when (val value: JsonElement? = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }
}
