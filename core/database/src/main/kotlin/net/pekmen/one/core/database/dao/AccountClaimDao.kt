package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface AccountClaimDao {
    @Query("SELECT COUNT(*) FROM focus_sessions WHERE deleted_at_ms IS NULL AND owner_user_id IS NULL")
    suspend fun countUnclaimedGuestSessions(): Int

    @Query("SELECT COUNT(*) FROM learning_subjects WHERE deleted_at_ms IS NULL AND owner_user_id IS NULL")
    suspend fun countUnclaimedGuestSubjects(): Int

    @Query("SELECT COUNT(*) FROM planner_tasks WHERE deleted_at_ms IS NULL AND owner_user_id IS NULL")
    suspend fun countUnclaimedGuestTasks(): Int

    @Query(
        "UPDATE focus_profiles SET owner_user_id = :ownerUserId, " +
            "client_updated_at_ms = :updatedAtMs, sync_status = 'PENDING_UPLOAD', version = version + 1 " +
            "WHERE owner_user_id IS NULL AND built_in_slug = 'CUSTOM'"
    )
    suspend fun claimGuestCustomProfiles(ownerUserId: String, updatedAtMs: Long): Int

    @Query(
        "UPDATE focus_sessions SET owner_user_id = :ownerUserId, " +
            "client_updated_at_ms = :updatedAtMs, sync_status = 'PENDING_UPLOAD', version = version + 1 " +
            "WHERE owner_user_id IS NULL"
    )
    suspend fun claimGuestSessions(ownerUserId: String, updatedAtMs: Long): Int

    @Query(
        "UPDATE learning_subjects SET owner_user_id = :ownerUserId, " +
            "client_updated_at_ms = :updatedAtMs, sync_status = 'PENDING_UPLOAD', version = version + 1 " +
            "WHERE owner_user_id IS NULL"
    )
    suspend fun claimGuestSubjects(ownerUserId: String, updatedAtMs: Long): Int

    @Query(
        "UPDATE learning_books SET owner_user_id = :ownerUserId, " +
            "client_updated_at_ms = :updatedAtMs, sync_status = 'PENDING_UPLOAD', version = version + 1 " +
            "WHERE owner_user_id IS NULL"
    )
    suspend fun claimGuestBooks(ownerUserId: String, updatedAtMs: Long): Int

    @Query(
        "UPDATE learning_topics SET owner_user_id = :ownerUserId, " +
            "client_updated_at_ms = :updatedAtMs, sync_status = 'PENDING_UPLOAD', version = version + 1 " +
            "WHERE owner_user_id IS NULL"
    )
    suspend fun claimGuestTopics(ownerUserId: String, updatedAtMs: Long): Int

    @Query(
        "UPDATE planner_tasks SET owner_user_id = :ownerUserId, " +
            "client_updated_at_ms = :updatedAtMs, sync_status = 'PENDING_UPLOAD', version = version + 1 " +
            "WHERE owner_user_id IS NULL"
    )
    suspend fun claimGuestTasks(ownerUserId: String, updatedAtMs: Long): Int
}
