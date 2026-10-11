package com.chrisgoac.gymlog.domain.achievement

import kotlinx.coroutines.flow.Flow

/** An unlocked milestone: the catalog [key] and when it was unlocked (earliest live row of that `clave`). */
data class UnlockedAchievement(val key: String, val unlockedAt: Long)

/** Heaviest weight ever lifted on an exercise and the earliest date it was reached (`PRItem`). [name] is "—" for an unknown exercise. */
data class PersonalRecord(val exerciseId: String, val name: String, val weight: Double, val achievedAt: Long)

/** Reads behind the Achievements screen; reconciliation is [ReconcileAchievementsUseCase]. */
interface AchievementRepository {
    /** Live achievements, one per `clave`, refreshed on every change. */
    fun observeUnlocked(): Flow<List<UnlockedAchievement>>

    /** Records per trained exercise, heaviest first. */
    fun observePersonalRecords(): Flow<List<PersonalRecord>>
}
