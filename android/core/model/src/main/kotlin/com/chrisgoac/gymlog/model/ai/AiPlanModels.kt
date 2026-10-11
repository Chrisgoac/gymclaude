package com.chrisgoac.gymlog.model.ai

import kotlinx.serialization.Serializable

/**
 * Wire contract of `POST /api/v1/ai/mesocycles` and `POST /api/v1/ai/reports` (lib/api/v1/ai/contracts.ts,
 * contracts/v1/README.md). Field names are the server's Spanish ones; requests are strict on the server.
 */
object AiPlanLimits {
    /** `MAX_MESO_CATALOG`. */
    const val MAX_CATALOG = 400

    /** `EXERCISE_NAME_MAX`. */
    const val MAX_EXERCISE_NAME_CHARS = 200
}

@Serializable
data class MesocycleRequestDto(
    /** Idempotency key: a retry with the same id returns the same draft without a second model call. */
    val clientRequestId: String,
    val params: MesocycleParamsDto,
    val snapshot: CoachSnapshotDto,
    val catalogo: List<CatalogEntryDto>,
)

/** [objetivo] ∈ hipertrofia/fuerza/general; days 2..6, weeks 4..8, minutes 30..90. */
@Serializable
data class MesocycleParamsDto(val objetivo: String, val diasPorSemana: Int, val semanas: Int, val minutosPorSesion: Int)

/** One catalog exercise offered to the model: names only, never ids. [grupo]/[equipamiento] are wire values. */
@Serializable
data class CatalogEntryDto(val nombre: String, val grupo: String, val equipamiento: String)

@Serializable
data class MesocycleResponseDto(
    val clientRequestId: String,
    val responseId: String,
    val draft: MesocycleDraftDto,
    /** Proposed exercises that were not in the catalog (dropped by the server). */
    val omitidos: List<String>,
    val disclaimer: Boolean,
)

@Serializable
data class MesocycleDraftDto(
    val nombre: String,
    val objetivo: String,
    val semanas: Int,
    val diasPorSemana: Int,
    val notas: String?,
    val progresion: List<WeekDto>,
    val dias: List<DayDto>,
) {
    @Serializable
    data class WeekDto(val semana: Int, val descarga: Boolean, val ajuste: String)

    @Serializable
    data class DayDto(val nombre: String, val ejercicios: List<ExerciseDto>)

    /** [nombre] is the exact catalog name the request sent. */
    @Serializable
    data class ExerciseDto(val nombre: String, val seriesObjetivo: Int, val repsObjetivo: Int, val descansoSegundos: Int)
}

@Serializable
data class IntelligentReportRequestDto(val snapshot: CoachSnapshotDto)

@Serializable
data class IntelligentReportResponseDto(
    val reportId: String,
    val generatedAt: Long,
    val cached: Boolean,
    val report: ReportDto,
    val disclaimer: Boolean,
) {
    @Serializable
    data class ReportDto(
        val resumen: String,
        val puntosFuertes: List<String>,
        val aMejorar: List<String>,
        val recomendaciones: List<String>,
    )
}
