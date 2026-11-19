package net.pekmen.one.core.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.pekmen.one.core.model.FocusPhase
import java.text.NumberFormat
import java.util.Locale

class FocusNotificationManager(
    private val context: Context,
    private val permissionChecker: SystemPermissionChecker,
    private val launcherActivityClass: Class<out Activity>
) : FocusNotificationGateway {

    private val notificationManager: NotificationManagerCompat = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val ongoingChannel = NotificationChannel(
            CHANNEL_ONGOING,
            context.getString(R.string.channel_focus_ongoing_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_focus_ongoing_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val transitionChannel = NotificationChannel(
            CHANNEL_TRANSITIONS,
            context.getString(R.string.channel_focus_transitions_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_focus_transitions_desc)
            enableVibration(true)
        }
        manager.createNotificationChannels(listOf(ongoingChannel, transitionChannel))
    }

    override fun showRunningNotification(
        sessionId: String,
        profileName: String,
        phase: FocusPhase,
        isCountdown: Boolean,
        chronometerBaseWallMs: Long
    ) {
        showRunningNotification(
            sessionId = sessionId,
            profileName = profileName,
            phase = phase,
            isCountdown = isCountdown,
            chronometerBaseWallMs = chronometerBaseWallMs,
            routineSubtext = null
        )
    }

    override fun showRunningNotification(
        sessionId: String,
        profileName: String,
        phase: FocusPhase,
        isCountdown: Boolean,
        chronometerBaseWallMs: Long,
        routineSubtext: String?
    ) {
        if (!permissionChecker.hasNotificationPermission()) return
        ensureChannels()

        val phaseLabel = phaseLabel(phase)
        val notification = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(context.getString(R.string.notif_running_title, profileName, phaseLabel))
            .setContentText(context.getString(R.string.notif_running_body))
            .apply { if (!routineSubtext.isNullOrBlank()) setSubText(routineSubtext) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(chronometerBaseWallMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(isCountdown)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildPublicVersion())
            .setContentIntent(buildContentIntent())
            .addAction(
                0,
                context.getString(R.string.notif_action_pause),
                buildActionPendingIntent(FocusActionReceiver.ACTION_PAUSE, REQUEST_PAUSE)
            )
            .addAction(
                0,
                context.getString(R.string.notif_action_finish),
                buildActionPendingIntent(FocusActionReceiver.ACTION_FINISH, REQUEST_FINISH)
            )
            .build()

        postSafely(NOTIFICATION_ID_ONGOING, notification)
    }

    override fun showPausedNotification(
        sessionId: String,
        profileName: String,
        phase: FocusPhase,
        netFocusSec: Long,
        interruptedByReboot: Boolean
    ) {
        showPausedNotification(
            sessionId = sessionId,
            profileName = profileName,
            phase = phase,
            netFocusSec = netFocusSec,
            interruptedByReboot = interruptedByReboot,
            routineSubtext = null
        )
    }

    override fun showPausedNotification(
        sessionId: String,
        profileName: String,
        phase: FocusPhase,
        netFocusSec: Long,
        interruptedByReboot: Boolean,
        routineSubtext: String?
    ) {
        if (!permissionChecker.hasNotificationPermission()) return
        ensureChannels()

        val formattedFocus = formatBriefDuration(netFocusSec)
        val title = if (interruptedByReboot) {
            context.getString(R.string.notif_reboot_paused_title, profileName)
        } else {
            context.getString(R.string.notif_paused_title, profileName)
        }
        val body = if (interruptedByReboot) {
            context.getString(R.string.notif_reboot_paused_body, formattedFocus)
        } else {
            context.getString(R.string.notif_paused_body, formattedFocus)
        }
        val channelId = if (interruptedByReboot) CHANNEL_TRANSITIONS else CHANNEL_ONGOING

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(title)
            .setContentText(body)
            .apply { if (!routineSubtext.isNullOrBlank()) setSubText(routineSubtext) }
            .setOngoing(true)
            .setOnlyAlertOnce(!interruptedByReboot)
            .setUsesChronometer(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildPublicVersion())
            .setContentIntent(buildContentIntent())
            .addAction(
                0,
                context.getString(R.string.notif_action_resume),
                buildActionPendingIntent(FocusActionReceiver.ACTION_RESUME, REQUEST_RESUME)
            )
            .addAction(
                0,
                context.getString(R.string.notif_action_finish),
                buildActionPendingIntent(FocusActionReceiver.ACTION_FINISH, REQUEST_FINISH)
            )
            .build()

        postSafely(NOTIFICATION_ID_ONGOING, notification)
    }

    override fun showAwaitingNextPhaseNotification(
        sessionId: String,
        profileName: String,
        completedPhase: FocusPhase,
        nextPhase: FocusPhase?
    ) {
        showAwaitingNextPhaseNotification(
            sessionId = sessionId,
            profileName = profileName,
            completedPhase = completedPhase,
            nextPhase = nextPhase,
            routineSubtext = null
        )
    }

    override fun showAwaitingNextPhaseNotification(
        sessionId: String,
        profileName: String,
        completedPhase: FocusPhase,
        nextPhase: FocusPhase?,
        routineSubtext: String?
    ) {
        if (!permissionChecker.hasNotificationPermission()) return
        ensureChannels()

        val body = if (nextPhase != null) {
            context.getString(R.string.notif_awaiting_next_body, phaseLabel(nextPhase))
        } else {
            context.getString(R.string.notif_awaiting_finish_body)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_TRANSITIONS)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(context.getString(R.string.notif_awaiting_title, profileName))
            .setContentText(body)
            .apply { if (!routineSubtext.isNullOrBlank()) setSubText(routineSubtext) }
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildPublicVersion())
            .setContentIntent(buildContentIntent())

        if (nextPhase != null) {
            builder.addAction(
                0,
                context.getString(R.string.notif_action_start_next),
                buildActionPendingIntent(FocusActionReceiver.ACTION_START_NEXT, REQUEST_START_NEXT)
            )
        }
        builder.addAction(
            0,
            context.getString(R.string.notif_action_finish),
            buildActionPendingIntent(FocusActionReceiver.ACTION_FINISH, REQUEST_FINISH)
        )

        postSafely(NOTIFICATION_ID_ONGOING, builder.build())
    }

    override fun showPhaseCompletedAlert(
        profileName: String,
        completedPhase: FocusPhase,
        nextPhase: FocusPhase?
    ) {
        if (!permissionChecker.hasNotificationPermission()) return
        ensureChannels()

        val body = if (nextPhase != null) {
            context.getString(R.string.notif_awaiting_next_body, phaseLabel(nextPhase))
        } else {
            context.getString(R.string.notif_awaiting_finish_body)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_TRANSITIONS)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(context.getString(R.string.notif_awaiting_title, profileName))
            .setContentText(body)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildPublicVersion())
            .setContentIntent(buildContentIntent())
            .build()

        postSafely(NOTIFICATION_ID_TRANSITION_ALERT, notification)
    }

    override fun cancelAllSessionNotifications() {
        notificationManager.cancel(NOTIFICATION_ID_ONGOING)
        notificationManager.cancel(NOTIFICATION_ID_TRANSITION_ALERT)
    }

    @SuppressLint("MissingPermission")
    private fun postSafely(id: Int, notification: android.app.Notification) {
        if (!permissionChecker.hasNotificationPermission()) return
        notificationManager.notify(id, notification)
    }

    private fun buildPublicVersion(): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(context.getString(R.string.notif_public_ongoing_title))
            .setContentText(context.getString(R.string.notif_public_ongoing_body))
            .build()
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(context, launcherActivityClass).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, FocusActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun phaseLabel(phase: FocusPhase): String {
        return when (phase) {
            FocusPhase.FOCUS -> context.getString(R.string.notif_phase_focus)
            FocusPhase.SHORT_BREAK -> context.getString(R.string.notif_phase_short_break)
            FocusPhase.LONG_BREAK -> context.getString(R.string.notif_phase_long_break)
        }
    }

    private fun formatBriefDuration(totalSeconds: Long): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val nf = NumberFormat.getIntegerInstance(locale)
        val minutes = totalSeconds.coerceAtLeast(0L) / 60L
        val hours = minutes / 60L
        val remMin = minutes % 60L
        return if (hours > 0L) {
            context.getString(R.string.notif_duration_hm, nf.format(hours), nf.format(remMin))
        } else {
            context.getString(R.string.notif_duration_m, nf.format(minutes))
        }
    }

    private companion object {
        const val CHANNEL_ONGOING = "pekmen_focus_ongoing"
        const val CHANNEL_TRANSITIONS = "pekmen_focus_transitions"
        const val NOTIFICATION_ID_ONGOING = 1001
        const val NOTIFICATION_ID_TRANSITION_ALERT = 1002
        const val REQUEST_CONTENT = 4200
        const val REQUEST_PAUSE = 4201
        const val REQUEST_RESUME = 4202
        const val REQUEST_START_NEXT = 4203
        const val REQUEST_FINISH = 4204
    }
}
