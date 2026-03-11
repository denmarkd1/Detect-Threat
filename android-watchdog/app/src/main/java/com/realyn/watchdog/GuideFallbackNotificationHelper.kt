package com.realyn.watchdog

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object GuideFallbackNotificationHelper {

    fun showFoundationGuide(
        context: Context,
        title: String,
        currentTarget: String
    ) {
        showGuideState(
            context = context,
            notificationId = WatchdogConfig.FOUNDATION_GUIDE_FALLBACK_NOTIFICATION_ID,
            title = title,
            currentTarget = currentTarget,
            returnActivityClassName = CredentialDefenseActivity::class.java.name
        )
    }

    fun clearFoundationGuide(context: Context) {
        stopGuideFallbackService(context, WatchdogConfig.FOUNDATION_GUIDE_FALLBACK_NOTIFICATION_ID)
    }

    fun showIncidentGuide(
        context: Context,
        title: String,
        currentTarget: String
    ) {
        showGuideState(
            context = context,
            notificationId = WatchdogConfig.INCIDENT_GUIDE_FALLBACK_NOTIFICATION_ID,
            title = title,
            currentTarget = currentTarget,
            returnActivityClassName = ScanResultsActivity::class.java.name,
            screenMode = ScanResultsActivity.SCREEN_MODE_INCIDENT_ASSISTANT
        )
    }

    fun clearIncidentGuide(context: Context) {
        stopGuideFallbackService(context, WatchdogConfig.INCIDENT_GUIDE_FALLBACK_NOTIFICATION_ID)
    }

    fun showGuideState(
        context: Context,
        notificationId: Int,
        title: String,
        currentTarget: String,
        returnActivityClassName: String,
        screenMode: String = ""
    ) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, GuideFallbackNotificationService::class.java).apply {
                action = WatchdogConfig.ACTION_SHOW_GUIDE_FALLBACK
                putExtra(WatchdogConfig.EXTRA_GUIDE_FALLBACK_NOTIFICATION_ID, notificationId)
                putExtra(WatchdogConfig.EXTRA_GUIDE_FALLBACK_TITLE, title)
                putExtra(WatchdogConfig.EXTRA_GUIDE_FALLBACK_CURRENT_TARGET, currentTarget)
                putExtra(
                    WatchdogConfig.EXTRA_GUIDE_FALLBACK_RETURN_ACTIVITY,
                    returnActivityClassName
                )
                putExtra(WatchdogConfig.EXTRA_GUIDE_FALLBACK_SCREEN_MODE, screenMode)
            }
        )
    }

    private fun stopGuideFallbackService(context: Context, notificationId: Int) {
        context.startService(
            Intent(context, GuideFallbackNotificationService::class.java).apply {
                action = WatchdogConfig.ACTION_HIDE_GUIDE_FALLBACK
                putExtra(WatchdogConfig.EXTRA_GUIDE_FALLBACK_NOTIFICATION_ID, notificationId)
            }
        )
    }
}
