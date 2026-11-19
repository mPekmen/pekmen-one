package net.pekmen.one.core.platform.planner

import android.content.Intent
import android.provider.CalendarContract
import net.pekmen.one.core.model.PlannerTask
import net.pekmen.one.core.model.TaskRecurrenceRule

object CalendarInsertIntents {

    const val DEFAULT_EVENT_DURATION_MS = 60L * 60_000L

    fun buildInsertEventIntent(
        task: PlannerTask,
        fallbackBeginWallMs: Long? = null
    ): Intent {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, task.title)
        }

        task.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { description ->
            intent.putExtra(CalendarContract.Events.DESCRIPTION, description)
        }

        val startMs = task.dueAtWallMs ?: task.reminderAtWallMs ?: fallbackBeginWallMs
        if (startMs != null) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMs + DEFAULT_EVENT_DURATION_MS)
        }

        val rrule = when (task.recurrenceRule) {
            TaskRecurrenceRule.NONE -> null
            TaskRecurrenceRule.DAILY -> "FREQ=DAILY"
            TaskRecurrenceRule.WEEKLY -> "FREQ=WEEKLY"
        }
        if (rrule != null) {
            intent.putExtra(CalendarContract.Events.RRULE, rrule)
        }

        return intent
    }
}
