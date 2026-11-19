package net.pekmen.one.core.platform

import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.FocusSessionStatus

interface FocusTriggerHandler {
    suspend fun pauseActiveFromSystem()
    suspend fun resumeActiveFromSystem()
    suspend fun startAwaitingPhaseFromSystem()
    suspend fun completeActiveFromSystem()
    suspend fun handlePhaseAlarmExpired(sessionId: String, segmentId: String)
    suspend fun handleDeviceBootOrUpdate()
    suspend fun handleDeviceShutdown()

    suspend fun handleNotificationAction(action: String, sessionId: String?, ownerId: String?) {
        when (action) {
            FocusActionReceiver.ACTION_PAUSE -> pauseActiveFromSystem()
            FocusActionReceiver.ACTION_RESUME -> resumeActiveFromSystem()
            FocusActionReceiver.ACTION_START_NEXT -> startAwaitingPhaseFromSystem()
            FocusActionReceiver.ACTION_FINISH -> completeActiveFromSystem()
        }
    }

    suspend fun handleAccountPhaseAlarm(sessionId: String, segmentId: String, ownerId: String?) {
        handlePhaseAlarmExpired(sessionId, segmentId)
    }
}

interface FocusTriggerProvider {
    fun focusTriggerHandler(): FocusTriggerHandler
}

interface FocusZenTileGateway {
    fun syncSessionState(
        zenIntegrationEnabled: Boolean,
        profile: FocusProfile?,
        status: FocusSessionStatus?,
        phase: FocusPhase?
    ) = Unit

    fun deactivate() = Unit
}

object NoOpFocusZenTileGateway : FocusZenTileGateway

interface FocusNotificationGateway {
    fun showRunningNotification(
        sessionId: String,
        profileName: String,
        phase: FocusPhase,
        isCountdown: Boolean,
        chronometerBaseWallMs: Long
    )

    fun showRunningNotification(
        sessionId: String,
        profileName: String,
        phase: FocusPhase,
        isCountdown: Boolean,
        chronometerBaseWallMs: Long,
        routineSubtext: String?
    ) = showRunningNotification(sessionId, profileName, phase, isCountdown, chronometerBaseWallMs)

    fun showPausedNotification(
        sessionId: String,
        profileName: String,
        phase: FocusPhase,
        netFocusSec: Long,
        interruptedByReboot: Boolean
    )

    fun showPausedNotification(
        sessionId: String,
        profileName: String,
        phase: FocusPhase,
        netFocusSec: Long,
        interruptedByReboot: Boolean,
        routineSubtext: String?
    ) = showPausedNotification(sessionId, profileName, phase, netFocusSec, interruptedByReboot)

    fun showAwaitingNextPhaseNotification(
        sessionId: String,
        profileName: String,
        completedPhase: FocusPhase,
        nextPhase: FocusPhase?
    )

    fun showAwaitingNextPhaseNotification(
        sessionId: String,
        profileName: String,
        completedPhase: FocusPhase,
        nextPhase: FocusPhase?,
        routineSubtext: String?
    ) = showAwaitingNextPhaseNotification(sessionId, profileName, completedPhase, nextPhase)

    fun showPhaseCompletedAlert(profileName: String, completedPhase: FocusPhase, nextPhase: FocusPhase?)

    fun cancelAllSessionNotifications()
}

interface FocusAlarmGateway {
    fun schedulePhaseEnd(sessionId: String, segmentId: String, triggerAtElapsedMs: Long)
    fun cancelPhaseEnd()
    fun canScheduleExactAlarms(): Boolean
}
