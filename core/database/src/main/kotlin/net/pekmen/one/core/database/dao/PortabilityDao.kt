package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.relation.FocusSessionWithProfile

@Dao
interface PortabilityDao {
    @Query("SELECT * FROM focus_profiles WHERE deleted_at_ms IS NULL ORDER BY sort_order ASC, created_at_ms ASC")
    suspend fun getAllProfilesOnce(): List<FocusProfileEntity>

    @Query("SELECT * FROM learning_books WHERE deleted_at_ms IS NULL ORDER BY created_at_ms ASC")
    suspend fun getAllBooksOnce(): List<LearningBookEntity>

    @Query("SELECT * FROM learning_topics WHERE deleted_at_ms IS NULL ORDER BY sort_order ASC, created_at_ms ASC")
    suspend fun getAllTopicsOnce(): List<LearningTopicEntity>

    @Query("SELECT * FROM focus_sessions WHERE deleted_at_ms IS NULL ORDER BY started_at_wall_ms ASC")
    suspend fun getAllNonDeletedSessionsOnce(): List<FocusSessionEntity>

    @Query("SELECT * FROM focus_session_segments ORDER BY session_id ASC, segment_index ASC")
    suspend fun getAllSegmentsOnce(): List<FocusSessionSegmentEntity>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfiles(profiles: List<FocusProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubjects(subjects: List<LearningSubjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBooks(books: List<LearningBookEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTopics(topics: List<LearningTopicEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessions(sessions: List<FocusSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegments(segments: List<FocusSessionSegmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<PlannerTaskEntity>)
}
