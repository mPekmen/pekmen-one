package net.pekmen.one.core.data

import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.platform.FocusAlarmGateway
import net.pekmen.one.core.platform.SystemPermissionChecker
import java.time.ZoneId

data class SystemDiagnostics(
    val hasNotificationPermission: Boolean,
    val canScheduleExactAlarms: Boolean
)

interface SystemDiagnosticsRepository {
    fun refreshDiagnostics(): SystemDiagnostics
    fun currentWallTimeMs(): Long
    fun currentElapsedRealtimeMs(): Long
    fun currentBootCount(): Int
    fun currentZoneId(): ZoneId
}

class SystemDiagnosticsRepositoryImpl(
    private val permissionChecker: SystemPermissionChecker,
    private val alarmGateway: FocusAlarmGateway,
    private val timeProvider: TimeProvider
) : SystemDiagnosticsRepository {

    override fun refreshDiagnostics(): SystemDiagnostics {
        return SystemDiagnostics(
            hasNotificationPermission = permissionChecker.hasNotificationPermission(),
            canScheduleExactAlarms = alarmGateway.canScheduleExactAlarms()
        )
    }

    override fun currentWallTimeMs(): Long = timeProvider.wallTimeMs()

    override fun currentElapsedRealtimeMs(): Long = timeProvider.elapsedRealtimeMs()

    override fun currentBootCount(): Int = timeProvider.bootCount()

    override fun currentZoneId(): ZoneId = timeProvider.zoneId()
}
