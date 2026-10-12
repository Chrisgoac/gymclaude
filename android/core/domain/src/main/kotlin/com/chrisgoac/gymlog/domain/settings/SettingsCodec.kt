package com.chrisgoac.gymlog.domain.settings

import com.chrisgoac.gymlog.domain.body.CustomBodyMetric
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.workout.ProgressionSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.ceil

/** Row ids (= keys) of the synced `userSettings` table the PWA writes; the value is always JSON text (`JSON.stringify`). */
object SettingKeys {
    const val WEEKLY_TARGET = "objetivoSemanal"
    const val VOLUME_GOALS = "objetivosVolumen"
    const val PROGRESSION_MODE = "modoProgresion"
    const val INCREMENTS = "incrementos"
    const val CUSTOM_METRICS = "metricasPersonalizadas"
}

/** Default weekly session goal (PWA `useSetting('objetivoSemanal', 3)`). */
const val DEFAULT_WEEKLY_TARGET = 3

/**
 * The synced settings the features know about, decoded tolerantly. Anything else in the table is
 * never exposed here (and never rewritten by [SettingsRepository]).
 */
data class TrainingSettings(
    val weeklyTarget: Int = DEFAULT_WEEKLY_TARGET,
    val progression: ProgressionSettings = ProgressionSettings(),
    val volumeGoals: Map<MuscleGroup, Double> = emptyMap(),
)

/**
 * Pure decoding of the PWA's setting values, the single place every reader (stats, achievements,
 * body, settings) goes through so they agree. Decoding never throws: bad JSON is "absent".
 */
object SettingsCodec {
    private fun parse(raw: String?): JsonElement? = raw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

    /** `objetivoSemanal` (JSON number), default 3; a fractional goal counts as its ceiling (sessions are whole). */
    fun weeklyTarget(raw: String?): Int {
        val value = (parse(raw) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        return value?.let { ceil(it).toInt() } ?: DEFAULT_WEEKLY_TARGET
    }

    /** `objetivosVolumen` (JSON object group -> kg·rep); unknown groups and non-numbers are ignored. */
    fun volumeGoals(raw: String?): Map<MuscleGroup, Double> {
        val goals = parse(raw) as? JsonObject ?: return emptyMap()
        return goals.entries.mapNotNull { (key, value) ->
            val group = MuscleGroup.entries.firstOrNull { it.wire == key } ?: return@mapNotNull null
            val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: return@mapNotNull null
            group to number
        }.toMap()
    }

    /** `metricasPersonalizadas` (JSON array of `{clave, label, unidad}`); malformed entries are skipped. */
    fun customMetrics(raw: String?): List<CustomBodyMetric> {
        val array = parse(raw) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val key = obj.string("clave")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CustomBodyMetric(key, obj.string("label") ?: key, obj.string("unidad").orEmpty())
        }.distinctBy { it.key }
    }

    fun training(rows: Map<String, String>): TrainingSettings = TrainingSettings(
        weeklyTarget = weeklyTarget(rows[SettingKeys.WEEKLY_TARGET]),
        progression = ProgressionSettings.fromStored(rows[SettingKeys.PROGRESSION_MODE], rows[SettingKeys.INCREMENTS]),
        volumeGoals = volumeGoals(rows[SettingKeys.VOLUME_GOALS]),
    )

    /** `JSON.stringify` of a JS number: whole values carry no ".0", so the PWA's bytes match. */
    fun number(value: Double): JsonPrimitive =
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
