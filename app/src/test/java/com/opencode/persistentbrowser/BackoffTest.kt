package com.opencode.persistentbrowser

import com.opencode.persistentbrowser.util.Backoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    @Test
    fun `first delay is at least base`() {
        val backoff = Backoff(baseDelayMs = 1_000, maxDelayMs = 60_000, jitterFactor = 0.0)
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun `delays grow exponentially`() {
        val backoff = Backoff(baseDelayMs = 1_000, maxDelayMs = 60_000, jitterFactor = 0.0)
        val d1 = backoff.nextDelayMs()
        val d2 = backoff.nextDelayMs()
        val d3 = backoff.nextDelayMs()
        assertEquals(1_000L, d1)
        assertEquals(2_000L, d2)
        assertEquals(4_000L, d3)
    }

    @Test
    fun `delays are capped at max`() {
        val backoff = Backoff(baseDelayMs = 1_000, maxDelayMs = 5_000, jitterFactor = 0.0)
        var max = 0L
        repeat(20) {
            max = backoff.nextDelayMs()
        }
        assertEquals(5_000L, max)
    }

    @Test
    fun `reset returns to base`() {
        val backoff = Backoff(baseDelayMs = 1_000, maxDelayMs = 60_000, jitterFactor = 0.0)
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun `jitter keeps delay positive`() {
        val backoff = Backoff(baseDelayMs = 1_000, maxDelayMs = 60_000, jitterFactor = 0.25)
        repeat(50) {
            assertTrue(backoff.nextDelayMs() > 0)
        }
    }
}
