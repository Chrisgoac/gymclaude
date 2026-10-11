package com.chrisgoac.gymlog.domain.achievement

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
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
 * Runs contracts/domain/achievement-cases.json (generated from the PWA's lib/logros.ts and
 * lib/reconciliar-logros.ts by scripts/export-domain-fixtures.mjs) against [AchievementRules]. The scenarios'
 * metrics come from the PWA's `getLogroMetricas`; the Room-side computation of those metrics is covered by
 * OfflineAchievementRepositoryTest in core:data.
 */
class AchievementParityTest {
    private val fixture: JsonObject = run {
        val dir = File(checkNotNull(System.getProperty("gymlog.domainContractsDir")) { "gymlog.domainContractsDir not set" })
        Json.parseToJsonElement(File(dir, "achievement-cases.json").readText()).jsonObject
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonElement.keys() = jsonArray.map { it.jsonPrimitive.content }

    private fun metricsOf(json: JsonObject) = AchievementMetrics(
        sessions = json.getValue("sesionesTotales").jsonPrimitive.int,
        volume = json.getValue("volumenTotal").jsonPrimitive.double,
        personalRecords = json.getValue("prsTotales").jsonPrimitive.int,
        bestStreakWeeks = json.getValue("mejorRacha").jsonPrimitive.int,
        completedMesocycles = json.getValue("mesociclosCompletados").jsonPrimitive.int,
    )

    @Test
    fun catalogMatchesThePwa() {
        val expected = fixture.getValue("catalog").jsonArray.map { it.jsonObject }
        assertEquals(expected.map { it.str("clave") }, AchievementRules.catalog.map { it.key })
        assertEquals(expected.map { it.str("titulo") }, AchievementRules.catalog.map { it.title })
        assertEquals(expected.map { it.str("descripcion") }, AchievementRules.catalog.map { it.description })
        assertEquals(fixture.str("canonicalIdPrefix"), AchievementRules.CANONICAL_ID_PREFIX)
    }

    @Test
    fun evaluateMatchesThePwa() {
        val cases = fixture.getValue("evaluate").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val metrics = metricsOf(c.getValue("metrics").jsonObject)
            assertEquals("$metrics", c.getValue("expected").keys(), AchievementRules.evaluate(metrics))
        }
    }

    @Test
    fun streakMatchesThePwa() {
        val cases = fixture.getValue("streak").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val weeks = c.getValue("weeks").jsonArray.map { it.jsonObject }.map {
                WeekSessions(it.getValue("inicioTs").jsonPrimitive.long, it.getValue("sesiones").jsonPrimitive.int)
            }
            val streak = AchievementRules.streak(weeks, c.getValue("target").jsonPrimitive.int, c.getValue("currentWeekStart").jsonPrimitive.long)
            val expected = c.getValue("expected").jsonObject
            assertEquals(c.str("name"), expected.getValue("actual").jsonPrimitive.int to expected.getValue("mejor").jsonPrimitive.int, streak.current to streak.best)
        }
    }

    private fun rowsOf(array: JsonElement) = array.jsonArray.map { it.jsonObject }.map {
        StoredAchievement(it.str("id"), it.str("key"), unlockedAt = 0, deleted = it.str("state") == "tombstoned")
    }

    /** Applies [plan] the way every store must: revive/create the unlock rows, tombstone the live ones. */
    private fun apply(stored: List<StoredAchievement>, plan: AchievementPlan): List<StoredAchievement> {
        val byId = stored.associateBy { it.id }.toMutableMap()
        plan.unlocks.forEach { u ->
            assertEquals("revive only existing rows (${u.key})", u.revive, u.id in byId)
            byId[u.id] = StoredAchievement(u.id, u.key, unlockedAt = 1, deleted = false)
        }
        plan.tombstones.forEach { byId[it.id] = it.copy(deleted = true) }
        return byId.values.sortedBy { it.id }
    }

    @Test
    fun scenariosReconcileAsApproved() {
        val scenarios = fixture.getValue("scenarios").jsonArray.map { it.jsonObject }
        assertTrue(scenarios.isNotEmpty())
        for (scenario in scenarios) {
            var stored = rowsOf(scenario.getValue("initialStored"))
            for (step in scenario.getValue("steps").jsonArray.map { it.jsonObject }) {
                val label = "${scenario.str("name")}: ${step.str("label")}"
                val expected = step.getValue("expected").jsonObject
                val metrics = metricsOf(expected.getValue("metrics").jsonObject)
                assertEquals(label, expected.getValue("met").keys(), AchievementRules.evaluate(metrics))

                val plan = AchievementRules.plan(metrics, stored)
                assertEquals(label, expected.getValue("decisions").jsonObject.mapValues { it.value.jsonPrimitive.content }, plan.decisions.mapValues { it.value.wire })
                stored = apply(stored, plan)
                assertEquals(label, rowsOf(expected.getValue("storedAfter")).map { Triple(it.id, it.key, it.deleted) }, stored.map { Triple(it.id, it.key, it.deleted) })

                // Idempotent: the same evidence over the new state decides nothing to write.
                assertTrue("$label: second run must be a no-op", AchievementRules.plan(metrics, stored).isNoOp)
            }
        }
    }

    @Test
    fun unknownKeysAreNeverTouched() {
        val future = StoredAchievement("f1", "logro-del-futuro", 0, deleted = false)
        val plan = AchievementRules.plan(AchievementMetrics.NONE, listOf(future))
        assertTrue(plan.isNoOp)
    }
}
