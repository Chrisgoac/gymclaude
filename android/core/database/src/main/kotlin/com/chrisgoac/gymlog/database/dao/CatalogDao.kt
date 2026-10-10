package com.chrisgoac.gymlog.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.database.entity.CatalogMetaEntity
import kotlinx.coroutines.flow.Flow

/** The local-only global exercise catalog (never synced, never in the outbox). */
@Dao
interface CatalogDao {
    @Upsert suspend fun upsertAll(entities: List<CatalogExerciseEntity>)

    @Query("SELECT * FROM catalog_exercises ORDER BY nombre, id")
    fun observeAll(): Flow<List<CatalogExerciseEntity>>

    @Query("SELECT * FROM catalog_exercises ORDER BY id")
    suspend fun getAll(): List<CatalogExerciseEntity>

    @Query("SELECT * FROM catalog_exercises WHERE id = :id")
    suspend fun findById(id: String): CatalogExerciseEntity?

    @Query("SELECT importedVersion FROM catalog_meta WHERE id = 0")
    suspend fun importedVersion(): Int?

    @Upsert suspend fun upsertMeta(meta: CatalogMetaEntity)
}
