package com.chrisgoac.gymlog.data.sync

import com.chrisgoac.gymlog.model.ConflictReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs contracts/v1/fixtures/conflict-cases.json (read in place) against [ConflictResolver]; the
 * server runs the same file in lib/sync/server-merge.test.ts.
 */
class ConflictResolverTest {
    private val fixture: JsonObject =
        Json.parseToJsonElement(File(System.getProperty("gymlog.fixturesDir"), "conflict-cases.json").readText()).jsonObject

    @Test
    fun everyMergeCaseMatchesTheSharedFixture() {
        val cases = fixture.getValue("mergeCases").jsonArray
        assertTrue(cases.size >= 16)
        cases.forEach { element ->
            val case = element.jsonObject
            val name = case.str("name")!!
            val incoming = case.getValue("incoming").jsonObject
            val tuple = VersionTuple(incoming.getValue("updatedAt").jsonPrimitive.long, incoming.str("deviceId")!!, incoming.str("mutationId")!!)
            val existing = case["existing"]?.takeUnless { it is JsonNull }?.jsonObject?.let { row ->
                StoredVersion(
                    updatedAt = row.getValue("updatedAt").jsonPrimitive.long,
                    deviceId = row.str("deviceId"),
                    mutationId = row.str("mutationId"),
                    serverVersion = row.str("serverVersion")?.toLong(),
                )
            }
            val base = case.str("baseServerVersion")?.toLong()
            val expected = case.getValue("expected").jsonObject

            val decision = ConflictResolver.decide(existing, tuple, base)

            val expectedCompare = expected["compare"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
            if (existing == null) {
                assertEquals("$name: compare", null, expectedCompare)
            } else {
                val stored = VersionTuple(existing.updatedAt, existing.deviceId ?: "", existing.mutationId ?: "")
                assertEquals("$name: compare", expectedCompare, ConflictResolver.compare(tuple, stored))
            }
            assertEquals("$name: apply", expected.getValue("apply").jsonPrimitive.content.toBooleanStrict(), decision.apply)
            assertEquals("$name: conflict", expected.str("conflict")?.let(ConflictReason::fromWire), decision.conflict)
        }
    }

    @Test
    fun stringsCompareByUtf16CodeUnitsNotLocale() {
        // U+FF5E (one code unit) sorts after U+1F600 (surrogate pair starting at U+D83D) in UTF-16,
        // although its code point is smaller; lowercase sorts after uppercase.
        val a = VersionTuple(1, "😀", "m")
        val b = VersionTuple(1, "～", "m")
        assertEquals(-1, ConflictResolver.compare(a, b))
        assertEquals(1, ConflictResolver.compare(VersionTuple(1, "a", "m"), VersionTuple(1, "B", "m")))
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.takeUnless { it is JsonNull }?.let { (it as JsonElement).jsonPrimitive.content }
}
