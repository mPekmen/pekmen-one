package net.pekmen.one

import android.app.Activity
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.pekmen.one.core.cloud.sync.SyncTriggerProvider
import net.pekmen.one.core.platform.FocusTriggerHandler
import net.pekmen.one.core.platform.FocusTriggerProvider
import net.pekmen.one.core.platform.planner.TaskReminderTriggerHandler
import net.pekmen.one.core.platform.planner.TaskReminderTriggerProvider
import net.pekmen.one.core.platform.samsung.FocusQuickSettingsTileService
import net.pekmen.one.core.platform.samsung.FocusTileBridgeProvider
import net.pekmen.one.core.platform.samsung.FocusTileStateBridge

class PekmenApplication :
    Application(),
    FocusTriggerProvider,
    TaskReminderTriggerProvider,
    FocusTileBridgeProvider,
    SyncTriggerProvider {
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
        applicationScope.launch {
            container.userPreferencesRepository.preferences
                .map { it.zenIntegrationEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (!enabled) {
                        container.zenRuleManager.deactivateOwnedRule()
                        FocusQuickSettingsTileService.requestTileUpdate(this@PekmenApplication)
                    }
                }
        }
        applicationScope.launch {
            container.userPreferencesRepository.preferences
                .map { it.activeOwnerUserId }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    container.zenRuleManager.deactivateOwnedRule()
                    FocusQuickSettingsTileService.requestTileUpdate(this@PekmenApplication)
                    container.plannerRepository.rescheduleAllPendingReminders()
                }
        }
    }

    override fun focusTriggerHandler(): FocusTriggerHandler = container.focusTriggerHandler

    override fun taskReminderTriggerHandler(): TaskReminderTriggerHandler = container.taskReminderTriggerHandler

    override fun focusTileStateBridge(): FocusTileStateBridge = container.focusTileStateBridge

    override fun launcherActivityClass(): Class<out Activity> = MainActivity::class.java

    override fun syncRunner(): suspend () -> Result<Int> = {
        container.cloudAccountRepository.triggerSyncNow()
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
