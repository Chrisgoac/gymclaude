package com.chrisgoac.gymlog.domain.achievement

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReconcileAchievementsUseCaseTest {
    private class FakeStore(var metrics: AchievementMetrics, var rows: List<StoredAchievement> = emptyList()) : AchievementStore {
        var writes = 0
        var writeResult: Boolean? = null
        var unlockedAt = -1L
        var seenNow: Instant? = null
        var seenZone: ZoneId? = null

        override suspend fun <T> transaction(block: suspend AchievementTransaction.() -> T): T = object : AchievementTransaction {
            override suspend fun metrics(now: Instant, zone: ZoneId): AchievementMetrics {
                seenNow = now
                seenZone = zone
                return this@FakeStore.metrics
            }

            override suspend fun stored() = rows

            override suspend fun write(plan: AchievementPlan, unlockedAt: Long): Boolean {
                if (writeResult != null) return writeResult!!
                writes++
                this@FakeStore.unlockedAt = unlockedAt
                val byId = rows.associateBy { it.id }.toMutableMap()
                plan.unlocks.forEach { byId[it.id] = StoredAchievement(it.id, it.key, unlockedAt, deleted = false) }
                plan.tombstones.forEach { byId[it.id] = it.copy(deleted = true) }
                rows = byId.values.sortedBy { it.id }
                return true
            }
        }.block()
    }

    private val ten = AchievementMetrics(sessions = 10, volume = 0.0, personalRecords = 1, bestStreakWeeks = 0, completedMesocycles = 0)
    private fun useCase(store: FakeStore, at: Long = 5_000, zone: ZoneId = ZoneId.of("Europe/Madrid")) = ReconcileAchievementsUseCase(store, { at }, { zone })

    @Test fun `unlocks what is earned using the injected clock and zone`() = runTest {
        val store = FakeStore(ten)
        val result = useCase(store, at = 7_000).invoke()

        assertEquals(listOf("sesiones-10"), result.unlocked)
        assertTrue(result.wrote)
        assertEquals(7_000L, store.unlockedAt)
        assertEquals(Instant.ofEpochMilli(7_000), store.seenNow)
        assertEquals(ZoneId.of("Europe/Madrid"), store.seenZone)
        assertEquals(listOf(AchievementRules.canonicalId("sesiones-10")), store.rows.map { it.id })
    }

    @Test fun `a second run with the same evidence writes nothing`() = runTest {
        val store = FakeStore(ten)
        val reconcile = useCase(store)
        reconcile()
        val second = reconcile()

        assertFalse(second.wrote)
        assertEquals(1, store.writes)
        assertEquals(AchievementDecision.RETAIN, second.decisions.getValue("sesiones-10"))
    }

    @Test fun `no evidence and nothing stored never writes`() = runTest {
        val store = FakeStore(AchievementMetrics.NONE)
        assertFalse(useCase(store).invoke().wrote)
        assertEquals(0, store.writes)
    }

    @Test fun `without a local edit lost evidence is retained, never tombstoned`() = runTest {
        val store = FakeStore(ten)
        val reconcile = useCase(store)
        reconcile()
        store.metrics = ten.copy(sessions = 9)

        val result = reconcile()

        assertFalse(result.wrote)
        assertEquals(AchievementDecision.RETAIN, result.decisions.getValue("sesiones-10"))
        assertFalse(store.rows.single().deleted)
    }

    @Test fun `wrote is false when the store wrote nothing`() = runTest {
        val store = FakeStore(ten).apply { writeResult = false }
        assertFalse(useCase(store).invoke().wrote)
    }

    @Test fun `lost evidence tombstones and regained evidence revives the same row`() = runTest {
        val store = FakeStore(ten)
        val reconcile = useCase(store)
        reconcile()
        val id = store.rows.single().id

        store.metrics = ten.copy(sessions = 9)
        assertEquals(listOf("sesiones-10"), reconcile(allowTombstone = true).tombstoned)
        assertTrue(store.rows.single().deleted)

        store.metrics = ten
        assertEquals(listOf("sesiones-10"), reconcile().unlocked)
        assertEquals(id, store.rows.single().id)
        assertFalse(store.rows.single().deleted)
        assertEquals(3, store.writes)
    }
}
