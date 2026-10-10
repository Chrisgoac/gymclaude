package com.chrisgoac.gymlog.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Mirrors lib/api/v1/contracts/sync.ts (RecordSchemas). Field names are the wire names.
// Records never carry userId, serverVersion or serverUpdatedAt. Nullable TS fields ("nullish")
// are nullable here with a null default; the full-record-replace rule means callers must always
// send complete records (ContractJson encodes nulls explicitly).

/** Record id: 1..128 chars, `^[A-Za-z0-9._:-]+$` (client-generated, or catalog/setting key). */
@JvmInline
@Serializable
value class RecordId(val value: String) {
    override fun toString(): String = value
}

/** The 14 synchronized tables, with their exact wire names. */
@Serializable
enum class SyncTable(val wire: String) {
    @SerialName("exercises") EXERCISES("exercises"),
    @SerialName("routines") ROUTINES("routines"),
    @SerialName("routineExercises") ROUTINE_EXERCISES("routineExercises"),
    @SerialName("workoutSessions") WORKOUT_SESSIONS("workoutSessions"),
    @SerialName("loggedExercises") LOGGED_EXERCISES("loggedExercises"),
    @SerialName("loggedSets") LOGGED_SETS("loggedSets"),
    @SerialName("gyms") GYMS("gyms"),
    @SerialName("exercisePhotos") EXERCISE_PHOTOS("exercisePhotos"),
    @SerialName("userSettings") USER_SETTINGS("userSettings"),
    @SerialName("coachMessages") COACH_MESSAGES("coachMessages"),
    @SerialName("bodyMetrics") BODY_METRICS("bodyMetrics"),
    @SerialName("progressPhotos") PROGRESS_PHOTOS("progressPhotos"),
    @SerialName("mesocycles") MESOCYCLES("mesocycles"),
    @SerialName("achievements") ACHIEVEMENTS("achievements");
}

/** Columns shared by every synchronized record. `deletedAt == null` means live; otherwise tombstone. */
sealed interface SyncRecord {
    val id: RecordId
    val updatedAt: Long
    val deletedAt: Long?
}

@Serializable
data class ExerciseRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val nombre: String,
    val grupoMuscular: String,
    val equipamiento: String,
    val tipo: String,
    val videoUrl: String? = null,
    val notas: String? = null,
    val descripcion: String? = null,
    val execution: String? = null,
    val incrementoKg: Double? = null,
    val esPersonalizado: Boolean
) : SyncRecord

@Serializable
data class RoutineRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val nombre: String,
    val descripcion: String? = null,
    val orden: Int? = null,
    val archivada: Boolean,
    val mesocycleId: String? = null
) : SyncRecord

@Serializable
data class RoutineExerciseRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val routineId: String? = null,
    val exerciseId: String,
    val orden: Int,
    val seriesObjetivo: Int? = null,
    val repsObjetivo: Int? = null,
    val repsObjetivoMin: Int? = null,
    val descansoSegundos: Int? = null,
    val notas: String? = null
) : SyncRecord

@Serializable
data class WorkoutSessionRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val routineDayId: String? = null,
    val routineId: String? = null,
    val gymId: String? = null,
    val fecha: Long,
    val duracionSegundos: Int? = null,
    val notas: String? = null
) : SyncRecord

@Serializable
data class LoggedExerciseRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val sessionId: String,
    val exerciseId: String,
    val orden: Int
) : SyncRecord

@Serializable
data class LoggedSetRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val loggedExerciseId: String,
    val orden: Int,
    val peso: Double,
    val reps: Int,
    val esCalentamiento: Boolean? = null
) : SyncRecord

@Serializable
data class GymRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val nombre: String,
    val orden: Int,
    val archivada: Boolean
) : SyncRecord

@Serializable
data class ExercisePhotoRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val exerciseId: String,
    val url: String,
    val key: String
) : SyncRecord

@Serializable
data class UserSettingRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val valor: String
) : SyncRecord

@Serializable
data class CoachMessageRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val rol: String,
    val contenido: String,
    val createdAt: Long
) : SyncRecord

@Serializable
data class BodyMetricRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val tipo: String,
    val valor: Double,
    val fecha: Long
) : SyncRecord

@Serializable
data class ProgressPhotoRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val url: String,
    val key: String,
    val fecha: Long,
    val angulo: String,
    val nota: String? = null
) : SyncRecord

@Serializable
data class MesocycleRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val nombre: String,
    val objetivo: String,
    val semanas: Int,
    val diasPorSemana: Int,
    val notas: String? = null,
    val progresion: JsonElement,
    val fechaInicio: Long
) : SyncRecord

@Serializable
data class AchievementRecord(
    override val id: RecordId,
    override val updatedAt: Long,
    override val deletedAt: Long?,
    val clave: String,
    val fechaDesbloqueo: Long
) : SyncRecord
