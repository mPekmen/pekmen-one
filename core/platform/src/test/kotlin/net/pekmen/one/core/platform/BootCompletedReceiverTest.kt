package net.pekmen.one.core.platform

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = BootCompletedReceiverTest.FakeFocusApp::class)
class BootCompletedReceiverTest {

    @Test
    fun `ignores untrusted actions and delegates allowlisted boot and shutdown actions`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<FakeFocusApp>()
        val handler = context.fakeHandler
        val receiver = BootCompletedReceiver()

        receiver.onReceive(context, Intent("com.evil.SPOOFED_ACTION").putExtra("override", true))
        Thread.sleep(50)
        assertEquals(0, handler.bootCalls)
        assertEquals(0, handler.shutdownCalls)

        handler.bootLatch = CountDownLatch(1)
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED).putExtra("malicious", 999))
        assertTrue(handler.bootLatch.await(2, TimeUnit.SECONDS))
        assertEquals(1, handler.bootCalls)

        handler.bootLatch = CountDownLatch(1)
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(handler.bootLatch.await(2, TimeUnit.SECONDS))
        assertEquals(2, handler.bootCalls)

        handler.shutdownLatch = CountDownLatch(1)
        receiver.onReceive(context, Intent(Intent.ACTION_SHUTDOWN))
        assertTrue(handler.shutdownLatch.await(2, TimeUnit.SECONDS))
        assertEquals(1, handler.shutdownCalls)
    }

    class FakeFocusApp : Application(), FocusTriggerProvider {
        val fakeHandler = RecordingTriggerHandler()
        override fun focusTriggerHandler(): FocusTriggerHandler = fakeHandler
    }

    class RecordingTriggerHandler : FocusTriggerHandler {
        @Volatile
        var bootCalls: Int = 0
        @Volatile
        var shutdownCalls: Int = 0
        @Volatile
        var bootLatch: CountDownLatch = CountDownLatch(1)
        @Volatile
        var shutdownLatch: CountDownLatch = CountDownLatch(1)

        override suspend fun pauseActiveFromSystem() = Unit
        override suspend fun resumeActiveFromSystem() = Unit
        override suspend fun startAwaitingPhaseFromSystem() = Unit
        override suspend fun completeActiveFromSystem() = Unit
        override suspend fun handlePhaseAlarmExpired(sessionId: String, segmentId: String) = Unit
        override suspend fun handleDeviceBootOrUpdate() {
            bootCalls += 1
            bootLatch.countDown()
        }
        override suspend fun handleDeviceShutdown() {
            shutdownCalls += 1
            shutdownLatch.countDown()
        }
    }
}
