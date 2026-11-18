package net.pekmen.one.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidV7Test {

    @Test
    fun `generated UUIDs are valid RFC 9562 version 7 and extract timestamp`() {
        val epochMs = 1_760_000_123_456L
        val id = UuidV7.generate(epochMs)

        assertTrue("Expected valid UUIDv7: $id", UuidV7.isValidUuidV7(id))
        assertEquals(epochMs, UuidV7.extractTimestampMs(id))
    }

    @Test
    fun `generated UUIDs within same and advancing milliseconds preserve monotonic ordering and uniqueness`() {
        val ids = (0 until 5_000).map { index ->
            UuidV7.generate(1_760_100_000_000L + (index / 50))
        }

        assertEquals(5_000, ids.toSet().size)
        for (i in 1 until ids.size) {
            assertTrue(
                "Expected ${ids[i - 1]} < ${ids[i]} at index $i",
                ids[i - 1] < ids[i]
            )
        }
    }

    @Test
    fun `invalid or v4 UUIDs fail UUIDv7 validation`() {
        assertFalse(UuidV7.isValidUuidV7("not-a-uuid"))
        assertFalse(UuidV7.isValidUuidV7("550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun `minor backward clock jitter stays monotonic while historical timestamps reset`() {
        val base = 1_760_200_000_000L
        val first = UuidV7.generate(base)
        val minorJitter = UuidV7.generate(base - 250L)
        assertTrue(first < minorJitter)
        assertEquals(base, UuidV7.extractTimestampMs(minorJitter))

        val historicalEpoch = 1_750_000_000_000L
        val historical = UuidV7.generate(historicalEpoch)
        assertEquals(historicalEpoch, UuidV7.extractTimestampMs(historical))
    }
}
