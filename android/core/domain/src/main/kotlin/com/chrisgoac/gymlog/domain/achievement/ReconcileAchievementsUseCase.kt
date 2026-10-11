package com.chrisgoac.gymlog.domain.achievement

import java.time.Instant
import java.time.ZoneId

/** Reads of one reconciliation transaction: the evidence and the stored rows, consistent with each other. */
interface AchievementTransaction {
    /** Milestone evidence of the open account at [now] in [zone] (week boundaries are local). */
    suspend fun metrics(now: Instant, zone: ZoneId): AchievementMetrics

    /** Every stored achievement row, tombstones included. */
    suspend fun stored(): List<StoredAchievement>

    /**
     * Applies [plan] as ONE local mutation (domain rows + outbox row): [AchievementPlan.unlocks] stamped with
     * [unlockedAt] (a revived row keeps its original unlock date) and [AchievementPlan.tombstones]. Called only
     * for a plan that is not a no-op. Returns whether any row was actually written: a planned row that no
     * longer exists is skipped, and a plan that writes nothing leaves no outbox entry.
     */
    suspend fun write(plan: AchievementPlan, unlockedAt: Long): Boolean
}

/** Runs [block] with a transaction whose reads and write are atomic. */
interface AchievementStore {
    suspend fun <T> transaction(block: suspend AchievementTransaction.() -> T): T
}

/** Outcome of one reconciliation. [wrote] is false when the stored achievements already matched the evidence. */
data class ReconcileResult(val decisions: Map<String, AchievementDecision>, val wrote: Boolean) {
    val unlocked: List<String> get() = decisions.filterValues { it == AchievementDecision.UNLOCK }.keys.toList()
    val tombstoned: List<String> get() = decisions.filterValues { it == AchievementDecision.TOMBSTONE }.keys.toList()
}

/**
 * Brings the stored achievements in line with the history: unlocks what is earned, tombstones what a
 * LOCAL history edit made unearned (only when [invoke] is told so), revives a tombstoned one when it is earned again. Deterministic (clock
 * and zone are parameters) and idempotent: the evidence read, the decision and the write happen in one
 * transaction, and a run that finds nothing to change writes nothing (no row, no outbox entry).
 */
class ReconcileAchievementsUseCase(
    private val store: AchievementStore,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
) {
    suspend operator fun invoke(allowTombstone: Boolean = false): ReconcileResult = store.transaction {
        val at = now()
        val plan = AchievementRules.plan(metrics(Instant.ofEpochMilli(at), zone()), stored(), allowTombstone)
        ReconcileResult(plan.decisions, wrote = !plan.isNoOp && write(plan, at))
    }
}
