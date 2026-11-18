package net.pekmen.one.core.platform

import net.pekmen.one.core.model.FocusPhase

interface FocusTriggerHandler {
    suspend fun pauseActiveFromSystem()
    suspend fun resumeActiveFromSystem()
    suspend fun startAwaitingPhaseFromSystem()
    suspend fun completeActiveFromSystem()
    suspend fun handlePhaseAlarmExpired(sessionId: String, segmentId: String)
    suspend fun handleDeviceBootOrUpdate()
    suspend fun handleDeviceShutdown()
}

interface FocusTriggerProvider {
    fun focusTriggerHandler(): FocusTriggerHandler
}

interface FocusNotificationGateway {
    fun showRunningNotification(
        sessionId: String,
        profileName: String,
        phase: FocusPhase,
        isCountdown: Boolean,
        chronometerBaseWallMs: Long
    )

    fun showPausedNotification(
        sessionId: String,
        profileName: String,
        phase: FocusPhase,
        netFocusSec: Long,
        interruptedByReboot: Boolean
    )

    fun showAwaitingNextPhaseNotification(
        sessionId: String,
        profileName: String,
        completedPhase: FocusPhase,
        nextPhase: FocusPhase?
    )

    fun showPhaseCompletedAlert(profileName: String, completedPhase: FocusPhase, nextPhase: FocusPhase?)

    fun cancelAllSessionNotifications()
}

interface FocusAlarmGateway {
    fun schedulePhaseEnd(sessionId: String, segmentId: String, triggerAtElapsedMs: Long)
    fun cancelPhaseEnd()
    fun canScheduleExactAlarms(): Boolean
}
