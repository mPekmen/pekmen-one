package net.pekmen.one.core.platform.planner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.pekmen.one.core.platform.SystemPermissionChecker

class TaskReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TASK_REMINDER) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf { it.isNotBlank() } ?: return
        val fallbackTitle = intent.getStringExtra(EXTRA_TASK_TITLE)?.takeIf { it.isNotBlank() }
        val provider = context.applicationContext as? TaskReminderTriggerProvider

        if (provider == null) {
            if (fallbackTitle != null) {
                val scheduler = TaskReminderScheduler(
                    context = context.applicationContext,
                    permissionChecker = SystemPermissionChecker(context.applicationContext)
                )
                scheduler.showReminderNotification(taskId = taskId, title = fallbackTitle)
            }
            return
        }

        val pendingResult: PendingResult? = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                provider.taskReminderTriggerHandler().handleAccountReminderFired(
                    taskId,
                    intent.getStringExtra(EXTRA_OWNER_ID)
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_TASK_REMINDER = "net.pekmen.one.action.TASK_REMINDER"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TASK_TITLE = "extra_task_title"
        const val EXTRA_OWNER_ID = "extra_task_owner_id"
    }
}

interface TaskReminderTriggerHandler {
    suspend fun handleTaskReminderFired(taskId: String)
    suspend fun handleBootOrPackageReplaced()
    suspend fun handleAccountReminderFired(taskId: String, ownerId: String?) = handleTaskReminderFired(taskId)
}

interface TaskReminderTriggerProvider {
    fun taskReminderTriggerHandler(): TaskReminderTriggerHandler
}
