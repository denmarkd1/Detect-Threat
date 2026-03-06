package com.realyn.watchdog

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
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

class IncidentGuideOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

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

        container.addView(titleView)
        container.addView(counterView)

        if (compactMode) {
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
            targetButton.setOnClickListener { advanceStep() }
            completeButton.setOnClickListener { advanceStep() }

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
        } else {
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
}
