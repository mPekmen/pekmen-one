package net.pekmen.one.core.data

import kotlinx.coroutines.flow.Flow
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.SubjectWeeklyProgress
import net.pekmen.one.core.model.TopicStatus
import java.time.LocalDate

data class SaveSubjectRequest(
    val existingId: String? = null,
    val title: String,
    val colorHex: Long,
    val weeklyGoalMinutes: Int,
    val sortOrder: Int? = null,
    val dailyGoalMinutes: Int? = null
)

data class SaveBookRequest(
    val existingId: String? = null,
    val subjectId: String,
    val title: String,
    val author: String? = null,
    val totalPages: Int,
    val currentPage: Int = 0,
    val totalChapters: Int = 0,
    val completedChapters: Int = 0
)

data class SaveTopicRequest(
    val existingId: String? = null,
    val subjectId: String,
    val bookId: String? = null,
    val title: String,
    val status: TopicStatus = TopicStatus.TODO,
    val sortOrder: Int? = null,
    val parentTopicId: String? = null
)

interface LearningRepository {
    fun observeSubjects(): Flow<List<LearningSubject>>
    fun observeBooks(subjectId: String? = null): Flow<List<LearningBook>>
    fun observeTopics(subjectId: String? = null): Flow<List<LearningTopic>>
    fun observeWeeklySubjectProgress(anchorDate: LocalDate): Flow<List<SubjectWeeklyProgress>>
    fun observeTopicHistory(topicId: String): Flow<List<CompletedSessionItem>>

    suspend fun saveSubject(request: SaveSubjectRequest): Result<LearningSubject>
    suspend fun softDeleteSubject(subjectId: String): Result<Unit>

    suspend fun saveBook(request: SaveBookRequest): Result<LearningBook>
    suspend fun updateBookProgress(
        bookId: String,
        currentPage: Int,
        completedChapters: Int? = null
    ): Result<LearningBook>

    suspend fun softDeleteBook(bookId: String): Result<Unit>

    suspend fun saveTopic(request: SaveTopicRequest): Result<LearningTopic>
    suspend fun updateTopicStatus(topicId: String, status: TopicStatus): Result<LearningTopic>
    suspend fun softDeleteTopic(topicId: String): Result<Unit>
}
