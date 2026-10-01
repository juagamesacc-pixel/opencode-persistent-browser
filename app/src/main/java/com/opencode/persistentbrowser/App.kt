package com.opencode.persistentbrowser

import android.app.Application
import com.opencode.persistentbrowser.data.SessionStore
import com.opencode.persistentbrowser.session.SessionController

class App : Application() {

    lateinit var sessionStore: SessionStore
        private set

    lateinit var sessionController: SessionController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        sessionStore = SessionStore(this)
        sessionController = SessionController(this, sessionStore)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
