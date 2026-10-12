package com.chrisgoac.gymlog.domain.workout

import com.chrisgoac.gymlog.domain.exercise.Equipment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Progression mode setting (`modoProgresion`); wire values mirror `lib/progresion.ts`. */
enum class ProgressionMode(val wire: String) {
    DOBLE("doble"), OBJETIVO("objetivo"), REPITE("repite"), OFF("off");

    companion object {
        /** Unknown values fall back to the PWA default, [OBJETIVO]. */
        fun fromWire(value: String?): ProgressionMode = entries.firstOrNull { it.wire == value } ?: OBJETIVO
    }
}

/** Why a suggestion was made (`Motivo` in the PWA). */
enum class SuggestionReason(val wire: String) {
    SUBIO_PESO("subio-peso"), SUBIO_REPS("subio-reps"), REPITE("repite"),
    SIN_HISTORIAL("sin-historial"), LIBRE("libre"), OFF("off");

    companion object {
        fun fromWire(value: String): SuggestionReason = entries.first { it.wire == value }
    }
}

data class WeightReps(val weight: Double, val reps: Int)

/** Routine target for a suggestion: [repsMax] is `repsObjetivo` (top of the range), [repsMin] `repsObjetivoMin`. */
data class RepTarget(val repsMax: Int?, val repsMin: Int?)

data class Suggestion(val weight: Double, val reps: Int, val reason: SuggestionReason)

/** The user's synced progression preferences; defaults match the PWA. */
data class ProgressionSettings(
    val mode: ProgressionMode = ProgressionMode.OBJETIVO,
    val increments: Map<Equipment, Double> = Progression.INCREMENT_DEFAULTS,
) {
    companion object {
        const val MODE_KEY = "modoProgresion"
        const val INCREMENTS_KEY = "incrementos"

        /**
         * From the stored JSON `valor` of the two user settings. Like the PWA, an unknown mode is
         * [ProgressionMode.OBJETIVO] and stored increments merge over the defaults; non-numeric
         * entries and unknown equipment are ignored.
         */
        fun fromStored(mode: String?, increments: String?): ProgressionSettings {
            val parsedMode = (parse(mode) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val stored = (parse(increments) as? JsonObject).orEmpty()
            val merged = Progression.INCREMENT_DEFAULTS.toMutableMap()
            stored.forEach { (key, value) ->
                val equipment = Equipment.entries.firstOrNull { it.wire == key } ?: return@forEach
                val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: return@forEach
                merged[equipment] = number
            }
            return ProgressionSettings(ProgressionMode.fromWire(parsedMode), merged)
        }

        private fun parse(raw: String?) = raw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
    }
}

/** Pure port of `lib/progresion.ts` (inferirSalto, calcularSugerencia, describeMotivo); shared fixture: contracts/domain/progression.json. */
object Progression {
    /** Default weight step (kg) per equipment, used when there is no history. */
    val INCREMENT_DEFAULTS: Map<Equipment, Double> = mapOf(
        Equipment.BARRA to 2.5,
        Equipment.MANCUERNA to 2.0,
        Equipment.MAQUINA to 5.0,
        Equipment.POLEA to 2.5,
        Equipment.PESO_CORPORAL to 0.0,
        Equipment.OTRO to 2.5,
    )

    /** Plausible steps the inference snaps to. */
    val SANE_STEPS = listOf(0.5, 1.0, 1.25, 2.0, 2.5, 5.0, 7.5, 10.0)

    /** Manual override -> GCD of the differences between distinct logged weights -> equipment default. */
    fun inferStep(weights: List<Double>, equipment: Equipment, defaults: Map<Equipment, Double>, override: Double?): Double {
        if (override != null && override > 0) return override
        val distinct = weights.filter { it > 0 }.distinct().sorted()
        if (distinct.size >= 2) {
            // x100 to work with integers (2.5 / 1.25 without floating point noise).
            var g = 0L
            for (i in 1 until distinct.size) g = gcd(g, Math.round((distinct[i] - distinct[i - 1]) * 100))
            return snap(g / 100.0)
        }
        return defaults[equipment] ?: 2.5
    }

    fun suggest(mode: ProgressionMode, last: List<WeightReps>?, target: RepTarget?, step: Double, bodyweight: Boolean): Suggestion {
        val lastSet = last?.lastOrNull()
        if (mode == ProgressionMode.OFF) return Suggestion(lastSet?.weight ?: 0.0, lastSet?.reps ?: 0, SuggestionReason.OFF)
        val top = target?.repsMax
        if (target == null || top == null || top == 0) return Suggestion(lastSet?.weight ?: 0.0, lastSet?.reps ?: 0, SuggestionReason.LIBRE)
        if (lastSet == null) return Suggestion(0.0, top, SuggestionReason.SIN_HISTORIAL)

        val base = lastSet.weight
        val min = maxOf(1, minOf(target.repsMin ?: (top - 4), top))
        val goal = top // 'doble' aims at the top of the range; the others at repsObjetivo, the same value.
        val success = last.all { it.reps >= goal }

        if (bodyweight) {
            return if (success) Suggestion(base, lastSet.reps + 1, SuggestionReason.SUBIO_REPS) else Suggestion(base, goal, SuggestionReason.REPITE)
        }
        if (mode == ProgressionMode.DOBLE) {
            return if (success) Suggestion(base + step, min, SuggestionReason.SUBIO_PESO)
            else Suggestion(base, minOf(top, lastSet.reps + 1), SuggestionReason.SUBIO_REPS)
        }
        return if (success) Suggestion(base + step, goal, SuggestionReason.SUBIO_PESO) else Suggestion(base, goal, SuggestionReason.REPITE)
    }

    /** Short badge text, or null for no badge. [step] must be the one passed to [suggest]. */
    fun describe(suggestion: Suggestion, step: Double): String? = when (suggestion.reason) {
        SuggestionReason.SUBIO_PESO -> "▲ +${jsNumber(step)} kg"
        SuggestionReason.SUBIO_REPS -> "▲ +1 rep"
        SuggestionReason.REPITE -> "= repite"
        else -> null
    }

    /** How JavaScript prints a number in a template literal (5 -> "5", 2.5 -> "2.5"). */
    private fun jsNumber(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun snap(value: Double): Double =
        SANE_STEPS.fold(SANE_STEPS.first()) { best, s -> if (Math.abs(s - value) < Math.abs(best - value)) s else best }
}
