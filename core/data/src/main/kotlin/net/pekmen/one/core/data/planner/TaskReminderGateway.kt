package net.pekmen.one.core.data.planner

import android.content.Intent
import net.pekmen.one.core.model.PlannerTask
import net.pekmen.one.core.platform.planner.CalendarInsertIntents
import net.pekmen.one.core.platform.planner.TaskReminderScheduler
import net.pekmen.one.core.platform.planner.TaskReminderTriggerHandler

interface TaskReminderGateway {
    fun scheduleReminder(taskId: String, title: String, triggerAtWallMs: Long)
    fun cancelReminder(taskId: String)
    fun showReminderNotification(taskId: String, title: String)
}

class PlatformTaskReminderGateway(
    private val scheduler: TaskReminderScheduler,
    private val publish: (() -> Unit) -> Unit = { it() }
) : TaskReminderGateway {

    override fun scheduleReminder(taskId: String, title: String, triggerAtWallMs: Long) {
        publish { scheduler.scheduleReminder(taskId = taskId, title = title, triggerAtWallMs = triggerAtWallMs) }
    }

    override fun cancelReminder(taskId: String) {
        publish { scheduler.cancelReminder(taskId = taskId) }
    }

    override fun showReminderNotification(taskId: String, title: String) {
        publish { scheduler.showReminderNotification(taskId = taskId, title = title) }
    }
}

fun interface CalendarInsertGateway {
    fun buildInsertEventIntent(task: PlannerTask): Intent
}

class PlatformCalendarInsertGateway : CalendarInsertGateway {
    override fun buildInsertEventIntent(task: PlannerTask): Intent {
        return CalendarInsertIntents.buildInsertEventIntent(task)
    }
}

class PlannerTriggerAdapter(
    private val plannerRepository: PlannerRepository
) : TaskReminderTriggerHandler {

    override suspend fun handleTaskReminderFired(taskId: String) {
        plannerRepository.handleReminderFired(taskId)
    }

    override suspend fun handleBootOrPackageReplaced() {
        plannerRepository.rescheduleAllPendingReminders()
    }
}
