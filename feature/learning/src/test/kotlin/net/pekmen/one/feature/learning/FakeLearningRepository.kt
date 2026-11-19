package net.pekmen.one.feature.learning

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.pekmen.one.core.data.CompletedSessionItem
import net.pekmen.one.core.data.LearningRepository
import net.pekmen.one.core.data.SaveBookRequest
import net.pekmen.one.core.data.SaveSubjectRequest
import net.pekmen.one.core.data.SaveTopicRequest
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.SubjectWeeklyProgress
import net.pekmen.one.core.model.TopicStatus
import java.time.LocalDate

internal class FakeLearningRepository : LearningRepository {
    private val subjects = MutableStateFlow<List<LearningSubject>>(emptyList())
    private val books = MutableStateFlow<List<LearningBook>>(emptyList())
    private val topics = MutableStateFlow<List<LearningTopic>>(emptyList())
    private var seq = 1

    override fun observeSubjects(): Flow<List<LearningSubject>> = subjects
    override fun observeTopicHistory(topicId: String): Flow<List<CompletedSessionItem>> = flowOf(emptyList())
    override fun observeBooks(subjectId: String?): Flow<List<LearningBook>> =
        books.map { list -> list.filter { subjectId == null || it.subjectId == subjectId } }

    override fun observeTopics(subjectId: String?): Flow<List<LearningTopic>> =
        topics.map { list -> list.filter { subjectId == null || it.subjectId == subjectId } }

    override fun observeWeeklySubjectProgress(anchorDate: LocalDate): Flow<List<SubjectWeeklyProgress>> {
        return combine(subjects, books, topics) { subList, bookList, topicList ->
            subList.map { subject ->
                SubjectWeeklyProgress(
                    subject = subject,
                    netFocusSecondsThisWeek = 0L,
                    completedSessionCount = 0,
                    books = bookList.filter { it.subjectId == subject.id },
                    topics = topicList.filter { it.subjectId == subject.id }
                )
            }
        }
    }

    override suspend fun saveSubject(request: SaveSubjectRequest): Result<LearningSubject> {
        val item = LearningSubject(
            id = request.existingId ?: "sub-${seq++}",
            title = request.title,
            colorHex = request.colorHex,
            weeklyGoalMinutes = request.weeklyGoalMinutes
        )
        subjects.value = subjects.value.filterNot { it.id == item.id } + item
        return Result.success(item)
    }

    override suspend fun softDeleteSubject(subjectId: String): Result<Unit> {
        subjects.value = subjects.value.filterNot { it.id == subjectId }
        return Result.success(Unit)
    }

    override suspend fun saveBook(request: SaveBookRequest): Result<LearningBook> {
        val item = LearningBook(
            id = request.existingId ?: "book-${seq++}",
            subjectId = request.subjectId,
            title = request.title,
            author = request.author,
            totalPages = request.totalPages,
            currentPage = request.currentPage
        )
        books.value = books.value.filterNot { it.id == item.id } + item
        return Result.success(item)
    }

    override suspend fun updateBookProgress(
        bookId: String,
        currentPage: Int,
        completedChapters: Int?
    ): Result<LearningBook> {
        val current = books.value.first { it.id == bookId }
        val updated = current.copy(currentPage = currentPage)
        books.value = books.value.map { if (it.id == bookId) updated else it }
        return Result.success(updated)
    }

    override suspend fun softDeleteBook(bookId: String): Result<Unit> {
        books.value = books.value.filterNot { it.id == bookId }
        return Result.success(Unit)
    }

    override suspend fun saveTopic(request: SaveTopicRequest): Result<LearningTopic> {
        val item = LearningTopic(
            id = request.existingId ?: "topic-${seq++}",
            subjectId = request.subjectId,
            bookId = request.bookId,
            title = request.title,
            status = request.status
        )
        topics.value = topics.value.filterNot { it.id == item.id } + item
        return Result.success(item)
    }

    override suspend fun updateTopicStatus(topicId: String, status: TopicStatus): Result<LearningTopic> {
        val current = topics.value.first { it.id == topicId }
        val updated = current.copy(status = status)
        topics.value = topics.value.map { if (it.id == topicId) updated else it }
        return Result.success(updated)
    }

    override suspend fun softDeleteTopic(topicId: String): Result<Unit> {
        topics.value = topics.value.filterNot { it.id == topicId }
        return Result.success(Unit)
    }
}
