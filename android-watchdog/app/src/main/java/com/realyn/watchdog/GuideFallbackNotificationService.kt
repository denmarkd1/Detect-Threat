package com.realyn.watchdog

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

class GuideFallbackNotificationService : Service() {

    private var activeNotificationId: Int = 0

    override fun onCreate() {
        super.onCreate()
        createGuideChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: WatchdogConfig.ACTION_SHOW_GUIDE_FALLBACK
        if (action == WatchdogConfig.ACTION_HIDE_GUIDE_FALLBACK) {
            val requestedNotificationId = intent?.getIntExtra(
                WatchdogConfig.EXTRA_GUIDE_FALLBACK_NOTIFICATION_ID,
                0
            ) ?: 0
            if (
                activeNotificationId == 0 ||
                requestedNotificationId == 0 ||
                requestedNotificationId == activeNotificationId
            ) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (!canPostGuideNotifications()) {
            Log.w(TAG, "Guide fallback notification suppressed because notifications are unavailable")
            stopSelf()
            return START_NOT_STICKY
        }

        val notificationId = intent?.getIntExtra(
            WatchdogConfig.EXTRA_GUIDE_FALLBACK_NOTIFICATION_ID,
            WatchdogConfig.FOUNDATION_GUIDE_FALLBACK_NOTIFICATION_ID
        ) ?: WatchdogConfig.FOUNDATION_GUIDE_FALLBACK_NOTIFICATION_ID
        val title = intent?.getStringExtra(WatchdogConfig.EXTRA_GUIDE_FALLBACK_TITLE)
            .orEmpty()
            .ifBlank { getString(R.string.incident_overlay_title) }
        val currentTarget = intent?.getStringExtra(WatchdogConfig.EXTRA_GUIDE_FALLBACK_CURRENT_TARGET)
            .orEmpty()
            .trim()
            .ifBlank { getString(R.string.incident_overlay_default_step) }
        val returnActivityClassName = intent?.getStringExtra(
            WatchdogConfig.EXTRA_GUIDE_FALLBACK_RETURN_ACTIVITY
        )
        val screenMode = intent?.getStringExtra(WatchdogConfig.EXTRA_GUIDE_FALLBACK_SCREEN_MODE)
            .orEmpty()
            .trim()
        if (activeNotificationId != 0 && activeNotificationId != notificationId) {
            cancelNotification(activeNotificationId)
        }
        activeNotificationId = notificationId
        val notification = buildNotification(
            notificationId = notificationId,
            title = title,
            currentTarget = currentTarget,
            returnActivityClassName = returnActivityClassName,
            screenMode = screenMode
        )
        startGuideForeground(notificationId, notification)
        Log.i(
            TAG,
            "Guide fallback notification active id=$notificationId title=$title target=$currentTarget"
        )
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        if (activeNotificationId != 0) {
            cancelNotification(activeNotificationId)
            activeNotificationId = 0
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(
        notificationId: Int,
        title: String,
        currentTarget: String,
        returnActivityClassName: String?,
        screenMode: String
    ): Notification {
        val reopenPendingIntent = buildPendingIntent(
            notificationId = notificationId,
            returnActivityClassName = returnActivityClassName,
            screenMode = screenMode
        )
        val detail = buildString {
            appendLine(getString(R.string.guide_fallback_notification_summary))
            appendLine()
            appendLine(currentTarget)
            appendLine()
            append(getString(R.string.guide_fallback_notification_hint))
        }
        return NotificationCompat.Builder(this, WatchdogConfig.GUIDE_FALLBACK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(currentTarget)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(reopenPendingIntent)
            .addAction(
                0,
                getString(R.string.guide_fallback_notification_action_open),
                reopenPendingIntent
            )
            .build()
    }

    private fun buildPendingIntent(
        notificationId: Int,
        returnActivityClassName: String?,
        screenMode: String
    ): PendingIntent {
        val targetActivity = returnActivityClassName
            ?.takeIf { it.isNotBlank() }
            ?.let { className ->
                runCatching {
                    Class.forName(className).asSubclass(Activity::class.java)
                }.getOrNull()
            }
            ?: CredentialDefenseActivity::class.java
        val openIntent = Intent(this, targetActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (
                targetActivity == ScanResultsActivity::class.java &&
                screenMode.isNotBlank()
            ) {
                putExtra(ScanResultsActivity.EXTRA_SCREEN_MODE, screenMode)
            }
        }
        return PendingIntent.getActivity(
            this,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startGuideForeground(notificationId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun createGuideChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            WatchdogConfig.GUIDE_FALLBACK_CHANNEL_ID,
            getString(R.string.notification_channel_guide_fallback),
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = getString(R.string.notification_channel_guide_fallback_description)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
    }

    private fun canPostGuideNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return false
            }
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun cancelNotification(notificationId: Int) {
        runCatching {
            NotificationManagerCompat.from(this).cancel(notificationId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to cancel guide fallback notification id=$notificationId", error)
        }
    }

    private companion object {
        private const val TAG = "GuideFallbackNotif"
    }
}
