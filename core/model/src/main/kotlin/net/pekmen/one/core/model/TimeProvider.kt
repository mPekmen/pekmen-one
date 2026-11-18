package net.pekmen.one.core.model

import java.time.ZoneId

/**
 * Clock abstraction enabling deterministic testing across process death,
 * device reboot, and timezone/day-rollover boundaries.
 */
interface TimeProvider {
    fun wallTimeMs(): Long
    fun elapsedRealtimeMs(): Long
    fun bootCount(): Int
    fun zoneId(): ZoneId
}
