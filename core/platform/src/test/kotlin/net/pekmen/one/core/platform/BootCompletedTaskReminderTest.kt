package net.pekmen.one.core.platform

import android.app.Application
import android.content.Intent
import net.pekmen.one.core.platform.planner.TaskReminderTriggerHandler
import net.pekmen.one.core.platform.planner.TaskReminderTriggerProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeBootTaskReminderApp::class)
class BootCompletedTaskReminderTest {

    @Test
    fun `boot and package replace reschedule tasks while untrusted ignored`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<FakeBootTaskReminderApp>()
        app.resetCounts(expectedEvents = 5)
        val receiver = BootCompletedReceiver()

        receiver.onReceive(app, Intent("com.untrusted.FAKE_BOOT"))
        receiver.onReceive(app, Intent())
        assertEquals(0, app.focusBootCalls.get())
        assertEquals(0, app.taskBootCalls.get())
        assertEquals(0, app.focusShutdownCalls.get())

        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        receiver.onReceive(app, Intent(Intent.ACTION_SHUTDOWN))

        assertTrue(app.latch.await(5L, TimeUnit.SECONDS))
        assertEquals(2, app.focusBootCalls.get())
        assertEquals(2, app.taskBootCalls.get())
        assertEquals(1, app.focusShutdownCalls.get())
    }
}

class FakeBootTaskReminderApp : Application(), FocusTriggerProvider, TaskReminderTriggerProvider {
    val focusBootCalls = AtomicInteger(0)
    val focusShutdownCalls = AtomicInteger(0)
    val taskBootCalls = AtomicInteger(0)

    @Volatile
    var latch = CountDownLatch(0)
        private set

    fun resetCounts(expectedEvents: Int) {
        focusBootCalls.set(0)
        focusShutdownCalls.set(0)
        taskBootCalls.set(0)
        latch = CountDownLatch(expectedEvents)
    }

    override fun focusTriggerHandler(): FocusTriggerHandler = object : FocusTriggerHandler {
        override suspend fun pauseActiveFromSystem() = Unit
        override suspend fun resumeActiveFromSystem() = Unit
        override suspend fun startAwaitingPhaseFromSystem() = Unit
        override suspend fun completeActiveFromSystem() = Unit
        override suspend fun handlePhaseAlarmExpired(sessionId: String, segmentId: String) = Unit
        override suspend fun handleDeviceBootOrUpdate() {
            focusBootCalls.incrementAndGet()
            latch.countDown()
        }

        override suspend fun handleDeviceShutdown() {
            focusShutdownCalls.incrementAndGet()
            latch.countDown()
        }
    }

    override fun taskReminderTriggerHandler(): TaskReminderTriggerHandler = object : TaskReminderTriggerHandler {
        override suspend fun handleTaskReminderFired(taskId: String) = Unit
        override suspend fun handleBootOrPackageReplaced() {
            taskBootCalls.incrementAndGet()
            latch.countDown()
        }
    }
}
