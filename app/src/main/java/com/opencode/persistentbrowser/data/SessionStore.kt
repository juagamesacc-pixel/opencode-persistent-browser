package com.opencode.persistentbrowser.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * Persistent, non-secret session state.
 *
 * Stores only the last URL, whether a session is active, and the monitor
 * sync checkpoint (last event id + time). No cookies, tokens or credentials.
 */
class SessionStore(private val context: Context) {

    private object Keys {
        val LAST_URL = stringPreferencesKey("last_url")
        val SESSION_ACTIVE = booleanPreferencesKey("session_active")
        val LAST_EVENT_ID = stringPreferencesKey("last_event_id")
        val LAST_EVENT_TIME = longPreferencesKey("last_event_time")
        val MONITOR_CONNECTED_AT = longPreferencesKey("monitor_connected_at")
    }

    suspend fun getLastUrl(): String? =
        context.dataStore.data.first()[Keys.LAST_URL]

    suspend fun isSessionActive(): Boolean =
        context.dataStore.data.first()[Keys.SESSION_ACTIVE] ?: false

    suspend fun getLastEventId(): String? =
        context.dataStore.data.first()[Keys.LAST_EVENT_ID]

    suspend fun getLastEventTime(): Long =
        context.dataStore.data.first()[Keys.LAST_EVENT_TIME] ?: 0L

    suspend fun saveUrl(url: String) {
        context.dataStore.edit { it[Keys.LAST_URL] = url }
    }

    suspend fun setSessionActive(active: Boolean) {
        context.dataStore.edit { it[Keys.SESSION_ACTIVE] = active }
    }

    suspend fun saveCheckpoint(eventId: String?, eventTime: Long) {
        context.dataStore.edit {
            if (eventId != null) it[Keys.LAST_EVENT_ID] = eventId
            it[Keys.LAST_EVENT_TIME] = eventTime
        }
    }

    suspend fun saveMonitorConnectedAt(time: Long) {
        context.dataStore.edit { it[Keys.MONITOR_CONNECTED_AT] = time }
    }

    suspend fun clearCheckpoint() {
        context.dataStore.edit {
            it.remove(Keys.LAST_EVENT_ID)
            it.remove(Keys.LAST_EVENT_TIME)
            it.remove(Keys.MONITOR_CONNECTED_AT)
        }
    }
}
