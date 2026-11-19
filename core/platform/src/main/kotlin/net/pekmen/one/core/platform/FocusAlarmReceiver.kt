package net.pekmen.one.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FocusAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PHASE_EXPIRED) return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return
        val segmentId = intent.getStringExtra(EXTRA_SEGMENT_ID)?.takeIf { it.isNotBlank() } ?: return
        val provider = context.applicationContext as? FocusTriggerProvider ?: return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                provider.focusTriggerHandler().handleAccountPhaseAlarm(
                    sessionId,
                    segmentId,
                    intent.getStringExtra(FocusActionReceiver.EXTRA_OWNER_ID)
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PHASE_EXPIRED = "net.pekmen.one.action.PHASE_EXPIRED"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_SEGMENT_ID = "extra_segment_id"
    }
}
