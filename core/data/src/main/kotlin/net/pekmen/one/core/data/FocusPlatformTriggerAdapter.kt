package net.pekmen.one.core.data

import net.pekmen.one.core.platform.FocusTriggerHandler

class FocusPlatformTriggerAdapter(
    private val sessionRepository: FocusSessionRepository
) : FocusTriggerHandler {

    override suspend fun pauseActiveFromSystem() {
        sessionRepository.pauseActiveSession()
    }

    override suspend fun resumeActiveFromSystem() {
        sessionRepository.resumeActiveSession()
    }

    override suspend fun startAwaitingPhaseFromSystem() {
        sessionRepository.startAwaitingNextPhase()
    }

    override suspend fun completeActiveFromSystem() {
        sessionRepository.completeActiveSession()
    }

    override suspend fun handlePhaseAlarmExpired(sessionId: String, segmentId: String) {
        sessionRepository.handlePhaseTimerExpired(sessionId, segmentId)
    }

    override suspend fun handleDeviceBootOrUpdate() {
        sessionRepository.reconcileActiveSessionAfterBootOrWake()
    }

    override suspend fun handleDeviceShutdown() {
        sessionRepository.checkpointActiveSession()
    }
}
