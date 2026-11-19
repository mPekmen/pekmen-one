package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.relation.FocusSessionWithDetails
import net.pekmen.one.core.database.relation.FocusSessionWithProfile

@Dao
interface AccountSessionDao {
    @Transaction
    @Query(
        "SELECT * FROM focus_sessions " +
            "WHERE deleted_at_ms IS NULL AND status IN ('RUNNING', 'PAUSED', 'AWAITING_NEXT_PHASE') " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY started_at_wall_ms DESC LIMIT 1"
    )
    fun observeActiveSessionByOwner(ownerUserId: String?): Flow<FocusSessionWithDetails?>

    @Transaction
    @Query(
        "SELECT * FROM focus_sessions " +
            "WHERE deleted_at_ms IS NULL AND status IN ('RUNNING', 'PAUSED', 'AWAITING_NEXT_PHASE') " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY started_at_wall_ms DESC LIMIT 1"
    )
    suspend fun getActiveSessionByOwnerOnce(ownerUserId: String?): FocusSessionWithDetails?

    @Transaction
    @Query(
        "SELECT * FROM focus_sessions " +
            "WHERE deleted_at_ms IS NULL AND status = 'COMPLETED' " +
            "AND effective_day_date BETWEEN :startDate AND :endDate " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY started_at_wall_ms DESC"
    )
    fun observeCompletedSessionsByOwner(
        startDate: String,
        endDate: String,
        ownerUserId: String?
    ): Flow<List<FocusSessionWithProfile>>

    @Transaction
    @Query(
        "SELECT * FROM focus_sessions " +
            "WHERE deleted_at_ms IS NULL AND status = 'COMPLETED' " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY started_at_wall_ms DESC LIMIT :limit"
    )
    fun observeRecentCompletedSessionsByOwner(
        limit: Int,
        ownerUserId: String?
    ): Flow<List<FocusSessionWithProfile>>

    @Transaction
    @Query(
        "SELECT * FROM focus_sessions WHERE topic_id = :topicId " +
            "AND deleted_at_ms IS NULL AND status = 'COMPLETED' " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY started_at_wall_ms DESC"
    )
    fun observeTopicHistory(
        topicId: String,
        ownerUserId: String?
    ): Flow<List<FocusSessionWithProfile>>

    @Transaction
    @Query(
        "SELECT * FROM focus_sessions " +
            "WHERE deleted_at_ms IS NULL AND status = 'COMPLETED' " +
            "AND effective_day_date BETWEEN :startDate AND :endDate " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY started_at_wall_ms DESC"
    )
    suspend fun getCompletedSessionsOnce(
        startDate: String,
        endDate: String,
        ownerUserId: String?
    ): List<FocusSessionWithProfile>

    @Query(
        "SELECT * FROM focus_profiles WHERE deleted_at_ms IS NULL AND is_archived = 0 " +
            "AND (built_in_slug != 'CUSTOM' OR ((:ownerUserId IS NULL AND owner_user_id IS NULL) " +
            "OR owner_user_id = :ownerUserId)) ORDER BY sort_order ASC, created_at_ms ASC"
    )
    fun observeActiveProfilesByOwner(ownerUserId: String?): Flow<List<FocusProfileEntity>>
}
