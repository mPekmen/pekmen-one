package net.pekmen.one.core.data

import net.pekmen.one.core.database.entity.FocusProfileEntity
import net.pekmen.one.core.database.entity.FocusSessionEntity
import net.pekmen.one.core.database.entity.FocusSessionSegmentEntity
import net.pekmen.one.core.database.relation.FocusSessionWithDetails
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusSessionMode
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.model.FocusTimeMath
import net.pekmen.one.core.model.UserPreferences
import net.pekmen.one.core.platform.FocusAlarmGateway
import net.pekmen.one.core.platform.FocusNotificationGateway
import net.pekmen.one.core.platform.FocusZenTileGateway
import net.pekmen.one.core.platform.NoOpFocusZenTileGateway
import net.pekmen.one.core.platform.ProfileNameProvider
import net.pekmen.one.core.platform.samsung.SamsungRoutineBridgeHelper

internal data class PlatformGateways(
    val notificationGateway: FocusNotificationGateway,
    val alarmGateway: FocusAlarmGateway,
    val profileNameProvider: ProfileNameProvider,
    val zenTileGateway: FocusZenTileGateway = NoOpFocusZenTileGateway,
    val prefs: UserPreferences? = null
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
        cyclesBeforeLongBreak: Int,
        customBreakEnabled: Boolean
    ): FocusPhase? {
        return when (mode) {
            FocusSessionMode.STOPWATCH -> null
            FocusSessionMode.CUSTOM -> FocusPhase.SHORT_BREAK
                .takeIf { customBreakEnabled && currentPhase == FocusPhase.FOCUS }
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
                val domainProfile = effect.profile.toDomain()
                val phase = FocusPhase.valueOf(effect.session.currentPhase)
                val subtext = routineSubtextFor(gateways.prefs, domainProfile, FocusSessionStatus.PAUSED, phase)
                gateways.notificationGateway.showPausedNotification(
                    sessionId = effect.session.id,
                    profileName = gateways.profileNameProvider.resolve(domainProfile),
                    phase = phase,
                    netFocusSec = effect.session.actualFocusSec,
                    interruptedByReboot = effect.interruptedByReboot,
                    routineSubtext = subtext
                )
                gateways.zenTileGateway.syncSessionState(
                    zenIntegrationEnabled = gateways.prefs?.zenIntegrationEnabled ?: false,
                    profile = domainProfile,
                    status = FocusSessionStatus.PAUSED,
                    phase = phase
                )
            }
            is TransitionEffect.Awaiting -> {
                gateways.alarmGateway.cancelPhaseEnd()
                val domainProfile = effect.profile.toDomain()
                val completedPhase = FocusPhase.valueOf(effect.session.currentPhase)
                val subtext = routineSubtextFor(
                    gateways.prefs,
                    domainProfile,
                    FocusSessionStatus.AWAITING_NEXT_PHASE,
                    completedPhase
                )
                gateways.notificationGateway.showAwaitingNextPhaseNotification(
                    sessionId = effect.session.id,
                    profileName = gateways.profileNameProvider.resolve(domainProfile),
                    completedPhase = completedPhase,
                    nextPhase = effect.nextPhase,
                    routineSubtext = subtext
                )
                gateways.zenTileGateway.syncSessionState(
                    zenIntegrationEnabled = gateways.prefs?.zenIntegrationEnabled ?: false,
                    profile = domainProfile,
                    status = FocusSessionStatus.AWAITING_NEXT_PHASE,
                    phase = completedPhase
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
        val domainProfile = running.profile.toDomain()
        val profileName = gateways.profileNameProvider.resolve(domainProfile)
        val phase = FocusPhase.valueOf(running.session.currentPhase)
        val subtext = routineSubtextFor(gateways.prefs, domainProfile, FocusSessionStatus.RUNNING, phase)

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
                chronometerBaseWallMs = nowWall + remainingMs,
                routineSubtext = subtext
            )
        } else {
            gateways.alarmGateway.cancelPhaseEnd()
            val runningOpenMs = (nowElapsed - openSegment.startedAtElapsedMs).coerceAtLeast(0L)
            gateways.notificationGateway.showRunningNotification(
                sessionId = running.session.id,
                profileName = profileName,
                phase = phase,
                isCountdown = false,
                chronometerBaseWallMs = nowWall - running.totalFocusMs - runningOpenMs,
                routineSubtext = subtext
            )
        }
        gateways.zenTileGateway.syncSessionState(
            zenIntegrationEnabled = gateways.prefs?.zenIntegrationEnabled ?: false,
            profile = domainProfile,
            status = FocusSessionStatus.RUNNING,
            phase = phase
        )
    }

    private fun routineSubtextFor(
        prefs: UserPreferences?,
        profile: net.pekmen.one.core.model.FocusProfile,
        status: FocusSessionStatus,
        phase: FocusPhase
    ): String? {
        return SamsungRoutineBridgeHelper.appendKeywordSubtext(
            baseSubtext = null,
            routineKeywordEnabled = prefs?.routineKeywordEnabled ?: false,
            slug = profile.builtInSlug,
            status = status,
            phase = phase
        )
    }
}
