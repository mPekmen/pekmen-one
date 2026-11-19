package net.pekmen.one.core.platform.planner

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import net.pekmen.one.core.model.CalendarReadResult
import net.pekmen.one.core.model.PlannerTask
import net.pekmen.one.core.model.TaskRecurrenceRule
import net.pekmen.one.core.platform.SystemPermissionChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = TaskReminderPlatformTest.FakePlannerApp::class)
class TaskReminderPlatformTest {

    @Test
    fun `scheduler schedules cancels per task and posts private reminder notification`() {
        val app = ApplicationProvider.getApplicationContext<FakePlannerApp>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val scheduler = TaskReminderScheduler(
            context = app,
            permissionChecker = SystemPermissionChecker(app)
        )
        val alarmManager = app.getSystemService(AlarmManager::class.java)
        val shadowAlarm = Shadows.shadowOf(alarmManager)

        scheduler.scheduleReminder(taskId = "task-1", title = "Read notes", triggerAtWallMs = 2_000_000L)
        scheduler.scheduleReminder(taskId = "task-2", title = "Practice quiz", triggerAtWallMs = 3_000_000L)
        assertEquals(2, shadowAlarm.scheduledAlarms.size)

        scheduler.cancelReminder("task-1")
        assertEquals(1, shadowAlarm.scheduledAlarms.size)
        val remainingIntent = Shadows.shadowOf(shadowAlarm.scheduledAlarms.first().operation).savedIntent
        assertEquals("task-2", remainingIntent.getStringExtra(TaskReminderReceiver.EXTRA_TASK_ID))

        scheduler.showReminderNotification("task-2", "Practice quiz")
        val notifManager = app.getSystemService(NotificationManager::class.java)
        val shadowNotif = Shadows.shadowOf(notifManager)
        assertEquals(1, shadowNotif.allNotifications.size)
        assertEquals(
            "Practice quiz",
            shadowNotif.allNotifications.first().extras.getString("android.title")
        )
    }

    @Test
    fun `task reminder receiver ignores untrusted action and dispatches valid reminder`() {
        val app = ApplicationProvider.getApplicationContext<FakePlannerApp>()
        val receiver = TaskReminderReceiver()

        receiver.onReceive(app, Intent("com.evil.FAKE_ACTION").putExtra(TaskReminderReceiver.EXTRA_TASK_ID, "t1"))
        Thread.sleep(50)
        assertTrue(app.handler.firedTaskIds.isEmpty())

        app.handler.latch = CountDownLatch(1)
        val validIntent = Intent(TaskReminderReceiver.ACTION_TASK_REMINDER).apply {
            putExtra(TaskReminderReceiver.EXTRA_TASK_ID, "task-99")
        }
        receiver.onReceive(app, validIntent)
        assertTrue(app.handler.latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("task-99"), app.handler.firedTaskIds)
    }

    @Test
    fun `calendar reader respects permission and insert intent sets read only export extras`() {
        val app = ApplicationProvider.getApplicationContext<FakePlannerApp>()
        val reader = CalendarInstancesReader(app)

        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        assertEquals(CalendarReadResult.PermissionDenied, reader.readInstances(1000L, 5000L))

        val provider = StubCalendarProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = CalendarContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)

        val result = reader.readInstances(1000L, 9000L)
        val available = result as CalendarReadResult.Available
        assertEquals(1, available.events.size)
        assertEquals("Untitled event", available.events.first().title)

        val task = PlannerTask(
            id = "task-export",
            title = "Essay draft",
            notes = "Outline intro",
            dueAtWallMs = 1_760_000_000_000L,
            recurrenceRule = TaskRecurrenceRule.DAILY
        )
        val intent = CalendarInsertIntents.buildInsertEventIntent(task)
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("Essay draft", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals("Outline intro", intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertEquals("FREQ=DAILY", intent.getStringExtra(CalendarContract.Events.RRULE))
    }

    class FakePlannerApp : Application(), TaskReminderTriggerProvider {
        val handler = RecordingHandler()
        override fun taskReminderTriggerHandler(): TaskReminderTriggerHandler = handler
    }

    class RecordingHandler : TaskReminderTriggerHandler {
        val firedTaskIds = mutableListOf<String>()

        @Volatile
        var latch: CountDownLatch = CountDownLatch(1)

        override suspend fun handleTaskReminderFired(taskId: String) {
            firedTaskIds.add(taskId)
            latch.countDown()
        }

        override suspend fun handleBootOrPackageReplaced() = Unit
    }

    private class StubCalendarProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val cursor = MatrixCursor(
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME
                )
            )
            cursor.addRow(arrayOf<Any?>(7L, "", 2000L, 4000L, 1, null))
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }
}
