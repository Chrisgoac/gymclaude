package com.chrisgoac.gymlog.domain.achievement

/** Aggregates the milestones are judged on (`LogroMetricas`). Warm-up sets count, as in the PWA. */
data class AchievementMetrics(
    val sessions: Int,
    /** Σ weight × reps over every live set. */
    val volume: Double,
    /** Exercises with at least one live set (the PWA's `prsTotales`). */
    val personalRecords: Int,
    /** Longest run of weeks reaching the weekly target. */
    val bestStreakWeeks: Int,
    val completedMesocycles: Int,
) {
    companion object {
        val NONE = AchievementMetrics(0, 0.0, 0, 0, 0)
    }
}

/** A milestone of the catalog (`LOGROS_DEF`): [key] is the wire `clave`, text is the PWA's. */
data class AchievementDef(val key: String, val title: String, val description: String, val isEarned: (AchievementMetrics) -> Boolean)

/** One synced `achievements` row as reconciliation sees it. [deleted] = tombstone. */
data class StoredAchievement(val id: String, val key: String, val unlockedAt: Long, val deleted: Boolean)

/** What reconciliation decides for one catalog milestone. */
enum class AchievementDecision(val wire: String) {
    /** Earned and no live row: write one (revive a tombstoned row, else create the canonical one). */
    UNLOCK("unlock"),

    /** Earned and already live: nothing to write. */
    RETAIN("retain"),

    /** No longer earned after a local history edit and live: tombstone every live row. */
    TOMBSTONE("tombstone"),

    /** Not earned and nothing live: nothing to write. */
    NONE("none"),
}

/**
 * The row an [AchievementDecision.UNLOCK] writes: [id] is the row to revive when [revive] (tombstoned
 * row found), else the canonical new row.
 */
data class AchievementUnlock(val key: String, val id: String, val revive: Boolean)

/** Per-milestone [decisions] (catalog order) and the writes they imply. */
data class AchievementPlan(
    val decisions: Map<String, AchievementDecision>,
    val unlocks: List<AchievementUnlock>,
    /** Live rows to tombstone. */
    val tombstones: List<StoredAchievement>,
) {
    /** True when the stored state already matches the evidence: nothing may be written. */
    val isNoOp: Boolean get() = unlocks.isEmpty() && tombstones.isEmpty()
}

/**
 * Pure port of `lib/logros.ts` (catalog, `evaluarLogros`) plus the Android
 * reconciliation rule. Shared fixture: contracts/domain/achievement-cases.json.
 *
 * Identity. The PWA identifies an achievement by its `clave` (it looks for an active row with that
 * clave and, if none, inserts one with a random id; it never deletes). Android keeps that logical
 * identity: the achievement is "the rows with this clave". It differs from the PWA in two approved
 * ways: it tombstones an earned achievement whose evidence disappeared (history edit), and a
 * re-earned one revives its row instead of inserting a duplicate. New rows use [canonicalId], so two
 * devices that earn the same milestone offline write the same row and converge by LWW; PWA rows
 * (random ids) are found by `clave`, retained while live and revived (lowest id) when tombstoned.
 */
object AchievementRules {
    const val CANONICAL_ID_PREFIX = "ach:"

    val catalog: List<AchievementDef> = listOf(
        AchievementDef("sesiones-10", "Calentando", "10 entrenos") { it.sessions >= 10 },
        AchievementDef("sesiones-50", "Constante", "50 entrenos") { it.sessions >= 50 },
        AchievementDef("sesiones-100", "Centurión", "100 entrenos") { it.sessions >= 100 },
        AchievementDef("sesiones-250", "Veterano", "250 entrenos") { it.sessions >= 250 },
        AchievementDef("volumen-100k", "100K", "100.000 kg movidos") { it.volume >= 100_000 },
        AchievementDef("volumen-500k", "Medio millón", "500.000 kg movidos") { it.volume >= 500_000 },
        AchievementDef("volumen-1m", "Una tonelada x1000", "1.000.000 kg movidos") { it.volume >= 1_000_000 },
        AchievementDef("racha-4", "Racha de 4", "4 semanas seguidas cumpliendo el objetivo") { it.bestStreakWeeks >= 4 },
        AchievementDef("racha-8", "Racha de 8", "8 semanas seguidas cumpliendo el objetivo") { it.bestStreakWeeks >= 8 },
        AchievementDef("racha-12", "Racha de 12", "12 semanas seguidas cumpliendo el objetivo") { it.bestStreakWeeks >= 12 },
        AchievementDef("mesociclo-1", "Planificador", "Completa tu primer mesociclo") { it.completedMesocycles >= 1 },
    )

    private val byKey: Map<String, AchievementDef> = catalog.associateBy { it.key }

    fun definition(key: String): AchievementDef? = byKey[key]

    /** Row id new achievements get (and revived-by-default): one per `clave`. */
    fun canonicalId(key: String): String = CANONICAL_ID_PREFIX + key

    /** Keys of the milestones earned with [metrics], in catalog order (`evaluarLogros`). */
    fun evaluate(metrics: AchievementMetrics): List<String> = catalog.filter { it.isEarned(metrics) }.map { it.key }

    /**
     * What to do with [stored] given the evidence in [metrics]. Only catalog milestones are judged:
     * rows with an unknown `clave` (a newer client's milestone) are never touched. Live duplicates
     * (two devices) are all kept while earned and, once not earned, tombstoned ONLY when
     * [allowTombstone] (the run was caused by a local history edit or workout delete). Any other trigger
     * (pull, account ready, finishing a workout) is unlock/retain only: evidence that is merely missing
     * locally (not pulled yet) must never revoke, and a tombstone the PWA would re-insert past would loop.
     */
    fun plan(metrics: AchievementMetrics, stored: Collection<StoredAchievement>, allowTombstone: Boolean): AchievementPlan {
        val earned = evaluate(metrics).toSet()
        val rowsByKey = stored.groupBy { it.key }
        val decisions = LinkedHashMap<String, AchievementDecision>()
        val unlocks = ArrayList<AchievementUnlock>()
        val tombstones = ArrayList<StoredAchievement>()
        for (def in catalog) {
            val rows = rowsByKey[def.key].orEmpty()
            val live = rows.filter { !it.deleted }
            decisions[def.key] = when {
                def.key in earned && live.isNotEmpty() -> AchievementDecision.RETAIN
                def.key in earned -> {
                    val target = rows.firstOrNull { it.id == canonicalId(def.key) } ?: rows.minByOrNull { it.id }
                    unlocks += AchievementUnlock(def.key, target?.id ?: canonicalId(def.key), revive = target != null)
                    AchievementDecision.UNLOCK
                }
                live.isNotEmpty() && !allowTombstone -> AchievementDecision.RETAIN
                live.isNotEmpty() -> {
                    tombstones += live.sortedBy { it.id }
                    AchievementDecision.TOMBSTONE
                }
                else -> AchievementDecision.NONE
            }
        }
        return AchievementPlan(decisions, unlocks, tombstones)
    }
}
