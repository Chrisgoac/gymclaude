package com.chrisgoac.gymlog.data.settings

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.LocalMutationScope
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.settings.GymItem
import com.chrisgoac.gymlog.domain.settings.GymNameProblem
import com.chrisgoac.gymlog.domain.settings.GymNotFoundException
import com.chrisgoac.gymlog.domain.settings.GymRepository
import com.chrisgoac.gymlog.domain.settings.InvalidGymNameException
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/** Gyms over the open account's Room database; every write is one [LocalMutation] (row + outbox). */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineGymRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : GymRepository {

    override fun observeGyms(): Flow<List<GymItem>> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(emptyList())
        } else {
            val dao = open.database.gymDao()
            combine(dao.observeLive(), dao.observeSessionCounts()) { gyms, counts ->
                val bySession = counts.associate { it.gymId to it.sessions }
                gyms.map { GymItem(it.id, it.nombre, it.archivada, bySession[it.id] ?: 0) }
            }
        }
    }.distinctUntilChanged()

    override suspend fun create(name: String): GymItem {
        val clean = validName(name)
        return mutate { database ->
            val next = (database.gymDao().live().maxOfOrNull { it.orden } ?: -1) + 1
            val id = RecordId(newId())
            put(GymRecord(id, nextTimestamp(SyncTable.GYMS, id), null, clean, next, false))
            GymItem(id.value, clean, archived = false, sessionCount = 0)
        }
    }

    override suspend fun rename(id: String, name: String) {
        val clean = validName(name)
        mutate { _ ->
            val gym = live(id) ?: throw GymNotFoundException(id)
            if (gym.nombre != clean) put(gym.copy(nombre = clean, updatedAt = nextTimestamp(SyncTable.GYMS, gym.id)))
        }
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        mutate { _ ->
            val gym = live(id) ?: throw GymNotFoundException(id)
            if (gym.archivada != archived) put(gym.copy(archivada = archived, updatedAt = nextTimestamp(SyncTable.GYMS, gym.id)))
        }
    }

    override suspend fun reorder(idsInOrder: List<String>) {
        mutate { database ->
            val current = database.gymDao().live()
            val byId = current.associateBy { it.id }
            val wanted = idsInOrder.distinct().filter { it in byId }
            val ordered = wanted + current.map { it.id }.filter { it !in wanted }
            ordered.forEachIndexed { position, id ->
                val row = byId.getValue(id)
                if (row.orden != position) {
                    put(GymRecord(RecordId(id), nextTimestamp(SyncTable.GYMS, RecordId(id)), null, row.nombre, position, row.archivada))
                }
            }
        }
    }

    override suspend fun delete(id: String) {
        mutate { _ ->
            val gym = live(id) ?: return@mutate
            val stamp = nextTimestamp(SyncTable.GYMS, gym.id)
            put(gym.copy(updatedAt = stamp, deletedAt = stamp))
        }
    }

    private fun validName(name: String): String {
        val clean = name.trim()
        if (clean.isEmpty()) throw InvalidGymNameException(GymNameProblem.BLANK)
        if (clean.length > GymRepository.NAME_MAX) throw InvalidGymNameException(GymNameProblem.TOO_LONG)
        return clean
    }

    private suspend fun <T> mutate(block: suspend LocalMutationScope.(GymLogDatabase) -> T): T {
        val database = checkNotNull(databases.current.value) { "No account database is open" }.database
        return runnerFor(database).run(LocalMutation { block(database) })
    }

    private suspend fun LocalMutationScope.live(id: String): GymRecord? =
        (find(SyncTable.GYMS, RecordId(id)) as? GymRecord)?.takeIf { it.deletedAt == null }
}
