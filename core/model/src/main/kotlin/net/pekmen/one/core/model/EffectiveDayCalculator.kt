package net.pekmen.one.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Computes the logical study/focus day for a timestamp so late-night work
 * (for example, 01:45 when rollover is 04:00) remains attributed to the previous day.
 */
object EffectiveDayCalculator {
    const val DEFAULT_ROLLOVER_HOUR = 4
    const val MIN_ROLLOVER_HOUR = 0
    const val MAX_ROLLOVER_HOUR = 12

    fun effectiveDateFor(
        epochMs: Long,
        zoneId: ZoneId,
        rolloverHour: Int = DEFAULT_ROLLOVER_HOUR
    ): LocalDate {
        require(rolloverHour in MIN_ROLLOVER_HOUR..MAX_ROLLOVER_HOUR) {
            "Rollover hour must be in $MIN_ROLLOVER_HOUR..$MAX_ROLLOVER_HOUR, got $rolloverHour"
        }
        val zoned = Instant.ofEpochMilli(epochMs).atZone(zoneId)
        val localDate = zoned.toLocalDate()
        return if (zoned.hour < rolloverHour) {
            localDate.minusDays(1)
        } else {
            localDate
        }
    }

    fun sevenDayWindowEndingOn(anchorDate: LocalDate): List<LocalDate> {
        return (6L downTo 0L).map { offset -> anchorDate.minusDays(offset) }
    }
}
