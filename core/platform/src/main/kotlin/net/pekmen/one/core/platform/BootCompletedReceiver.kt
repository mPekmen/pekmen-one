package net.pekmen.one.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ALLOWED_ACTIONS) return
        val provider = context.applicationContext as? FocusTriggerProvider ?: return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val handler = provider.focusTriggerHandler()
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> handler.handleDeviceBootOrUpdate()
                    Intent.ACTION_SHUTDOWN -> handler.handleDeviceShutdown()
                }
            } finally {
                pendingResult.finish()
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
