package com.chrisgoac.gymlog.data.sync

import com.chrisgoac.gymlog.model.ConflictReason

/** Logical version of a write: `(updatedAt, deviceId, mutationId)` (contracts/v1/README.md, LWW). */
data class VersionTuple(val updatedAt: Long, val deviceId: String, val mutationId: String)

/** Stored state of a row; device/mutation of legacy writers may be missing and compare as "". */
data class StoredVersion(
    val updatedAt: Long,
    val deviceId: String?,
    val mutationId: String?,
    val serverVersion: Long?,
)

data class WriteDecision(val apply: Boolean, val conflict: ConflictReason?)

/**
 * Kotlin twin of `decideVersionedWrite` / `compareVersionTuple` in lib/sync/server-merge.ts. Both
 * sides run contracts/v1/fixtures/conflict-cases.json and must agree case by case.
 */
object ConflictResolver {
    /**
     * Lexicographic tuple order: updatedAt numerically, then deviceId and mutationId by UTF-16 code
     * units (`String.compareTo`, never locale-aware). Returns -1, 0 or 1.
     */
    fun compare(a: VersionTuple, b: VersionTuple): Int {
        if (a.updatedAt != b.updatedAt) return if (a.updatedAt < b.updatedAt) -1 else 1
        val byDevice = a.deviceId.compareTo(b.deviceId).sign()
        return if (byDevice != 0) byDevice else a.mutationId.compareTo(b.mutationId).sign()
    }

    /**
     * Whether [incoming] replaces [existing]. The greater tuple wins; equal means the same
     * mutation already applied (no-op). A stale [baseServerVersion] never blocks the winner, it is
     * only reported: STALE_BASE when applied, SUPERSEDED whenever the write loses.
     */
    fun decide(existing: StoredVersion?, incoming: VersionTuple, baseServerVersion: Long?): WriteDecision {
        if (existing == null) {
            return WriteDecision(apply = true, conflict = if (baseServerVersion == null) null else ConflictReason.STALE_BASE)
        }
        val stored = VersionTuple(existing.updatedAt, existing.deviceId ?: "", existing.mutationId ?: "")
        val cmp = compare(incoming, stored)
        if (cmp == 0) return WriteDecision(apply = false, conflict = null)
        if (cmp < 0) return WriteDecision(apply = false, conflict = ConflictReason.SUPERSEDED)
        val stale = baseServerVersion == null || baseServerVersion != existing.serverVersion
        return WriteDecision(apply = true, conflict = if (stale) ConflictReason.STALE_BASE else null)
    }

    private fun Int.sign(): Int = Integer.signum(this)
}
