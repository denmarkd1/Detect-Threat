package com.realyn.watchdog

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class CredentialOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: WatchdogConfig.ACTION_SHOW_OVERLAY

        if (action == WatchdogConfig.ACTION_HIDE_OVERLAY) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val password = intent?.getStringExtra(WatchdogConfig.EXTRA_OVERLAY_PASSWORD).orEmpty()
        val targetUrl = intent?.getStringExtra(WatchdogConfig.EXTRA_OVERLAY_TARGET_URL).orEmpty()
        showOverlay(password, targetUrl)
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(password: String, targetUrl: String) {
        removeOverlay()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 22, 26, 22)
            setBackgroundColor(0xEE102A43.toInt())
        }

        val title = TextView(this).apply {
            text = getString(R.string.overlay_title)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }

        val subtitle = TextView(this).apply {
            text = getString(R.string.overlay_subtitle)
            setTextColor(0xFFD2E6F7.toInt())
            textSize = 12f
        }

        val copyPasswordButton = Button(this).apply {
            text = getString(R.string.overlay_copy_password)
            setOnClickListener {
                if (password.isBlank()) {
                    Toast.makeText(context, R.string.overlay_no_password, Toast.LENGTH_SHORT).show()
                } else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("DT generated password", password))
                    Toast.makeText(context, R.string.overlay_password_copied, Toast.LENGTH_SHORT).show()
                }
            }
        }

        val openUrlButton = Button(this).apply {
            text = getString(R.string.overlay_open_site)
            setOnClickListener {
                val normalized = CredentialPolicy.normalizeUrl(targetUrl)
                if (normalized.isBlank()) {
                    Toast.makeText(context, R.string.overlay_no_url, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(openIntent) }
                    .onFailure {
                        Toast.makeText(context, R.string.overlay_open_site_failed, Toast.LENGTH_SHORT).show()
                    }
            }
        }

        val closeButton = Button(this).apply {
            text = getString(R.string.overlay_close)
            setOnClickListener { stopSelf() }
        }

        container.addView(title)
        container.addView(subtitle)
        container.addView(copyPasswordButton)
        container.addView(openUrlButton)
        container.addView(closeButton)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 180
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager?.addView(container, params)
        overlayView = container
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
    }
}
