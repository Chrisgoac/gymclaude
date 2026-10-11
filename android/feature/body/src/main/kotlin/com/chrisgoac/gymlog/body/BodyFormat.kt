package com.chrisgoac.gymlog.body

import android.content.res.Resources
import com.chrisgoac.gymlog.domain.body.BodyMetricDef
import com.chrisgoac.gymlog.domain.math.WeightUnit
import com.chrisgoac.gymlog.feature.body.R
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Spanish number and date formatting (the app is Spanish-only), independent of the device locale. */
internal object BodyFormat {
    private val ES: Locale = Locale.forLanguageTag("es-ES")
    private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", ES)
    private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", ES)

    /** "80,5" / "80,25": at most [decimals] decimals, comma separator, no grouping. */
    fun number(value: Double, decimals: Int = 2): String =
        NumberFormat.getNumberInstance(ES).apply {
            maximumFractionDigits = decimals
            minimumFractionDigits = 0
            isGroupingUsed = false
            roundingMode = RoundingMode.HALF_UP
        }.format(value)

    /** Text for an editable field: the same number, comma decimals (the strict parser accepts comma or dot). */
    fun input(value: Double): String = number(value)

    /** Zero-padded "dd/MM" in the user's zone ("03/08"), the chart label. */
    fun shortDate(millis: Long, zone: ZoneId): String = SHORT_DATE.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** "3 ago 2026". */
    fun longDate(millis: Long, zone: ZoneId): String = LONG_DATE.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun longDate(date: java.time.LocalDate): String = LONG_DATE.format(date)

    /** A stored value as shown: weight follows the display unit (kg storage), everything else is untouched. */
    fun display(def: BodyMetricDef, storage: Double, unit: WeightUnit): Double = if (def.isWeight) unit.fromKg(storage) else storage

    fun unitSymbol(def: BodyMetricDef, unit: WeightUnit): String = if (def.isWeight) unit.symbol else def.unit

    /** "80,5 kg" (the unit is dropped for unit-less custom metrics). */
    fun valueWithUnit(def: BodyMetricDef, storage: Double, unit: WeightUnit): String {
        val symbol = unitSymbol(def, unit)
        val number = number(display(def, storage, unit))
        return if (symbol.isEmpty()) number else "$number $symbol"
    }

    /**
     * Unit as TalkBack should say it: never a lone letter ("l" -> "unidad l"), symbols spelled out
     * ("%" -> "por ciento", "cm" -> "centímetros").
     */
    fun spokenUnit(symbol: String, resources: Resources): String = when {
        symbol.isEmpty() -> ""
        symbol == "kg" -> resources.getString(R.string.body_unit_kg_spoken)
        symbol == "lb" -> resources.getString(R.string.body_unit_lb_spoken)
        symbol == "cm" -> resources.getString(R.string.body_unit_cm_spoken)
        symbol == "%" -> resources.getString(R.string.body_unit_percent_spoken)
        symbol.length == 1 -> resources.getString(R.string.body_unit_other_spoken, symbol)
        else -> symbol
    }

    fun spokenValue(def: BodyMetricDef, storage: Double, unit: WeightUnit, resources: Resources): String {
        val spoken = spokenUnit(unitSymbol(def, unit), resources)
        val number = number(display(def, storage, unit))
        return if (spoken.isEmpty()) number else "$number $spoken"
    }
}
