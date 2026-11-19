package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.database.entity.SyncCursorEntity
import net.pekmen.one.core.database.entity.SyncOutboxEntity

@Dao
interface SyncOutboxDao {
    @Query("SELECT * FROM sync_outbox ORDER BY created_at_ms ASC")
    fun observeOutbox(): Flow<List<SyncOutboxEntity>>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE owner_user_id IS :ownerUserId OR :ownerUserId IS NULL")
    fun observePendingCount(ownerUserId: String?): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE owner_user_id = :ownerUserId")
    suspend fun countPendingForOwner(ownerUserId: String): Int

    @Query("SELECT * FROM sync_outbox WHERE owner_user_id = :ownerUserId ORDER BY created_at_ms ASC LIMIT :limit")
    suspend fun getPendingBatchForOwner(ownerUserId: String, limit: Int = 50): List<SyncOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE mutation_id IN (:mutationIds)")
    suspend fun deleteByMutationIds(mutationIds: List<String>)

    @Query(
        "UPDATE sync_outbox SET attempt_count = attempt_count + 1, last_error = :error " +
            "WHERE mutation_id = :mutationId"
    )
    suspend fun markFailedAttempt(mutationId: String, error: String?)

    @Query("SELECT * FROM sync_cursors WHERE entity_table = :tableName LIMIT 1")
    suspend fun getCursor(tableName: String): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: SyncCursorEntity)

    @Query("DELETE FROM sync_cursors")
    suspend fun clearCursors()
}
