package com.chrisgoac.gymlog.model.ai

import kotlinx.serialization.Serializable

/**
 * Coach wire contract (lib/api/v1/ai/contracts.ts, contracts/v1/README.md "AI (Pro)"). Field names are
 * the server's Spanish ones; every object is strict on the server, so nothing beyond these fields is sent.
 * Encode with `ContractJson` (explicit nulls: the server requires nullable keys to be present).
 */
object CoachLimits {
    /** `MAX_COACH_TEXT_CHARS`. */
    const val MAX_TEXT_CHARS = 2000

    /** `MAX_REPORT_CONTEXT_CHARS`. */
    const val MAX_REPORT_CONTEXT_CHARS = 500

    /** `MAX_STREAM_DELTA_CHARS`. */
    const val MAX_DELTA_CHARS = 8000

    /** Exercise names in the snapshot (`EXERCISE_NAME_MAX`). */
    const val MAX_EXERCISE_NAME_CHARS = 200

    /** Muscle-group labels in the snapshot. */
    const val MAX_GROUP_LABEL_CHARS = 40

    /** Body-metric labels in the snapshot. */
    const val MAX_METRIC_LABEL_CHARS = 80
}

/** `AI_REPORT_CATEGORIES`. */
enum class AiReportCategory(val wire: String) {
    INACCURATE("inaccurate"),
    HARMFUL("harmful"),
    MEDICAL("medical"),
    OFFENSIVE("offensive"),
    OTHER("other"),
}

@Serializable
data class CoachRequestDto(
    /** Id of the user's coach message: retries reuse it, so a response is never duplicated. */
    val clientMessageId: String,
    val text: String,
    val snapshot: CoachSnapshotDto,
)

/** `CoachSnapshotSchema`: an allowlist with no ids, URLs, object keys or photos. */
@Serializable
data class CoachSnapshotDto(
    val estancados: List<Estancado>,
    val semana: Semana,
    val grupos: List<Grupo>,
    val cuerpo: Cuerpo,
) {
    @Serializable
    data class Estancado(val ejercicio: String, val sesionesSinMejora: Int)

    @Serializable
    data class Semana(val sesiones: Int, val objetivo: Int, val volumen: Long, val deltaPct: Int?, val prs: List<Pr>)

    /** [tipo] is `peso` or `1rm`. */
    @Serializable
    data class Pr(val ejercicio: String, val tipo: String)

    @Serializable
    data class Grupo(val grupo: String, val volumenSemana: Long, val diasSinEntrenar: Long?, val objetivo: Double?)

    @Serializable
    data class Cuerpo(val peso: Resumen?, val medidas: List<Medida>)

    @Serializable
    data class Resumen(val actual: Double, val delta4sem: Double?)

    @Serializable
    data class Medida(val metrica: String, val actual: Double, val delta4sem: Double?)
}

/** Body of `POST /api/v1/ai/responses/:id/report`. */
@Serializable
data class ReportResponseRequestDto(val category: String, val context: String? = null)

@Serializable
data class ReportResponseDto(val responseId: String, val created: Boolean)
