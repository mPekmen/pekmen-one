package net.pekmen.one

import android.app.Activity
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.pekmen.one.core.platform.FocusTriggerHandler
import net.pekmen.one.core.platform.FocusTriggerProvider
import net.pekmen.one.core.platform.planner.TaskReminderTriggerHandler
import net.pekmen.one.core.platform.planner.TaskReminderTriggerProvider
import net.pekmen.one.core.platform.samsung.FocusTileBridgeProvider
import net.pekmen.one.core.platform.samsung.FocusTileStateBridge

class PekmenApplication :
    Application(),
    FocusTriggerProvider,
    TaskReminderTriggerProvider,
    FocusTileBridgeProvider {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appShortcutsPublisher.publishDynamicShortcuts()
        applicationScope.launch {
            container.profileRepository.ensureSeeded()
            container.sessionRepository.reconcileActiveSessionAfterBootOrWake()
            container.plannerRepository.rescheduleAllPendingReminders()
        }
    }

    override fun focusTriggerHandler(): FocusTriggerHandler = container.focusTriggerHandler

    override fun taskReminderTriggerHandler(): TaskReminderTriggerHandler = container.taskReminderTriggerHandler

    override fun focusTileStateBridge(): FocusTileStateBridge = container.focusTileStateBridge

    override fun launcherActivityClass(): Class<out Activity> = MainActivity::class.java
}
