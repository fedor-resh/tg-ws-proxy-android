package org.flowseal.tgwsproxy.android

import android.app.Application

class TgWsProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val configStore = ProxyConfigStore(this)
        ProxyLog.configure(this, configStore.load().verbose)
        ProxyLog.i("Application created")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            ProxyLog.captureCrash("Uncaught exception on thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
