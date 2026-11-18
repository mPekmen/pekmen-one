package net.pekmen.one.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class EffectiveDayCalculatorTest {

    private val istanbul = ZoneId.of("Europe/Istanbul")

    @Test
    fun `timestamps before rollover hour map to previous logical study day`() {
        val beforeRollover = LocalDateTime.of(2026, 11, 19, 3, 59, 59)
            .atZone(istanbul)
            .toInstant()
            .toEpochMilli()

        val result = EffectiveDayCalculator.effectiveDateFor(
            epochMs = beforeRollover,
            zoneId = istanbul,
            rolloverHour = 4
        )

        assertEquals(LocalDate.of(2026, 11, 18), result)
    }

    @Test
    fun `timestamp exactly at rollover hour maps to current calendar day`() {
        val atRollover = LocalDateTime.of(2026, 11, 19, 4, 0, 0)
            .atZone(istanbul)
            .toInstant()
            .toEpochMilli()

        val result = EffectiveDayCalculator.effectiveDateFor(
            epochMs = atRollover,
            zoneId = istanbul,
            rolloverHour = 4
        )

        assertEquals(LocalDate.of(2026, 11, 19), result)
    }

    @Test
    fun `seven day window returns ordered 7 dates ending at anchor`() {
        val anchor = LocalDate.of(2026, 11, 19)
        val window = EffectiveDayCalculator.sevenDayWindowEndingOn(anchor)

        assertEquals(7, window.size)
        assertEquals(LocalDate.of(2026, 11, 13), window.first())
        assertEquals(anchor, window.last())
    }
}
