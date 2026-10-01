package com.opencode.persistentbrowser.util

/**
 * Pure decision logic for stale-state detection and reconciliation.
 *
 * The app must never show stale OpenCode state as if it were current. When the
 * app returns to the foreground after being backgrounded, we reconcile:
 *
 *  - the service-side monitor checkpoint (what the server sent while away)
 *  - the page-side EventSource observer (whether the page's live channel is open)
 *
 * and decide whether the page needs a reload to resynchronize.
 */
object Staleness {

    /** Snapshot of the page's injected EventSource observer. */
    data class PageObserver(
        val eventSourceUsed: Boolean,
        val anyOpen: Boolean,
        val lastMessageTime: Long,
        val lastErrorTime: Long,
        val openCount: Int
    )

    /** Snapshot of the service-side monitor. */
    data class MonitorState(
        val connected: Boolean,
        val lastEventTime: Long,
        val eventsWhileAway: Int,
        val disconnectedWhileAway: Boolean
    )

    enum class Decision {
        /** Page's live channel is open and monitor agrees — safe to show Connected. */
        PAGE_LIVE,
        /** Page has no live channel but monitor is healthy — page may self-heal; wait briefly. */
        PAGE_UNKNOWN,
        /** Page is stale or dead — reload required. */
        RELOAD_REQUIRED
    }

    /**
     * Decide whether the page needs a reload.
     *
     * @param now current time (ms)
     * @param page page observer snapshot (null if not yet read)
     * @param monitor service monitor snapshot
     * @param backgroundedMs how long the app was backgrounded (ms)
     */
    fun decide(
        now: Long,
        page: PageObserver?,
        monitor: MonitorState,
        backgroundedMs: Long
    ): Decision {
        // If the page uses EventSource and one is open, the page is live.
        // Its own reconnect (with Last-Event-ID) handles catch-up.
        if (page != null && page.eventSourceUsed && page.anyOpen) {
            return Decision.PAGE_LIVE
        }

        // Page does not use EventSource (non-OpenCode URL): trust the monitor.
        if (page != null && !page.eventSourceUsed) {
            return if (monitor.connected) Decision.PAGE_LIVE else Decision.RELOAD_REQUIRED
        }

        // Page uses EventSource but none is open.
        if (page != null && page.eventSourceUsed) {
            // If it never opened and we have no evidence of life, reload.
            if (page.openCount == 0) return Decision.RELOAD_REQUIRED
            // If it opened before but is now closed and the monitor saw activity
            // while we were away, the page missed events -> reload.
            if (monitor.disconnectedWhileAway || monitor.eventsWhileAway > 0) {
                return Decision.RELOAD_REQUIRED
            }
            // Monitor is quiet and connected; the page may reconnect on its own.
            // Give it a short grace period before forcing a reload.
            val sinceError = if (page.lastErrorTime > 0) now - page.lastErrorTime else Long.MAX_VALUE
            return if (sinceError > GRACE_PERIOD_MS) Decision.RELOAD_REQUIRED else Decision.PAGE_UNKNOWN
        }

        // No page observer yet (page still loading): wait.
        return Decision.PAGE_UNKNOWN
    }

    /** Grace period to let a page's own EventSource reconnect before forcing a reload. */
    const val GRACE_PERIOD_MS = 8_000L
}
