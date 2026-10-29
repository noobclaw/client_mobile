package com.noobclaw.mobile.run

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noobclaw.mobile.util.Prefs

/** Restarts the standby service after a reboot, only if the user had started automation before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val autostart = context.getSharedPreferences("nc_mobile", Context.MODE_PRIVATE).getBoolean("autostart", false)
        if (autostart && Prefs.token.isNotEmpty()) RunService.start(context)
    }
}

/** Reserved for a watchdog alarm; the foreground service schedules runs itself. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.token.isNotEmpty()) RunService.start(context)
    }
}
