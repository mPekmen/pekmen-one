package net.pekmen.one.core.cloud.circles

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.pekmen.one.core.database.entity.SocialMemberSummaryEntity
import net.pekmen.one.core.model.CircleMembership
import net.pekmen.one.core.model.CirclePeriodType
import net.pekmen.one.core.model.FocusSession
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.LearningSubject
import net.pekmen.one.core.model.SubjectSharedTotal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class CirclePeriodWindow(
    val periodType: CirclePeriodType,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val periodStartDate: String = startDate.toString()
)

data class ProjectedCircleSummary(
    val circleId: String,
    val userId: String,
    val displayName: String,
    val periodType: CirclePeriodType,
    val periodStartDate: String,
    val liveTimerFocusSec: Long,
    val manualEntryFocusSec: Long,
    val subjectTotals: List<SubjectSharedTotal>,
    val isCurrentlyFocusing: Boolean,
    val updatedAtMs: Long
) {
    fun toRemoteDto(id: String): RemoteMemberSummaryDto {
        return RemoteMemberSummaryDto(
            id = id,
            circleId = circleId,
            userId = userId,
            displayName = displayName,
            periodType = periodType,
            periodStartDate = periodStartDate,
            liveTimerFocusSec = liveTimerFocusSec,
            manualEntryFocusSec = manualEntryFocusSec,
            subjectTotals = subjectTotals.map {
                RemoteSubjectTotalDto(
                    subjectTitle = it.subjectTitle,
                    focusSeconds = it.focusSeconds
                )
            },
            isCurrentlyFocusing = isCurrentlyFocusing,
            updatedAtMs = updatedAtMs
        )
    }

    fun toEntity(id: String, json: Json = Json): SocialMemberSummaryEntity {
        val dtos = subjectTotals.map {
            RemoteSubjectTotalDto(
                subjectTitle = it.subjectTitle,
                focusSeconds = it.focusSeconds
            )
        }
        return SocialMemberSummaryEntity(
            id = id,
            circleId = circleId,
            userId = userId,
            displayName = displayName,
            periodType = periodType.name,
            periodStartDate = periodStartDate,
            liveTimerFocusSec = liveTimerFocusSec,
            manualEntryFocusSec = manualEntryFocusSec,
            subjectTotalsJson = json.encodeToString(dtos),
            isCurrentlyFocusing = isCurrentlyFocusing,
            updatedAtMs = updatedAtMs
        )
    }
}

/**
 * Projects local COMPLETED FocusSessions into an aggregate, privacy-bounded summary.
 * Structurally excludes raw sessions, reflection notes, focus ratings, books, and planner tasks.
 */
object CircleSummaryProjector {

    fun periodWindow(periodType: CirclePeriodType, anchorDate: LocalDate): CirclePeriodWindow {
        return when (periodType) {
            CirclePeriodType.DAILY -> CirclePeriodWindow(
                periodType = CirclePeriodType.DAILY,
                startDate = anchorDate,
                endDate = anchorDate
            )
            CirclePeriodType.WEEKLY -> {
                val weekStart = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val weekEnd = weekStart.plusDays(DAYS_IN_WEEK_MINUS_ONE)
                CirclePeriodWindow(
                    periodType = CirclePeriodType.WEEKLY,
                    startDate = weekStart,
                    endDate = weekEnd
                )
            }
        }
    }

    fun project(
        circleId: String,
        membership: CircleMembership,
        periodWindow: CirclePeriodWindow,
        completedSessions: List<FocusSession>,
        subjectsById: Map<String, LearningSubject>,
        activeFocusRunning: Boolean,
        nowMs: Long
    ): ProjectedCircleSummary? {
        if (membership.leftAtMs != null) return null
        val config = membership.sharingConfig
        if (!config.shareEnabled) return null

        val allowedTitles = config.allowedSubjectTitles
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        var liveTimerFocusSec = 0L
        var manualEntryFocusSec = 0L
        val subjectSecAccumulator = linkedMapOf<String, Long>()

        for (session in completedSessions) {
            if (!isEligibleSession(session, periodWindow)) continue
            val focusSec = session.actualFocusSec.coerceAtLeast(0L)
            if (session.isManualEntry) {
                manualEntryFocusSec += focusSec
            } else {
                liveTimerFocusSec += focusSec
            }
            if (allowedTitles.isNotEmpty()) {
                val subjectTitle = session.subjectId?.let { subjectsById[it]?.title?.trim() }
                if (subjectTitle != null && subjectTitle in allowedTitles) {
                    subjectSecAccumulator[subjectTitle] = (subjectSecAccumulator[subjectTitle] ?: 0L) + focusSec
                }
            }
        }

        val subjectTotals = subjectSecAccumulator.entries
            .map { (title, seconds) -> SubjectSharedTotal(subjectTitle = title, focusSeconds = seconds) }
            .sortedWith(
                compareByDescending<SubjectSharedTotal> { it.focusSeconds }
                    .thenBy { it.subjectTitle }
            )

        return ProjectedCircleSummary(
            circleId = circleId,
            userId = membership.userId,
            displayName = membership.displayName,
            periodType = periodWindow.periodType,
            periodStartDate = periodWindow.periodStartDate,
            liveTimerFocusSec = liveTimerFocusSec,
            manualEntryFocusSec = manualEntryFocusSec,
            subjectTotals = subjectTotals,
            isCurrentlyFocusing = config.shareLiveFocusStatus && activeFocusRunning,
            updatedAtMs = nowMs
        )
    }

    private fun isEligibleSession(session: FocusSession, periodWindow: CirclePeriodWindow): Boolean {
        if (session.status != FocusSessionStatus.COMPLETED) return false
        return !session.effectiveDayDate.isBefore(periodWindow.startDate) &&
            !session.effectiveDayDate.isAfter(periodWindow.endDate)
    }

    private const val DAYS_IN_WEEK_MINUS_ONE = 6L
}
