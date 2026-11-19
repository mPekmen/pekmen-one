package net.pekmen.one

import android.content.Context
import net.pekmen.one.core.data.FocusHistoryRepository
import net.pekmen.one.core.data.FocusHistoryRepositoryImpl
import net.pekmen.one.core.data.FocusPlatformTriggerAdapter
import net.pekmen.one.core.data.FocusProfileRepository
import net.pekmen.one.core.data.FocusProfileRepositoryImpl
import net.pekmen.one.core.data.FocusSessionRepository
import net.pekmen.one.core.data.FocusSessionRepositoryImpl
import net.pekmen.one.core.data.LearningRepository
import net.pekmen.one.core.data.LearningRepositoryImpl
import net.pekmen.one.core.data.SystemDiagnosticsRepository
import net.pekmen.one.core.data.SystemDiagnosticsRepositoryImpl
import net.pekmen.one.core.data.UserPreferencesRepository
import net.pekmen.one.core.data.UserPreferencesRepositoryImpl
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.platform.AndroidProfileNameProvider
import net.pekmen.one.core.platform.FocusAlarmScheduler
import net.pekmen.one.core.platform.FocusNotificationManager
import net.pekmen.one.core.platform.FocusTriggerHandler
import net.pekmen.one.core.platform.SystemPermissionChecker
import net.pekmen.one.core.platform.SystemTimeProvider

/**
 * Application-scoped dependency composition root.
 * Feature modules never depend on this class; they receive narrow interfaces.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val timeProvider = SystemTimeProvider(appContext)
    private val permissionChecker = SystemPermissionChecker(appContext)
    private val database: PekmenDatabase by lazy { PekmenDatabase.create(appContext) }
    private val userPreferencesDataStore: UserPreferencesDataStore by lazy {
        UserPreferencesDataStore(appContext)
    }

    private val notificationManager: FocusNotificationManager by lazy {
        FocusNotificationManager(
            context = appContext,
            permissionChecker = permissionChecker,
            launcherActivityClass = MainActivity::class.java
        )
    }

    private val alarmScheduler: FocusAlarmScheduler by lazy {
        FocusAlarmScheduler(
            context = appContext,
            permissionChecker = permissionChecker
        )
    }

    private val profileNameProvider by lazy { AndroidProfileNameProvider(appContext) }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepositoryImpl(userPreferencesDataStore)
    }

    val profileRepository: FocusProfileRepository by lazy {
        FocusProfileRepositoryImpl(
            profileDao = database.focusProfileDao(),
            timeProvider = timeProvider
        )
    }

    val sessionRepository: FocusSessionRepository by lazy {
        FocusSessionRepositoryImpl(
            database = database,
            profileRepository = profileRepository,
            preferencesDataStore = userPreferencesDataStore,
            timeProvider = timeProvider,
            notificationGateway = notificationManager,
            alarmGateway = alarmScheduler,
            profileNameProvider = profileNameProvider
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

    val learningRepository: LearningRepository by lazy {
        LearningRepositoryImpl(
            database = database,
            preferencesDataStore = userPreferencesDataStore,
            timeProvider = timeProvider
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
}
