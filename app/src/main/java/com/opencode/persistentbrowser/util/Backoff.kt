package com.opencode.persistentbrowser.util

import kotlin.math.min
import kotlin.math.pow

/**
 * Exponential backoff calculator.
 *
 * delay(n) = base * 2^(n-1), capped at [maxDelayMs].
 * [reset] returns the calculator to its initial state (used on network change).
 */
class Backoff(
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 60_000,
    private val jitterFactor: Double = 0.25
) {
    private var attempt = 0

    fun nextDelayMs(): Long {
        attempt++
        val raw = baseDelayMs * 2.0.pow((attempt - 1).toDouble())
        val capped = min(raw.toLong(), maxDelayMs)
        val jitter = (capped * jitterFactor * Math.random()).toLong()
        return capped + jitter
    }

    fun reset() {
        attempt = 0
    }
}
