package net.pekmen.one.core.data

import net.pekmen.one.core.model.DailyFocusSummary
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.LearningBook
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.LearningTopic
import net.pekmen.one.core.model.ProfileFocusBreakdown
import net.pekmen.one.core.model.SubjectWeeklyProgress
import net.pekmen.one.core.model.WeeklyFocusSummary
import java.time.LocalDate

object FocusSummaryAggregator {

    fun summarizeDay(date: LocalDate, items: List<CompletedSessionItem>): DailyFocusSummary {
        val dayItems = items.filter { it.session.effectiveDayDate == date }
        val focusSeconds = dayItems.sumOf { it.session.actualFocusSec }
        val breakSeconds = dayItems.sumOf { it.session.actualBreakSec }
        val ratings = dayItems.mapNotNull { it.session.focusRating }
        val avgRating = if (ratings.isNotEmpty()) ratings.average() else null

        return DailyFocusSummary(
            date = date,
            netFocusSeconds = focusSeconds,
            breakSeconds = breakSeconds,
            completedSessionCount = dayItems.size,
            averageFocusRating = avgRating
        )
    }

    fun summarizeWeek(anchorDate: LocalDate, items: List<CompletedSessionItem>): WeeklyFocusSummary {
        val windowDates = EffectiveDayCalculator.sevenDayWindowEndingOn(anchorDate)
        val startDate = windowDates.first()
        val endDate = windowDates.last()
        val windowItems = items.filter {
            !it.session.effectiveDayDate.isBefore(startDate) &&
                !it.session.effectiveDayDate.isAfter(endDate)
        }

        val dailyBreakdown = windowDates.map { day -> summarizeDay(day, windowItems) }
        val profileBreakdown = windowItems
            .groupBy { it.profile.id }
            .values
            .map { group ->
                ProfileFocusBreakdown(
                    profile = group.first().profile,
                    focusSeconds = group.sumOf { it.session.actualFocusSec },
                    breakSeconds = group.sumOf { it.session.actualBreakSec },
                    sessionCount = group.size
                )
            }
            .sortedByDescending { it.focusSeconds }

        return WeeklyFocusSummary(
            startDate = startDate,
            endDate = endDate,
            totalFocusSeconds = windowItems.sumOf { it.session.actualFocusSec },
            totalBreakSeconds = windowItems.sumOf { it.session.actualBreakSec },
            completedSessionCount = windowItems.size,
            dailyBreakdown = dailyBreakdown,
            profileBreakdown = profileBreakdown
        )
    }

    fun summarizeSubjectsForWeek(
        anchorDate: LocalDate,
        subjects: List<LearningSubject>,
        books: List<LearningBook>,
        topics: List<LearningTopic>,
        items: List<CompletedSessionItem>
    ): List<SubjectWeeklyProgress> {
        val windowDates = EffectiveDayCalculator.sevenDayWindowEndingOn(anchorDate)
        val startDate = windowDates.first()
        val endDate = windowDates.last()
        val windowLinkedItems = items.filter { item ->
            val day = item.session.effectiveDayDate
            item.session.subjectId != null &&
                !day.isBefore(startDate) &&
                !day.isAfter(endDate)
        }
        val sessionsBySubject = windowLinkedItems.groupBy { it.session.subjectId }
        val booksBySubject = books.groupBy { it.subjectId }
        val topicsBySubject = topics.groupBy { it.subjectId }

        return subjects.map { subject ->
            val subjectSessions = sessionsBySubject[subject.id].orEmpty()
            SubjectWeeklyProgress(
                subject = subject,
                netFocusSecondsThisWeek = subjectSessions.sumOf { it.session.actualFocusSec },
                completedSessionCount = subjectSessions.size,
                books = booksBySubject[subject.id].orEmpty(),
                topics = topicsBySubject[subject.id].orEmpty()
            )
        }
    }
}
