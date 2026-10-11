package com.chrisgoac.gymlog.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.chrisgoac.gymlog.database.entity.PendingMediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingMediaDao {
    @Upsert suspend fun upsert(entity: PendingMediaEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PendingMediaEntity)

    @Query("SELECT * FROM pending_media WHERE id = :id")
    suspend fun findById(id: String): PendingMediaEntity?

    @Query("SELECT * FROM pending_media WHERE kind = :kind ORDER BY createdAt, id")
    fun observeByKind(kind: String): Flow<List<PendingMediaEntity>>

    @Query("SELECT * FROM pending_media ORDER BY createdAt, id")
    suspend fun getAll(): List<PendingMediaEntity>

    @Query("SELECT COUNT(*) FROM pending_media")
    suspend fun count(): Int

    @Query("DELETE FROM pending_media WHERE id = :id")
    suspend fun delete(id: String)
}
