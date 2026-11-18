package net.pekmen.one.core.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import net.pekmen.one.core.model.TimeProvider
import java.time.ZoneId

class SystemTimeProvider(
    private val context: Context
) : TimeProvider {

    override fun wallTimeMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Int {
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
    }

    override fun zoneId(): ZoneId = ZoneId.systemDefault()
}
