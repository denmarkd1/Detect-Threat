package com.realyn.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        if (!SecurityScanner.isContinuousModeEnabled(context)) {
            return
        }

        val serviceIntent = Intent(context, ContinuousWatchdogService::class.java).apply {
            action = WatchdogConfig.ACTION_START_CONTINUOUS
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
