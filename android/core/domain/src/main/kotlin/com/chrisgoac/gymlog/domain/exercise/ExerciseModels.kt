package com.chrisgoac.gymlog.domain.exercise

import java.text.Normalizer

/** Muscle groups, wire values and Spanish labels mirror the PWA (`lib/db/types.ts`, `lib/labels.ts`). */
enum class MuscleGroup(val wire: String, val label: String) {
    PECHO("pecho", "Pecho"), ESPALDA("espalda", "Espalda"), HOMBROS("hombros", "Hombros"),
    BICEPS("biceps", "Bíceps"), TRICEPS("triceps", "Tríceps"), CUADRICEPS("cuadriceps", "Cuádriceps"),
    FEMORAL("femoral", "Femoral"), GLUTEO("gluteo", "Glúteo"), GEMELO("gemelo", "Gemelo"),
    ABDOMEN("abdomen", "Abdomen"), ANTEBRAZO("antebrazo", "Antebrazo"), OTRO("otro", "Otro");

    companion object {
        /** Unknown wire values (written by a newer client) degrade to [OTRO] instead of crashing. */
        fun fromWire(value: String): MuscleGroup = entries.firstOrNull { it.wire == value } ?: OTRO
    }
}

enum class Equipment(val wire: String, val label: String) {
    BARRA("barra", "Barra"), MANCUERNA("mancuerna", "Mancuerna"), MAQUINA("maquina", "Máquina"),
    POLEA("polea", "Polea"), PESO_CORPORAL("peso_corporal", "Peso corporal"), OTRO("otro", "Otro");

    companion object {
        fun fromWire(value: String): Equipment = entries.firstOrNull { it.wire == value } ?: OTRO
    }
}

enum class ExerciseType(val wire: String, val label: String) {
    COMPUESTO("compuesto", "Compuesto"), AISLAMIENTO("aislamiento", "Aislamiento");

    companion object {
        fun fromWire(value: String): ExerciseType = entries.firstOrNull { it.wire == value } ?: COMPUESTO
    }
}

/** An exercise as the UI sees it: a catalog entry ([isCustom] = false) or one of the user's own. */
data class Exercise(
    val id: String,
    val name: String,
    val muscleGroup: MuscleGroup,
    val equipment: Equipment,
    val type: ExerciseType,
    val videoUrl: String? = null,
    val notes: String? = null,
    val description: String? = null,
    val execution: String? = null,
    val incrementKg: Double? = null,
    val isCustom: Boolean,
)

/** List criteria: free text (accent/case-insensitive over the name) plus optional exact filters. */
data class ExerciseFilter(
    val query: String = "",
    val muscleGroup: MuscleGroup? = null,
    val equipment: Equipment? = null,
) {
    val isActive: Boolean get() = query.isNotBlank() || muscleGroup != null || equipment != null

    fun matches(exercise: Exercise): Boolean =
        (muscleGroup == null || exercise.muscleGroup == muscleGroup) &&
            (equipment == null || exercise.equipment == equipment) &&
            (query.isBlank() || exercise.name.searchKey().contains(query.searchKey()))
}

/** Lowercase + NFD with combining marks stripped: "Sentadilla Búlgara" matches "bulgara". */
fun String.searchKey(): String =
    Normalizer.normalize(trim(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase()

private val COMBINING_MARKS = Regex("\\p{M}+")

/** Raw form values for creating/editing a custom exercise. [incrementKg] is text so the form can show bad input. */
data class ExerciseInput(
    val name: String = "",
    val muscleGroup: MuscleGroup = MuscleGroup.OTRO,
    val equipment: Equipment = Equipment.OTRO,
    val type: ExerciseType = ExerciseType.COMPUESTO,
    val videoUrl: String = "",
    val notes: String = "",
    val description: String = "",
    val execution: String = "",
    val incrementKg: String = "",
) {
    companion object {
        fun from(exercise: Exercise) = ExerciseInput(
            name = exercise.name,
            muscleGroup = exercise.muscleGroup,
            equipment = exercise.equipment,
            type = exercise.type,
            videoUrl = exercise.videoUrl.orEmpty(),
            notes = exercise.notes.orEmpty(),
            description = exercise.description.orEmpty(),
            execution = exercise.execution.orEmpty(),
            incrementKg = exercise.incrementKg?.let(::formatKg).orEmpty(),
        )

        private fun formatKg(value: Double): String =
            if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
    }
}

enum class ExerciseField { NAME, VIDEO_URL, NOTES, DESCRIPTION, EXECUTION, INCREMENT_KG }

enum class ExerciseFieldError {
    NAME_REQUIRED, NAME_TOO_LONG, VIDEO_URL_INVALID, VIDEO_URL_TOO_LONG, TEXT_TOO_LONG,
    INCREMENT_NOT_A_NUMBER, INCREMENT_NOT_POSITIVE,
}

/** Allowed lengths (documented caps; the wire contract itself only forbids NUL). */
object ExerciseLimits {
    const val NAME_MAX = 120
    const val VIDEO_URL_MAX = 2048
    const val NOTES_MAX = 2000
    const val DESCRIPTION_MAX = 4000
    const val EXECUTION_MAX = 4000
}

/** Normalized values ready to be stored: trimmed, blank optionals as null, increment parsed. */
data class ValidExercise(
    val name: String,
    val muscleGroup: MuscleGroup,
    val equipment: Equipment,
    val type: ExerciseType,
    val videoUrl: String?,
    val notes: String?,
    val description: String?,
    val execution: String?,
    val incrementKg: Double?,
)

sealed interface ExerciseValidation {
    data class Valid(val value: ValidExercise) : ExerciseValidation
    data class Invalid(val errors: Map<ExerciseField, ExerciseFieldError>) : ExerciseValidation
}

/** PWA parity of the editor rules plus the contract's limits. Muscle/equipment/type are enums, so always allowed. */
object ExerciseValidator {
    fun validate(input: ExerciseInput): ExerciseValidation {
        val errors = LinkedHashMap<ExerciseField, ExerciseFieldError>()
        val name = input.name.cleaned()
        when {
            name.isEmpty() -> errors[ExerciseField.NAME] = ExerciseFieldError.NAME_REQUIRED
            name.length > ExerciseLimits.NAME_MAX -> errors[ExerciseField.NAME] = ExerciseFieldError.NAME_TOO_LONG
        }
        val video = input.videoUrl.cleaned()
        when {
            video.isEmpty() -> Unit
            video.length > ExerciseLimits.VIDEO_URL_MAX -> errors[ExerciseField.VIDEO_URL] = ExerciseFieldError.VIDEO_URL_TOO_LONG
            !isHttpUrl(video) -> errors[ExerciseField.VIDEO_URL] = ExerciseFieldError.VIDEO_URL_INVALID
        }
        capped(input.notes, ExerciseLimits.NOTES_MAX, ExerciseField.NOTES, errors)
        capped(input.description, ExerciseLimits.DESCRIPTION_MAX, ExerciseField.DESCRIPTION, errors)
        capped(input.execution, ExerciseLimits.EXECUTION_MAX, ExerciseField.EXECUTION, errors)

        var increment: Double? = null
        val incrementText = input.incrementKg.cleaned()
        if (incrementText.isNotEmpty()) {
            val parsed = incrementText.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
            when {
                parsed == null -> errors[ExerciseField.INCREMENT_KG] = ExerciseFieldError.INCREMENT_NOT_A_NUMBER
                parsed <= 0.0 -> errors[ExerciseField.INCREMENT_KG] = ExerciseFieldError.INCREMENT_NOT_POSITIVE
                else -> increment = parsed
            }
        }
        if (errors.isNotEmpty()) return ExerciseValidation.Invalid(errors)
        return ExerciseValidation.Valid(
            ValidExercise(
                name = name,
                muscleGroup = input.muscleGroup,
                equipment = input.equipment,
                type = input.type,
                videoUrl = video.ifEmpty { null },
                notes = input.notes.cleaned().ifEmpty { null },
                description = input.description.cleaned().ifEmpty { null },
                execution = input.execution.cleaned().ifEmpty { null },
                incrementKg = increment,
            ),
        )
    }

    private fun capped(raw: String, max: Int, field: ExerciseField, errors: MutableMap<ExerciseField, ExerciseFieldError>) {
        if (raw.cleaned().length > max) errors[field] = ExerciseFieldError.TEXT_TOO_LONG
    }

    /** Trims and drops NUL, which the wire contract forbids in any string. */
    private fun String.cleaned(): String = replace("\u0000", "").trim()

    private fun isHttpUrl(value: String): Boolean = try {
        val uri = java.net.URI(value)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
    } catch (_: java.net.URISyntaxException) {
        false
    }
}
