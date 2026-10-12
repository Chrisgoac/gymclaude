package com.chrisgoac.gymlog.domain.routine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the cases shared with the PWA (contracts/domain/routine-rotation.json, read in place). */
class RoutineRotationTest {
    private fun cases(): JsonArray {
        val dir = checkNotNull(System.getProperty("gymlog.domainContractsDir")) { "gymlog.domainContractsDir not set" }
        val root = Json.parseToJsonElement(File(dir, "routine-rotation.json").readText()).jsonObject
        return root.getValue("cases").jsonArray
    }

    private fun JsonObject.nullableString(key: String): String? = getValue(key).takeIf { it !is JsonNull }?.jsonPrimitive?.content

    @Test
    fun sharedFixtureCases() {
        val all = cases()
        assertTrue("fixture has cases", all.isNotEmpty())
        all.forEach { element ->
            val case = element.jsonObject
            val routines = case.getValue("routines").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(
                case.getValue("name").jsonPrimitive.content,
                case.nullableString("expected"),
                nextRoutineId(routines, case.nullableString("last")),
            )
        }
    }
}
