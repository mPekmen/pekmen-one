package net.pekmen.one.core.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

class FocusAlarmScheduler(
    private val context: Context,
    private val permissionChecker: SystemPermissionChecker
) : FocusAlarmGateway {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExactAlarms(): Boolean = permissionChecker.canScheduleExactAlarms()

    override fun schedulePhaseEnd(sessionId: String, segmentId: String, triggerAtElapsedMs: Long) {
        val manager = alarmManager ?: return
        val pendingIntent = buildAlarmPendingIntent(sessionId, segmentId)
        manager.cancel(pendingIntent)

        if (canScheduleExactAlarms()) {
            try {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMs,
                    pendingIntent
                )
                return
            } catch (_: SecurityException) {
                // Fall back gracefully if permission was revoked mid-flight.
            }
        }

        manager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtElapsedMs,
            pendingIntent
        )
    }

    override fun cancelPhaseEnd() {
        val manager = alarmManager ?: return
        val intent = Intent(context, FocusAlarmReceiver::class.java).apply {
            action = FocusAlarmReceiver.ACTION_PHASE_EXPIRED
        }
        val existing = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_PHASE_ALARM,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existing != null) {
            manager.cancel(existing)
            existing.cancel()
        }
    }

    private fun buildAlarmPendingIntent(sessionId: String, segmentId: String): PendingIntent {
        val intent = Intent(context, FocusAlarmReceiver::class.java).apply {
            action = FocusAlarmReceiver.ACTION_PHASE_EXPIRED
            putExtra(FocusAlarmReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(FocusAlarmReceiver.EXTRA_SEGMENT_ID, segmentId)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_PHASE_ALARM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val REQUEST_CODE_PHASE_ALARM = 4101
    }
}
