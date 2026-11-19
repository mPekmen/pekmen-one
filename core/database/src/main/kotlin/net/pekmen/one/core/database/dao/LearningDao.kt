package net.pekmen.one.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity

@Dao
interface LearningDao {
    @Query(
        "SELECT * FROM learning_subjects " +
            "WHERE deleted_at_ms IS NULL AND is_archived = 0 " +
            "ORDER BY sort_order ASC, created_at_ms ASC"
    )
    fun observeActiveSubjects(): Flow<List<LearningSubjectEntity>>

    @Query(
        "SELECT * FROM learning_subjects WHERE deleted_at_ms IS NULL AND is_archived = 0 " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY sort_order ASC, created_at_ms ASC"
    )
    fun observeActiveSubjectsByOwner(ownerUserId: String?): Flow<List<LearningSubjectEntity>>

    @Query("SELECT * FROM learning_subjects WHERE deleted_at_ms IS NULL")
    suspend fun getAllActiveSubjects(): List<LearningSubjectEntity>

    @Query("SELECT * FROM learning_subjects WHERE id = :subjectId AND deleted_at_ms IS NULL LIMIT 1")
    suspend fun getSubjectById(subjectId: String): LearningSubjectEntity?

    @Upsert
    suspend fun upsertSubject(subject: LearningSubjectEntity)

    @Update
    suspend fun updateSubject(subject: LearningSubjectEntity)

    @Query(
        "UPDATE learning_subjects " +
            "SET deleted_at_ms = :deletedAtMs, client_updated_at_ms = :updatedAtMs, version = version + 1 " +
            "WHERE id = :subjectId AND deleted_at_ms IS NULL"
    )
    suspend fun softDeleteSubject(subjectId: String, deletedAtMs: Long, updatedAtMs: Long)

    @Query("SELECT * FROM learning_books WHERE deleted_at_ms IS NULL ORDER BY created_at_ms ASC")
    fun observeAllActiveBooks(): Flow<List<LearningBookEntity>>

    @Query(
        "SELECT * FROM learning_books WHERE deleted_at_ms IS NULL " +
            "AND ((:ownerUserId IS NULL AND owner_user_id IS NULL) OR owner_user_id = :ownerUserId) " +
            "ORDER BY created_at_ms ASC"
    )
    fun observeActiveBooksByOwner(ownerUserId: String?): Flow<List<LearningBookEntity>>

    @Query("SELECT * FROM learning_books WHERE id = :bookId AND deleted_at_ms IS NULL LIMIT 1")
    suspend fun getBookById(bookId: String): LearningBookEntity?

    @Upsert
    suspend fun upsertBook(book: LearningBookEntity)

    @Update
    suspend fun updateBook(book: LearningBookEntity)

    @Query(
        "UPDATE learning_books " +
            "SET deleted_at_ms = :deletedAtMs, client_updated_at_ms = :updatedAtMs, version = version + 1 " +
            "WHERE id = :bookId AND deleted_at_ms IS NULL"
    )
    suspend fun softDeleteBook(bookId: String, deletedAtMs: Long, updatedAtMs: Long)
}
