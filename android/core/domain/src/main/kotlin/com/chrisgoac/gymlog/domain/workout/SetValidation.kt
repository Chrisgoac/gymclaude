package com.chrisgoac.gymlog.domain.workout

/** Why a typed weight/reps value cannot be stored (or, for [ZERO_REPS], cannot complete a set). */
enum class SetFieldError { REQUIRED, NOT_A_NUMBER, NOT_INTEGER, NEGATIVE, TOO_LARGE, ZERO_REPS }

sealed interface FieldParse<out T> {
    data class Valid<T>(val value: T) : FieldParse<T>
    data class Invalid(val error: SetFieldError) : FieldParse<Nothing>
}

/**
 * Rules for the numbers of a logged set. Fields are typed text that only reaches Room once it
 * parses: weight is a decimal with comma or dot, 0..[MAX_WEIGHT_KG] kg (0 = bodyweight); reps an
 * integer 0..[MAX_REPS]. Zero reps can be stored (the PWA pre-fills a new set with 0 when there is
 * no history) but a set needs at least one repetition to be marked completed.
 */
object SetValidation {
    const val MAX_WEIGHT_KG = 1000.0
    const val MAX_REPS = 1000

    /** Longest text worth parsing; longer input is reported as too large instead of parsed. */
    private const val MAX_TEXT = 12

    private val DECIMAL = Regex("""^(\d*)(?:[.,](\d*))?$""")
    private val SIGNED = Regex("""^-\s*\d+(?:[.,]\d*)?$""")

    fun parseWeight(text: String): FieldParse<Double> {
        val raw = text.trim()
        if (raw.isEmpty()) return FieldParse.Invalid(SetFieldError.REQUIRED)
        if (SIGNED.matches(raw)) return FieldParse.Invalid(SetFieldError.NEGATIVE)
        val match = DECIMAL.matchEntire(raw) ?: return FieldParse.Invalid(SetFieldError.NOT_A_NUMBER)
        val (whole, fraction) = match.destructured
        if (whole.isEmpty() && fraction.isEmpty()) return FieldParse.Invalid(SetFieldError.NOT_A_NUMBER)
        if (raw.length > MAX_TEXT) return FieldParse.Invalid(SetFieldError.TOO_LARGE)
        val value = "${whole.ifEmpty { "0" }}.${fraction.ifEmpty { "0" }}".toDouble()
        return if (value > MAX_WEIGHT_KG) FieldParse.Invalid(SetFieldError.TOO_LARGE) else FieldParse.Valid(value)
    }

    fun parseReps(text: String): FieldParse<Int> {
        val raw = text.trim()
        return when {
            raw.isEmpty() -> FieldParse.Invalid(SetFieldError.REQUIRED)
            SIGNED.matches(raw) -> FieldParse.Invalid(SetFieldError.NEGATIVE)
            DECIMAL.matches(raw) && raw.any { it == '.' || it == ',' } -> FieldParse.Invalid(SetFieldError.NOT_INTEGER)
            !raw.all { it in '0'..'9' } -> FieldParse.Invalid(SetFieldError.NOT_A_NUMBER)
            raw.length > MAX_TEXT -> FieldParse.Invalid(SetFieldError.TOO_LARGE)
            else -> raw.toLong().let { if (it > MAX_REPS) FieldParse.Invalid(SetFieldError.TOO_LARGE) else FieldParse.Valid(it.toInt()) }
        }
    }

    /** The same bounds, for values about to be written (repository guard before the transaction). */
    fun isStorable(weight: Double, reps: Int): Boolean =
        weight.isFinite() && weight in 0.0..MAX_WEIGHT_KG && reps in 0..MAX_REPS

    /** Null when [reps] allows marking the set completed. */
    fun completionError(reps: Int): SetFieldError? = if (reps < 1) SetFieldError.ZERO_REPS else null

    /** Text for a stored weight: "60", "62.5". */
    fun formatWeight(weight: Double): String =
        if (weight == Math.floor(weight) && !weight.isInfinite()) weight.toLong().toString() else weight.toString()
}

/** Free-text limits of a workout. */
object WorkoutLimits {
    const val NOTES_MAX = 2000
}
