package com.chrisgoac.gymlog.domain.settings

import kotlinx.coroutines.flow.Flow

/** A gym as the management screen sees it; [sessionCount] = live workouts recorded there. */
data class GymItem(val id: String, val name: String, val archived: Boolean, val sessionCount: Int)

enum class GymNameProblem { BLANK, TOO_LONG }

class InvalidGymNameException(val problem: GymNameProblem) : IllegalArgumentException(problem.name)
class GymNotFoundException(val id: String) : NoSuchElementException("Gym $id does not exist or was deleted")

/**
 * Gyms of the open account (archived included, tombstoned never). Deleting only tombstones the
 * gym: workouts keep their `gymId` and show a "deleted gym" label instead of being rewritten.
 * Writes are local mutations (domain row + outbox).
 */
interface GymRepository {
    fun observeGyms(): Flow<List<GymItem>>

    /** @throws InvalidGymNameException */
    suspend fun create(name: String): GymItem

    /** @throws InvalidGymNameException; [GymNotFoundException] */
    suspend fun rename(id: String, name: String)

    /** @throws GymNotFoundException */
    suspend fun setArchived(id: String, archived: Boolean)

    /** Gives the live gyms compact positions 0..n-1: [idsInOrder] first (unknown ids ignored), the rest after in their current order. */
    suspend fun reorder(idsInOrder: List<String>)

    /** Tombstones the gym (idempotent). Historical workouts are not touched. */
    suspend fun delete(id: String)

    companion object {
        const val NAME_MAX = 60
    }
}
