package net.pekmen.one.core.data

import kotlinx.coroutines.flow.first
import net.pekmen.one.core.database.dao.LearningDao
import net.pekmen.one.core.database.dao.LearningTopicDao
import net.pekmen.one.core.database.dao.SyncOutboxDao
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.model.UuidV7

internal class LearningCatalogHelper(
    private val learningDao: LearningDao,
    private val topicDao: LearningTopicDao,
    private val outboxDao: SyncOutboxDao
) {

    suspend fun updateExistingSubject(
        existing: LearningSubjectEntity,
        cleanedTitle: String,
        request: SaveSubjectRequest,
        ownerUserId: String?,
        now: Long
    ): LearningSubjectEntity {
        require(existing.sync.ownerUserId == ownerUserId) {
            "Subject belongs to a different account"
        }
        val updated = existing.copy(
            title = cleanedTitle,
            colorHex = request.colorHex,
            weeklyGoalMinutes = request.weeklyGoalMinutes,
            sortOrder = request.sortOrder ?: existing.sortOrder,
            sync = existing.sync.copy(
                clientUpdatedAtMs = now,
                version = existing.sync.version + 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        learningDao.updateSubject(updated)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildSubjectMutation(
                entity = updated,
                opType = OP_UPSERT,
                baseVersion = existing.sync.version,
                nowMs = now
            )
        )
        return updated
    }

    suspend fun createNewSubject(
        cleanedTitle: String,
        request: SaveSubjectRequest,
        ownerUserId: String?,
        now: Long
    ): LearningSubjectEntity {
        val activeCount = learningDao.observeActiveSubjectsByOwner(ownerUserId).first().size
        val created = LearningSubjectEntity(
            id = UuidV7.generate(now),
            title = cleanedTitle,
            colorHex = request.colorHex,
            weeklyGoalMinutes = request.weeklyGoalMinutes,
            sortOrder = request.sortOrder ?: activeCount,
            isArchived = false,
            sync = SyncMetadataEntity(
                ownerUserId = ownerUserId,
                createdAtMs = now,
                clientUpdatedAtMs = now,
                version = 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        learningDao.upsertSubject(created)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildSubjectMutation(
                entity = created,
                opType = OP_UPSERT,
                baseVersion = 0L,
                nowMs = now
            )
        )
        return created
    }

    suspend fun softDeleteChildLearningRows(
        subjectId: String,
        ownerUserId: String?,
        now: Long
    ) {
        val childBooks = learningDao.observeActiveBooksByOwner(ownerUserId).first()
            .filter { it.subjectId == subjectId }
        val childTopics = topicDao.observeActiveTopicsByOwner(ownerUserId).first()
            .filter { it.subjectId == subjectId }

        childTopics.forEach { topic ->
            topicDao.softDeleteTopic(topic.id, deletedAtMs = now, updatedAtMs = now)
            val deletedTopic = topic.copy(
                sync = topic.sync.copy(
                    clientUpdatedAtMs = now,
                    deletedAtMs = now,
                    version = topic.sync.version + 1L
                )
            )
            outboxDao.enqueue(
                LearningOutboxEncoder.buildTopicMutation(
                    entity = deletedTopic,
                    opType = OP_DELETE,
                    baseVersion = topic.sync.version,
                    nowMs = now
                )
            )
        }

        childBooks.forEach { book ->
            learningDao.softDeleteBook(book.id, deletedAtMs = now, updatedAtMs = now)
            val deletedBook = book.copy(
                sync = book.sync.copy(
                    clientUpdatedAtMs = now,
                    deletedAtMs = now,
                    version = book.sync.version + 1L
                )
            )
            outboxDao.enqueue(
                LearningOutboxEncoder.buildBookMutation(
                    entity = deletedBook,
                    opType = OP_DELETE,
                    baseVersion = book.sync.version,
                    nowMs = now
                )
            )
        }
    }

    suspend fun updateExistingBook(
        existing: LearningBookEntity,
        cleanedTitle: String,
        request: SaveBookRequest,
        ownerUserId: String?,
        now: Long
    ): LearningBookEntity {
        require(existing.sync.ownerUserId == ownerUserId) {
            "Book belongs to a different account"
        }
        val resolvedPage = clampPage(request.currentPage, request.totalPages)
        val resolvedChapters = clampPage(request.completedChapters, request.totalChapters)
        val updated = existing.copy(
            subjectId = request.subjectId,
            title = cleanedTitle,
            author = request.author?.trim()?.take(MAX_TITLE_LENGTH)?.ifEmpty { null },
            totalPages = request.totalPages,
            currentPage = resolvedPage,
            totalChapters = request.totalChapters,
            completedChapters = resolvedChapters,
            isCompleted = computeBookCompleted(
                request.totalPages,
                resolvedPage,
                request.totalChapters,
                resolvedChapters
            ),
            sync = existing.sync.copy(
                clientUpdatedAtMs = now,
                version = existing.sync.version + 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        learningDao.updateBook(updated)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildBookMutation(
                entity = updated,
                opType = OP_UPSERT,
                baseVersion = existing.sync.version,
                nowMs = now
            )
        )
        return updated
    }

    suspend fun createNewBook(
        cleanedTitle: String,
        request: SaveBookRequest,
        ownerUserId: String?,
        now: Long
    ): LearningBookEntity {
        val resolvedPage = clampPage(request.currentPage, request.totalPages)
        val resolvedChapters = clampPage(request.completedChapters, request.totalChapters)
        val created = LearningBookEntity(
            id = UuidV7.generate(now),
            subjectId = request.subjectId,
            title = cleanedTitle,
            author = request.author?.trim()?.take(MAX_TITLE_LENGTH)?.ifEmpty { null },
            totalPages = request.totalPages,
            currentPage = resolvedPage,
            totalChapters = request.totalChapters,
            completedChapters = resolvedChapters,
            isCompleted = computeBookCompleted(
                request.totalPages,
                resolvedPage,
                request.totalChapters,
                resolvedChapters
            ),
            sync = SyncMetadataEntity(
                ownerUserId = ownerUserId,
                createdAtMs = now,
                clientUpdatedAtMs = now,
                version = 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        learningDao.upsertBook(created)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildBookMutation(
                entity = created,
                opType = OP_UPSERT,
                baseVersion = 0L,
                nowMs = now
            )
        )
        return created
    }

    suspend fun unlinkTopicsFromDeletedBook(
        bookId: String,
        ownerUserId: String?,
        now: Long
    ) {
        val linkedTopics = topicDao.observeActiveTopicsByOwner(ownerUserId).first()
            .filter { it.bookId == bookId }
        linkedTopics.forEach { topic ->
            val unlinked = topic.copy(
                bookId = null,
                sync = topic.sync.copy(
                    clientUpdatedAtMs = now,
                    version = topic.sync.version + 1L,
                    syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
                )
            )
            topicDao.upsertTopic(unlinked)
            outboxDao.enqueue(
                LearningOutboxEncoder.buildTopicMutation(
                    entity = unlinked,
                    opType = OP_UPSERT,
                    baseVersion = topic.sync.version,
                    nowMs = now
                )
            )
        }
    }

    suspend fun updateExistingTopic(
        existing: LearningTopicEntity,
        cleanedTitle: String,
        request: SaveTopicRequest,
        ownerUserId: String?,
        now: Long
    ): LearningTopicEntity {
        require(existing.sync.ownerUserId == ownerUserId) {
            "Topic belongs to a different account"
        }
        val updated = existing.copy(
            subjectId = request.subjectId,
            bookId = request.bookId,
            title = cleanedTitle,
            status = request.status.name,
            sortOrder = request.sortOrder ?: existing.sortOrder,
            sync = existing.sync.copy(
                clientUpdatedAtMs = now,
                version = existing.sync.version + 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        topicDao.upsertTopic(updated)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildTopicMutation(
                entity = updated,
                opType = OP_UPSERT,
                baseVersion = existing.sync.version,
                nowMs = now
            )
        )
        return updated
    }

    suspend fun createNewTopic(
        cleanedTitle: String,
        request: SaveTopicRequest,
        ownerUserId: String?,
        now: Long
    ): LearningTopicEntity {
        val topicCount = topicDao.observeActiveTopicsByOwner(ownerUserId).first()
            .count { it.subjectId == request.subjectId }
        val created = LearningTopicEntity(
            id = UuidV7.generate(now),
            subjectId = request.subjectId,
            bookId = request.bookId,
            title = cleanedTitle,
            status = request.status.name,
            sortOrder = request.sortOrder ?: topicCount,
            sync = SyncMetadataEntity(
                ownerUserId = ownerUserId,
                createdAtMs = now,
                clientUpdatedAtMs = now,
                version = 1L,
                syncStatus = LearningOutboxEncoder.syncStatusFor(ownerUserId)
            )
        )
        topicDao.upsertTopic(created)
        outboxDao.enqueue(
            LearningOutboxEncoder.buildTopicMutation(
                entity = created,
                opType = OP_UPSERT,
                baseVersion = 0L,
                nowMs = now
            )
        )
        return created
    }

    fun clampPage(value: Int, total: Int): Int {
        return if (total > 0) value.coerceIn(0, total) else value.coerceAtLeast(0)
    }

    fun computeBookCompleted(
        totalPages: Int,
        currentPage: Int,
        totalChapters: Int,
        completedChapters: Int
    ): Boolean {
        return when {
            totalPages > 0 -> currentPage >= totalPages
            totalChapters > 0 -> completedChapters >= totalChapters
            else -> false
        }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 80
        const val OP_UPSERT = "UPSERT"
        const val OP_DELETE = "DELETE"
    }
}
