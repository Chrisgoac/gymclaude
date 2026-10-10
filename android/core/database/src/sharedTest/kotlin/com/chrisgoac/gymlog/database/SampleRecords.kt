package com.chrisgoac.gymlog.database

import com.chrisgoac.gymlog.model.AchievementRecord
import com.chrisgoac.gymlog.model.BodyMetricRecord
import com.chrisgoac.gymlog.model.CoachMessageRecord
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
import com.chrisgoac.gymlog.model.SyncRecord
import com.chrisgoac.gymlog.model.UserSettingRecord
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Synthetic records with EVERY field populated (drift fields included), one per synced table. */
object SampleRecords {
    const val T = 1_767_225_600_000L

    fun exercise(id: String = "ex-1", updatedAt: Long = T, deletedAt: Long? = null) = ExerciseRecord(
        id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, nombre = "Press banca",
        grupoMuscular = "pecho", equipamiento = "barra", tipo = "compuesto",
        videoUrl = "https://example.invalid/v", notas = "notas", descripcion = "descripcion",
        execution = "execution", incrementoKg = 2.5, esPersonalizado = true,
    )

    fun routine(id: String = "ro-1") = RoutineRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, nombre = "Torso", descripcion = "d",
        orden = 3, archivada = false, mesocycleId = "me-1",
    )

    fun routineExercise(id: String = "re-1") = RoutineExerciseRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, routineId = "ro-1", exerciseId = "ex-1",
        orden = 1, seriesObjetivo = 4, repsObjetivo = 10, repsObjetivoMin = 8, descansoSegundos = 90,
        notas = "n",
    )

    fun session(id: String = "ws-1", updatedAt: Long = T, deletedAt: Long? = null) = WorkoutSessionRecord(
        id = RecordId(id), updatedAt = updatedAt, deletedAt = deletedAt, routineDayId = "rd-1",
        routineId = "ro-1", gymId = "gy-1", fecha = T - 3_600_000, duracionSegundos = 3600, notas = "bien",
    )

    fun loggedExercise(id: String = "le-1", sessionId: String = "ws-1") = LoggedExerciseRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, sessionId = sessionId, exerciseId = "ex-1", orden = 0,
    )

    fun loggedSet(id: String = "ls-1", loggedExerciseId: String = "le-1", peso: Double = 80.0) = LoggedSetRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, loggedExerciseId = loggedExerciseId, orden = 0,
        peso = peso, reps = 8, esCalentamiento = false,
    )

    fun gym(id: String = "gy-1") = GymRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, nombre = "Casa", orden = 0, archivada = true,
    )

    fun exercisePhoto(id: String = "ep-1") = ExercisePhotoRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, exerciseId = "ex-1",
        url = "https://example.invalid/p.jpg", key = "photos/p.jpg",
    )

    fun userSetting(id: String = "restSeconds") = UserSettingRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, valor = "90",
    )

    fun coachMessage(id: String = "cm-1") = CoachMessageRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, rol = "user", contenido = "hola", createdAt = T - 1,
    )

    fun bodyMetric(id: String = "bm-1") = BodyMetricRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, tipo = "peso", valor = 81.3, fecha = T - 2,
    )

    fun progressPhoto(id: String = "pp-1") = ProgressPhotoRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, url = "https://example.invalid/f.jpg",
        key = "progress/f.jpg", fecha = T - 3, angulo = "frente", nota = "nota",
    )

    fun mesocycle(id: String = "me-1") = MesocycleRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, nombre = "Fuerza", objetivo = "fuerza",
        semanas = 6, diasPorSemana = 4, notas = "n",
        progresion = JsonObject(
            mapOf(
                "tipo" to JsonPrimitive("lineal"),
                "pasos" to JsonArray(listOf(JsonPrimitive(2.5), JsonPrimitive(5), JsonNull)),
                "deload" to JsonPrimitive(true),
            ),
        ),
        fechaInicio = T - 4,
    )

    fun achievement(id: String = "ac-1") = AchievementRecord(
        id = RecordId(id), updatedAt = T, deletedAt = null, clave = "primer-pr", fechaDesbloqueo = T - 5,
    )

    /** One fully populated record per synced table (14). */
    fun all(): List<SyncRecord> = listOf(
        exercise(), routine(), routineExercise(), session(), loggedExercise(), loggedSet(), gym(),
        exercisePhoto(), userSetting(), coachMessage(), bodyMetric(), progressPhoto(), mesocycle(),
        achievement(),
    )
}
