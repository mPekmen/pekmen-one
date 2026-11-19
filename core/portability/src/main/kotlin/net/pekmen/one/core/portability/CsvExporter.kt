package net.pekmen.one.core.portability

import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.LearningBookEntity
import net.pekmen.one.core.database.entity.LearningSubjectEntity
import net.pekmen.one.core.database.entity.LearningTopicEntity
import net.pekmen.one.core.database.entity.PlannerTaskEntity

object CsvExporter {

    private val formulaStartChars = charArrayOf('=', '+', '-', '@', '\t', '\r')
    private val trimmedFormulaChars = charArrayOf('=', '+', '-', '@')
    private val rfc4180QuoteTriggers = charArrayOf(',', '"', '\n', '\r')

    fun neutralizeFormulaInjection(raw: String): String {
        if (raw.isEmpty()) return raw
        val firstChar = raw[0]
        val trimmedFirst = raw.trimStart().firstOrNull()
        val needsPrefix = firstChar in formulaStartChars ||
            (trimmedFirst != null && trimmedFirst in trimmedFormulaChars)
        return if (needsPrefix) "'$raw" else raw
    }

    fun escapeCell(raw: String?): String {
        if (raw == null) return ""
        val safe = neutralizeFormulaInjection(raw)
        return if (safe.indexOfAny(rfc4180QuoteTriggers) >= 0) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else {
            safe
        }
    }

    fun formatRow(cells: List<String?>): String {
        return cells.joinToString(separator = ",") { escapeCell(it) }
    }

    fun exportSessionsCsv(
        sessions: List<FocusSessionEntity>,
        profilesById: Map<String, FocusProfileEntity>
    ): String {
        val header = formatRow(SESSIONS_HEADERS)
        val rows = sessions.map { session ->
            val profile = profilesById[session.profileId]
            val profileLabel = profile?.customName?.takeIf { it.isNotBlank() }
                ?: profile?.builtInSlug
                ?: session.profileId
            formatRow(
                listOf(
                    session.id,
                    session.effectiveDayDate,
                    session.profileId,
                    profileLabel,
                    session.subjectId,
                    session.bookId,
                    session.topicId,
                    session.taskId,
                    session.sessionMode,
                    session.status,
                    session.currentPhase,
                    session.startedAtWallMs.toString(),
                    session.endedAtWallMs?.toString(),
                    session.plannedFocusSec?.toString(),
                    session.actualFocusSec.toString(),
                    session.actualBreakSec.toString(),
                    session.pauseCount.toString(),
                    session.isManualEntry.toString(),
                    session.wasManuallyAdjusted.toString(),
                    session.interruptedByReboot.toString(),
                    session.focusRating?.toString(),
                    session.reflectionNote
                )
            )
        }
        return (listOf(header) + rows).joinToString(separator = "\r\n")
    }

    fun exportLearningCsv(
        subjects: List<LearningSubjectEntity>,
        books: List<LearningBookEntity>,
        topics: List<LearningTopicEntity>
    ): String {
        val subjectsById = subjects.associateBy { it.id }
        val header = formatRow(LEARNING_HEADERS)
        val rows = buildList {
            subjects.forEach { subject ->
                add(
                    formatRow(
                        listOf(
                            "SUBJECT",
                            subject.id,
                            subject.id,
                            subject.title,
                            null,
                            subject.title,
                            null,
                            if (subject.isArchived) "ARCHIVED" else "ACTIVE",
                            subject.weeklyGoalMinutes.toString(),
                            null,
                            null,
                            null,
                            null,
                            subject.sortOrder.toString()
                        )
                    )
                )
            }
            books.forEach { book ->
                add(
                    formatRow(
                        listOf(
                            "BOOK",
                            book.id,
                            book.subjectId,
                            subjectsById[book.subjectId]?.title,
                            book.id,
                            book.title,
                            book.author,
                            if (book.isCompleted) "COMPLETED" else "IN_PROGRESS",
                            null,
                            book.currentPage.toString(),
                            book.totalPages.toString(),
                            book.totalChapters.toString(),
                            book.completedChapters.toString(),
                            null
                        )
                    )
                )
            }
            topics.forEach { topic ->
                add(
                    formatRow(
                        listOf(
                            "TOPIC",
                            topic.id,
                            topic.subjectId,
                            subjectsById[topic.subjectId]?.title,
                            topic.bookId,
                            topic.title,
                            null,
                            topic.status,
                            null,
                            null,
                            null,
                            null,
                            null,
                            topic.sortOrder.toString()
                        )
                    )
                )
            }
        }
        return (listOf(header) + rows).joinToString(separator = "\r\n")
    }

    fun exportTasksCsv(tasks: List<PlannerTaskEntity>): String {
        val header = formatRow(TASKS_HEADERS)
        val rows = tasks.map { task ->
            formatRow(
                listOf(
                    task.id,
                    task.title,
                    task.notes,
                    task.subjectId,
                    task.status,
                    task.priority.toString(),
                    task.recurrenceRule,
                    task.dueAtWallMs?.toString(),
                    task.reminderAtWallMs?.toString(),
                    task.completedAtWallMs?.toString()
                )
            )
        }
        return (listOf(header) + rows).joinToString(separator = "\r\n")
    }

    private val SESSIONS_HEADERS = listOf(
        "session_id",
        "effective_day_date",
        "profile_id",
        "profile_name",
        "subject_id",
        "book_id",
        "topic_id",
        "task_id",
        "session_mode",
        "status",
        "current_phase",
        "started_at_wall_ms",
        "ended_at_wall_ms",
        "planned_focus_sec",
        "actual_focus_sec",
        "actual_break_sec",
        "pause_count",
        "is_manual_entry",
        "was_manually_adjusted",
        "interrupted_by_reboot",
        "focus_rating",
        "reflection_note"
    )

    private val LEARNING_HEADERS = listOf(
        "record_type",
        "id",
        "subject_id",
        "subject_title",
        "book_id",
        "title",
        "author",
        "status_or_archived",
        "weekly_goal_minutes",
        "current_page",
        "total_pages",
        "total_chapters",
        "completed_chapters",
        "sort_order"
    )

    private val TASKS_HEADERS = listOf(
        "task_id",
        "title",
        "notes",
        "subject_id",
        "status",
        "priority",
        "recurrence_rule",
        "due_at_wall_ms",
        "reminder_at_wall_ms",
        "completed_at_wall_ms"
    )
}
