package com.chrisgoac.gymlog.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// One Room table per synchronized wire table (contracts/v1/README.md, core:model Entities.kt).
// Columns mirror the wire record 1:1 (including the schema-drift fields descripcion, execution,
// incrementoKg and repsObjetivoMin), plus one local column: `serverVersion`, the last version the
// client saw from the server (null = never synced). Nothing else the server owns is stored here:
// there is no userId column because every user has a physically separate database.
//
// Tombstones are rows with `deletedAt != null`; synced rows are never physically deleted.
// Reference columns are indexed but carry NO foreign-key constraints (see GymLogDatabase).

@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val nombre: String,
    val grupoMuscular: String,
    val equipamiento: String,
    val tipo: String,
    val videoUrl: String?,
    val notas: String?,
    val descripcion: String?,
    val execution: String?,
    val incrementoKg: Double?,
    val esPersonalizado: Boolean,
    val serverVersion: Long?,
)

@Entity(tableName = "routines", indices = [Index("mesocycleId")])
data class RoutineEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val nombre: String,
    val descripcion: String?,
    val orden: Int?,
    val archivada: Boolean,
    val mesocycleId: String?,
    val serverVersion: Long?,
)

@Entity(tableName = "routine_exercises", indices = [Index("routineId"), Index("exerciseId")])
data class RoutineExerciseEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val routineId: String?,
    val exerciseId: String,
    val orden: Int,
    val seriesObjetivo: Int?,
    val repsObjetivo: Int?,
    val repsObjetivoMin: Int?,
    val descansoSegundos: Int?,
    val notas: String?,
    val serverVersion: Long?,
)

@Entity(
    tableName = "workout_sessions",
    indices = [Index("fecha"), Index("routineId"), Index("routineDayId"), Index("gymId")],
)
data class WorkoutSessionEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val routineDayId: String?,
    val routineId: String?,
    val gymId: String?,
    val fecha: Long,
    val duracionSegundos: Int?,
    val notas: String?,
    val serverVersion: Long?,
)

@Entity(tableName = "logged_exercises", indices = [Index("sessionId"), Index("exerciseId")])
data class LoggedExerciseEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val sessionId: String,
    val exerciseId: String,
    val orden: Int,
    val serverVersion: Long?,
)

@Entity(tableName = "logged_sets", indices = [Index("loggedExerciseId")])
data class LoggedSetEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val loggedExerciseId: String,
    val orden: Int,
    val peso: Double,
    val reps: Int,
    val esCalentamiento: Boolean?,
    val serverVersion: Long?,
)

@Entity(tableName = "gyms")
data class GymEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val nombre: String,
    val orden: Int,
    val archivada: Boolean,
    val serverVersion: Long?,
)

@Entity(tableName = "exercise_photos", indices = [Index("exerciseId")])
data class ExercisePhotoEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val exerciseId: String,
    val url: String,
    val key: String,
    val serverVersion: Long?,
)

/** Keyed by the setting id (the setting key): one database per user, so no userId column. */
@Entity(tableName = "user_settings")
data class UserSettingEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val valor: String,
    val serverVersion: Long?,
)

@Entity(tableName = "coach_messages", indices = [Index("createdAt")])
data class CoachMessageEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val rol: String,
    val contenido: String,
    val createdAt: Long,
    val serverVersion: Long?,
)

@Entity(tableName = "body_metrics", indices = [Index("tipo", "fecha")])
data class BodyMetricEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val tipo: String,
    val valor: Double,
    val fecha: Long,
    val serverVersion: Long?,
)

@Entity(tableName = "progress_photos", indices = [Index("fecha")])
data class ProgressPhotoEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val url: String,
    val key: String,
    val fecha: Long,
    val angulo: String,
    val nota: String?,
    val serverVersion: Long?,
)

/** `progresionJson` holds the wire `progresion` JSON value, encoded with ContractJson. */
@Entity(tableName = "mesocycles")
data class MesocycleEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val nombre: String,
    val objetivo: String,
    val semanas: Int,
    val diasPorSemana: Int,
    val notas: String?,
    val progresionJson: String,
    val fechaInicio: Long,
    val serverVersion: Long?,
)

@Entity(tableName = "achievements", indices = [Index("clave")])
data class AchievementEntity(
    @PrimaryKey val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val clave: String,
    val fechaDesbloqueo: Long,
    val serverVersion: Long?,
)
