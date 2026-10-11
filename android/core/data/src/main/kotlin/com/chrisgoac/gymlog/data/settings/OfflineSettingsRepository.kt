package com.chrisgoac.gymlog.data.settings

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.LocalMutationScope
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.settings.InvalidSettingException
import com.chrisgoac.gymlog.domain.settings.SettingKeys
import com.chrisgoac.gymlog.domain.settings.SettingProblem
import com.chrisgoac.gymlog.domain.settings.SettingsCodec
import com.chrisgoac.gymlog.domain.settings.SettingsRepository
import com.chrisgoac.gymlog.domain.settings.TrainingSettings
import com.chrisgoac.gymlog.domain.workout.ProgressionMode
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The PWA's synced `userSettings` rows (id = key, `valor` = `JSON.stringify` text) over the open
 * account's Room database. A write only ever puts the row of the key it changes, and merges into
 * the stored JSON object instead of replacing it, so keys and fields this version does not know
 * (written by a newer client) survive untouched and keep syncing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSettingsRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
) : SettingsRepository {

    override fun observeTraining(): Flow<TrainingSettings> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(TrainingSettings())
        } else {
            open.database.userSettingDao().observeLive().map { rows -> SettingsCodec.training(rows.associate { it.id to it.valor }) }
        }
    }.distinctUntilChanged()

    override suspend fun setWeeklyTarget(sessionsPerWeek: Int) {
        if (sessionsPerWeek !in SettingsRepository.WEEKLY_TARGET_RANGE) throw InvalidSettingException(SettingProblem.WEEKLY_TARGET_OUT_OF_RANGE)
        mutate { write(SettingKeys.WEEKLY_TARGET, sessionsPerWeek.toString()) }
    }

    override suspend fun setProgressionMode(mode: ProgressionMode) {
        mutate { write(SettingKeys.PROGRESSION_MODE, JsonPrimitive(mode.wire).toString()) }
    }

    override suspend fun setIncrement(equipment: Equipment, kg: Double) {
        if (!kg.isFinite() || kg !in SettingsRepository.INCREMENT_RANGE_KG) throw InvalidSettingException(SettingProblem.INCREMENT_OUT_OF_RANGE)
        mutate { mergeObject(SettingKeys.INCREMENTS) { it[equipment.wire] = SettingsCodec.number(kg) } }
    }

    override suspend fun setVolumeGoal(group: MuscleGroup, volume: Double?) {
        if (volume != null && (!volume.isFinite() || volume !in SettingsRepository.VOLUME_GOAL_RANGE)) {
            throw InvalidSettingException(SettingProblem.VOLUME_GOAL_OUT_OF_RANGE)
        }
        mutate {
            mergeObject(SettingKeys.VOLUME_GOALS) { if (volume == null) it.remove(group.wire) else it[group.wire] = SettingsCodec.number(volume) }
        }
    }

    // ---- Helpers ----

    private suspend fun <T> mutate(block: suspend LocalMutationScope.() -> T): T {
        val database = checkNotNull(databases.current.value) { "No account database is open" }.database
        return runnerFor(database).run(LocalMutation { block() })
    }

    private suspend fun LocalMutationScope.stored(key: String): String? =
        (find(SyncTable.USER_SETTINGS, RecordId(key)) as? UserSettingRecord)?.takeIf { it.deletedAt == null }?.valor

    /** Puts the row unless it already holds exactly [valor] (no spurious outbox entry). */
    private suspend fun LocalMutationScope.write(key: String, valor: String) {
        if (stored(key) == valor) return
        val id = RecordId(key)
        put(UserSettingRecord(id, nextTimestamp(SyncTable.USER_SETTINGS, id), null, valor))
    }

    /** Read-modify-write on a stored JSON object: unknown members stay; an unreadable stored value is never overwritten. */
    private suspend fun LocalMutationScope.mergeObject(key: String, edit: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit) {
        val current = stored(key)?.takeIf { it.isNotBlank() }
        val base = if (current == null) JsonObject(emptyMap()) else
            runCatching { Json.parseToJsonElement(current) as? JsonObject }.getOrNull() ?: throw InvalidSettingException(SettingProblem.STORED_VALUE_UNREADABLE)
        val members = base.toMutableMap()
        edit(members)
        write(key, JsonObject(members).toString())
    }
}
