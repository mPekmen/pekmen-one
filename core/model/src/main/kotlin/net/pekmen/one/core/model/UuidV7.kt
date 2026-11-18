package net.pekmen.one.core.model

import java.security.SecureRandom
import java.util.UUID

/**
 * Pure-Kotlin RFC 9562 UUIDv7 generator with same-millisecond monotonic ordering
 * in the 12-bit rand_a field and CSPRNG entropy in the 62-bit rand_b field.
 */
object UuidV7 {
    private val random = SecureRandom()
    private val lock = Any()
    private var lastTimestampMs = -1L
    private var lastCounter = 0

    fun generate(epochMs: Long = System.currentTimeMillis()): String {
        require(epochMs >= 0L) { "Epoch timestamp must be non-negative: $epochMs" }

        val timestampAndCounter = synchronized(lock) {
            var ts = epochMs
            if (ts <= lastTimestampMs) {
                ts = lastTimestampMs
                if (lastCounter < MAX_COUNTER) {
                    lastCounter += 1
                } else {
                    ts += 1L
                    lastCounter = random.nextInt(INITIAL_COUNTER_BOUND)
                }
            } else {
                lastTimestampMs = ts
                lastCounter = random.nextInt(INITIAL_COUNTER_BOUND)
            }
            ts to lastCounter
        }

        val timestamp = timestampAndCounter.first
        val counter = timestampAndCounter.second

        val msb = ((timestamp and TIMESTAMP_MASK) shl 16) or
            VERSION_7_BITS or
            (counter.toLong() and COUNTER_MASK)

        val randomBytes = ByteArray(8)
        random.nextBytes(randomBytes)
        var rawLsb = 0L
        for (byte in randomBytes) {
            rawLsb = (rawLsb shl 8) or (byte.toLong() and 0xFFL)
        }
        val lsb = (rawLsb and VARIANT_CLEAR_MASK) or VARIANT_RFC_9562_BITS

        return UUID(msb, lsb).toString()
    }

    fun isValidUuidV7(value: String): Boolean {
        val parsed = runCatching { UUID.fromString(value) }.getOrNull() ?: return false
        return parsed.version() == 7 && parsed.variant() == 2
    }

    fun extractTimestampMs(uuid: String): Long {
        val parsed = UUID.fromString(uuid)
        require(parsed.version() == 7) { "Expected UUIDv7, got version ${parsed.version()}" }
        return parsed.mostSignificantBits ushr 16
    }

    private const val MAX_COUNTER = 0x0FFF
    private const val INITIAL_COUNTER_BOUND = 0x0800
    private const val TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL
    private const val VERSION_7_BITS = 0x7000L
    private const val COUNTER_MASK = 0x0FFFL
    private const val VARIANT_CLEAR_MASK = 0x3FFF_FFFF_FFFF_FFFFL
    private const val VARIANT_RFC_9562_BITS = Long.MIN_VALUE // 0b10 in top 2 bits
}
