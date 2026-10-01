package com.opencode.persistentbrowser

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * Tracks whether the app is in the foreground. Uses simple manual tracking
 * driven by the activity's onStart/onStop, exposed as LiveData so the service
 * can gate its SSE monitor on backgrounded state.
 */
class MainViewModel : ViewModel() {

    val appForegrounded = MutableLiveData(true)

    fun onForeground() {
        appForegrounded.value = true
    }

    fun onBackground() {
        appForegrounded.value = false
    }
}
