package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.database.entity.PlannerTaskEntity

@Dao
interface PlannerTaskDao {
    @Query(
        "SELECT * FROM planner_tasks " +
            "WHERE deleted_at_ms IS NULL ORDER BY " +
            "CASE WHEN due_at_wall_ms IS NULL THEN 1 ELSE 0 END ASC, " +
            "due_at_wall_ms ASC, priority DESC, created_at_ms ASC"
    )
    fun observeAllActiveTasks(): Flow<List<PlannerTaskEntity>>

    @Query("SELECT * FROM planner_tasks WHERE deleted_at_ms IS NULL")
    suspend fun getAllActiveTasksOnce(): List<PlannerTaskEntity>

    @Query("SELECT * FROM planner_tasks WHERE id = :taskId AND deleted_at_ms IS NULL LIMIT 1")
    suspend fun getTaskById(taskId: String): PlannerTaskEntity?

    @Query(
        "SELECT * FROM planner_tasks " +
            "WHERE deleted_at_ms IS NULL AND status = 'OPEN' AND reminder_at_wall_ms > :afterWallMs " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY reminder_at_wall_ms ASC"
    )
    suspend fun getPendingReminderTasksByOwner(afterWallMs: Long, ownerUserId: String?): List<PlannerTaskEntity>

    @Query(
        "SELECT * FROM planner_tasks " +
            "WHERE deleted_at_ms IS NULL AND status = 'OPEN' AND reminder_at_wall_ms > :afterWallMs " +
            "ORDER BY reminder_at_wall_ms ASC"
    )
    suspend fun getPendingReminderTasks(afterWallMs: Long): List<PlannerTaskEntity>

    @Upsert
    suspend fun upsertTask(task: PlannerTaskEntity)

    @Update
    suspend fun updateTask(task: PlannerTaskEntity)

    @Query(
        "UPDATE planner_tasks " +
            "SET deleted_at_ms = :deletedAtMs, client_updated_at_ms = :updatedAtMs, version = version + 1 " +
            "WHERE id = :taskId AND deleted_at_ms IS NULL"
    )
    suspend fun softDeleteTask(taskId: String, deletedAtMs: Long, updatedAtMs: Long)

    @Query(
        "SELECT * FROM planner_tasks WHERE deleted_at_ms IS NULL " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY CASE WHEN due_at_wall_ms IS NULL THEN 1 ELSE 0 END ASC, " +
            "due_at_wall_ms ASC, priority DESC, created_at_ms ASC"
    )
    fun observeTasksByOwner(ownerUserId: String?): Flow<List<PlannerTaskEntity>>

    @Query(
        "UPDATE planner_tasks SET owner_user_id = :ownerUserId, " +
            "client_updated_at_ms = :updatedAtMs, sync_status = 'PENDING_UPLOAD', version = version + 1 " +
            "WHERE owner_user_id IS NULL"
    )
    suspend fun claimGuestTasks(ownerUserId: String, updatedAtMs: Long): Int
}
