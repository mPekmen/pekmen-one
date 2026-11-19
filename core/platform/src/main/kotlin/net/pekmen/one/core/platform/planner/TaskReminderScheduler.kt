package net.pekmen.one.core.platform.planner

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.pekmen.one.core.platform.R
import net.pekmen.one.core.platform.SystemPermissionChecker

class TaskReminderScheduler(
    private val context: Context,
    private val permissionChecker: SystemPermissionChecker,
    private val launcherActivityClass: Class<out Activity>? = null,
    private val ownerId: String? = null
) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    private val notificationManager: NotificationManagerCompat = NotificationManagerCompat.from(context)

    fun scheduleReminder(taskId: String, title: String, triggerAtWallMs: Long) {
        if (taskId.isBlank() || title.isBlank()) return
        val manager = alarmManager ?: return
        val pendingIntent = buildBroadcastPendingIntent(
            taskId = taskId,
            title = title,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) ?: return

        manager.cancel(pendingIntent)

        if (permissionChecker.canScheduleExactAlarms()) {
            try {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtWallMs,
                    pendingIntent
                )
                return
            } catch (_: SecurityException) {
                // Fall back gracefully if exact alarm permission is revoked mid-flight.
            }
        }

        manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtWallMs,
            pendingIntent
        )
    }

    fun cancelReminder(taskId: String) {
        if (taskId.isBlank()) return
        val manager = alarmManager ?: return
        val existing = buildBroadcastPendingIntent(
            taskId = taskId,
            title = null,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existing != null) {
            manager.cancel(existing)
            existing.cancel()
        }
        notificationManager.cancel(notificationIdFor(taskId))
    }

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_TASK_REMINDERS,
            context.getString(R.string.channel_planner_reminders_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_planner_reminders_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun showReminderNotification(taskId: String, title: String) {
        if (taskId.isBlank() || title.isBlank()) return
        if (!permissionChecker.hasNotificationPermission()) return
        ensureChannel()

        val builder = NotificationCompat.Builder(context, CHANNEL_TASK_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_task_reminder_body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildPublicVersion())

        buildContentIntent(taskId)?.let { contentIntent ->
            builder.setContentIntent(contentIntent)
        }

        postSafely(notificationIdFor(taskId), builder.build())
    }

    @SuppressLint("MissingPermission")
    private fun postSafely(id: Int, notification: Notification) {
        if (!permissionChecker.hasNotificationPermission()) return
        notificationManager.notify(id, notification)
    }

    private fun buildPublicVersion(): Notification {
        return NotificationCompat.Builder(context, CHANNEL_TASK_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(context.getString(R.string.notif_task_reminder_public_title))
            .setContentText(context.getString(R.string.notif_task_reminder_public_body))
            .build()
    }

    private fun buildContentIntent(taskId: String): PendingIntent? {
        val activityClass = launcherActivityClass ?: return null
        val intent = Intent(context, activityClass).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TaskReminderReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskReminderReceiver.EXTRA_OWNER_ID, ownerId)
        }
        return PendingIntent.getActivity(
            context,
            requestCodeFor(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildBroadcastPendingIntent(
        taskId: String,
        title: String?,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = TaskReminderReceiver.ACTION_TASK_REMINDER
            data = Uri.fromParts(REMINDER_URI_SCHEME, "${ownerId.orEmpty()}/$taskId", null)
            putExtra(TaskReminderReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskReminderReceiver.EXTRA_OWNER_ID, ownerId)
            if (title != null) {
                putExtra(TaskReminderReceiver.EXTRA_TASK_TITLE, title)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(taskId),
            intent,
            flags
        )
    }

    private fun requestCodeFor(taskId: String): Int {
        return REQUEST_CODE_BASE + (taskId.hashCode() and HASH_MASK)
    }

    private fun notificationIdFor(taskId: String): Int {
        return NOTIFICATION_ID_BASE + (taskId.hashCode() and HASH_MASK)
    }

    companion object {
        const val CHANNEL_TASK_REMINDERS = "pekmen_planner_reminders"
        private const val REMINDER_URI_SCHEME = "pekmen-reminder"
        private const val REQUEST_CODE_BASE = 5100
        private const val NOTIFICATION_ID_BASE = 2100
        private const val HASH_MASK = 0x0FFF_FFFF
    }
}
