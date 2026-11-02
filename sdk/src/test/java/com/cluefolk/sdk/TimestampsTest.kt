package com.cluefolk.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class TimestampsTest {
    @Test
    fun utcAndOffsetsRepresentTheSameSessionExpiry() {
        val expected = Timestamps.parseRfc3339("2026-10-26T12:00:00Z")
        assertEquals(expected, Timestamps.parseRfc3339("2026-10-26T13:00:00.123456789+01:00"))
        assertEquals(expected, Timestamps.parseRfc3339("2026-10-26T07:00:00-05:00"))
        assertEquals(expected, Timestamps.parseRfc3339("2026-10-26T12:00:00.999Z"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDateCannotCreateASession() {
        Timestamps.parseRfc3339("2026-02-30T12:00:00Z")
    }

    @Test(expected = IllegalArgumentException::class)
    fun trailingDataIsRejected() {
        Timestamps.parseRfc3339("2026-10-26T12:00:00Zgarbage")
    }
}
