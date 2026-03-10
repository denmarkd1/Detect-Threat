package com.realyn.watchdog

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Paint
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class IncidentGuideOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayTitle: String = ""
    private var overlayReturnActivityClassName: String? = null

    override fun onCreate() {
        super.onCreate()
        createGuideChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: WatchdogConfig.ACTION_SHOW_INCIDENT_OVERLAY
        if (action == WatchdogConfig.ACTION_HIDE_INCIDENT_OVERLAY) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.incident_overlay_permission_required, Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(WatchdogConfig.EXTRA_INCIDENT_OVERLAY_TITLE)
            .orEmpty()
            .ifBlank { getString(R.string.incident_overlay_title) }
        overlayTitle = title
        overlayReturnActivityClassName = intent?.getStringExtra(
            WatchdogConfig.EXTRA_INCIDENT_OVERLAY_RETURN_ACTIVITY
        )
        val instructions = intent
            ?.getStringArrayListExtra(WatchdogConfig.EXTRA_INCIDENT_OVERLAY_STEPS)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val compactMode = intent?.getBooleanExtra(
            WatchdogConfig.EXTRA_INCIDENT_OVERLAY_COMPACT_MODE,
            false
        ) == true
        showOverlay(title = title, instructions = instructions, compactMode = compactMode)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        cancelPinnedGuideNotification()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(title: String, instructions: List<String>, compactMode: Boolean) {
        removeOverlay()

        val steps = if (instructions.isEmpty()) {
            listOf(getString(R.string.incident_overlay_default_step))
        } else {
            instructions
        }
        var stepIndex = 0

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            setBackgroundColor(0xEE2C1806.toInt())
        }

        val titleView = TextView(this).apply {
            text = title
            setTextColor(0xFFFFF6E8.toInt())
            textSize = 14f
        }

        val counterView = TextView(this).apply {
            setTextColor(0xFFF8D9A9.toInt())
            textSize = 12f
        }

        if (compactMode) {
            var isCollapsed = false
            val titleColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(counterView)
            }
            val toggleLink = TextView(this).apply {
                setTextColor(0xFFEFC47C.toInt())
                textSize = 12f
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                setPadding(dpToPx(8), dpToPx(4), dpToPx(0), dpToPx(4))
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    titleColumn,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
                addView(toggleLink)
            }
            val focusLabel = TextView(this).apply {
                text = getString(R.string.incident_overlay_focus_label)
                setTextColor(0xFFEFC47C.toInt())
                textSize = 12f
            }
            val targetButton = Button(this).apply {
                isAllCaps = false
                textSize = 16f
                setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
            }
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val primaryControls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val prevButton = Button(this).apply {
                text = getString(R.string.incident_overlay_prev)
            }
            val completeButton = Button(this)
            val closeButton = Button(this).apply {
                text = getString(R.string.incident_overlay_done)
                setOnClickListener { stopSelf() }
            }

            val buttonLayout = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = 8
            }
            primaryControls.addView(prevButton, buttonLayout)
            primaryControls.addView(
                completeButton,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            controls.addView(primaryControls)
            controls.addView(
                closeButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(6)
                }
            )

            fun advanceStep() {
                if (stepIndex < steps.lastIndex) {
                    stepIndex += 1
                } else {
                    stopSelf()
                    return
                }
                renderCompactStep(
                    stepIndex = stepIndex,
                    total = steps.size,
                    steps = steps,
                    counterView = counterView,
                    targetButton = targetButton,
                    prevButton = prevButton,
                    completeButton = completeButton
                )
            }

            prevButton.setOnClickListener {
                if (stepIndex > 0) {
                    stepIndex -= 1
                    renderCompactStep(
                        stepIndex = stepIndex,
                        total = steps.size,
                        steps = steps,
                        counterView = counterView,
                        targetButton = targetButton,
                        prevButton = prevButton,
                        completeButton = completeButton
                    )
                }
            }
            toggleLink.setOnClickListener {
                isCollapsed = !isCollapsed
                applyCompactLayoutState(
                    isCollapsed = isCollapsed,
                    toggleLink = toggleLink,
                    focusLabel = focusLabel,
                    targetButton = targetButton,
                    controls = controls
                )
            }
            targetButton.setOnClickListener {
                if (!isCollapsed) {
                    advanceStep()
                }
            }
            completeButton.setOnClickListener { advanceStep() }

            container.addView(headerRow)
            container.addView(focusLabel)
            container.addView(targetButton)
            container.addView(controls)
            renderCompactStep(
                stepIndex = stepIndex,
                total = steps.size,
                steps = steps,
                counterView = counterView,
                targetButton = targetButton,
                prevButton = prevButton,
                completeButton = completeButton
            )
            applyCompactLayoutState(
                isCollapsed = isCollapsed,
                toggleLink = toggleLink,
                focusLabel = focusLabel,
                targetButton = targetButton,
                controls = controls
            )
        } else {
            container.addView(titleView)
            container.addView(counterView)
            val stepView = TextView(this).apply {
                setTextColor(0xFFFFF3E0.toInt())
                textSize = 13f
                setLineSpacing(4f, 1f)
            }
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val prevButton = Button(this).apply {
                text = getString(R.string.incident_overlay_prev)
            }
            val nextButton = Button(this).apply {
                text = getString(R.string.incident_overlay_next)
            }
            val doneButton = Button(this).apply {
                text = getString(R.string.incident_overlay_done)
                setOnClickListener { stopSelf() }
            }

            val buttonLayout = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = 8
            }
            controls.addView(prevButton, buttonLayout)
            controls.addView(nextButton, buttonLayout)
            controls.addView(
                doneButton,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            fun renderStep() {
                counterView.text = getString(
                    R.string.incident_overlay_step_template,
                    stepIndex + 1,
                    steps.size
                )
                stepView.text = steps[stepIndex]
                prevButton.isEnabled = stepIndex > 0
                nextButton.isEnabled = stepIndex < steps.lastIndex
                updatePinnedGuideNotification(
                    stepIndex = stepIndex,
                    total = steps.size,
                    steps = steps
                )
            }

            prevButton.setOnClickListener {
                if (stepIndex > 0) {
                    stepIndex -= 1
                    renderStep()
                }
            }
            nextButton.setOnClickListener {
                if (stepIndex < steps.lastIndex) {
                    stepIndex += 1
                    renderStep()
                }
            }
            container.addView(stepView)
            container.addView(controls)
            renderStep()
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager?.addView(container, createOverlayParams())
        overlayView = container
    }

    private fun renderCompactStep(
        stepIndex: Int,
        total: Int,
        steps: List<String>,
        counterView: TextView,
        targetButton: Button,
        prevButton: Button,
        completeButton: Button
    ) {
        counterView.text = getString(
            R.string.incident_overlay_step_template,
            stepIndex + 1,
            total
        )
        targetButton.text = steps[stepIndex]
        prevButton.isEnabled = stepIndex > 0
        completeButton.text = if (stepIndex >= total - 1) {
            getString(R.string.incident_overlay_finish)
        } else {
            getString(R.string.incident_overlay_complete_step)
        }
        updatePinnedGuideNotification(
            stepIndex = stepIndex,
            total = total,
            steps = steps
        )
    }

    private fun applyCompactLayoutState(
        isCollapsed: Boolean,
        toggleLink: TextView,
        focusLabel: View,
        targetButton: Button,
        controls: View
    ) {
        val state = IncidentGuideOverlayLayout.compactLayoutState(isCollapsed = isCollapsed)
        toggleLink.text = getString(state.toggleLabelRes)
        focusLabel.visibility = state.focusLabelVisibility
        controls.visibility = state.controlsVisibility
        targetButton.maxLines = state.targetMaxLines
        val horizontalPadding = dpToPx(16)
        val verticalPadding = dpToPx(state.targetVerticalPaddingDp)
        targetButton.setPadding(
            horizontalPadding,
            verticalPadding,
            horizontalPadding,
            verticalPadding
        )
    }

    private fun createOverlayParams(): WindowManager.LayoutParams {
        val horizontalMargin = dpToPx(12)
        val maxOverlayWidth = dpToPx(440)
        val availableWidth = (resources.displayMetrics.widthPixels - (horizontalMargin * 2)).coerceAtLeast(
            dpToPx(260)
        )
        val overlayWidth = minOf(availableWidth, maxOverlayWidth)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            overlayWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dpToPx(72)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
    }

    private fun updatePinnedGuideNotification(
        stepIndex: Int,
        total: Int,
        steps: List<String>
    ) {
        if (!shouldPinGuideNotification() || !canPostGuideNotifications()) {
            return
        }
        val stepLabel = getString(
            R.string.incident_overlay_step_template,
            stepIndex + 1,
            total
        )
        val currentTarget = steps.getOrNull(stepIndex)
            .orEmpty()
            .trim()
            .ifBlank { getString(R.string.incident_overlay_default_step) }
        val detail = buildString {
            appendLine(stepLabel)
            appendLine()
            appendLine(currentTarget)
            appendLine()
            append(getString(R.string.incident_overlay_notification_hint))
        }
        val title = overlayTitle.ifBlank { getString(R.string.incident_overlay_title) }
        val notification = NotificationCompat.Builder(this, WatchdogConfig.INCIDENT_GUIDE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(currentTarget)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildGuidePendingIntent())
            .build()
        try {
            NotificationManagerCompat.from(this).notify(
                WatchdogConfig.INCIDENT_GUIDE_NOTIFICATION_ID,
                notification
            )
        } catch (_: SecurityException) {
            // Ignore if notifications are denied.
        }
    }

    private fun buildGuidePendingIntent(): PendingIntent {
        val targetActivity = overlayReturnActivityClassName
            ?.takeIf { it.isNotBlank() }
            ?.let { className ->
                runCatching {
                    Class.forName(className).asSubclass(android.app.Activity::class.java)
                }.getOrNull()
            }
            ?: ScanResultsActivity::class.java
        val openIntent = Intent(this, targetActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (targetActivity == ScanResultsActivity::class.java) {
                putExtra(
                    ScanResultsActivity.EXTRA_SCREEN_MODE,
                    ScanResultsActivity.SCREEN_MODE_INCIDENT_ASSISTANT
                )
            }
        }
        return PendingIntent.getActivity(
            this,
            WatchdogConfig.INCIDENT_GUIDE_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

    private fun shouldPinGuideNotification(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return manufacturer.contains("samsung") ||
            brand.contains("samsung") ||
            manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco") ||
            brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco")
    }

    private fun createGuideChannel() {
        if (!shouldPinGuideNotification() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            WatchdogConfig.INCIDENT_GUIDE_CHANNEL_ID,
            getString(R.string.notification_channel_incident_guide),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun cancelPinnedGuideNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(WatchdogConfig.INCIDENT_GUIDE_NOTIFICATION_ID)
        } catch (_: SecurityException) {
            // Ignore if notifications are denied.
        }
    }
}
