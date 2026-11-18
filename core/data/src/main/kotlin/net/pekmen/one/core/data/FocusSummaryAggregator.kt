package net.pekmen.one.core.data

import net.pekmen.one.core.model.DailyFocusSummary
import net.pekmen.one.core.model.EffectiveDayCalculator
import net.pekmen.one.core.model.ProfileFocusBreakdown
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
}
