package com.chrisgoac.gymlog.domain.body

import com.chrisgoac.gymlog.domain.math.WeightUnit
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

/**
 * One body measurement. [value] is in the metric's STORAGE unit (kg for weight, cm for lengths, the
 * custom unit otherwise); [at] is the wire `fecha` in epoch millis.
 */
data class BodyMeasurement(val id: String, val type: String, val value: Double, val at: Long, val updatedAt: Long)

/** A user-defined metric, stored exactly like the PWA does (synced setting `metricasPersonalizadas`: `{clave, label, unidad}`). */
data class CustomBodyMetric(val key: String, val label: String, val unit: String)

/**
 * A metric the UI can chart. [minValue]..[maxValue] are the storable bounds in the storage unit.
 * Only the PWA's predefined set plus its custom metrics exist: the wire `tipo` is always one of
 * those keys (or an unknown key synced from another device, shown with its key as label).
 */
data class BodyMetricDef(
    val key: String,
    val label: String,
    val unit: String,
    val minValue: Double,
    val maxValue: Double,
    val predefined: Boolean,
) {
    val isWeight: Boolean get() = key == BodyMetrics.WEIGHT
}

/** Predefined catalog (`METRICAS_PREDEF` / `ORDEN_PREDEF` in `lib/body-metrics.ts`), custom-metric rules and `slugify`. */
object BodyMetrics {
    const val WEIGHT = "peso"

    /** Synced `userSettings` row id that holds the custom metric list (`CLAVE_PERSONALIZADAS`). */
    const val CUSTOM_SETTING_KEY = "metricasPersonalizadas"

    const val CUSTOM_MAX = 30
    const val NAME_MAX = 40
    const val UNIT_MAX = 12

    const val WEIGHT_MIN_KG = 20.0
    const val WEIGHT_MAX_KG = 400.0
    const val LENGTH_MIN_CM = 10.0
    const val LENGTH_MAX_CM = 300.0
    const val CUSTOM_MIN = 0.01
    const val CUSTOM_MAX_VALUE = 100_000.0

    private fun length(key: String, label: String) = BodyMetricDef(key, label, "cm", LENGTH_MIN_CM, LENGTH_MAX_CM, predefined = true)

    /** In presentation order (weight first). */
    val predefined: List<BodyMetricDef> = listOf(
        BodyMetricDef(WEIGHT, "Peso", "kg", WEIGHT_MIN_KG, WEIGHT_MAX_KG, predefined = true),
        length("cintura", "Cintura"),
        length("cadera", "Cadera"),
        length("pecho", "Pecho"),
        length("hombros", "Hombros"),
        length("biceps", "Bíceps"),
        length("antebrazo", "Antebrazo"),
        length("muslo", "Muslo"),
        length("pantorrilla", "Pantorrilla"),
        length("cuello", "Cuello"),
    )

    private val byKey = predefined.associateBy { it.key }
    private val combining = Regex("[̀-ͯ]")
    private val nonAlnum = Regex("[^a-z0-9]+")

    /** Stable key from a free name: no accents, lowercase, hyphens (`slugify`). */
    fun slugify(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(combining, "").lowercase(Locale.ROOT).trim()
            .replace(nonAlnum, "-").trim('-')

    fun custom(metric: CustomBodyMetric) =
        BodyMetricDef(metric.key, metric.label, metric.unit, CUSTOM_MIN, CUSTOM_MAX_VALUE, predefined = false)

    /** Predefined, then custom, then the defensive fallback (key as label, no unit) like `resolverMetrica`. */
    fun resolve(key: String, custom: List<CustomBodyMetric>): BodyMetricDef =
        byKey[key] ?: custom.firstOrNull { it.key == key }?.let(::custom)
            ?: BodyMetricDef(key, key, "", CUSTOM_MIN, CUSTOM_MAX_VALUE, predefined = false)

    /** True when [key] can be written: predefined or in [custom]. */
    fun isKnown(key: String, custom: List<CustomBodyMetric>): Boolean = key in byKey || custom.any { it.key == key }

    /** What [name]/[unit] would create, or why not. Pure; the repository re-checks inside its transaction. */
    fun checkCustom(name: String, unit: String, existing: List<CustomBodyMetric>): CustomMetricCheck {
        val label = name.trim()
        val symbol = unit.trim()
        val key = slugify(label)
        return when {
            key.isEmpty() -> CustomMetricCheck.Invalid(CustomMetricProblem.NAME_EMPTY)
            label.length > NAME_MAX -> CustomMetricCheck.Invalid(CustomMetricProblem.NAME_TOO_LONG)
            symbol.length > UNIT_MAX -> CustomMetricCheck.Invalid(CustomMetricProblem.UNIT_TOO_LONG)
            key in byKey -> CustomMetricCheck.Invalid(CustomMetricProblem.RESERVED)
            existing.any { it.key == key } -> CustomMetricCheck.Existing(existing.first { it.key == key })
            existing.size >= CUSTOM_MAX -> CustomMetricCheck.Invalid(CustomMetricProblem.LIMIT)
            else -> CustomMetricCheck.New(CustomBodyMetric(key, label, symbol))
        }
    }
}

enum class CustomMetricProblem { NAME_EMPTY, NAME_TOO_LONG, UNIT_TOO_LONG, RESERVED, LIMIT }

sealed interface CustomMetricCheck {
    data class New(val metric: CustomBodyMetric) : CustomMetricCheck
    /** Same key already exists: adding is idempotent and returns the stored one (like the PWA). */
    data class Existing(val metric: CustomBodyMetric) : CustomMetricCheck
    data class Invalid(val problem: CustomMetricProblem) : CustomMetricCheck
}

enum class BodyValueError { REQUIRED, NOT_A_NUMBER, NEGATIVE, TOO_SMALL, TOO_LARGE }

sealed interface BodyParse {
    /** [storage] is in the storage unit (kg/cm/custom), rounded to 2 decimals. */
    data class Valid(val storage: Double) : BodyParse
    data class Invalid(val error: BodyValueError, val min: Double? = null, val max: Double? = null) : BodyParse
}

enum class BodyDateError { IN_FUTURE, TOO_OLD }

/**
 * Strict entry rules. User text is never parsed with the PWA-compatible permissive
 * [com.chrisgoac.gymlog.domain.math.Numbers] parsers (they accept "Infinity", "0x10", "1e3").
 */
object BodyValidation {
    /** Same tolerance as the history editor: the server clock plus five minutes. */
    const val FUTURE_TOLERANCE_MILLIS = 5L * 60 * 1000

    /** 2000-01-01T00:00Z: older dates are almost certainly typos. */
    const val MIN_DATE_MILLIS = 946_684_800_000L

    private const val MAX_TEXT = 12
    private val DECIMAL = Regex("""^(\d*)(?:[.,](\d*))?$""")
    private val SIGNED = Regex("""^-\s*\d+(?:[.,]\d*)?$""")

    /**
     * [text] typed in the display unit ([weightUnit] applies to the weight metric only) -> a value to store.
     * Comma or dot decimal separator; the range check happens on the stored (kg/cm) value.
     */
    fun parseValue(text: String, metric: BodyMetricDef, weightUnit: WeightUnit = WeightUnit.KG): BodyParse {
        val raw = text.trim()
        if (raw.isEmpty()) return BodyParse.Invalid(BodyValueError.REQUIRED)
        if (SIGNED.matches(raw)) return BodyParse.Invalid(BodyValueError.NEGATIVE)
        val match = DECIMAL.matchEntire(raw) ?: return BodyParse.Invalid(BodyValueError.NOT_A_NUMBER)
        val (whole, fraction) = match.destructured
        if (whole.isEmpty() && fraction.isEmpty()) return BodyParse.Invalid(BodyValueError.NOT_A_NUMBER)
        if (raw.length > MAX_TEXT) return BodyParse.Invalid(BodyValueError.TOO_LARGE, max = displayBound(metric.maxValue, metric, weightUnit))
        val typed = "${whole.ifEmpty { "0" }}.${fraction.ifEmpty { "0" }}".toDouble()
        val unit = if (metric.isWeight) weightUnit else WeightUnit.KG
        val stored = round2(unit.toKg(typed))
        return when {
            stored < metric.minValue -> BodyParse.Invalid(BodyValueError.TOO_SMALL, min = displayBound(metric.minValue, metric, weightUnit))
            stored > metric.maxValue -> BodyParse.Invalid(BodyValueError.TOO_LARGE, max = displayBound(metric.maxValue, metric, weightUnit))
            else -> BodyParse.Valid(stored)
        }
    }

    /** A stored bound in the unit the user types in, to one decimal (for the error message). */
    private fun displayBound(storage: Double, metric: BodyMetricDef, weightUnit: WeightUnit): Double =
        if (metric.isWeight) weightUnit.display(storage) else storage

    /** Guard for values about to be written (repository, before the transaction). */
    fun isStorable(metric: BodyMetricDef, value: Double): Boolean = value.isFinite() && value in metric.minValue..metric.maxValue

    fun checkDate(at: Long, nowMillis: Long): BodyDateError? = when {
        at > nowMillis + FUTURE_TOLERANCE_MILLIS -> BodyDateError.IN_FUTURE
        at < MIN_DATE_MILLIS -> BodyDateError.TOO_OLD
        else -> null
    }

    private fun round2(value: Double): Double = BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()
}

enum class BodyProblem { UNKNOWN_METRIC, VALUE_OUT_OF_RANGE, DATE_IN_FUTURE, DATE_TOO_OLD }

/** The write was refused before touching Room; nothing was changed. */
class InvalidBodyMeasurementException(val problem: BodyProblem) : IllegalArgumentException("Invalid body measurement: $problem")

class InvalidCustomMetricException(val problem: CustomMetricProblem) : IllegalArgumentException("Invalid custom metric: $problem")

/** The measurement to edit or delete is gone (deleted elsewhere / synced away). */
class BodyMeasurementMissingException(val id: String) : IllegalStateException("Body measurement $id no longer exists")

/**
 * Body measurements of the open account. Every write is ONE local mutation (domain row + outbox
 * entry, tombstones only) and validated before the transaction starts.
 */
interface BodyRepository {
    /** The account's custom metrics, in creation order. */
    fun observeCustomMetrics(): Flow<List<CustomBodyMetric>>

    /** Metric keys that have at least one live measurement (includes keys this device cannot name yet). */
    fun observeMeasuredTypes(): Flow<Set<String>>

    /** Live measurements of [type], chronological: oldest first, same-day ties by write order. */
    fun observeMeasurements(type: String): Flow<List<BodyMeasurement>>

    /** Stores [value] (storage unit) at [at]; returns the new id. */
    suspend fun addMeasurement(type: String, value: Double, at: Long): String

    suspend fun updateMeasurement(id: String, value: Double, at: Long)

    /** Tombstones the measurement; idempotent if it is already gone. */
    suspend fun deleteMeasurement(id: String)

    /** Idempotent by key; throws [InvalidCustomMetricException]. */
    suspend fun addCustomMetric(name: String, unit: String): CustomBodyMetric
}
