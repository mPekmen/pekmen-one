package net.pekmen.one.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FocusActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ALLOWED_ACTIONS) return
        val provider = context.applicationContext as? FocusTriggerProvider ?: return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                provider.focusTriggerHandler().handleNotificationAction(
                    action,
                    intent.getStringExtra(EXTRA_SESSION_ID),
                    intent.getStringExtra(EXTRA_OWNER_ID)
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "net.pekmen.one.action.FOCUS_PAUSE"
        const val ACTION_RESUME = "net.pekmen.one.action.FOCUS_RESUME"
        const val ACTION_START_NEXT = "net.pekmen.one.action.FOCUS_START_NEXT"
        const val ACTION_FINISH = "net.pekmen.one.action.FOCUS_FINISH"
        const val EXTRA_SESSION_ID = "extra_focus_session_id"
        const val EXTRA_OWNER_ID = "extra_focus_owner_id"

        private val ALLOWED_ACTIONS = setOf(
            ACTION_PAUSE,
            ACTION_RESUME,
            ACTION_START_NEXT,
            ACTION_FINISH
        )
    }
}
