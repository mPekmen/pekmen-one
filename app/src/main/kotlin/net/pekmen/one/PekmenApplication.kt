package net.pekmen.one

import android.app.Activity
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.pekmen.one.account.PekmenAccounts
import net.pekmen.one.core.cloud.auth.SupabaseAuthConfig
import net.pekmen.one.core.cloud.sync.SyncTriggerProvider
import net.pekmen.one.core.platform.FocusTriggerHandler
import net.pekmen.one.core.platform.FocusTriggerProvider
import net.pekmen.one.core.platform.planner.TaskReminderTriggerHandler
import net.pekmen.one.core.platform.planner.TaskReminderTriggerProvider
import net.pekmen.one.core.platform.samsung.FocusTileBridgeProvider
import net.pekmen.one.core.platform.samsung.FocusTileStateBridge
import net.pekmen.one.core.platform.samsung.FocusTileStatus

class PekmenApplication :
    Application(),
    FocusTriggerProvider,
    TaskReminderTriggerProvider,
    FocusTileBridgeProvider,
    SyncTriggerProvider {
    // Legacy composition remains available for source-data import and local feature tests.
    internal val container: AppContainer by lazy { AppContainer(this) }
    internal lateinit var accounts: PekmenAccounts
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val config = if (BuildConfig.CLOUD_ENABLED) {
            SupabaseAuthConfig.parse(
                BuildConfig.SUPABASE_URL,
                BuildConfig.SUPABASE_ANON_KEY,
                BuildConfig.AUTH_REDIRECT_HOST
            )
        } else {
            null
        }
        accounts = PekmenAccounts(this, applicationScope, config)
        container.appShortcutsPublisher.publishDynamicShortcuts()
        applicationScope.launch { accounts.restore() }
    }

    override fun focusTriggerHandler(): FocusTriggerHandler = object : FocusTriggerHandler {
        override suspend fun pauseActiveFromSystem() = Unit
        override suspend fun resumeActiveFromSystem() = Unit
        override suspend fun startAwaitingPhaseFromSystem() = Unit
        override suspend fun completeActiveFromSystem() = Unit
        override suspend fun handlePhaseAlarmExpired(sessionId: String, segmentId: String) = Unit

        override suspend fun handleNotificationAction(action: String, sessionId: String?, ownerId: String?) {
            accounts.withReadyAccount { account ->
                val local = account.container
                if (ownerId != account.id.value || sessionId == null ||
                    local.sessionRepository.activeSession.first()?.session?.id != sessionId
                ) return@withReadyAccount
                local.focusTriggerHandler.handleNotificationAction(action, sessionId, ownerId)
            }
        }

        override suspend fun handleAccountPhaseAlarm(sessionId: String, segmentId: String, ownerId: String?) {
            accounts.withReadyAccount { account ->
                if (account.id.value == ownerId) {
                    account.container.focusTriggerHandler.handlePhaseAlarmExpired(sessionId, segmentId)
                }
            }
        }

        override suspend fun handleDeviceBootOrUpdate() {
            accounts.restore()
            accounts.withReadyAccount { it.container.focusTriggerHandler.handleDeviceBootOrUpdate() }
        }

        override suspend fun handleDeviceShutdown() {
            accounts.withReadyAccount { it.container.focusTriggerHandler.handleDeviceShutdown() }
        }
    }

    override fun taskReminderTriggerHandler(): TaskReminderTriggerHandler = object : TaskReminderTriggerHandler {
        override suspend fun handleTaskReminderFired(taskId: String) = Unit

        override suspend fun handleAccountReminderFired(taskId: String, ownerId: String?) {
            accounts.withReadyAccount { account ->
                if (account.id.value == ownerId) {
                    account.container.taskReminderTriggerHandler.handleTaskReminderFired(taskId)
                }
            }
        }

        override suspend fun handleBootOrPackageReplaced() {
            accounts.withReadyAccount { it.container.taskReminderTriggerHandler.handleBootOrPackageReplaced() }
        }
    }

    override fun focusTileStateBridge(): FocusTileStateBridge = object : FocusTileStateBridge {
        override suspend fun currentTileStatus(): FocusTileStatus =
            accounts.withReadyAccount { it.container.focusTileStateBridge.currentTileStatus() }
                ?: FocusTileStatus(status = null)

        override suspend fun toggleFromTile(): FocusTileStatus =
            accounts.withReadyAccount { it.container.focusTileStateBridge.toggleFromTile() }
                ?: FocusTileStatus(status = null)
    }

    override fun launcherActivityClass(): Class<out Activity> = MainActivity::class.java

    override fun syncRunner(): suspend () -> Result<Int> = {
        accounts.withReadyAccount { it.container.cloudAccountRepository.triggerSyncNow() }
            ?: Result.failure(IllegalStateException("No verified account"))
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
