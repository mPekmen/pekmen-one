package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.relation.FocusSessionWithDetails
import net.pekmen.one.core.database.relation.FocusSessionWithProfile

@Dao
interface FocusSessionDao {
    @Transaction
    @Query(
        "SELECT * FROM focus_sessions " +
            "WHERE deleted_at_ms IS NULL AND status IN ('RUNNING', 'PAUSED', 'AWAITING_NEXT_PHASE') " +
            "ORDER BY started_at_wall_ms DESC LIMIT 1"
    )
    fun observeActiveSession(): Flow<FocusSessionWithDetails?>

    @Transaction
    @Query(
        "SELECT * FROM focus_sessions " +
            "WHERE deleted_at_ms IS NULL AND status IN ('RUNNING', 'PAUSED', 'AWAITING_NEXT_PHASE') " +
            "ORDER BY started_at_wall_ms DESC LIMIT 1"
    )
    suspend fun getActiveSessionOnce(): FocusSessionWithDetails?

    @Query(
        "SELECT COUNT(*) FROM focus_sessions " +
            "WHERE deleted_at_ms IS NULL AND status IN ('RUNNING', 'PAUSED', 'AWAITING_NEXT_PHASE')"
    )
    suspend fun countActiveSessions(): Int

    @Transaction
    @Query(
        "SELECT * FROM focus_sessions " +
            "WHERE deleted_at_ms IS NULL AND status = 'COMPLETED' " +
            "AND effective_day_date BETWEEN :startDate AND :endDate " +
            "ORDER BY started_at_wall_ms DESC"
    )
    fun observeCompletedSessionsInDateRange(
        startDate: String,
        endDate: String
    ): Flow<List<FocusSessionWithProfile>>

    @Transaction
    @Query(
        "SELECT * FROM focus_sessions " +
            "WHERE deleted_at_ms IS NULL AND status = 'COMPLETED' " +
            "ORDER BY started_at_wall_ms DESC LIMIT :limit"
    )
    fun observeRecentCompletedSessions(limit: Int): Flow<List<FocusSessionWithProfile>>

    @Query("SELECT * FROM focus_sessions WHERE id = :sessionId AND deleted_at_ms IS NULL LIMIT 1")
    suspend fun getSessionById(sessionId: String): FocusSessionEntity?

    @Transaction
    @Query("SELECT * FROM focus_sessions WHERE id = :sessionId AND deleted_at_ms IS NULL LIMIT 1")
    suspend fun getSessionDetailsById(sessionId: String): FocusSessionWithDetails?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: FocusSessionEntity)

    @Update
    suspend fun updateSession(session: FocusSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegment(segment: FocusSessionSegmentEntity)

    @Update
    suspend fun updateSegment(segment: FocusSessionSegmentEntity)

    @Query(
        "UPDATE focus_session_segments " +
            "SET last_checkpointed_duration_ms = :checkpointedMs " +
            "WHERE id = :segmentId AND ended_at_wall_ms IS NULL"
    )
    suspend fun updateOpenSegmentCheckpoint(segmentId: String, checkpointedMs: Long)

    @Query(
        "UPDATE focus_sessions " +
            "SET deleted_at_ms = :deletedAtMs, client_updated_at_ms = :updatedAtMs, version = version + 1 " +
            "WHERE id = :sessionId AND deleted_at_ms IS NULL"
    )
    suspend fun softDeleteSession(sessionId: String, deletedAtMs: Long, updatedAtMs: Long)
}
