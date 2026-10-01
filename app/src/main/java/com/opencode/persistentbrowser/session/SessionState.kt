package com.opencode.persistentbrowser.session

/** Connection status shown in the UI status chip. */
enum class ConnectionStatus {
    IDLE,
    LOADING,
    CONNECTED,
    SYNCHRONIZING,
    RECONNECTING,
    OFFLINE,
    ERROR
}

/** High-level session state observed by the UI. */
data class SessionState(
    val status: ConnectionStatus = ConnectionStatus.IDLE,
    val url: String? = null,
    val errorMessage: String? = null,
    val pageLive: Boolean = false,
    val monitorConnected: Boolean = false,
    val eventsWhileAway: Int = 0
) {
    val statusLabel: String
        get() = when (status) {
            ConnectionStatus.IDLE -> ""
            ConnectionStatus.LOADING -> "Loading…"
            ConnectionStatus.CONNECTED -> "Connected"
            ConnectionStatus.SYNCHRONIZING -> "Synchronizing…"
            ConnectionStatus.RECONNECTING -> "Reconnecting…"
            ConnectionStatus.OFFLINE -> "Offline"
            ConnectionStatus.ERROR -> errorMessage ?: "Error"
        }
}
