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
        // Keep the last crash so it can be shown and uploaded on the next start (the system turns the
        // accessibility service off after a crash, so we need to know why).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                getSharedPreferences("nc_mobile", MODE_PRIVATE).edit()
                    .putString("last_crash", "${System.currentTimeMillis()} [${t.name}] ${android.util.Log.getStackTraceString(e).take(4000)}")
                    .commit()
            }
            previous?.uncaughtException(t, e)
        }
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
