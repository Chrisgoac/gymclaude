package com.chrisgoac.gymlog.data.ai

import com.chrisgoac.gymlog.data.coach.retryAfterMillis
import com.chrisgoac.gymlog.data.coach.toDto
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.ai.MesocycleDraft
import com.chrisgoac.gymlog.domain.ai.MesocycleDraftDay
import com.chrisgoac.gymlog.domain.ai.MesocycleDraftExercise
import com.chrisgoac.gymlog.domain.ai.MesocycleParams
import com.chrisgoac.gymlog.domain.ai.MesocycleRepository
import com.chrisgoac.gymlog.domain.ai.MesocycleSaveException
import com.chrisgoac.gymlog.domain.ai.MesocycleWeek
import com.chrisgoac.gymlog.domain.coach.CoachException
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.stats.CoachSnapshot
import com.chrisgoac.gymlog.model.MesocycleRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.RoutineExerciseRecord
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.ai.AiPlanLimits
import com.chrisgoac.gymlog.model.ai.CatalogEntryDto
import com.chrisgoac.gymlog.model.ai.MesocycleParamsDto
import com.chrisgoac.gymlog.model.ai.MesocycleRequestDto
import com.chrisgoac.gymlog.network.AiPlanApi
import com.chrisgoac.gymlog.network.SyncApiException
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * [MesocycleRepository] over the v1 mesocycle endpoint and the open account's Room database.
 *
 * Generation sends the current snapshot and the account's exercise catalog (names only, deduplicated by
 * normalized name, custom exercises first, capped at the server's 400) and maps the draft's catalog names
 * back to the account's own exercise ids. Saving writes the same rows as the PWA's `guardarMesociclo`
 * (lib/save-mesocycle.ts): a mesocycle, one routine per day (`mesocycleId`, `orden` after the existing
 * routines) and its routine exercises (`orden` 0..n-1) — all in ONE [LocalMutation] with fresh local ids,
 * which the runner splits into ordered outbox chunks when it exceeds 50 operations. Nothing is logged.
 */
class OfflineMesocycleRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
    private val api: AiPlanApi,
    private val exercises: ExerciseRepository,
    private val snapshot: suspend () -> CoachSnapshot,
    /** Server-corrected clock: the mesocycle's `fechaInicio`. */
    private val now: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val inProgressBackoffMillis: List<Long> = listOf(2_000L, 4_000L, 8_000L),
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : MesocycleRepository {

    override suspend fun generate(clientRequestId: String, params: MesocycleParams): MesocycleDraft {
        val open = databases.current.value ?: throw CoachException(CoachFailure.NO_ACCOUNT)
        if (!params.isValid) throw CoachException(CoachFailure.REJECTED)
        val catalog = catalogOf(exercises.observeExercises().first())
        val request = MesocycleRequestDto(
            clientRequestId = clientRequestId,
            params = MesocycleParamsDto(params.goal.wire, params.daysPerWeek, params.weeks, params.minutesPerSession),
            snapshot = snapshot().toDto(),
            catalogo = catalog.map { CatalogEntryDto(it.name, it.muscleGroup.wire, it.equipment.wire) },
        )
        val response = withInProgressRetry(inProgressBackoffMillis, wait) { api.generateMesocycle(open.userId, request) }
        val idsByName = catalog.associate { normalizeName(it.name) to it }
        val omitted = response.omitidos.toMutableList()
        val draft = response.draft
        return MesocycleDraft(
            name = draft.nombre,
            goal = draft.objetivo,
            weeks = draft.semanas,
            daysPerWeek = draft.diasPorSemana,
            notes = draft.notas,
            progression = draft.progresion.map { MesocycleWeek(it.semana, it.descarga, it.ajuste) },
            days = draft.dias.map { day ->
                MesocycleDraftDay(
                    key = newId(),
                    name = day.nombre,
                    exercises = day.ejercicios.mapNotNull { exercise ->
                        // The catalog may have changed since the request: an unknown name is left out, never guessed.
                        val local = idsByName[normalizeName(exercise.nombre)] ?: run {
                            omitted += exercise.nombre
                            return@mapNotNull null
                        }
                        MesocycleDraftExercise(newId(), local.id, local.name, exercise.seriesObjetivo, exercise.repsObjetivo, exercise.descansoSegundos)
                    },
                )
            },
            omitted = omitted.distinct(),
        )
    }

    override suspend fun save(draft: MesocycleDraft): String {
        val open = databases.current.value ?: throw MesocycleSaveException("No account database is open")
        if (!draft.canSave) throw MesocycleSaveException("Every day needs at least one exercise and the mesocycle a name")
        val known = exercises.observeExercises().first().map { it.id }.toSet()
        draft.days.flatMap { it.exercises }.firstOrNull { it.exerciseId !in known }?.let {
            throw MesocycleSaveException("Exercise ${it.exerciseId} no longer exists")
        }
        val database = open.database
        val existingRoutines = database.routineDao().observeLive().first().size
        val mesocycleId = RecordId(newId())
        val routineIds = draft.days.map { RecordId(newId()) }
        runnerFor(database).run(
            LocalMutation {
                put(
                    MesocycleRecord(
                        id = mesocycleId,
                        updatedAt = nextTimestamp(SyncTable.MESOCYCLES, mesocycleId),
                        deletedAt = null,
                        nombre = draft.name.trim().take(MesocycleDraft.MAX_NAME_CHARS),
                        objetivo = draft.goal,
                        semanas = draft.weeks,
                        diasPorSemana = draft.daysPerWeek,
                        notas = draft.notes?.takeIf { it.isNotBlank() },
                        progresion = buildJsonArray {
                            draft.progression.forEach { week ->
                                add(
                                    buildJsonObject {
                                        put("semana", JsonPrimitive(week.week))
                                        put("descarga", JsonPrimitive(week.deload))
                                        put("ajuste", JsonPrimitive(week.adjustment))
                                    },
                                )
                            }
                        },
                        fechaInicio = now(),
                    ),
                )
                // Routines before their exercises: when the runner chunks, the referenced rows go first.
                draft.days.forEachIndexed { index, day ->
                    val id = routineIds[index]
                    put(
                        RoutineRecord(
                            id = id,
                            updatedAt = nextTimestamp(SyncTable.ROUTINES, id),
                            deletedAt = null,
                            nombre = day.name.trim().ifEmpty { "Día ${index + 1}" },
                            orden = existingRoutines + index,
                            archivada = false,
                            mesocycleId = mesocycleId.value,
                        ),
                    )
                }
                draft.days.forEachIndexed { dayIndex, day ->
                    day.exercises.forEachIndexed { position, exercise ->
                        val id = RecordId(newId())
                        put(
                            RoutineExerciseRecord(
                                id = id,
                                updatedAt = nextTimestamp(SyncTable.ROUTINE_EXERCISES, id),
                                deletedAt = null,
                                routineId = routineIds[dayIndex].value,
                                exerciseId = exercise.exerciseId,
                                orden = position,
                                seriesObjetivo = exercise.sets,
                                repsObjetivo = exercise.reps,
                                descansoSegundos = exercise.restSeconds,
                            ),
                        )
                    }
                }
            },
        )
        return mesocycleId.value
    }

    companion object {
        /** Same normalization as the server and the PWA: case, accents and spacing ignored. */
        fun normalizeName(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFD).replace(COMBINING, "").lowercase().trim().replace(SPACES, " ")

        private val COMBINING = Regex("[\\u0300-\\u036f]")
        private val SPACES = Regex("\\s+")

        /** One entry per normalized name (custom exercises win), within the server's catalog bounds. */
        internal fun catalogOf(all: List<Exercise>): List<Exercise> {
            val seen = HashSet<String>()
            return all
                .filter { it.name.isNotBlank() && it.name.length <= AiPlanLimits.MAX_EXERCISE_NAME_CHARS && !it.name.contains('\u0000') }
                .sortedWith(compareByDescending<Exercise> { it.isCustom }.thenBy { normalizeName(it.name) }.thenBy { it.id })
                .filter { seen.add(normalizeName(it.name)) }
                .take(AiPlanLimits.MAX_CATALOG)
        }
    }
}

/** Waits out `409 AI_RESPONSE_IN_PROGRESS` (another attempt holds the server lease); maps failures to [CoachException]. */
internal suspend fun <T> withInProgressRetry(backoff: List<Long>, wait: suspend (Long) -> Unit, call: suspend () -> T): T {
    var waits = 0
    while (true) {
        try {
            return call()
        } catch (e: SyncApiException.Api) {
            if (e.rawCode != IN_PROGRESS || waits >= backoff.size) throw e.toAiFailure()
            wait(retryAfterMillis(e.retryAfterSeconds) ?: backoff[waits])
            waits++
        } catch (e: SyncApiException) {
            throw e.toAiFailure()
        }
    }
}

private const val IN_PROGRESS = "AI_RESPONSE_IN_PROGRESS"

internal fun SyncApiException.toAiFailure(): CoachException = CoachException(
    when (this) {
        is SyncApiException.Api -> when {
            rawCode == "PRO_REQUIRED" -> CoachFailure.PRO_REQUIRED
            rawCode == IN_PROGRESS -> CoachFailure.BUSY
            retryable || httpStatus == 429 || httpStatus >= 500 -> CoachFailure.UNAVAILABLE
            else -> CoachFailure.REJECTED
        }
        is SyncApiException.Transport, is SyncApiException.Malformed -> CoachFailure.INTERRUPTED
        is SyncApiException.SessionRequired, is SyncApiException.SessionRejected -> CoachFailure.SESSION
    },
    this,
)
