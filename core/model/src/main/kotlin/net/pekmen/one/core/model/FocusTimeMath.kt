package net.pekmen.one.core.model

data class FocusDisplayClock(
    val primarySeconds: Long,
    val phaseElapsedSeconds: Long,
    val phasePlannedSeconds: Long?,
    val totalFocusSeconds: Long,
    val totalBreakSeconds: Long,
    val isCountdown: Boolean,
    val progressFraction: Float
)

object FocusTimeMath {

    const val MIN_VALID_SESSION_SECONDS = 60L

    fun phasePlannedDurationMs(profile: FocusProfile, phase: FocusPhase, customFocusSeconds: Long?): Long? {
        return when (phase) {
            FocusPhase.FOCUS -> (customFocusSeconds ?: (profile.workMinutes * 60L)) * 1000L
            FocusPhase.SHORT_BREAK -> profile.shortBreakMinutes * 60_000L
            FocusPhase.LONG_BREAK -> profile.longBreakMinutes * 60_000L
        }
    }

    fun openSegmentElapsedMs(
        segment: FocusSessionSegment,
        nowElapsedMs: Long,
        currentBootCount: Int
    ): Long {
        val raw = if (segment.bootCount == currentBootCount && nowElapsedMs >= segment.startedAtElapsedMs) {
            nowElapsedMs - segment.startedAtElapsedMs
        } else {
            segment.lastCheckpointedDurationMs
        }
        val nonNegative = raw.coerceAtLeast(0L)
        return segment.plannedDurationMs?.let { nonNegative.coerceAtMost(it) } ?: nonNegative
    }

    fun computeDisplayClock(
        snapshot: ActiveSessionSnapshot,
        nowElapsedMs: Long,
        currentBootCount: Int
    ): FocusDisplayClock {
        val (totalFocusMs, totalBreakMs, openElapsedMs) = sumTotalsMs(snapshot, nowElapsedMs, currentBootCount)

        if (snapshot.session.sessionMode == FocusSessionMode.STOPWATCH) {
            val focusSec = totalFocusMs / 1000L
            return FocusDisplayClock(
                primarySeconds = focusSec,
                phaseElapsedSeconds = focusSec,
                phasePlannedSeconds = null,
                totalFocusSeconds = focusSec,
                totalBreakSeconds = totalBreakMs / 1000L,
                isCountdown = false,
                progressFraction = (focusSec % 60L) / 60f
            )
        }

        val fullPhasePlannedMs = resolveFullPhasePlannedMs(snapshot)
        val remainingMs = computeCountdownRemainingMs(snapshot, openElapsedMs, fullPhasePlannedMs)
        val phaseElapsedMs = (fullPhasePlannedMs - remainingMs).coerceAtLeast(0L)
        val progress = (phaseElapsedMs.toFloat() / fullPhasePlannedMs.toFloat()).coerceIn(0f, 1f)

        return FocusDisplayClock(
            primarySeconds = remainingMs / 1000L,
            phaseElapsedSeconds = phaseElapsedMs / 1000L,
            phasePlannedSeconds = fullPhasePlannedMs / 1000L,
            totalFocusSeconds = totalFocusMs / 1000L,
            totalBreakSeconds = totalBreakMs / 1000L,
            isCountdown = true,
            progressFraction = progress
        )
    }

    private fun sumTotalsMs(
        snapshot: ActiveSessionSnapshot,
        nowElapsedMs: Long,
        currentBootCount: Int
    ): Triple<Long, Long, Long> {
        var closedFocusMs = 0L
        var closedBreakMs = 0L
        for (seg in snapshot.segments) {
            val dur = seg.durationMs ?: continue
            if (seg.phase == FocusPhase.FOCUS) closedFocusMs += dur else closedBreakMs += dur
        }
        val openSeg = snapshot.openSegment
        val openElapsedMs = if (snapshot.session.status == FocusSessionStatus.RUNNING && openSeg != null) {
            openSegmentElapsedMs(openSeg, nowElapsedMs, currentBootCount)
        } else {
            0L
        }
        val openFocus = if (openSeg?.phase == FocusPhase.FOCUS) openElapsedMs else 0L
        val openBreak = if (openSeg != null && openSeg.phase != FocusPhase.FOCUS) openElapsedMs else 0L
        return Triple(closedFocusMs + openFocus, closedBreakMs + openBreak, openElapsedMs)
    }

    private fun resolveFullPhasePlannedMs(snapshot: ActiveSessionSnapshot): Long {
        val displayPhase = snapshot.session.awaitingNextPhase ?: snapshot.session.currentPhase
        val customSec = snapshot.session.plannedFocusSec
            .takeIf { snapshot.session.sessionMode == FocusSessionMode.CUSTOM }
        return phasePlannedDurationMs(snapshot.profile, displayPhase, customSec)
            ?.coerceAtLeast(1000L)
            ?: 60_000L
    }

    private fun computeCountdownRemainingMs(
        snapshot: ActiveSessionSnapshot,
        openElapsedMs: Long,
        fullPhasePlannedMs: Long
    ): Long {
        val session = snapshot.session
        return when (session.status) {
            FocusSessionStatus.RUNNING -> {
                val planned = snapshot.openSegment?.plannedDurationMs ?: fullPhasePlannedMs
                (planned - openElapsedMs).coerceAtLeast(0L)
            }
            FocusSessionStatus.PAUSED -> {
                val lastSeg = snapshot.segments.lastOrNull { it.phase == session.currentPhase }
                if (lastSeg?.plannedDurationMs != null && lastSeg.durationMs != null) {
                    (lastSeg.plannedDurationMs - lastSeg.durationMs).coerceAtLeast(0L)
                } else {
                    fullPhasePlannedMs
                }
            }
            FocusSessionStatus.AWAITING_NEXT_PHASE -> {
                if (session.awaitingNextPhase == null) 0L else fullPhasePlannedMs
            }
            FocusSessionStatus.COMPLETED, FocusSessionStatus.ABANDONED -> 0L
        }
    }
}
