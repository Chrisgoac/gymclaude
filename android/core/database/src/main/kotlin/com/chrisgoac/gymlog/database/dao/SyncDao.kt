package com.chrisgoac.gymlog.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.chrisgoac.gymlog.database.entity.BootstrapCheckpointEntity
import com.chrisgoac.gymlog.database.entity.OutboxMutationEntity
import com.chrisgoac.gymlog.database.entity.OutboxStatus
import com.chrisgoac.gymlog.database.entity.SyncCursorEntity
import kotlinx.coroutines.flow.Flow

/** Outbox, pull cursor and bootstrap checkpoint of the open user's database. */
@Dao
interface SyncDao {
    // ---- Outbox ----

    /** ABORT: a duplicate mutationId fails the enclosing transaction instead of overwriting. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(entity: OutboxMutationEntity)

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM outbox_mutations")
    suspend fun nextOutboxSequence(): Long

    /** Oldest pending mutations first; quarantined rows never block the ones behind them. */
    @Query("SELECT * FROM outbox_mutations WHERE status = 'PENDING' ORDER BY sequence LIMIT :limit")
    suspend fun pendingOutbox(limit: Int): List<OutboxMutationEntity>

    @Query("SELECT mutationId FROM outbox_mutations WHERE status = 'PENDING'")
    suspend fun pendingOutboxIds(): List<String>

    @Query("SELECT * FROM outbox_mutations WHERE mutationId IN (:mutationIds)")
    suspend fun outboxByIds(mutationIds: List<String>): List<OutboxMutationEntity>

    @Query("SELECT * FROM outbox_mutations ORDER BY sequence")
    suspend fun allOutbox(): List<OutboxMutationEntity>

    /** Rejected rows first, then oldest first; for diagnostics only. */
    @Query("SELECT * FROM outbox_mutations ORDER BY CASE status WHEN 'QUARANTINED' THEN 0 ELSE 1 END, sequence LIMIT :limit")
    fun observeOutbox(limit: Int): Flow<List<OutboxMutationEntity>>

    @Query("SELECT COUNT(*) FROM outbox_mutations WHERE status = :status")
    fun observeOutboxCount(status: OutboxStatus): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_mutations WHERE status = :status")
    suspend fun outboxCount(status: OutboxStatus): Int

    /** A retryable failure: the row stays pending and is resent unchanged. */
    @Query("UPDATE outbox_mutations SET attemptCount = attemptCount + 1, lastErrorCode = :errorCode WHERE mutationId = :mutationId")
    suspend fun recordOutboxFailure(mutationId: String, errorCode: String)

    /** A non-retryable failure naming this mutation: keep it, flag it, stop sending it. */
    @Query("UPDATE outbox_mutations SET status = 'QUARANTINED', attemptCount = attemptCount + 1, lastErrorCode = :errorCode WHERE mutationId = :mutationId")
    suspend fun quarantineOutbox(mutationId: String, errorCode: String)

    /** Only for ids the server listed in `acceptedMutationIds`, or a user-confirmed discard. */
    @Query("DELETE FROM outbox_mutations WHERE mutationId IN (:mutationIds)")
    suspend fun deleteOutbox(mutationIds: List<String>)

    // ---- Pull cursor ----

    @Query("SELECT * FROM sync_cursor WHERE id = 0")
    suspend fun cursor(): SyncCursorEntity?

    @Upsert suspend fun saveCursor(entity: SyncCursorEntity)

    // ---- Bootstrap checkpoint ----

    @Query("SELECT * FROM bootstrap_checkpoint WHERE id = 0")
    suspend fun bootstrapCheckpoint(): BootstrapCheckpointEntity?

    @Query("SELECT * FROM bootstrap_checkpoint WHERE id = 0")
    fun observeBootstrapCheckpoint(): Flow<BootstrapCheckpointEntity?>

    @Upsert suspend fun saveBootstrapCheckpoint(entity: BootstrapCheckpointEntity)
}
