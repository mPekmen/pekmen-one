package net.pekmen.one.core.model

enum class TopicStatus {
    TODO,
    IN_PROGRESS,
    MASTERED
}

data class LearningSubject(
    val id: String,
    val ownerUserId: String? = null,
    val title: String,
    val colorHex: Long,
    val weeklyGoalMinutes: Int,
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
)

data class LearningBook(
    val id: String,
    val ownerUserId: String? = null,
    val subjectId: String,
    val title: String,
    val author: String? = null,
    val totalPages: Int,
    val currentPage: Int = 0,
    val totalChapters: Int = 0,
    val completedChapters: Int = 0,
    val isCompleted: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
) {
    val pageProgressFraction: Float
        get() = if (totalPages > 0) (currentPage.toFloat() / totalPages.toFloat()).coerceIn(0f, 1f) else 0f
}

data class LearningTopic(
    val id: String,
    val ownerUserId: String? = null,
    val subjectId: String,
    val bookId: String? = null,
    val title: String,
    val status: TopicStatus = TopicStatus.TODO,
    val sortOrder: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY
)

data class SubjectWeeklyProgress(
    val subject: LearningSubject,
    val netFocusSecondsThisWeek: Long,
    val completedSessionCount: Int,
    val books: List<LearningBook>,
    val topics: List<LearningTopic>
) {
    val goalProgressFraction: Float
        get() {
            val goalSec = (subject.weeklyGoalMinutes * 60L).coerceAtLeast(1L)
            return (netFocusSecondsThisWeek.toFloat() / goalSec.toFloat()).coerceIn(0f, 1f)
        }
}

data class SessionLearningLink(
    val subjectId: String? = null,
    val bookId: String? = null,
    val topicId: String? = null,
    val taskId: String? = null,
    val bookStartPage: Int? = null,
    val bookEndPage: Int? = null
)
