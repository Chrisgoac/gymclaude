package com.chrisgoac.gymlog.data.achievement

import androidx.room.withTransaction
import com.chrisgoac.gymlog.data.stats.OfflineStatsRepository
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.LocalMutationScope
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.achievement.AchievementMetrics
import com.chrisgoac.gymlog.domain.achievement.AchievementPlan
import com.chrisgoac.gymlog.domain.achievement.AchievementRepository
import com.chrisgoac.gymlog.domain.achievement.AchievementStore
import com.chrisgoac.gymlog.domain.achievement.AchievementTransaction
import com.chrisgoac.gymlog.domain.achievement.PersonalRecord
import com.chrisgoac.gymlog.domain.achievement.StoredAchievement
import com.chrisgoac.gymlog.domain.achievement.UnlockedAchievement
import com.chrisgoac.gymlog.domain.stats.WorkoutStats
import com.chrisgoac.gymlog.model.AchievementRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.time.Instant
import java.time.ZoneId

/**
 * Achievements of the open account over Room.
 *
 * Evidence ([AchievementMetrics], the PWA's `getLogroMetricas`) is aggregated in SQL (counts, one
 * volume sum, one distinct count) plus one column of session dates for the week streak: no
 * logged row is loaded. Like every Task-2 statistic, a row counts only when its parents are live
 * (the PWA also counts a live set under a tombstoned session; a tombstoned session cascades to its
 * children in every client, so the two only differ for rows no client produces).
 *
 * [transaction] is ONE [LocalMutation]: evidence read, stored rows and the write share one Room
 * transaction, so a decision is never made on rows another write changed meanwhile, and the write is
 * one outbox row (none when the plan is empty).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineAchievementRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
) : AchievementRepository, AchievementStore {

    override fun observeUnlocked(): Flow<List<UnlockedAchievement>> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(emptyList())
        } else {
            open.database.achievementDao().observeLive().map { rows ->
                rows.groupBy { it.clave }.map { (key, same) -> UnlockedAchievement(key, same.minOf { it.fechaDesbloqueo }) }.sortedBy { it.key }
            }
        }
    }.distinctUntilChanged()

    override fun observePersonalRecords(): Flow<List<PersonalRecord>> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(emptyList())
        } else {
            val database = open.database
            database.invalidationTracker.createFlow("workout_sessions", "logged_exercises", "logged_sets", "exercises", "catalog_exercises")
                .mapLatest { database.withTransaction { personalRecords(database) } }
        }
    }.distinctUntilChanged()

    override suspend fun <T> transaction(block: suspend AchievementTransaction.() -> T): T {
        val database = checkNotNull(databases.current.value) { "No account database is open" }.database
        return runnerFor(database).run(LocalMutation { RoomAchievementTransaction(database, this).block() })
    }

    private suspend fun personalRecords(database: GymLogDatabase): List<PersonalRecord> {
        val dao = database.statsDao()
        val rows = dao.personalRecords()
        val names = HashMap<String, String>()
        rows.map { it.exerciseId }.chunked(CHUNK).forEach { ids ->
            (dao.catalogInfo(ids) + dao.syncedInfo(ids)).forEach { names.putIfAbsent(it.id, it.nombre) }
        }
        return rows
            .map { PersonalRecord(it.exerciseId, names[it.exerciseId] ?: WorkoutStats.UNKNOWN_NAME, it.peso, it.startedAt) }
            .sortedWith(compareByDescending<PersonalRecord> { it.weight }.thenBy { it.name }.thenBy { it.exerciseId })
    }

    private class RoomAchievementTransaction(
        private val database: GymLogDatabase,
        private val scope: LocalMutationScope,
    ) : AchievementTransaction {
        override suspend fun metrics(now: Instant, zone: ZoneId): AchievementMetrics {
            val dao = database.statsDao()
            val target = OfflineStatsRepository.weeklyTarget(dao.setting(OfflineStatsRepository.WEEKLY_TARGET_KEY))
            // The same reducer the Progress and Achievements screens use: they agree by construction.
            val streak = WorkoutStats.weeklyStreak(dao.allSessionStarts(), target, now, zone)
            return AchievementMetrics(
                sessions = dao.liveSessionCount(),
                volume = dao.totalVolume(),
                personalRecords = dao.exercisesWithSets(),
                bestStreakWeeks = streak.best,
                completedMesocycles = dao.completedMesocycles(now.toEpochMilli()),
            )
        }

        override suspend fun stored(): List<StoredAchievement> =
            database.achievementDao().getAll().map { StoredAchievement(it.id, it.clave, it.fechaDesbloqueo, it.deletedAt != null) }

        override suspend fun write(plan: AchievementPlan, unlockedAt: Long): Boolean {
            var wrote = false
            plan.unlocks.forEach { unlock ->
                val id = RecordId(unlock.id)
                val existing = scope.find(SyncTable.ACHIEVEMENTS, id) as? AchievementRecord
                val stamp = scope.nextTimestamp(SyncTable.ACHIEVEMENTS, id)
                // A revived row keeps its id (stable identity per clave) AND its original unlock date.
                scope.put(AchievementRecord(id, updatedAt = stamp, deletedAt = null, clave = unlock.key, fechaDesbloqueo = existing?.fechaDesbloqueo ?: unlockedAt))
                wrote = true
            }
            plan.tombstones.forEach { row ->
                val id = RecordId(row.id)
                val current = scope.find(SyncTable.ACHIEVEMENTS, id) as? AchievementRecord ?: return@forEach
                val stamp = scope.nextTimestamp(SyncTable.ACHIEVEMENTS, id)
                scope.put(current.copy(updatedAt = stamp, deletedAt = stamp))
                wrote = true
            }
            return wrote
        }
    }

    private companion object {
        /** Below SQLite's 999 bound-variable limit (API 26). */
        const val CHUNK = 400
    }
}
