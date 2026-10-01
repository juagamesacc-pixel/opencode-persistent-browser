package com.opencode.persistentbrowser.session

import android.app.Application
import com.opencode.persistentbrowser.data.SessionStore
import com.opencode.persistentbrowser.util.Staleness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central session coordinator shared by the UI (MainActivity) and the
 * SessionKeepaliveService. Both run in the same process, so a singleton with
 * a StateFlow is the correct, simple communication channel.
 *
 * Responsibilities:
 *  - expose the observable [SessionState] to the UI
 *  - track whether the app is foregrounded (gates the service SSE monitor)
 *  - accept monitor updates from the service
 *  - run reconciliation when the app returns to the foreground
 */
class SessionController(
    private val app: Application,
    private val store: SessionStore
) {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _foregrounded = MutableStateFlow(true)

    /** True while any activity is in the foreground. */
    val foregrounded: StateFlow<Boolean> = _foregrounded.asStateFlow()

    /** True while any activity is in the foreground. */
    @Volatile
    var appForegrounded: Boolean = true
        private set

    /** Time the app was backgrounded (0 = not backgrounded). */
    @Volatile
    private var backgroundedAt: Long = 0L

    /** Monitor state maintained by the service. */
    @Volatile
    private var monitorConnected: Boolean = false

    @Volatile
    private var monitorLastEventTime: Long = 0L

    @Volatile
    private var monitorEventsWhileAway: Int = 0

    @Volatile
    private var monitorDisconnectedWhileAway: Boolean = false

    /** Page observer snapshot, read from the WebView. */
    @Volatile
    var pageObserver: Staleness.PageObserver? = null

    /** Listener notified when reconciliation decides a reload is needed. */
    var onReloadRequested: (() -> Unit)? = null

    /** Listener notified when the session should be marked connected. */
    var onConnected: (() -> Unit)? = null

    fun setForegrounded(foregrounded: Boolean) {
        if (appForegrounded == foregrounded) return
        appForegrounded = foregrounded
        _foregrounded.value = foregrounded
        if (foregrounded) {
            val backgroundedMs = if (backgroundedAt > 0) System.currentTimeMillis() - backgroundedAt else 0L
            backgroundedAt = 0L
            onAppForeground(backgroundedMs)
        } else {
            backgroundedAt = System.currentTimeMillis()
            monitorEventsWhileAway = 0
            monitorDisconnectedWhileAway = false
            // The service will start its SSE monitor now.
            updateState { it.copy(status = ConnectionStatus.SYNCHRONIZING) }
        }
    }

    fun onUrlLoaded(url: String) {
        updateState {
            it.copy(
                url = url,
                status = ConnectionStatus.LOADING,
                errorMessage = null,
                eventsWhileAway = 0
            )
        }
    }

    fun onPageStarted() {
        updateState { it.copy(status = ConnectionStatus.LOADING) }
    }

    fun onPageFinished() {
        // Page loaded; if the monitor is connected and the page is live, we can
        // show Connected. Otherwise wait for reconciliation.
        updateState {
            if (it.status == ConnectionStatus.ERROR) it
            else it.copy(status = if (monitorConnected) ConnectionStatus.SYNCHRONIZING else it.status)
        }
    }

    fun onPageError(message: String) {
        updateState { it.copy(status = ConnectionStatus.ERROR, errorMessage = message) }
    }

    fun onMonitorState(
        connected: Boolean,
        lastEventTime: Long,
        eventsWhileAway: Int,
        disconnectedWhileAway: Boolean
    ) {
        monitorConnected = connected
        monitorLastEventTime = lastEventTime
        monitorEventsWhileAway = eventsWhileAway
        monitorDisconnectedWhileAway = disconnectedWhileAway
        updateState {
            it.copy(
                monitorConnected = connected,
                eventsWhileAway = eventsWhileAway,
                status = when {
                    it.status == ConnectionStatus.ERROR -> it.status
                    !connected && it.url != null -> ConnectionStatus.RECONNECTING
                    else -> it.status
                }
            )
        }
    }

    fun onNetworkAvailable() {
        updateState {
            if (it.url != null && it.status != ConnectionStatus.ERROR) {
                it.copy(status = ConnectionStatus.RECONNECTING)
            } else it
        }
    }

    fun onNetworkLost() {
        updateState {
            if (it.url != null) it.copy(status = ConnectionStatus.OFFLINE) else it
        }
    }

    fun stopSession() {
        updateState { SessionState() }
        pageObserver = null
        monitorConnected = false
        monitorEventsWhileAway = 0
        monitorDisconnectedWhileAway = false
    }

    /**
     * Called when the app returns to the foreground. Reconciles the page's
     * live channel with the monitor checkpoint and decides whether to reload.
     */
    private fun onAppForeground(backgroundedMs: Long) {
        val url = _state.value.url ?: return
        val monitor = Staleness.MonitorState(
            connected = monitorConnected,
            lastEventTime = monitorLastEventTime,
            eventsWhileAway = monitorEventsWhileAway,
            disconnectedWhileAway = monitorDisconnectedWhileAway
        )
        val decision = Staleness.decide(System.currentTimeMillis(), pageObserver, monitor, backgroundedMs)
        when (decision) {
            Staleness.Decision.PAGE_LIVE -> {
                updateState { it.copy(status = ConnectionStatus.CONNECTED) }
                onConnected?.invoke()
            }
            Staleness.Decision.PAGE_UNKNOWN -> {
                updateState { it.copy(status = ConnectionStatus.SYNCHRONIZING) }
            }
            Staleness.Decision.RELOAD_REQUIRED -> {
                updateState { it.copy(status = ConnectionStatus.SYNCHRONIZING) }
                onReloadRequested?.invoke()
            }
        }
    }

    /** Called by the UI after a reload completes to re-check liveness. */
    fun onPageReloaded() {
        updateState { it.copy(status = ConnectionStatus.SYNCHRONIZING) }
    }

    private fun updateState(transform: (SessionState) -> SessionState) {
        _state.value = transform(_state.value)
    }
}
