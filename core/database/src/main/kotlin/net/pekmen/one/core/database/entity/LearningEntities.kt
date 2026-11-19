package net.pekmen.one.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TopicStatus

@Entity(
    tableName = "learning_subjects",
    indices = [
        Index(value = ["deleted_at_ms"]),
        Index(value = ["owner_user_id"]),
        Index(value = ["sort_order"])
    ]
)
data class LearningSubjectEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "color_hex")
    val colorHex: Long,
    @ColumnInfo(name = "weekly_goal_minutes")
    val weeklyGoalMinutes: Int,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,
    @Embedded
    val sync: SyncMetadataEntity,
    @ColumnInfo(name = "daily_goal_minutes", defaultValue = "0")
    val dailyGoalMinutes: Int = 0
) {
    fun toDomain(): LearningSubject = LearningSubject(
        id = id,
        ownerUserId = sync.ownerUserId,
        title = title,
        colorHex = colorHex,
        weeklyGoalMinutes = weeklyGoalMinutes,
        dailyGoalMinutes = dailyGoalMinutes,
        sortOrder = sortOrder,
        isArchived = isArchived,
        syncStatus = SyncStatus.entries.firstOrNull { it.name == sync.syncStatus } ?: SyncStatus.LOCAL_ONLY
    )
}

@Entity(
    tableName = "learning_books",
    foreignKeys = [
        ForeignKey(
            entity = LearningSubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subject_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["subject_id"]),
        Index(value = ["deleted_at_ms"])
    ]
)
data class LearningBookEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "subject_id")
    val subjectId: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "author")
    val author: String? = null,
    @ColumnInfo(name = "total_pages")
    val totalPages: Int,
    @ColumnInfo(name = "current_page")
    val currentPage: Int = 0,
    @ColumnInfo(name = "total_chapters")
    val totalChapters: Int = 0,
    @ColumnInfo(name = "completed_chapters")
    val completedChapters: Int = 0,
    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false,
    @Embedded
    val sync: SyncMetadataEntity
) {
    fun toDomain(): LearningBook = LearningBook(
        id = id,
        ownerUserId = sync.ownerUserId,
        subjectId = subjectId,
        title = title,
        author = author,
        totalPages = totalPages,
        currentPage = currentPage,
        totalChapters = totalChapters,
        completedChapters = completedChapters,
        isCompleted = isCompleted,
        syncStatus = SyncStatus.entries.firstOrNull { it.name == sync.syncStatus } ?: SyncStatus.LOCAL_ONLY
    )
}

@Entity(
    tableName = "learning_topics",
    foreignKeys = [
        ForeignKey(
            entity = LearningSubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subject_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = LearningTopicEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_topic_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["subject_id"]),
        Index(value = ["book_id"]),
        Index(value = ["parent_topic_id"]),
        Index(value = ["deleted_at_ms"])
    ]
)
data class LearningTopicEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "subject_id")
    val subjectId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String? = null,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "status")
    val status: String = TopicStatus.TODO.name,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @Embedded
    val sync: SyncMetadataEntity,
    @ColumnInfo(name = "parent_topic_id")
    val parentTopicId: String? = null
) {
    fun toDomain(): LearningTopic = LearningTopic(
        id = id,
        ownerUserId = sync.ownerUserId,
        subjectId = subjectId,
        bookId = bookId,
        parentTopicId = parentTopicId,
        title = title,
        status = TopicStatus.entries.firstOrNull { it.name == status } ?: TopicStatus.TODO,
        sortOrder = sortOrder,
        syncStatus = SyncStatus.entries.firstOrNull { it.name == sync.syncStatus } ?: SyncStatus.LOCAL_ONLY
    )
}
