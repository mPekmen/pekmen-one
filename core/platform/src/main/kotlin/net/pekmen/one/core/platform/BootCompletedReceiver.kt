package net.pekmen.one.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.pekmen.one.core.platform.planner.TaskReminderTriggerProvider

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ALLOWED_ACTIONS) return
        val provider = context.applicationContext as? FocusTriggerProvider
        val taskProvider = context.applicationContext as? TaskReminderTriggerProvider
        if (provider == null && taskProvider == null) return

        val pendingResult: PendingResult? = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val handler = provider?.focusTriggerHandler()
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        handler?.handleDeviceBootOrUpdate()
                        taskProvider?.taskReminderTriggerHandler()?.handleBootOrPackageReplaced()
                    }
                    Intent.ACTION_SHUTDOWN -> handler?.handleDeviceShutdown()
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        private val ALLOWED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_SHUTDOWN
        )
    }
}
