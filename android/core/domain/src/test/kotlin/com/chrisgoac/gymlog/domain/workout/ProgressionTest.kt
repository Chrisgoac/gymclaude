package com.chrisgoac.gymlog.domain.workout

import com.chrisgoac.gymlog.domain.exercise.Equipment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the cases shared with the PWA (contracts/domain/progression.json, read in place). */
class ProgressionTest {
    private val root: JsonObject by lazy {
        val dir = checkNotNull(System.getProperty("gymlog.domainContractsDir")) { "gymlog.domainContractsDir not set" }
        Json.parseToJsonElement(File(dir, "progression.json").readText()).jsonObject
    }

    private fun cases(key: String): JsonArray = root.getValue(key).jsonArray.also { assertTrue("$key has cases", it.isNotEmpty()) }

    private fun JsonObject.opt(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

    private fun JsonObject.name() = getValue("name").jsonPrimitive.content

    private fun JsonElement.suggestion(): Suggestion {
        val o = jsonObject
        return Suggestion(
            weight = o.getValue("pesoSugerido").jsonPrimitive.double,
            reps = o.getValue("repsSugeridas").jsonPrimitive.int,
            reason = SuggestionReason.fromWire(o.getValue("motivo").jsonPrimitive.content),
        )
    }

    @Test
    fun inferStepMatchesPwa() {
        cases("inferirSalto").forEach { element ->
            val case = element.jsonObject
            val step = Progression.inferStep(
                weights = case.getValue("pesos").jsonArray.map { it.jsonPrimitive.double },
                equipment = Equipment.fromWire(case.getValue("equipamiento").jsonPrimitive.content),
                defaults = Progression.INCREMENT_DEFAULTS,
                override = case.opt("override")?.jsonPrimitive?.double,
            )
            assertEquals(case.name(), case.getValue("expected").jsonPrimitive.double, step, 0.0)
        }
    }

    @Test
    fun suggestMatchesPwa() {
        cases("calcularSugerencia").forEach { element ->
            val case = element.jsonObject
            val input = case.getValue("input").jsonObject
            val target = input.opt("objetivo")?.jsonObject?.let {
                RepTarget(repsMax = it.opt("repsObjetivo")?.jsonPrimitive?.int, repsMin = it.opt("repsObjetivoMin")?.jsonPrimitive?.int)
            }
            val actual = Progression.suggest(
                mode = ProgressionMode.fromWire(input.getValue("modo").jsonPrimitive.content),
                last = input.opt("ultimo")?.jsonArray?.map {
                    WeightReps(it.jsonObject.getValue("peso").jsonPrimitive.double, it.jsonObject.getValue("reps").jsonPrimitive.int)
                },
                target = target,
                step = input.getValue("salto").jsonPrimitive.double,
                bodyweight = input.getValue("esCorporal").jsonPrimitive.content.toBooleanStrict(),
            )
            assertEquals(case.name(), case.getValue("expected").suggestion(), actual)
        }
    }

    @Test
    fun describeMatchesPwa() {
        cases("describeMotivo").forEach { element ->
            val case = element.jsonObject
            assertEquals(
                case.name(),
                case.opt("expected")?.jsonPrimitive?.content,
                Progression.describe(case.getValue("sugerencia").suggestion(), case.getValue("salto").jsonPrimitive.double),
            )
        }
    }

    @Test
    fun unknownModeFallsBackToObjetivoLikeThePwa() {
        assertEquals(ProgressionMode.OBJETIVO, ProgressionMode.fromWire("turbo"))
    }

    @Test
    fun settingsParseStoredJsonValuesAndMergeIncrementsOverDefaults() {
        val settings = ProgressionSettings.fromStored(mode = "\"doble\"", increments = """{"barra":1.25,"maquina":"x","raro":3}""")
        assertEquals(ProgressionMode.DOBLE, settings.mode)
        assertEquals(1.25, settings.increments.getValue(Equipment.BARRA), 0.0)
        assertEquals(5.0, settings.increments.getValue(Equipment.MAQUINA), 0.0)
        assertEquals(ProgressionSettings(), ProgressionSettings.fromStored(mode = "not json", increments = "[1]"))
        assertEquals(ProgressionSettings(), ProgressionSettings.fromStored(mode = null, increments = null))
    }
}
