package com.chrisgoac.gymlog.domain.settings

import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.workout.ProgressionMode
import kotlinx.coroutines.flow.Flow

enum class SettingProblem { WEEKLY_TARGET_OUT_OF_RANGE, INCREMENT_OUT_OF_RANGE, VOLUME_GOAL_OUT_OF_RANGE, STORED_VALUE_UNREADABLE }

/** A write the validator refused; nothing was stored. */
class InvalidSettingException(val problem: SettingProblem) : IllegalArgumentException(problem.name)

/**
 * The PWA-compatible synced user settings. Reads expose only the known keys (typed); writes touch
 * only the row of the key being changed - other rows (including keys a future version adds) and
 * unknown fields inside a known JSON object are carried through untouched. Every write is one
 * local mutation (domain row + outbox) and never waits for the network.
 */
interface SettingsRepository {
    fun observeTraining(): Flow<TrainingSettings>

    /** @throws InvalidSettingException unless [sessionsPerWeek] is in [WEEKLY_TARGET_RANGE]. */
    suspend fun setWeeklyTarget(sessionsPerWeek: Int)

    suspend fun setProgressionMode(mode: ProgressionMode)

    /** @throws InvalidSettingException unless [kg] is in [INCREMENT_RANGE_KG]. */
    suspend fun setIncrement(equipment: Equipment, kg: Double)

    /** Sets (or, with null, removes) the weekly volume goal of [group]. @throws InvalidSettingException */
    suspend fun setVolumeGoal(group: MuscleGroup, volume: Double?)

    companion object {
        val WEEKLY_TARGET_RANGE = 1..14
        val INCREMENT_RANGE_KG = 0.0..500.0
        val VOLUME_GOAL_RANGE = 1.0..10_000_000.0
    }
}
