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

class AdaptiveGuideOverlayService : Service() {

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

        val flowId = intent?.getStringExtra(WatchdogConfig.EXTRA_INCIDENT_OVERLAY_ADAPTIVE_FLOW_ID)
            .orEmpty()
            .trim()
        val pack = AdaptiveGuideRulePackStore.load(this)
        val invalidFlowIds = AdaptiveGuideRulePackValidator.invalidFlowIds(pack)
        val initialState = AdaptiveGuideEngine.start(pack, flowId)
        if (flowId.isBlank() || initialState == null || invalidFlowIds.contains(flowId)) {
            AdaptiveGuideAuditLog.append(
                context = this,
                event = "adaptive_guide_unavailable",
                detail = "flow_id=$flowId invalid=${invalidFlowIds.contains(flowId)}"
            )
            Toast.makeText(this, R.string.adaptive_guide_unavailable, Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        overlayTitle = intent?.getStringExtra(WatchdogConfig.EXTRA_INCIDENT_OVERLAY_TITLE)
            .orEmpty()
            .ifBlank { initialState.title.ifBlank { getString(R.string.incident_overlay_title) } }
        overlayReturnActivityClassName = intent?.getStringExtra(
            WatchdogConfig.EXTRA_INCIDENT_OVERLAY_RETURN_ACTIVITY
        )
        showOverlay(
            pack = pack,
            flowId = flowId,
            initialState = initialState
        )
        AdaptiveGuideAuditLog.append(
            context = this,
            event = "adaptive_guide_started",
            detail = "flow_id=$flowId state_id=${initialState.stateId}"
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        cancelPinnedGuideNotification()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(
        pack: AdaptiveGuideRulePack,
        flowId: String,
        initialState: AdaptiveGuideResolvedState
    ) {
        removeOverlay()

        val history = mutableListOf(initialState.stateId)
        var currentState = initialState
        var isCollapsed = false

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            setBackgroundColor(0xEE2C1806.toInt())
        }
        val titleView = TextView(this).apply {
            text = overlayTitle
            setTextColor(0xFFFFF6E8.toInt())
            textSize = 14f
        }
        val counterView = TextView(this).apply {
            setTextColor(0xFFF8D9A9.toInt())
            textSize = 12f
        }
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
        val matchLabel = TextView(this).apply {
            text = getString(R.string.adaptive_guide_match_label)
            setTextColor(0xFFEFC47C.toInt())
            textSize = 12f
        }
        val hintView = TextView(this).apply {
            setTextColor(0xFFFFE7C4.toInt())
            textSize = 12f
            setLineSpacing(3f, 1f)
        }
        val anchorButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val primaryControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val previousButton = Button(this).apply {
            text = getString(R.string.incident_overlay_prev)
        }
        val resetButton = Button(this).apply {
            text = getString(R.string.adaptive_guide_reset_route)
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
        primaryControls.addView(previousButton, buttonLayout)
        primaryControls.addView(
            resetButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        controls.addView(primaryControls)
        controls.addView(
            doneButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
            }
        )

        fun renderState() {
            val hasAnchors = currentState.anchors.isNotEmpty()
            counterView.text = getString(
                R.string.incident_overlay_step_template,
                currentState.stepNumber,
                currentState.totalSteps
            )
            targetButton.text = currentState.currentTarget
            hintView.text = currentState.assistantHint.ifBlank {
                getString(R.string.adaptive_guide_hint_default)
            }
            previousButton.isEnabled = history.size > 1
            resetButton.isEnabled = history.size > 1
            doneButton.text = if (currentState.complete) {
                getString(R.string.incident_overlay_finish)
            } else {
                getString(R.string.incident_overlay_done)
            }
            anchorButtons.removeAllViews()
            currentState.anchors.forEachIndexed { index, anchor ->
                val anchorButton = Button(this).apply {
                    isAllCaps = false
                    text = anchor.label
                    textSize = 14f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setOnClickListener {
                        val next = AdaptiveGuideEngine.transition(
                            pack = pack,
                            flowId = flowId,
                            stateId = currentState.stateId,
                            anchorId = anchor.id
                        ) ?: return@setOnClickListener
                        AdaptiveGuideAuditLog.append(
                            context = this@AdaptiveGuideOverlayService,
                            event = "adaptive_guide_anchor_selected",
                            detail = "flow_id=$flowId state_id=${currentState.stateId} anchor_id=${anchor.id} next_state_id=${next.stateId}"
                        )
                        history += next.stateId
                        currentState = next
                        renderState()
                    }
                }
                anchorButtons.addView(
                    anchorButton,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index > 0) {
                            topMargin = dpToPx(6)
                        }
                    }
                )
            }
            applyAdaptiveLayoutState(
                isCollapsed = isCollapsed,
                hasAnchors = hasAnchors,
                toggleLink = toggleLink,
                focusLabel = focusLabel,
                targetButton = targetButton,
                matchLabel = matchLabel,
                hintView = hintView,
                anchorButtons = anchorButtons,
                controls = controls
            )
            updatePinnedGuideNotification(
                stepLabel = counterView.text.toString(),
                currentTarget = currentState.currentTarget
            )
        }

        previousButton.setOnClickListener {
            if (history.size <= 1) {
                return@setOnClickListener
            }
            history.removeAt(history.lastIndex)
            val previousStateId = history.lastOrNull() ?: return@setOnClickListener
            val previousState = AdaptiveGuideEngine.resolve(
                pack = pack,
                flowId = flowId,
                stateId = previousStateId
            ) ?: return@setOnClickListener
            currentState = previousState
            AdaptiveGuideAuditLog.append(
                context = this,
                event = "adaptive_guide_previous",
                detail = "flow_id=$flowId state_id=${currentState.stateId}"
            )
            renderState()
        }
        resetButton.setOnClickListener {
            currentState = AdaptiveGuideEngine.start(pack, flowId) ?: return@setOnClickListener
            history.clear()
            history += currentState.stateId
            AdaptiveGuideAuditLog.append(
                context = this,
                event = "adaptive_guide_reset",
                detail = "flow_id=$flowId state_id=${currentState.stateId}"
            )
            renderState()
        }
        toggleLink.setOnClickListener {
            isCollapsed = !isCollapsed
            applyAdaptiveLayoutState(
                isCollapsed = isCollapsed,
                hasAnchors = currentState.anchors.isNotEmpty(),
                toggleLink = toggleLink,
                focusLabel = focusLabel,
                targetButton = targetButton,
                matchLabel = matchLabel,
                hintView = hintView,
                anchorButtons = anchorButtons,
                controls = controls
            )
        }

        container.addView(headerRow)
        container.addView(focusLabel)
        container.addView(targetButton)
        container.addView(matchLabel)
        container.addView(hintView)
        container.addView(
            anchorButtons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
            }
        )
        container.addView(
            controls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
            }
        )

        renderState()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager?.addView(container, createOverlayParams())
        overlayView = container
    }

    private fun applyAdaptiveLayoutState(
        isCollapsed: Boolean,
        hasAnchors: Boolean,
        toggleLink: TextView,
        focusLabel: View,
        targetButton: Button,
        matchLabel: View,
        hintView: View,
        anchorButtons: View,
        controls: View
    ) {
        val state = IncidentGuideOverlayLayout.adaptiveLayoutState(
            isCollapsed = isCollapsed,
            hasAnchors = hasAnchors
        )
        toggleLink.text = getString(state.toggleLabelRes)
        focusLabel.visibility = state.focusLabelVisibility
        matchLabel.visibility = state.matchLabelVisibility
        hintView.visibility = state.hintVisibility
        anchorButtons.visibility = state.anchorButtonsVisibility
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

    private fun updatePinnedGuideNotification(
        stepLabel: String,
        currentTarget: String
    ) {
        if (!shouldPinGuideNotification() || !canPostGuideNotifications()) {
            return
        }
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

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
    }

    private fun cancelPinnedGuideNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(WatchdogConfig.INCIDENT_GUIDE_NOTIFICATION_ID)
        } catch (_: SecurityException) {
            // Ignore if notifications are denied.
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
