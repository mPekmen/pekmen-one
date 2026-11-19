package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.database.entity.LearningTopicEntity

@Dao
interface LearningTopicDao {
    @Query(
        "SELECT * FROM learning_topics " +
            "WHERE deleted_at_ms IS NULL ORDER BY sort_order ASC, created_at_ms ASC"
    )
    fun observeAllActiveTopics(): Flow<List<LearningTopicEntity>>

    @Query(
        "SELECT * FROM learning_topics WHERE deleted_at_ms IS NULL " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY sort_order ASC, created_at_ms ASC"
    )
    fun observeActiveTopicsByOwner(ownerUserId: String?): Flow<List<LearningTopicEntity>>

    @Query("SELECT * FROM learning_topics WHERE id = :topicId AND deleted_at_ms IS NULL LIMIT 1")
    suspend fun getTopicById(topicId: String): LearningTopicEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTopic(topic: LearningTopicEntity)

    @Query(
        "UPDATE learning_topics " +
            "SET deleted_at_ms = :deletedAtMs, client_updated_at_ms = :updatedAtMs, version = version + 1 " +
            "WHERE id = :topicId AND deleted_at_ms IS NULL"
    )
    suspend fun softDeleteTopic(topicId: String, deletedAtMs: Long, updatedAtMs: Long)
}
