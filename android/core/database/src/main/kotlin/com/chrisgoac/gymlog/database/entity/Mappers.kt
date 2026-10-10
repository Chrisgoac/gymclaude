package com.chrisgoac.gymlog.database.entity

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
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.RoutineExerciseRecord
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.UserSettingRecord
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import kotlinx.serialization.json.JsonElement

// Explicit, field-by-field mappers between Room entities and core:model wire records.
// `serverVersion` is local sync metadata: it is passed in when writing and dropped when reading.

fun ExerciseRecord.toEntity(serverVersion: Long?) = ExerciseEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, nombre = nombre,
    grupoMuscular = grupoMuscular, equipamiento = equipamiento, tipo = tipo, videoUrl = videoUrl,
    notas = notas, descripcion = descripcion, execution = execution, incrementoKg = incrementoKg,
    esPersonalizado = esPersonalizado, serverVersion = serverVersion,
)

fun ExerciseEntity.toRecord() = ExerciseRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, nombre = nombre,
    grupoMuscular = grupoMuscular, equipamiento = equipamiento, tipo = tipo, videoUrl = videoUrl,
    notas = notas, descripcion = descripcion, execution = execution, incrementoKg = incrementoKg,
    esPersonalizado = esPersonalizado,
)

fun RoutineRecord.toEntity(serverVersion: Long?) = RoutineEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, nombre = nombre,
    descripcion = descripcion, orden = orden, archivada = archivada, mesocycleId = mesocycleId,
    serverVersion = serverVersion,
)

fun RoutineEntity.toRecord() = RoutineRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, nombre = nombre,
    descripcion = descripcion, orden = orden, archivada = archivada, mesocycleId = mesocycleId,
)

fun RoutineExerciseRecord.toEntity(serverVersion: Long?) = RoutineExerciseEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, routineId = routineId,
    exerciseId = exerciseId, orden = orden, seriesObjetivo = seriesObjetivo,
    repsObjetivo = repsObjetivo, repsObjetivoMin = repsObjetivoMin,
    descansoSegundos = descansoSegundos, notas = notas, serverVersion = serverVersion,
)

fun RoutineExerciseEntity.toRecord() = RoutineExerciseRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, routineId = routineId,
    exerciseId = exerciseId, orden = orden, seriesObjetivo = seriesObjetivo,
    repsObjetivo = repsObjetivo, repsObjetivoMin = repsObjetivoMin,
    descansoSegundos = descansoSegundos, notas = notas,
)

fun WorkoutSessionRecord.toEntity(serverVersion: Long?) = WorkoutSessionEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, routineDayId = routineDayId,
    routineId = routineId, gymId = gymId, fecha = fecha, duracionSegundos = duracionSegundos,
    notas = notas, serverVersion = serverVersion,
)

fun WorkoutSessionEntity.toRecord() = WorkoutSessionRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, routineDayId = routineDayId,
    routineId = routineId, gymId = gymId, fecha = fecha, duracionSegundos = duracionSegundos,
    notas = notas,
)

fun LoggedExerciseRecord.toEntity(serverVersion: Long?) = LoggedExerciseEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, sessionId = sessionId,
    exerciseId = exerciseId, orden = orden, serverVersion = serverVersion,
)

fun LoggedExerciseEntity.toRecord() = LoggedExerciseRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, sessionId = sessionId,
    exerciseId = exerciseId, orden = orden,
)

fun LoggedSetRecord.toEntity(serverVersion: Long?) = LoggedSetEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt,
    loggedExerciseId = loggedExerciseId, orden = orden, peso = peso, reps = reps,
    esCalentamiento = esCalentamiento, serverVersion = serverVersion,
)

fun LoggedSetEntity.toRecord() = LoggedSetRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt,
    loggedExerciseId = loggedExerciseId, orden = orden, peso = peso, reps = reps,
    esCalentamiento = esCalentamiento,
)

fun GymRecord.toEntity(serverVersion: Long?) = GymEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, nombre = nombre, orden = orden,
    archivada = archivada, serverVersion = serverVersion,
)

fun GymEntity.toRecord() = GymRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, nombre = nombre, orden = orden,
    archivada = archivada,
)

fun ExercisePhotoRecord.toEntity(serverVersion: Long?) = ExercisePhotoEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, exerciseId = exerciseId,
    url = url, key = key, serverVersion = serverVersion,
)

fun ExercisePhotoEntity.toRecord() = ExercisePhotoRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, exerciseId = exerciseId,
    url = url, key = key,
)

fun UserSettingRecord.toEntity(serverVersion: Long?) = UserSettingEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, valor = valor,
    serverVersion = serverVersion,
)

fun UserSettingEntity.toRecord() = UserSettingRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, valor = valor,
)

fun CoachMessageRecord.toEntity(serverVersion: Long?) = CoachMessageEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, rol = rol, contenido = contenido,
    createdAt = createdAt, serverVersion = serverVersion,
)

fun CoachMessageEntity.toRecord() = CoachMessageRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, rol = rol,
    contenido = contenido, createdAt = createdAt,
)

fun BodyMetricRecord.toEntity(serverVersion: Long?) = BodyMetricEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, tipo = tipo, valor = valor,
    fecha = fecha, serverVersion = serverVersion,
)

fun BodyMetricEntity.toRecord() = BodyMetricRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, tipo = tipo, valor = valor,
    fecha = fecha,
)

fun ProgressPhotoRecord.toEntity(serverVersion: Long?) = ProgressPhotoEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, url = url, key = key,
    fecha = fecha, angulo = angulo, nota = nota, serverVersion = serverVersion,
)

fun ProgressPhotoEntity.toRecord() = ProgressPhotoRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, url = url, key = key,
    fecha = fecha, angulo = angulo, nota = nota,
)

fun MesocycleRecord.toEntity(serverVersion: Long?) = MesocycleEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, nombre = nombre,
    objetivo = objetivo, semanas = semanas, diasPorSemana = diasPorSemana, notas = notas,
    progresionJson = ContractJson.encodeToString(JsonElement.serializer(), progresion),
    fechaInicio = fechaInicio, serverVersion = serverVersion,
)

fun MesocycleEntity.toRecord() = MesocycleRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, nombre = nombre,
    objetivo = objetivo, semanas = semanas, diasPorSemana = diasPorSemana, notas = notas,
    progresion = ContractJson.parseToJsonElement(progresionJson), fechaInicio = fechaInicio,
)

fun AchievementRecord.toEntity(serverVersion: Long?) = AchievementEntity(
    id = id.value, updatedAt = updatedAt, deletedAt = deletedAt, clave = clave,
    fechaDesbloqueo = fechaDesbloqueo, serverVersion = serverVersion,
)

fun AchievementEntity.toRecord() = AchievementRecord(
    id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, clave = clave,
    fechaDesbloqueo = fechaDesbloqueo,
)
