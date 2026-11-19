package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.database.entity.SocialCircleEntity
import net.pekmen.one.core.database.entity.SocialCircleMembershipEntity
import net.pekmen.one.core.database.entity.SocialMemberSummaryEntity

@Dao
interface SocialCircleDao {
    @Query(
        "SELECT c.* FROM social_circles c " +
            "INNER JOIN social_circle_memberships m ON c.id = m.circle_id " +
            "WHERE c.deleted_at_ms IS NULL AND m.user_id = :userId AND m.left_at_ms IS NULL " +
            "ORDER BY c.created_at_ms DESC"
    )
    fun observeJoinedCircles(userId: String): Flow<List<SocialCircleEntity>>

    @Query(
        "SELECT * FROM social_circle_memberships " +
            "WHERE circle_id = :circleId AND left_at_ms IS NULL ORDER BY joined_at_ms ASC"
    )
    fun observeActiveMemberships(circleId: String): Flow<List<SocialCircleMembershipEntity>>

    @Query(
        "SELECT * FROM social_member_summaries " +
            "WHERE circle_id = :circleId AND period_type = :periodType AND period_start_date = :periodStartDate " +
            "ORDER BY (live_timer_focus_sec + manual_entry_focus_sec) DESC, user_id ASC"
    )
    fun observeSummaries(
        circleId: String,
        periodType: String,
        periodStartDate: String
    ): Flow<List<SocialMemberSummaryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCircle(circle: SocialCircleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembership(membership: SocialCircleMembershipEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummaries(summaries: List<SocialMemberSummaryEntity>)

    @Query("DELETE FROM social_member_summaries WHERE circle_id = :circleId AND user_id = :userId")
    suspend fun deleteMemberSummaries(circleId: String, userId: String)

    @Query("DELETE FROM social_member_summaries WHERE circle_id = :circleId")
    suspend fun clearCircleSummaries(circleId: String)
}
