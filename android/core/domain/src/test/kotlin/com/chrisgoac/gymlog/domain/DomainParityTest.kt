package com.chrisgoac.gymlog.domain

import com.chrisgoac.gymlog.domain.math.BodyEntry
import com.chrisgoac.gymlog.domain.math.BodySummary
import com.chrisgoac.gymlog.domain.math.Numbers
import com.chrisgoac.gymlog.domain.math.OneRepMax
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.domain.time.Periods
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Runs every date/number fixture shared with the PWA (the contracts/domain "-cases" JSON files, read in place;
 * generated from the TypeScript code by scripts/export-domain-fixtures.mjs). progression.json and
 * routine-rotation.json and insight-cases.json have their own runners (ProgressionTest, RoutineRotationTest, InsightsParityTest).
 */
class DomainParityTest {
    private val dir: File = File(checkNotNull(System.getProperty("gymlog.domainContractsDir")) { "gymlog.domainContractsDir not set" })

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(File(dir, name).readText()).jsonObject

    private fun JsonObject.cases(key: String): JsonArray = getValue(key).jsonArray.also { assertTrue("$key has cases", it.isNotEmpty()) }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.instant(key: String) = Instant.ofEpochMilli(getValue(key).jsonPrimitive.long)
    private fun JsonObject.zone(key: String = "zone") = ZoneId.of(str(key))

    /** A fixture number: finite values are JSON numbers, Infinity travels as a string. */
    private fun JsonElement.number(): Double = jsonPrimitive.let { if (it.isString) it.content.toDouble() else it.double }

    @Test
    fun everyFixtureFileHasARunner() {
        val runners = setOf(
            "period-cases.json", "fecha-cases.json", "num-cases.json", "body-stats-cases.json",
            "progression.json", "routine-rotation.json", "insight-cases.json",
        )
        val files = dir.listFiles { f -> f.extension == "json" }!!.map { it.name }.toSet()
        assertEquals("fixture without a runner (add it to DomainParityTest)", emptySet<String>(), files - runners)
    }

    @Test
    fun periodSince() {
        for (c in fixture("period-cases.json").cases("since").map { it.jsonObject }) {
            val period = Period.fromWire(c.str("periodo"))!!
            assertEquals("$c", c.getValue("expected").jsonPrimitive.long, Periods.since(period, c.instant("ahora")).toEpochMilli())
        }
    }

    @Test
    fun weekStart() {
        for (c in fixture("period-cases.json").cases("weekStart").map { it.jsonObject }) {
            assertEquals("$c", c.getValue("expected").jsonPrimitive.long, Periods.weekStart(c.instant("at"), c.zone()).toEpochMilli())
        }
    }

    @Test
    fun formatSeconds() {
        for (c in fixture("fecha-cases.json").cases("formatSegundos").map { it.jsonObject }) {
            assertEquals("$c", c.str("expected"), Periods.formatSeconds(c.getValue("seconds").number()))
        }
    }

    @Test
    fun formatDaysAgo() {
        for (c in fixture("fecha-cases.json").cases("formatHaceDias").map { it.jsonObject }) {
            assertEquals("$c", c.str("expected"), Periods.formatDaysAgo(c.instant("ts"), c.instant("now"), c.zone()))
        }
    }

    private fun parseCases(key: String, parse: (String) -> Double?) {
        for (c in fixture("num-cases.json").cases(key).map { it.jsonObject }) {
            val expected = c.getValue("expected")
            val actual = parse(c.str("text"))
            if (expected is JsonNull) assertNull("$key $c", actual) else assertEquals("$key $c", expected.number(), actual!!, 0.0)
        }
    }

    @Test fun parseIntegerOrNull() = parseCases("parseEnteroOpt", Numbers::parseIntegerOrNull)
    @Test fun parseInteger() = parseCases("parseEntero", Numbers::parseInteger)
    @Test fun parseDecimal() = parseCases("parseDecimal", Numbers::parseDecimal)

    @Test
    fun estimatedOneRepMax() {
        for (c in fixture("num-cases.json").cases("estimar1RM").map { it.jsonObject }) {
            assertEquals("$c", c.getValue("expected").number(), OneRepMax.estimate(c.getValue("peso").number(), c.getValue("reps").jsonPrimitive.int), 0.0)
        }
    }

    @Test
    fun bodySeries() {
        for (c in fixture("body-stats-cases.json").cases("cases").map { it.jsonObject }) {
            val entries = c.getValue("metrics").jsonArray.map { BodyEntry(it.jsonObject.getValue("valor").number(), it.jsonObject.instant("fecha")) }
            val actual = BodySummary.of(entries, c.zone())
            val expected = c.getValue("expected").jsonObject
            val name = c.str("name")
            fun JsonElement.orNull(): Double? = if (this is JsonNull) null else number()
            assertEquals(name, expected.getValue("actual").orNull(), actual.current)
            assertEquals(name, expected.getValue("primero").orNull(), actual.first)
            assertEquals(name, expected.getValue("delta").orNull(), actual.delta)
            val points = expected.getValue("puntos").jsonArray.map { it.jsonObject }
            assertEquals(name, points.map { it.str("fecha") to it.getValue("valor").number() }, actual.points.map { it.label to it.value })
        }
    }
}
