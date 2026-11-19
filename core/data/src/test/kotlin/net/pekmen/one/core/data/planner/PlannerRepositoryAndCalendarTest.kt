package net.pekmen.one.core.data.planner

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.pekmen.one.core.database.PekmenDatabase
import net.pekmen.one.core.database.entity.PlannerTaskEntity
import net.pekmen.one.core.database.entity.SyncMetadataEntity
import net.pekmen.one.core.datastore.UserPreferencesDataStore
import net.pekmen.one.core.model.CalendarReadResult
import net.pekmen.one.core.model.SyncStatus
import net.pekmen.one.core.model.TaskRecurrenceRule
import net.pekmen.one.core.model.TaskStatus
import net.pekmen.one.core.model.TimeProvider
import net.pekmen.one.core.platform.planner.CalendarInsertIntents
import net.pekmen.one.core.platform.planner.CalendarInstancesReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class PlannerRepositoryAndCalendarTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var appContext: Application
    private lateinit var database: PekmenDatabase
    private lateinit var fakeTime: FakeTimeProvider
    private lateinit var fakeReminders: FakeTaskReminderGateway
    private lateinit var dataStore: UserPreferencesDataStore

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext<Application>()
        database = PekmenDatabase.createInMemory(appContext)
        val baseWall = LocalDateTime.of(2026, 3, 28, 10, 0, 0)
            .atZone(ZoneId.of("Europe/Berlin"))
            .toInstant()
            .toEpochMilli()
        fakeTime = FakeTimeProvider(
            wallMs = baseWall,
            elapsedMs = 10_000L,
            boot = 1,
            zone = ZoneId.of("Europe/Berlin")
        )
        fakeReminders = FakeTaskReminderGateway()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun buildRepo(scope: TestScope): PlannerRepositoryImpl {
        if (!::dataStore.isInitialized) {
            val file = File(tempFolder.newFolder(), "planner_prefs.preferences_pb")
            val rawStore = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { file }
            dataStore = UserPreferencesDataStore(rawStore)
        }
        return PlannerRepositoryImpl(
            database = database,
            preferencesDataStore = dataStore,
            timeProvider = fakeTime,
            reminderGateway = fakeReminders,
            calendarReader = CalendarInstancesReader(appContext)
        )
    }

    @Test
    fun `crud soft delete outbox enqueue and owner scoping enforce isolation`() = runTest {
        val repo = buildRepo(this)
        val futureDue = fakeTime.wallTimeMs() + 3_600_000L
        val futureReminder = fakeTime.wallTimeMs() + 1_800_000L

        val guestTask = repo.saveTask(
            SavePlannerTaskRequest(
                title = "Read chapter 4",
                notes = "Summarize key proofs",
                dueAtWallMs = futureDue,
                reminderAtWallMs = futureReminder,
                priority = 2
            )
        ).getOrThrow()

        assertEquals(SyncStatus.LOCAL_ONLY, guestTask.syncStatus)
        assertEquals(1, repo.observeTasks().first().size)
        assertTrue(fakeReminders.scheduled.any { it.taskId == guestTask.id && it.triggerAtWallMs == futureReminder })

        val outboxAfterCreate = database.syncOutboxDao().observeOutbox().first()
        assertEquals(1, outboxAfterCreate.size)
        assertEquals("planner_tasks", outboxAfterCreate.first().entityTable)
        assertEquals(guestTask.id, outboxAfterCreate.first().entityId)
        assertEquals("UPSERT", outboxAfterCreate.first().opType)

        val updated = repo.saveTask(
            SavePlannerTaskRequest(
                existingId = guestTask.id,
                title = "Read chapter 4 & 5",
                notes = null,
                dueAtWallMs = futureDue,
                reminderAtWallMs = futureReminder + 600_000L,
                priority = 3
            )
        ).getOrThrow()
        assertEquals("Read chapter 4 & 5", updated.title)
        assertEquals(3, updated.priority)

        // Owner scoping: switch to User A and create a task
        dataStore.setActiveOwnerUserId("user-a")
        assertTrue(repo.observeTasks().first().isEmpty())

        val userATask = repo.saveTask(
            SavePlannerTaskRequest(title = "User A private task")
        ).getOrThrow()
        assertEquals("user-a", userATask.ownerUserId)
        assertEquals(SyncStatus.PENDING_UPLOAD, userATask.syncStatus)
        assertEquals(listOf(userATask.id), repo.observeTasks().first().map { it.id })

        // Switch to User B: cannot view or mutate User A's task
        dataStore.setActiveOwnerUserId("user-b")
        assertTrue(repo.observeTasks().first().isEmpty())
        assertNull(repo.getTaskById(userATask.id))
        assertTrue(repo.softDeleteTask(userATask.id).isFailure)

        // Switch back to User A and soft-delete User A's task
        dataStore.setActiveOwnerUserId("user-a")
        repo.softDeleteTask(userATask.id).getOrThrow()
        assertTrue(repo.observeTasks().first().isEmpty())
        assertTrue(fakeReminders.cancelledIds.contains(userATask.id))

        val outboxEntries = database.syncOutboxDao().observeOutbox().first()
        val deletedOutbox = outboxEntries.first { it.entityId == userATask.id }
        assertEquals("DELETE", deletedOutbox.opType)
    }

    @Test
    fun `daily recurrence completes task and schedules next across dst in user timezone`() = runTest {
        val berlinZone = ZoneId.of("Europe/Berlin")
        val beforeDstNow = LocalDateTime.of(2026, 3, 28, 10, 0, 0)
            .atZone(berlinZone)
            .toInstant()
            .toEpochMilli()
        fakeTime.set(wallMs = beforeDstNow, zone = berlinZone)

        val repo = buildRepo(this)
        val dueToday = LocalDateTime.of(2026, 3, 28, 18, 0, 0)
            .atZone(berlinZone)
            .toInstant()
            .toEpochMilli()
        val reminderToday = LocalDateTime.of(2026, 3, 28, 17, 45, 0)
            .atZone(berlinZone)
            .toInstant()
            .toEpochMilli()

        val initial = repo.saveTask(
            SavePlannerTaskRequest(
                title = "Anki German B2",
                notes = "Review 30 cards",
                subjectId = "subject-german",
                dueAtWallMs = dueToday,
                reminderAtWallMs = reminderToday,
                recurrenceRule = TaskRecurrenceRule.DAILY,
                priority = 2
            )
        ).getOrThrow()

        fakeReminders.clear()
        val outcome = repo.completeTask(initial.id).getOrThrow()

        assertEquals(TaskStatus.COMPLETED, outcome.completedTask.status)
        assertEquals(beforeDstNow, outcome.completedTask.completedAtWallMs)
        assertTrue(fakeReminders.cancelledIds.contains(initial.id))

        val next = outcome.nextOccurrence
        assertNotNull(next)
        val expectedNextDue = LocalDateTime.of(2026, 3, 29, 18, 0, 0)
            .atZone(berlinZone)
            .toInstant()
            .toEpochMilli()
        val expectedNextReminder = LocalDateTime.of(2026, 3, 29, 17, 45, 0)
            .atZone(berlinZone)
            .toInstant()
            .toEpochMilli()

        assertEquals(TaskStatus.OPEN, next?.status)
        assertNull(next?.completedAtWallMs)
        assertEquals("Anki German B2", next?.title)
        assertEquals("Review 30 cards", next?.notes)
        assertEquals("subject-german", next?.subjectId)
        assertEquals(TaskRecurrenceRule.DAILY, next?.recurrenceRule)
        assertEquals(2, next?.priority)
        assertEquals(expectedNextDue, next?.dueAtWallMs)
        assertEquals(expectedNextReminder, next?.reminderAtWallMs)
        assertTrue(
            fakeReminders.scheduled.any {
                it.taskId == next?.id && it.triggerAtWallMs == expectedNextReminder
            }
        )

        // Repeating completeTask on an already-completed task is idempotent
        val repeatOutcome = repo.completeTask(initial.id).getOrThrow()
        assertNull(repeatOutcome.nextOccurrence)
        assertEquals(2, repo.observeTasks().first().size)
    }

    @Test
    fun `weekly recurrence adds 7 calendar days across dst fall back in user timezone`() = runTest {
        val nyZone = ZoneId.of("America/New_York")
        val nowNy = LocalDateTime.of(2026, 10, 30, 9, 0, 0)
            .atZone(nyZone)
            .toInstant()
            .toEpochMilli()
        fakeTime.set(wallMs = nowNy, zone = nyZone)

        val repo = buildRepo(this)
        val dueFri = LocalDateTime.of(2026, 10, 30, 14, 0, 0)
            .atZone(nyZone)
            .toInstant()
            .toEpochMilli()
        val remFri = LocalDateTime.of(2026, 10, 30, 13, 45, 0)
            .atZone(nyZone)
            .toInstant()
            .toEpochMilli()

        val weekly = repo.saveTask(
            SavePlannerTaskRequest(
                title = "Weekly lab report",
                subjectId = "subject-lab",
                dueAtWallMs = dueFri,
                reminderAtWallMs = remFri,
                recurrenceRule = TaskRecurrenceRule.WEEKLY
            )
        ).getOrThrow()

        val outcome = repo.completeTask(weekly.id).getOrThrow()
        val next = outcome.nextOccurrence
        assertNotNull(next)

        val expectedNextDue = LocalDateTime.of(2026, 11, 6, 14, 0, 0)
            .atZone(nyZone)
            .toInstant()
            .toEpochMilli()
        val expectedNextRem = LocalDateTime.of(2026, 11, 6, 13, 45, 0)
            .atZone(nyZone)
            .toInstant()
            .toEpochMilli()

        assertEquals(expectedNextDue, next?.dueAtWallMs)
        assertEquals(expectedNextRem, next?.reminderAtWallMs)
        assertEquals(TaskRecurrenceRule.WEEKLY, next?.recurrenceRule)
    }

    @Test
    fun `cancel and reschedule cancel prior reminders and boot restores only future open tasks`() = runTest {
        val repo = buildRepo(this)
        val now = fakeTime.wallTimeMs()

        val task1 = repo.saveTask(
            SavePlannerTaskRequest(
                title = "Future reminder",
                reminderAtWallMs = now + 3_600_000L
            )
        ).getOrThrow()
        assertEquals(1, fakeReminders.scheduled.size)

        // Removing reminder cancels prior alarm
        repo.saveTask(
            SavePlannerTaskRequest(
                existingId = task1.id,
                title = "Future reminder",
                reminderAtWallMs = null
            )
        ).getOrThrow()
        assertTrue(fakeReminders.cancelledIds.contains(task1.id))

        // Re-adding reminder then cancelling task cancels alarm
        repo.saveTask(
            SavePlannerTaskRequest(
                existingId = task1.id,
                title = "Future reminder",
                reminderAtWallMs = now + 7_200_000L
            )
        ).getOrThrow()
        fakeReminders.clear()
        val cancelled = repo.cancelTask(task1.id).getOrThrow()
        assertEquals(TaskStatus.CANCELLED, cancelled.status)
        assertTrue(fakeReminders.cancelledIds.contains(task1.id))

        // Populate mixed tasks directly in DB to verify boot restoration
        val futureOpen = repo.saveTask(
            SavePlannerTaskRequest(
                title = "Open future task",
                reminderAtWallMs = now + 10_800_000L
            )
        ).getOrThrow()
        val pastOpen = repo.saveTask(
            SavePlannerTaskRequest(
                title = "Open past task",
                reminderAtWallMs = now - 60_000L
            )
        ).getOrThrow()
        val completedFuture = repo.saveTask(
            SavePlannerTaskRequest(
                title = "Completed future",
                reminderAtWallMs = now + 14_400_000L
            )
        ).getOrThrow()
        repo.completeTask(completedFuture.id).getOrThrow()

        val deletedOpen = repo.saveTask(
            SavePlannerTaskRequest(
                title = "Deleted future",
                reminderAtWallMs = now + 18_000_000L
            )
        ).getOrThrow()
        repo.softDeleteTask(deletedOpen.id).getOrThrow()

        database.plannerTaskDao().upsertTask(
            PlannerTaskEntity(
                id = "01900000-0000-7000-8000-000000000999",
                title = "Other user task",
                reminderAtWallMs = now + 20_000_000L,
                status = TaskStatus.OPEN.name,
                sync = SyncMetadataEntity(
                    ownerUserId = "other-user",
                    createdAtMs = now,
                    clientUpdatedAtMs = now
                )
            )
        )

        fakeReminders.clear()
        val restoredCount = repo.rescheduleAllPendingReminders()
        assertEquals(1, restoredCount)
        assertEquals(listOf(futureOpen.id), fakeReminders.scheduled.map { it.taskId })
        assertTrue(fakeReminders.scheduled.none { it.taskId == pastOpen.id })
    }

    @Test
    fun `calendar permission denied fallback preserves tasks and granted reads instances and insert extras`() =
        runTest {
            val repo = buildRepo(this)
            val now = fakeTime.wallTimeMs()
            val task = repo.saveTask(
                SavePlannerTaskRequest(
                    title = "Mock exam",
                    notes = "Paper 1 & 2",
                    dueAtWallMs = now + 3_600_000L,
                    recurrenceRule = TaskRecurrenceRule.WEEKLY
                )
            ).getOrThrow()

            // Denied by default
            Shadows.shadowOf(appContext).denyPermissions(Manifest.permission.READ_CALENDAR)
            val agendaDenied = repo.observeAgenda(now, now + 86_400_000L).first()
            assertTrue(agendaDenied.calendarResult is CalendarReadResult.PermissionDenied)
            assertEquals(1, agendaDenied.openTasks.size)

            // Granted with fake CalendarContract provider
            val rows = listOf(
                CalendarRow(
                    eventId = 42L,
                    title = "Lecture: Algorithms",
                    begin = now + 7_200_000L,
                    end = now + 10_800_000L,
                    allDay = 0,
                    calendarName = "University"
                )
            )
            registerFakeCalendarProvider(appContext, rows)
            Shadows.shadowOf(appContext).grantPermissions(Manifest.permission.READ_CALENDAR)

            val agendaGranted = repo.observeAgenda(now, now + 86_400_000L).first()
            val available = agendaGranted.calendarResult as CalendarReadResult.Available
            assertEquals(1, available.events.size)
            assertEquals("Lecture: Algorithms", available.events.first().title)
            assertEquals("University", available.events.first().calendarDisplayName)

            // Verify ACTION_INSERT intent & extras (read-only export flow)
            val insertIntent = CalendarInsertIntents.buildInsertEventIntent(task)
            assertEquals(Intent.ACTION_INSERT, insertIntent.action)
            assertEquals(CalendarContract.Events.CONTENT_URI, insertIntent.data)
            assertEquals("Mock exam", insertIntent.getStringExtra(CalendarContract.Events.TITLE))
            assertEquals("Paper 1 & 2", insertIntent.getStringExtra(CalendarContract.Events.DESCRIPTION))
            assertEquals(
                now + 3_600_000L,
                insertIntent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L)
            )
            assertEquals(
                now + 3_600_000L + CalendarInsertIntents.DEFAULT_EVENT_DURATION_MS,
                insertIntent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L)
            )
            assertEquals("FREQ=WEEKLY", insertIntent.getStringExtra(CalendarContract.Events.RRULE))
        }

    private fun registerFakeCalendarProvider(context: Context, rows: List<CalendarRow>) {
        val provider = FakeCalendarProvider(rows)
        val info = ProviderInfo().apply { authority = CalendarContract.AUTHORITY }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
    }

    private data class CalendarRow(
        val eventId: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Int,
        val calendarName: String?
    )

    private class FakeCalendarProvider(
        private val rows: List<CalendarRow>
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val cols = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
            )
            val cursor = MatrixCursor(cols)
            for (row in rows) {
                cursor.addRow(
                    arrayOf<Any?>(
                        row.eventId,
                        row.title,
                        row.begin,
                        row.end,
                        row.allDay,
                        row.calendarName
                    )
                )
            }
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

    private class FakeTimeProvider(
        private var wallMs: Long,
        private var elapsedMs: Long,
        private var boot: Int,
        private var zone: ZoneId
    ) : TimeProvider {
        override fun wallTimeMs(): Long = wallMs
        override fun elapsedRealtimeMs(): Long = elapsedMs
        override fun bootCount(): Int = boot
        override fun zoneId(): ZoneId = zone

        fun set(wallMs: Long, zone: ZoneId) {
            this.wallMs = wallMs
            this.zone = zone
        }
    }

    private data class ScheduledCall(
        val taskId: String,
        val title: String,
        val triggerAtWallMs: Long
    )

    private class FakeTaskReminderGateway : TaskReminderGateway {
        val scheduled = mutableListOf<ScheduledCall>()
        val cancelledIds = mutableListOf<String>()
        val shownNotifications = mutableListOf<Pair<String, String>>()

        override fun scheduleReminder(taskId: String, title: String, triggerAtWallMs: Long) {
            scheduled.add(ScheduledCall(taskId, title, triggerAtWallMs))
        }

        override fun cancelReminder(taskId: String) {
            cancelledIds.add(taskId)
        }

        override fun showReminderNotification(taskId: String, title: String) {
            shownNotifications.add(taskId to title)
        }

        fun clear() {
            scheduled.clear()
            cancelledIds.clear()
            shownNotifications.clear()
        }
    }
}
