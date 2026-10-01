package com.opencode.persistentbrowser

import com.opencode.persistentbrowser.util.Staleness
import org.junit.Assert.assertEquals
import org.junit.Test

class StalenessTest {

    private val now = 1_000_000L

    private fun page(
        used: Boolean = true,
        open: Boolean = true,
        lastMsg: Long = now - 1000,
        lastErr: Long = 0,
        openCount: Int = 1
    ) = Staleness.PageObserver(used, open, lastMsg, lastErr, openCount)

    private fun monitor(
        connected: Boolean = true,
        lastEvent: Long = now - 1000,
        eventsAway: Int = 0,
        disconnectedAway: Boolean = false
    ) = Staleness.MonitorState(connected, lastEvent, eventsAway, disconnectedAway)

    @Test
    fun `page live when event source open`() {
        val d = Staleness.decide(now, page(open = true), monitor(), 5000)
        assertEquals(Staleness.Decision.PAGE_LIVE, d)
    }

    @Test
    fun `reload required when page never opened`() {
        val d = Staleness.decide(now, page(open = false, openCount = 0), monitor(), 5000)
        assertEquals(Staleness.Decision.RELOAD_REQUIRED, d)
    }

    @Test
    fun `reload required when monitor saw events while away and page closed`() {
        val d = Staleness.decide(
            now,
            page(open = false, openCount = 2, lastErr = now - 100),
            monitor(eventsAway = 5, disconnectedAway = true),
            60_000
        )
        assertEquals(Staleness.Decision.RELOAD_REQUIRED, d)
    }

    @Test
    fun `page unknown when monitor quiet and page recently errored`() {
        val d = Staleness.decide(
            now,
            page(open = false, openCount = 2, lastErr = now - 1000),
            monitor(eventsAway = 0, disconnectedAway = false),
            60_000
        )
        assertEquals(Staleness.Decision.PAGE_UNKNOWN, d)
    }

    @Test
    fun `reload required when page error is older than grace period`() {
        val d = Staleness.decide(
            now,
            page(open = false, openCount = 2, lastErr = now - 20_000),
            monitor(eventsAway = 0, disconnectedAway = false),
            60_000
        )
        assertEquals(Staleness.Decision.RELOAD_REQUIRED, d)
    }

    @Test
    fun `non-event-source page trusts monitor`() {
        val live = Staleness.decide(now, page(used = false), monitor(connected = true), 5000)
        assertEquals(Staleness.Decision.PAGE_LIVE, live)
        val dead = Staleness.decide(now, page(used = false), monitor(connected = false), 5000)
        assertEquals(Staleness.Decision.RELOAD_REQUIRED, dead)
    }

    @Test
    fun `no observer means wait`() {
        val d = Staleness.decide(now, null, monitor(), 5000)
        assertEquals(Staleness.Decision.PAGE_UNKNOWN, d)
    }
}
