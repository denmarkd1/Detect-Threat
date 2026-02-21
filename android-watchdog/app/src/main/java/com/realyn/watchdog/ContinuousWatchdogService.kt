package com.realyn.watchdog

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ContinuousWatchdogService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: WatchdogConfig.ACTION_START_CONTINUOUS

        if (action == WatchdogConfig.ACTION_STOP_CONTINUOUS) {
            stopContinuousMode()
            return START_NOT_STICKY
        }

        val access = PricingPolicy.resolveFeatureAccess(this)
        if (!access.features.continuousScanEnabled) {
            SecurityScanner.setContinuousModeEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        val posture = SecurityScanner.currentRootPosture(this)
        val hardening = RootDefense.resolveHardeningPolicy(posture)
        if (!hardening.allowContinuousMode) {
            SecurityScanner.setContinuousModeEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        SecurityScanner.setContinuousModeEnabled(this, true)
        startForeground(
            WatchdogConfig.FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification(getString(R.string.notification_foreground_text))
        )

        if (scanJob?.isActive != true) {
            scanJob = serviceScope.launch {
                while (isActive) {
                    val result = SecurityScanner.runScan(this@ContinuousWatchdogService, createBaselineIfMissing = true)
                    val summary = SecurityScanner.summaryLine(result)
                    updateForegroundNotification(summary)

                    if (result.alerts.isNotEmpty()) {
                        maybeSendAlertNotification(result)
                    }

                    delay(WatchdogConfig.SCAN_INTERVAL_MS)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        SecurityScanner.setContinuousModeEnabled(this, false)
        scanJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopContinuousMode() {
        SecurityScanner.setContinuousModeEnabled(this, false)
        scanJob?.cancel()
        scanJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildForegroundNotification(text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, WatchdogConfig.FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle(getString(R.string.notification_foreground_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateForegroundNotification(summary: String) {
        val notification = buildForegroundNotification(summary)
        val manager = NotificationManagerCompat.from(this)
        try {
            manager.notify(WatchdogConfig.FOREGROUND_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Ignore if notifications are denied.
        }
    }

    private fun maybeSendAlertNotification(result: ScanResult) {
        val importantAlerts = result.alerts.filter { it.severity == Severity.HIGH || it.severity == Severity.MEDIUM }
        if (importantAlerts.isEmpty()) {
            return
        }

        val fingerprint = SecurityScanner.alertFingerprint(importantAlerts)
        val previous = SecurityScanner.readLastAlertFingerprint(this)
        if (fingerprint == previous) {
            return
        }

        SecurityScanner.writeLastAlertFingerprint(this, fingerprint)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return
            }
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = importantAlerts.take(3).joinToString(" | ") { "${it.severity}: ${it.title}" }

        val notification = NotificationCompat.Builder(this, WatchdogConfig.ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("DT Scanner detected risk changes")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(WatchdogConfig.ALERT_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Ignore if notifications are denied.
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)

        val foreground = NotificationChannel(
            WatchdogConfig.FOREGROUND_CHANNEL_ID,
            getString(R.string.notification_channel_foreground),
            NotificationManager.IMPORTANCE_LOW
        )

        val alerts = NotificationChannel(
            WatchdogConfig.ALERT_CHANNEL_ID,
            getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_HIGH
        )

        manager.createNotificationChannel(foreground)
        manager.createNotificationChannel(alerts)
    }
}
