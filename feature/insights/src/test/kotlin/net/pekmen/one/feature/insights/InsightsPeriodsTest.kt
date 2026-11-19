package net.pekmen.one.feature.insights

import net.pekmen.one.core.data.CompletedSessionItem
import net.pekmen.one.core.model.BuiltInProfileSlug
import net.pekmen.one.core.model.BuiltInProfiles
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class InsightsPeriodsTest {
    @Test
    fun localPeriodBoundariesOnlyCountSessionsInTheSelectedDates() {
        val today = LocalDate.of(2026, 11, 19)
        val rows = listOf(row(today.minusDays(21)), row(today.minusDays(14)), row(today.minusDays(3)), row(today))
        val periods = InsightsPeriod.entries.associateWith { period ->
            periodSummary(period, period.startOn(today), today, rows, goalMinutes = 30)
        }
        val day = periods.getValue(InsightsPeriod.DAY)
        val week = periods.getValue(InsightsPeriod.WEEK)
        val month = periods.getValue(InsightsPeriod.MONTH)

        assertEquals(today, day.start)
        assertEquals(LocalDate.of(2026, 11, 16), week.start)
        assertEquals(LocalDate.of(2026, 11, 1), month.start)
        assertEquals(1, day.completed)
        assertEquals(2, week.completed)
        assertEquals(3, month.completed)
        assertEquals(1_800L, month.focusSeconds)
        assertEquals(180L, month.breakSeconds)
        assertEquals(1_800L, month.categorySeconds[BuiltInProfileSlug.STUDY])
        assertEquals(1_800L, month.subjectSeconds["subject"])
    }

    private fun row(date: LocalDate): CompletedSessionItem {
        val profile = BuiltInProfiles.defaults().first { it.builtInSlug == BuiltInProfileSlug.STUDY }
        val session = FocusSession(
            id = date.toString(),
            profileId = profile.id,
            subjectId = "subject",
            sessionMode = FocusSessionMode.POMODORO,
            status = FocusSessionStatus.COMPLETED,
            currentPhase = FocusPhase.FOCUS,
            completedFocusCycles = 1,
            startedAtWallMs = 0,
            effectiveDayDate = date,
            actualFocusSec = 600,
            actualBreakSec = 60,
            pauseCount = 0
        )
        return CompletedSessionItem(session, profile)
    }
}
