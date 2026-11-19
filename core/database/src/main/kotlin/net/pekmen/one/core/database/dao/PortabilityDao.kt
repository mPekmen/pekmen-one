package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity

@Dao
interface PortabilityDao {
    @Query(
        "SELECT * FROM focus_profiles WHERE deleted_at_ms IS NULL " +
            "AND (built_in_slug != 'CUSTOM' OR ((:ownerUserId IS NULL AND owner_user_id IS NULL) " +
            "OR owner_user_id = :ownerUserId)) ORDER BY sort_order ASC, created_at_ms ASC"
    )
    suspend fun getProfilesForOwnerOnce(ownerUserId: String?): List<FocusProfileEntity>

    @Query(
        "SELECT * FROM learning_subjects WHERE deleted_at_ms IS NULL " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY sort_order ASC, created_at_ms ASC"
    )
    suspend fun getSubjectsForOwnerOnce(ownerUserId: String?): List<LearningSubjectEntity>

    @Query(
        "SELECT * FROM learning_books WHERE deleted_at_ms IS NULL " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY created_at_ms ASC"
    )
    suspend fun getBooksForOwnerOnce(ownerUserId: String?): List<LearningBookEntity>

    @Query(
        "SELECT * FROM learning_topics WHERE deleted_at_ms IS NULL " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY sort_order ASC, created_at_ms ASC"
    )
    suspend fun getTopicsForOwnerOnce(ownerUserId: String?): List<LearningTopicEntity>

    @Query(
        "SELECT * FROM focus_sessions WHERE deleted_at_ms IS NULL " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY started_at_wall_ms ASC"
    )
    suspend fun getSessionsForOwnerOnce(ownerUserId: String?): List<FocusSessionEntity>

    @Query(
        "SELECT seg.* FROM focus_session_segments seg " +
            "INNER JOIN focus_sessions ses ON seg.session_id = ses.id " +
            "WHERE ses.deleted_at_ms IS NULL " +
            "AND ((:ownerUserId IS NULL AND ses.owner_user_id IS NULL) OR ses.owner_user_id = :ownerUserId) " +
            "ORDER BY seg.session_id ASC, seg.segment_index ASC"
    )
    suspend fun getSegmentsForOwnerOnce(ownerUserId: String?): List<FocusSessionSegmentEntity>

    @Query(
        "SELECT * FROM planner_tasks WHERE deleted_at_ms IS NULL " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY created_at_ms ASC"
    )
    suspend fun getTasksForOwnerOnce(ownerUserId: String?): List<PlannerTaskEntity>

    @Upsert
    suspend fun upsertProfiles(profiles: List<FocusProfileEntity>)

    @Upsert
    suspend fun upsertSubjects(subjects: List<LearningSubjectEntity>)

    @Upsert
    suspend fun upsertBooks(books: List<LearningBookEntity>)

    @Upsert
    suspend fun upsertTopics(topics: List<LearningTopicEntity>)

    @Upsert
    suspend fun upsertSessions(sessions: List<FocusSessionEntity>)

    @Upsert
    suspend fun upsertSegments(segments: List<FocusSessionSegmentEntity>)

    @Upsert
    suspend fun upsertTasks(tasks: List<PlannerTaskEntity>)
}
