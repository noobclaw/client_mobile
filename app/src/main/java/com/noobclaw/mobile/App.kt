package com.noobclaw.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.noobclaw.mobile.util.Prefs
import com.noobclaw.mobile.util.RunLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        RunLog.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_RUN = "run"
        lateinit var instance: App
            private set
    }
}
