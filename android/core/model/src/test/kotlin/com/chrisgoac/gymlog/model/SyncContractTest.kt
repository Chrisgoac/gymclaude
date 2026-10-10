package com.chrisgoac.gymlog.model

import java.io.File
import java.math.BigDecimal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncContractTest {
    private fun fixtureText(name: String): String =
        File(System.getProperty("gymlog.fixturesDir"), name).readText()

    private fun fixtureTree(name: String): JsonElement = Json.parseToJsonElement(fixtureText(name))

    /** Numbers compare by value (100 == 100.0); everything else structurally. */
    private fun normalize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { normalize(it.value) })
        is JsonArray -> JsonArray(e.map(::normalize))
        is JsonNull -> e
        is JsonPrimitive ->
            if (e.isString) e
            else e.content.toBooleanStrictOrNull()?.let { e }
                ?: JsonPrimitive(BigDecimal(e.content).stripTrailingZeros().toPlainString())
    }

    private inline fun <reified T> assertRoundTrips(name: String): T {
        val decoded = ContractJson.decodeFromString<T>(fixtureText(name))
        val reencoded = ContractJson.encodeToJsonElement(kotlinx.serialization.serializer<T>(), decoded)
        assertEquals(normalize(fixtureTree(name)), normalize(reencoded), "$name must round-trip")
        return decoded
    }

    @Test
    fun bootstrapFixtureCoversAllFourteenTablesLiveAndTombstone() {
        val page = assertRoundTrips<PullResponse>("bootstrap-all-entities.json")
        assertEquals(28, page.changes.size)
        assertEquals(SyncTable.entries.toSet(), page.changes.map { it.table }.toSet())
        for (table in SyncTable.entries) {
            val rows = page.changes.filter { it.table == table }
            assertEquals(1, rows.count { it.record.deletedAt == null }, table.wire)
            assertEquals(1, rows.count { it.record.deletedAt != null }, table.wire)
        }
        assertEquals(false, page.hasMore)
    }

    @Test
    fun pullPageFixtureRoundTripsWithLongServerVersions() {
        val page = assertRoundTrips<PullResponse>("pull-page.json")
        assertEquals(listOf(101L, 102L), page.changes.map { it.serverVersion })
        assertTrue(page.hasMore)
        assertEquals("MTAy", page.cursor)
        val meso = page.changes[1]
        assertIs<MesocycleChange>(meso)
        assertEquals(1767225700000L, meso.record.deletedAt)
        assertEquals(6, meso.record.semanas)
    }

    @Test
    fun pushFixtureRoundTripsAndKeepsNullableBaseVersion() {
        val push = assertRoundTrips<PushRequest>("push-valid.json")
        val ops = push.mutations.flatMap { it.operations }
        assertNull(ops[0].baseServerVersion)
        assertEquals(42L, ops[1].baseServerVersion)
        assertIs<ExerciseOperation>(ops[0])
    }

    @Test
    fun serverOwnedFieldsInRecordsAreRejectedByStrictJson() {
        val failure = assertFailsWith<SerializationException> {
            ContractJson.decodeFromString<PushRequest>(fixtureText("push-invalid-server-field.json"))
        }
        assertTrue(failure.message!!.contains("userId"), failure.message)
        // The same fixture minus the server-owned keys decodes, so those keys are the only cause.
        val cleaned = fixtureText("push-invalid-server-field.json")
            .replace(Regex("\"userId\":\\s*\"[^\"]*\",?"), "")
            .replace(Regex("\"serverVersion\":\\s*\"[^\"]*\",?"), "")
        assertEquals(1, ContractJson.decodeFromString<PushRequest>(cleaned).mutations.size)
    }

    @Test
    fun responseJsonToleratesFutureServerFieldsButContractJsonDoesNot() {
        val tree = fixtureTree("pull-page.json").jsonObject
        val withExtra = JsonObject(tree + ("futureField" to JsonPrimitive(1))).toString()
        assertEquals(2, ResponseJson.decodeFromString<PullResponse>(withExtra).changes.size)
        assertFailsWith<SerializationException> { ContractJson.decodeFromString<PullResponse>(withExtra) }

        val changes = tree.getValue("changes").jsonArray.toMutableList()
        val first = changes[0].jsonObject
        changes[0] = JsonObject(first + ("extra" to JsonPrimitive("x")))
        val nested = JsonObject(tree + ("changes" to JsonArray(changes))).toString()
        assertEquals(2, ResponseJson.decodeFromString<PullResponse>(nested).changes.size)
    }

    @Test
    fun unknownTableFailsDecodingEvenWithResponseJson() {
        val bad = """{"changes":[{"table":"ghosts","serverVersion":"1","record":{"id":"a","updatedAt":1,"deletedAt":null}}],"cursor":"","hasMore":false}"""
        assertFailsWith<SerializationException> { ResponseJson.decodeFromString<PullResponse>(bad) }
        assertFailsWith<SerializationException> { ContractJson.decodeFromString<PullResponse>(bad) }
    }

    private fun conflictJson(version: String) =
        """{"mutationId":"6f9619ff-8b86-4d11-b42d-00c04fc964ff","table":"gyms","recordId":"g","serverVersion":$version,"reason":"SUPERSEDED"}"""

    @Test
    fun serverVersionIsADecimalStringBounded64Bit() {
        assertEquals(Long.MAX_VALUE, ContractJson.decodeFromString<SyncConflict>(conflictJson("\"9223372036854775807\"")).serverVersion)
        assertEquals(0L, ContractJson.decodeFromString<SyncConflict>(conflictJson("\"0\"")).serverVersion)
        assertTrue(ContractJson.encodeToString(ContractJson.decodeFromString<SyncConflict>(conflictJson("\"9007199254740993\""))).contains("\"9007199254740993\""))
        for (bad in listOf("\"-1\"", "\"01\"", "\"+1\"", "\" 1\"", "\"1.0\"", "\"\"", "\"9223372036854775808\"", "\"99999999999999999999\"", "5", "null")) {
            assertFailsWith<SerializationException>(bad) { ContractJson.decodeFromString<SyncConflict>(conflictJson(bad)) }
        }
    }

    @Test
    fun int32FieldsRejectOutOfRangeValues() {
        val gym = """{"table":"gyms","serverVersion":"1","record":{"id":"g","updatedAt":1,"deletedAt":null,"nombre":"n","orden":2147483648,"archivada":false}}"""
        assertFailsWith<SerializationException> { ContractJson.decodeFromString<SyncChange>(gym) }
    }

    @Test
    fun conflictAndPushResponseDecode() {
        val body = """{"acceptedMutationIds":["6f9619ff-8b86-4d11-b42d-00c04fc964ff"],"conflicts":[${conflictJson("\"7\"")}]}"""
        val response = ResponseJson.decodeFromString<PushResponse>(body)
        assertEquals(SyncTable.GYMS, response.conflicts.single().table)
        assertEquals(ConflictReason.SUPERSEDED, response.conflicts.single().knownReason)
    }

    @Test
    fun unknownConflictReasonDoesNotBreakLenientDecoding() {
        val body = """{"acceptedMutationIds":["6f9619ff-8b86-4d11-b42d-00c04fc964ff"],"conflicts":[${conflictJson("\"7\"").replace("SUPERSEDED", "NEW_THING")}]}"""
        val response = ResponseJson.decodeFromString<PushResponse>(body)
        assertEquals(1, response.acceptedMutationIds.size)
        assertEquals("NEW_THING", response.conflicts.single().reason)
        assertEquals(ConflictReason.Unknown, response.conflicts.single().knownReason)
    }

    // conflict-cases.json (LWW merge vectors) is consumed by Task 7 (core:data), not here.
    @Test
    fun paginationFixtureCursorsAreOpaqueAndPageMathMatches() {
        val cases = fixtureTree("pull-pagination-cases.json").jsonObject
        val valid = cases.getValue("cursorRoundTrips").jsonArray.map {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it.jsonPrimitive.content.toByteArray())
        }
        val invalid = cases.getValue("invalidCursors").jsonArray.map { it.jsonObject.getValue("cursor").jsonPrimitive.content }
        for (cursor in valid + invalid) {
            val body = """{"changes":[],"cursor":${JsonPrimitive(cursor)},"hasMore":false}"""
            val page = ResponseJson.decodeFromString<PullResponse>(body)
            assertEquals(cursor, page.cursor)
            assertEquals(cursor, ContractJson.encodeToJsonElement(PullResponse.serializer(), page).jsonObject.getValue("cursor").jsonPrimitive.content)
        }
        val p = cases.getValue("pagination").jsonObject
        val total = p.getValue("totalChanges").jsonPrimitive.int
        val size = p.getValue("pageSize").jsonPrimitive.int
        val sizes = generateSequence(total) { it - size }.takeWhile { it > 0 }.map { minOf(it, size) }.toList()
        assertEquals(p.getValue("expectedPageSizes").jsonArray.map { it.jsonPrimitive.int }, sizes)
    }

    @Test
    fun apiErrorBodyDecodesAndMapsKnownCodes() {
        val body = """{"error":{"code":"RATE_LIMITED","message":"slow","retryable":true,"details":{"retryAfterSeconds":["12"]}}}"""
        val error = ResponseJson.decodeFromString<ApiErrorBody>(body)
        assertEquals(ApiErrorCode.RATE_LIMITED, error.error.knownCode)
        assertEquals(listOf("12"), error.error.details?.get("retryAfterSeconds"))
        assertEquals(ApiErrorCode.Unknown, ApiErrorCode.fromWire("SOMETHING_NEW"))
        for (code in ApiErrorCode.entries.filter { it != ApiErrorCode.Unknown }) {
            assertEquals(code, ApiErrorCode.fromWire(code.wire))
        }
        assertEquals(15, ApiErrorCode.entries.size - 1)
    }
}
