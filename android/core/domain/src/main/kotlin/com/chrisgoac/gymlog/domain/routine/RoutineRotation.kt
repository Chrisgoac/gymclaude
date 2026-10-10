package com.chrisgoac.gymlog.domain.routine

/**
 * The id of the "next" routine in the rotation, given the routine ids in manual order and the id
 * of the last trained routine (port of `getNextRoutineId`, lib/routine-rotation.ts).
 *
 * - No routines: null.
 * - No last routine, or it no longer exists (deleted/archived): the first one.
 * - Otherwise the one after it, cycling (the last one wraps to the first).
 */
fun nextRoutineId(routineIdsInOrder: List<String>, lastRoutineId: String?): String? {
    if (routineIdsInOrder.isEmpty()) return null
    val index = if (lastRoutineId == null) -1 else routineIdsInOrder.indexOf(lastRoutineId)
    if (index == -1) return routineIdsInOrder.first()
    return routineIdsInOrder[(index + 1) % routineIdsInOrder.size]
}
