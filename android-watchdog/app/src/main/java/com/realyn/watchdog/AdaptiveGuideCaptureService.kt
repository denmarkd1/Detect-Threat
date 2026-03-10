package com.realyn.watchdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AdaptiveGuideCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var analysisCompleted = false

    override fun onCreate() {
        super.onCreate()
        createCaptureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val flowId = intent?.getStringExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_ANALYSIS_FLOW_ID)
            .orEmpty()
            .trim()
        val currentStateId = intent?.getStringExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_ANALYSIS_STATE_ID)
            .orEmpty()
            .trim()
        val resultCode = intent?.getIntExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_CAPTURE_RESULT_CODE, 0) ?: 0
        val dataIntent = intent?.readIntentExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_CAPTURE_DATA_INTENT)
        if (flowId.isBlank() || resultCode == 0 || dataIntent == null) {
            handleFailure(flowId = flowId, detail = "missing_capture_permission_result")
            return START_NOT_STICKY
        }

        startCaptureForeground()
        serviceScope.launch {
            delay(CAPTURE_SETTLE_DELAY_MS)
            captureSingleFrame(
                flowId = flowId,
                currentStateId = currentStateId,
                resultCode = resultCode,
                dataIntent = dataIntent
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseCaptureResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun captureSingleFrame(
        flowId: String,
        currentStateId: String,
        resultCode: Int,
        dataIntent: Intent
    ) {
        val pack = AdaptiveGuideRulePackStore.load(this)
        val flow = pack.flows[flowId]
        if (flow == null) {
            handleFailure(flowId = flowId, detail = "flow_not_found:$flowId")
            return
        }
        val captureMetrics = resolveCaptureMetrics()
        val mediaProjectionManager = getSystemService<MediaProjectionManager>()
        if (mediaProjectionManager == null) {
            handleFailure(flowId = flowId, detail = "media_projection_manager_unavailable")
            return
        }
        val projection = runCatching {
            mediaProjectionManager.getMediaProjection(resultCode, dataIntent)
        }.getOrNull()
        if (projection == null) {
            handleFailure(flowId = flowId, detail = "media_projection_token_invalid")
            return
        }
        mediaProjection = projection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!analysisCompleted) {
                    handleFailure(flowId = flowId, detail = "media_projection_stopped")
                }
            }
        }
        mediaProjectionCallback = callback
        projection.registerCallback(callback, mainHandler)

        val reader = ImageReader.newInstance(
            captureMetrics.width,
            captureMetrics.height,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            source.setOnImageAvailableListener(null, null)
            val bitmap = image.toBitmap(captureMetrics.width, captureMetrics.height)
            image.close()
            if (bitmap == null) {
                handleFailure(flowId = flowId, detail = "capture_bitmap_unavailable")
                return@setOnImageAvailableListener
            }
            AdaptiveGuideScreenAnalyzer.analyzeBitmap(
                flow = flow,
                currentStateId = currentStateId,
                bitmap = bitmap,
                onComplete = { result ->
                    bitmap.recycle()
                    analysisCompleted = true
                    AdaptiveGuideAnalysisStore.put(result)
                    AdaptiveGuideAuditLog.append(
                        context = this,
                        event = "adaptive_guide_analysis_result",
                        detail = buildString {
                            append("flow_id=").append(flowId)
                            append(" requested_state_id=").append(currentStateId)
                            append(" confidence=").append(result.confidence.name.lowercase())
                            append(" primary_anchor=").append(result.primaryCandidate?.anchorId ?: "none")
                            append(" primary_state=").append(result.primaryCandidate?.stateId ?: "none")
                            append(" recognized_text_length=").append(result.recognizedTextLength)
                        }
                    )
                    AdaptiveGuideAnalysisBroadcasts.send(
                        context = this,
                        flowId = flowId,
                        status = AdaptiveGuideAnalysisBroadcasts.STATUS_RESULT
                    )
                    stopSelf()
                },
                onFailure = { error ->
                    bitmap.recycle()
                    handleFailure(flowId = flowId, detail = "ocr_failed:${error.javaClass.simpleName}")
                }
            )
        }, mainHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "AdaptiveGuideCapture",
            captureMetrics.width,
            captureMetrics.height,
            captureMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )
        serviceScope.launch {
            delay(CAPTURE_TIMEOUT_MS)
            if (!analysisCompleted) {
                handleFailure(flowId = flowId, detail = "capture_timeout")
            }
        }
    }

    private fun handleFailure(flowId: String, detail: String) {
        if (analysisCompleted) {
            return
        }
        analysisCompleted = true
        if (flowId.isNotBlank()) {
            AdaptiveGuideAnalysisStore.clear(flowId)
            AdaptiveGuideAuditLog.append(
                context = this,
                event = "adaptive_guide_analysis_failed",
                detail = "flow_id=$flowId detail=$detail"
            )
            AdaptiveGuideAnalysisBroadcasts.send(
                context = this,
                flowId = flowId,
                status = AdaptiveGuideAnalysisBroadcasts.STATUS_FAILED,
                detail = detail
            )
        }
        stopSelf()
    }

    private fun startCaptureForeground() {
        val notification = buildCaptureNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                WatchdogConfig.ADAPTIVE_GUIDE_CAPTURE_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(WatchdogConfig.ADAPTIVE_GUIDE_CAPTURE_NOTIFICATION_ID, notification)
        }
    }

    private fun buildCaptureNotification(): Notification {
        return NotificationCompat.Builder(this, WatchdogConfig.ADAPTIVE_GUIDE_CAPTURE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(getString(R.string.adaptive_guide_capture_notification_title))
            .setContentText(getString(R.string.adaptive_guide_capture_notification_body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createCaptureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            WatchdogConfig.ADAPTIVE_GUIDE_CAPTURE_CHANNEL_ID,
            getString(R.string.notification_channel_adaptive_guide_capture),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun resolveCaptureMetrics(): CaptureMetrics {
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            CaptureMetrics(
                width = bounds.width().coerceAtLeast(1),
                height = bounds.height().coerceAtLeast(1),
                densityDpi = resources.configuration.densityDpi
            )
        } else {
            val metrics = resources.displayMetrics
            CaptureMetrics(
                width = metrics.widthPixels.coerceAtLeast(1),
                height = metrics.heightPixels.coerceAtLeast(1),
                densityDpi = metrics.densityDpi
            )
        }
    }

    private fun releaseCaptureResources() {
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        mediaProjectionCallback?.let { callback ->
            runCatching { mediaProjection?.unregisterCallback(callback) }
        }
        mediaProjectionCallback = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    private fun Intent.readIntentExtra(name: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    private fun Image.toBitmap(width: Int, height: Int): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - (pixelStride * width)
        val fullBitmap = Bitmap.createBitmap(
            width + (rowPadding / pixelStride),
            height,
            Bitmap.Config.ARGB_8888
        )
        fullBitmap.copyPixelsFromBuffer(buffer)
        val croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
        fullBitmap.recycle()
        return croppedBitmap
    }

    private data class CaptureMetrics(
        val width: Int,
        val height: Int,
        val densityDpi: Int
    )

    private companion object {
        private const val CAPTURE_SETTLE_DELAY_MS = 400L
        private const val CAPTURE_TIMEOUT_MS = 5000L
    }
}
