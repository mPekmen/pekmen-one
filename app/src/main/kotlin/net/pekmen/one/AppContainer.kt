package net.pekmen.one

import android.content.ComponentName
import android.content.Context
import android.app.NotificationManager
import kotlinx.coroutines.flow.first
import net.pekmen.one.account.PersonalAccountStorage
import net.pekmen.one.core.cloud.CirclesRepository
import net.pekmen.one.core.cloud.CloudAccountRepository
import net.pekmen.one.core.cloud.CloudRlsGateProvider
import net.pekmen.one.core.data.FocusHistoryRepository
import net.pekmen.one.core.data.FocusHistoryRepositoryImpl
import net.pekmen.one.core.data.FocusPlatformTriggerAdapter
import net.pekmen.one.core.data.FocusProfileRepository
import net.pekmen.one.core.data.FocusProfileRepositoryImpl
import net.pekmen.one.core.data.FocusSessionRepository
import net.pekmen.one.core.data.FocusSessionRepositoryImpl
import net.pekmen.one.core.data.LearningRepository
import net.pekmen.one.core.data.LearningRepositoryImpl
import net.pekmen.one.core.data.SaveSubjectRequest
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.SystemDiagnosticsRepositoryImpl
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.data.UserPreferencesRepositoryImpl
import net.pekmen.one.core.data.planner.CalendarInsertGateway
import net.pekmen.one.core.data.planner.PlatformCalendarInsertGateway
import net.pekmen.one.core.data.planner.PlatformTaskReminderGateway
import net.pekmen.one.core.data.planner.PlannerRepository
import net.pekmen.one.core.data.planner.PlannerRepositoryImpl
import net.pekmen.one.core.data.planner.PlannerTriggerAdapter
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.FocusPhase
import net.pekmen.one.core.model.FocusProfile
import net.pekmen.one.core.model.FocusSessionStatus
import net.pekmen.one.core.platform.AndroidProfileNameProvider
import net.pekmen.one.core.platform.FocusAlarmScheduler
import net.pekmen.one.core.platform.FocusAlarmGateway
import net.pekmen.one.core.platform.FocusNotificationManager
import net.pekmen.one.core.platform.FocusTriggerHandler
import net.pekmen.one.core.platform.FocusZenTileGateway
import net.pekmen.one.core.platform.SystemPermissionChecker
import net.pekmen.one.core.platform.SystemTimeProvider
import net.pekmen.one.core.platform.planner.CalendarInstancesReader
import net.pekmen.one.core.platform.planner.TaskReminderScheduler
import net.pekmen.one.core.platform.planner.TaskReminderTriggerHandler
import net.pekmen.one.core.platform.samsung.AppShortcutsPublisher
import net.pekmen.one.core.platform.samsung.FocusQuickSettingsTileService
import net.pekmen.one.core.platform.samsung.FocusTileStateBridge
import net.pekmen.one.core.platform.samsung.FocusTileStatus
import net.pekmen.one.core.platform.zen.FocusZenRuleManager
import net.pekmen.one.core.portability.BackupPortabilityService
import net.pekmen.one.core.portability.BackupPortabilityServiceImpl

/**
 * Application-scoped dependency composition root.
 * Feature modules never depend on this class; they receive narrow interfaces.
 */
class AppContainer internal constructor(context: Context, private val storage: PersonalAccountStorage? = null) {
    private val appContext = context.applicationContext
    private val effectLock = Any()
    private var effectsEnabled = storage == null

    private val timeProvider = SystemTimeProvider(appContext)
    private val permissionChecker = SystemPermissionChecker(appContext)
    private val database: PekmenDatabase by lazy { storage?.database ?: PekmenDatabase.create(appContext) }
    internal val userPreferencesDataStore: UserPreferencesDataStore by lazy {
        storage?.preferences ?: UserPreferencesDataStore(appContext)
    }

    private val notificationManager: FocusNotificationManager by lazy {
        FocusNotificationManager(
            context = appContext,
            permissionChecker = permissionChecker,
            launcherActivityClass = MainActivity::class.java,
            ownerId = storage?.accountId?.value,
            publish = ::personalEffect
        )
    }

    private val alarmScheduler: FocusAlarmScheduler by lazy {
        FocusAlarmScheduler(
            context = appContext,
            permissionChecker = permissionChecker,
            ownerId = storage?.accountId?.value
        )
    }

    private val accountAlarmGateway: FocusAlarmGateway by lazy {
        object : FocusAlarmGateway by alarmScheduler {
            override fun schedulePhaseEnd(sessionId: String, segmentId: String, triggerAtElapsedMs: Long) =
                personalEffect { alarmScheduler.schedulePhaseEnd(sessionId, segmentId, triggerAtElapsedMs) }

            override fun cancelPhaseEnd() = personalEffect { alarmScheduler.cancelPhaseEnd() }
        }
    }

    private val profileNameProvider by lazy { AndroidProfileNameProvider(appContext) }

    val zenRuleManager: FocusZenRuleManager by lazy {
        FocusZenRuleManager(
            context = appContext,
            configurationActivity = ComponentName(appContext, MainActivity::class.java)
        )
    }

    val appShortcutsPublisher: AppShortcutsPublisher by lazy {
        AppShortcutsPublisher(
            context = appContext,
            launcherActivityClass = MainActivity::class.java
        )
    }

    private val zenTileGateway: FocusZenTileGateway by lazy {
        object : FocusZenTileGateway {
            override fun syncSessionState(
                zenIntegrationEnabled: Boolean,
                profile: FocusProfile?,
                status: FocusSessionStatus?,
                phase: FocusPhase?
            ) = personalEffect {
                zenRuleManager.syncForSessionState(
                    zenIntegrationEnabled = zenIntegrationEnabled,
                    profile = profile,
                    status = status,
                    phase = phase
                )
                FocusQuickSettingsTileService.requestTileUpdate(appContext)
            }

            override fun deactivate() = personalEffect {
                zenRuleManager.deactivateOwnedRule()
                FocusQuickSettingsTileService.requestTileUpdate(appContext)
            }
        }
    }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepositoryImpl(userPreferencesDataStore)
    }

    val profileRepository: FocusProfileRepository by lazy {
        FocusProfileRepositoryImpl(
            profileDao = database.focusProfileDao(),
            timeProvider = timeProvider,
            accountSessionDao = database.accountSessionDao(),
            preferencesDataStore = userPreferencesDataStore,
            syncOutboxDao = database.syncOutboxDao()
        )
    }

    val sessionRepository: FocusSessionRepository by lazy {
        FocusSessionRepositoryImpl(
            database = database,
            profileRepository = profileRepository,
            preferencesDataStore = userPreferencesDataStore,
            timeProvider = timeProvider,
            notificationGateway = notificationManager,
            alarmGateway = accountAlarmGateway,
            profileNameProvider = profileNameProvider,
            zenTileGateway = zenTileGateway
        )
    }

    val historyRepository: FocusHistoryRepository by lazy {
        FocusHistoryRepositoryImpl(
            database = database,
            profileRepository = profileRepository,
            preferencesDataStore = userPreferencesDataStore,
            timeProvider = timeProvider
        )
    }

    private val learningData: LearningRepositoryImpl by lazy {
        LearningRepositoryImpl(
            database = database,
            preferencesDataStore = userPreferencesDataStore,
            timeProvider = timeProvider
        )
    }
    val learningRepository: LearningRepository get() = learningData

    internal suspend fun ensureFirstLesson(id: String, title: String, weeklyGoalMinutes: Int) {
        learningData.ensureFirstSubject(
            id,
            SaveSubjectRequest(title = title, colorHex = 0xFF8B7CF6, weeklyGoalMinutes = weeklyGoalMinutes)
        )
    }

    val diagnosticsRepository: SystemDiagnosticsRepository by lazy {
        SystemDiagnosticsRepositoryImpl(
            permissionChecker = permissionChecker,
            alarmGateway = alarmScheduler,
            timeProvider = timeProvider
        )
    }

    val focusTriggerHandler: FocusTriggerHandler by lazy {
        FocusPlatformTriggerAdapter(sessionRepository)
    }

    val focusTileStateBridge: FocusTileStateBridge by lazy {
        object : FocusTileStateBridge {
            override suspend fun currentTileStatus(): FocusTileStatus {
                val active = sessionRepository.activeSession.first() ?: return FocusTileStatus(status = null)
                return FocusTileStatus(
                    status = active.session.status,
                    profileName = profileNameProvider.resolve(active.profile),
                    phase = active.session.currentPhase
                )
            }

            override suspend fun toggleFromTile(): FocusTileStatus {
                val active = sessionRepository.activeSession.first() ?: return FocusTileStatus(status = null)
                when (active.session.status) {
                    FocusSessionStatus.RUNNING -> sessionRepository.pauseActiveSession()
                    FocusSessionStatus.PAUSED -> sessionRepository.resumeActiveSession()
                    FocusSessionStatus.AWAITING_NEXT_PHASE -> sessionRepository.startAwaitingNextPhase()
                    FocusSessionStatus.COMPLETED, FocusSessionStatus.ABANDONED -> Unit
                }
                return currentTileStatus()
            }
        }
    }

    private val taskReminderScheduler: TaskReminderScheduler by lazy {
        TaskReminderScheduler(
            context = appContext,
            permissionChecker = permissionChecker,
            launcherActivityClass = MainActivity::class.java,
            ownerId = storage?.accountId?.value
        )
    }

    private val calendarInstancesReader: CalendarInstancesReader by lazy {
        CalendarInstancesReader(appContext)
    }

    val calendarInsertGateway: CalendarInsertGateway by lazy {
        PlatformCalendarInsertGateway()
    }

    val plannerRepository: PlannerRepository by lazy {
        PlannerRepositoryImpl(
            database = database,
            preferencesDataStore = userPreferencesDataStore,
            timeProvider = timeProvider,
            reminderGateway = PlatformTaskReminderGateway(taskReminderScheduler, ::personalEffect),
            calendarReader = calendarInstancesReader
        )
    }

    val taskReminderTriggerHandler: TaskReminderTriggerHandler by lazy {
        PlannerTriggerAdapter(plannerRepository)
    }

    val backupPortabilityService: BackupPortabilityService by lazy {
        BackupPortabilityServiceImpl(
            database = database,
            preferencesDataStore = userPreferencesDataStore,
            timeProvider = timeProvider,
            onRestoreCompleted = { plannerRepository.rescheduleAllPendingReminders() }
        )
    }

    private val cloudSlice: CloudContainerSlice by lazy {
        CloudContainerSlice(
            appContext = appContext,
            database = database,
            userPreferencesDataStore = userPreferencesDataStore,
            timeProvider = timeProvider
        )
    }

    val cloudRlsGateProvider: CloudRlsGateProvider
        get() = cloudSlice.cloudRlsGateProvider

    val cloudAccountRepository: CloudAccountRepository
        get() = cloudSlice.cloudAccountRepository

    val circlesRepository: CirclesRepository
        get() = cloudSlice.circlesRepository

    internal fun authorizePlatformEffects() = synchronized(effectLock) { effectsEnabled = true }

    internal suspend fun retirePlatformEffects() {
        synchronized(effectLock) {
            effectsEnabled = false
            alarmScheduler.cancelPhaseEnd()
            appContext.getSystemService(NotificationManager::class.java)?.cancelAll()
            zenRuleManager.deactivateOwnedRule()
            FocusQuickSettingsTileService.requestTileUpdate(appContext)
        }
        val owner = storage?.accountId ?: return
        database.plannerTaskDao().getPendingReminderTasksByOwner(Long.MIN_VALUE, owner.value)
            .forEach { taskReminderScheduler.cancelReminder(it.id) }
    }

    private fun personalEffect(action: () -> Unit) {
        synchronized(effectLock) {
            if (effectsEnabled) action()
        }
    }
}
