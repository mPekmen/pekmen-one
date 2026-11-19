# M3 Planner Integration Guide

This guide lists the exact host-module edits required for Coordinator integration of `:feature:planner`, `:core:data/.../planner`, and `:core:platform/.../planner` without file conflicts across worker branches.

---

## 1. AndroidManifest (`core/platform/src/main/AndroidManifest.xml`)

### Optional `READ_CALENDAR` permission (NEVER declare `WRITE_CALENDAR`)
Add inside `<manifest>`:

```xml
<!-- Optional runtime permission for read-only agenda calendar display; WRITE_CALENDAR must NEVER be declared -->
<uses-permission android:name="android.permission.READ_CALENDAR" />
```

### `TaskReminderReceiver` registration (`exported="false"`)
Add inside `<application>`:

```xml
<receiver
    android:name=".planner.TaskReminderReceiver"
    android:exported="false" />
```

---

## 2. `BootCompletedReceiver` Hook (`core/platform/src/main/kotlin/net/pekmen/one/core/platform/BootCompletedReceiver.kt`)

Inside `scope.launch` when handling `Intent.ACTION_BOOT_COMPLETED` or `Intent.ACTION_MY_PACKAGE_REPLACED`, also notify `TaskReminderTriggerProvider` so future `OPEN` task reminders are restored after device reboot or app update:

```kotlin
val taskProvider = context.applicationContext as? net.pekmen.one.core.platform.planner.TaskReminderTriggerProvider
when (action) {
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED -> {
        handler.handleDeviceBootOrUpdate()
        taskProvider?.taskReminderTriggerHandler()?.handleBootOrPackageReplaced()
    }
    Intent.ACTION_SHUTDOWN -> handler.handleDeviceShutdown()
}
```

---

## 3. `AppContainer` & `PekmenApplication` Wiring

### `app/src/main/kotlin/net/pekmen/one/AppContainer.kt`
Add imports:
```kotlin
import net.pekmen.one.core.data.planner.CalendarInsertGateway
import net.pekmen.one.core.data.planner.PlatformCalendarInsertGateway
import net.pekmen.one.core.data.planner.PlatformTaskReminderGateway
import net.pekmen.one.core.data.planner.PlannerRepository
import net.pekmen.one.core.data.planner.PlannerRepositoryImpl
import net.pekmen.one.core.data.planner.PlannerTriggerAdapter
import net.pekmen.one.core.platform.planner.CalendarInstancesReader
import net.pekmen.one.core.platform.planner.TaskReminderScheduler
import net.pekmen.one.core.platform.planner.TaskReminderTriggerHandler
```

Add properties inside `AppContainer`:
```kotlin
private val taskReminderScheduler: TaskReminderScheduler by lazy {
    TaskReminderScheduler(
        context = appContext,
        permissionChecker = permissionChecker,
        launcherActivityClass = MainActivity::class.java
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
        reminderGateway = PlatformTaskReminderGateway(taskReminderScheduler),
        calendarReader = calendarInstancesReader
    )
}

val taskReminderTriggerHandler: TaskReminderTriggerHandler by lazy {
    PlannerTriggerAdapter(plannerRepository)
}
```

### `app/src/main/kotlin/net/pekmen/one/PekmenApplication.kt`
Implement `TaskReminderTriggerProvider` and restore pending reminders on startup:
```kotlin
class PekmenApplication : Application(), FocusTriggerProvider, TaskReminderTriggerProvider {
    ...
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch {
            container.profileRepository.ensureSeeded()
            container.sessionRepository.reconcileActiveSessionAfterBootOrWake()
            container.plannerRepository.rescheduleAllPendingReminders()
        }
    }

    override fun focusTriggerHandler(): FocusTriggerHandler = container.focusTriggerHandler

    override fun taskReminderTriggerHandler(): TaskReminderTriggerHandler = container.taskReminderTriggerHandler
}
```

---

## 4. Gradle & NavHost Tab Wiring (`app/build.gradle.kts` & `PekmenNavHost.kt`)

### `app/build.gradle.kts`
Add dependency:
```kotlin
implementation(project(":feature:planner"))
```

### `app/src/main/kotlin/net/pekmen/one/navigation/PekmenNavHost.kt` (plus `MainActivity.kt` & `PekmenNavigationSmokeTest.kt`)
1. Pass `plannerRepository: PlannerRepository` and `calendarInsertGateway: CalendarInsertGateway` into `PekmenAppShell` from `appContainer` in `MainActivity.kt` (and `app.container` in `PekmenNavigationSmokeTest.kt`).
2. Add the Planner tab destination (`NAV_ID_PLANNER = "planner"`):
```kotlin
PekmenNavDestination(
    id = NAV_ID_PLANNER,
    labelRes = R.string.nav_planner,
    iconRes = PekmenIcons.Calendar
)
```
3. Update `selectedId` and `onSelectDestination`:
```kotlin
currentDestination?.hasRoute<PlannerRoute>() == true -> NAV_ID_PLANNER
// and in when(targetId):
NAV_ID_PLANNER -> PlannerRoute
```
4. Register `composable<PlannerRoute>` in `NavHost`:
```kotlin
composable<PlannerRoute> {
    val vm: PlannerViewModel = viewModel {
        PlannerViewModel(
            plannerRepository = plannerRepository,
            diagnosticsRepository = diagnosticsRepository,
            calendarInsertGateway = calendarInsertGateway
        )
    }
    PlannerScreenRoute(
        viewModel = vm,
        onFocusOnTask = { taskId, subjectId ->
            // Optionally forward taskId/subjectId to FocusViewModel or shared Focus prefill state
            navController.navigate(FocusRoute) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    )
}
```
