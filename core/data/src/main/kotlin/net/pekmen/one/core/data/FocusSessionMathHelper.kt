package net.pekmen.one.core.data

import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.relation.FocusSessionWithDetails
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusTimeMath
import net.pekmen.one.core.platform.FocusAlarmGateway
import net.pekmen.one.core.platform.FocusNotificationGateway
import net.pekmen.one.core.platform.ProfileNameProvider

internal data class PlatformGateways(
    val notificationGateway: FocusNotificationGateway,
    val alarmGateway: FocusAlarmGateway,
    val profileNameProvider: ProfileNameProvider
)

internal sealed interface TransitionEffect {
    data class Running(
        val session: FocusSessionEntity,
        val profile: FocusProfileEntity,
        val openSegment: FocusSessionSegmentEntity,
        val totalFocusMs: Long,
        val completedPhaseAlert: FocusPhase? = null
    ) : TransitionEffect

    data class Paused(
        val session: FocusSessionEntity,
        val profile: FocusProfileEntity,
        val interruptedByReboot: Boolean
    ) : TransitionEffect

    data class Awaiting(
        val session: FocusSessionEntity,
        val profile: FocusProfileEntity,
        val nextPhase: FocusPhase?,
        val showAlert: Boolean
    ) : TransitionEffect
}

internal object FocusSessionMathHelper {

    fun computeSegmentTotalsMs(segments: List<FocusSessionSegmentEntity>): Pair<Long, Long> {
        var focusMs = 0L
        var breakMs = 0L
        for (seg in segments) {
            val dur = seg.durationMs ?: continue
            if (seg.phase == FocusPhase.FOCUS.name) focusMs += dur else breakMs += dur
        }
        return focusMs to breakMs
    }

    fun computeRemainingCountdownMs(active: FocusSessionWithDetails, currentPhase: FocusPhase): Long {
        val lastSeg = active.segments.lastOrNull { it.phase == currentPhase.name }
        val planned = lastSeg?.plannedDurationMs
        val dur = lastSeg?.durationMs
        if (planned != null && dur != null) {
            return (planned - dur).coerceAtLeast(0L)
        }
        return FocusTimeMath.phasePlannedDurationMs(
            profile = active.profile.toDomain(),
            phase = currentPhase,
            customFocusSeconds = active.session.plannedFocusSec
        ) ?: 0L
    }

    fun determineNextPhase(
        mode: FocusSessionMode,
        currentPhase: FocusPhase,
        newCompletedCycles: Int,
        cyclesBeforeLongBreak: Int
    ): FocusPhase? {
        return when (mode) {
            FocusSessionMode.CUSTOM, FocusSessionMode.STOPWATCH -> null
            FocusSessionMode.POMODORO -> if (currentPhase == FocusPhase.FOCUS) {
                if (newCompletedCycles % cyclesBeforeLongBreak == 0) {
                    FocusPhase.LONG_BREAK
                } else {
                    FocusPhase.SHORT_BREAK
                }
            } else {
                FocusPhase.FOCUS
            }
        }
    }

    fun applyTransitionEffect(
        effect: TransitionEffect?,
        nowWall: Long,
        nowElapsed: Long,
        gateways: PlatformGateways
    ) {
        when (effect) {
            is TransitionEffect.Running -> {
                syncRunningPlatformState(effect, nowWall, nowElapsed, gateways)
                effect.completedPhaseAlert?.let { finished ->
                    gateways.notificationGateway.showPhaseCompletedAlert(
                        profileName = gateways.profileNameProvider.resolve(effect.profile.toDomain()),
                        completedPhase = finished,
                        nextPhase = FocusPhase.valueOf(effect.session.currentPhase)
                    )
                }
            }
            is TransitionEffect.Paused -> {
                gateways.alarmGateway.cancelPhaseEnd()
                gateways.notificationGateway.showPausedNotification(
                    sessionId = effect.session.id,
                    profileName = gateways.profileNameProvider.resolve(effect.profile.toDomain()),
                    phase = FocusPhase.valueOf(effect.session.currentPhase),
                    netFocusSec = effect.session.actualFocusSec,
                    interruptedByReboot = effect.interruptedByReboot
                )
            }
            is TransitionEffect.Awaiting -> {
                gateways.alarmGateway.cancelPhaseEnd()
                gateways.notificationGateway.showAwaitingNextPhaseNotification(
                    sessionId = effect.session.id,
                    profileName = gateways.profileNameProvider.resolve(effect.profile.toDomain()),
                    completedPhase = FocusPhase.valueOf(effect.session.currentPhase),
                    nextPhase = effect.nextPhase
                )
            }
            null -> Unit
        }
    }

    fun syncRunningPlatformState(
        running: TransitionEffect.Running,
        nowWall: Long,
        nowElapsed: Long,
        gateways: PlatformGateways
    ) {
        val openSegment = running.openSegment
        val plannedMs = openSegment.plannedDurationMs
        val profileName = gateways.profileNameProvider.resolve(running.profile.toDomain())
        val phase = FocusPhase.valueOf(running.session.currentPhase)

        if (plannedMs != null) {
            val remainingMs = (plannedMs - (nowElapsed - openSegment.startedAtElapsedMs)).coerceAtLeast(1000L)
            gateways.alarmGateway.schedulePhaseEnd(
                running.session.id,
                openSegment.id,
                openSegment.startedAtElapsedMs + plannedMs
            )
            gateways.notificationGateway.showRunningNotification(
                sessionId = running.session.id,
                profileName = profileName,
                phase = phase,
                isCountdown = true,
                chronometerBaseWallMs = nowWall + remainingMs
            )
        } else {
            gateways.alarmGateway.cancelPhaseEnd()
            val runningOpenMs = (nowElapsed - openSegment.startedAtElapsedMs).coerceAtLeast(0L)
            gateways.notificationGateway.showRunningNotification(
                sessionId = running.session.id,
                profileName = profileName,
                phase = phase,
                isCountdown = false,
                chronometerBaseWallMs = nowWall - running.totalFocusMs - runningOpenMs
            )
        }
    }
}
