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
}
