package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.database.relation.FocusSessionWithProfile

@Dao
interface AccountSessionDao {
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
}
