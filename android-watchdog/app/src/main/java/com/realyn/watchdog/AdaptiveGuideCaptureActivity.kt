package com.realyn.watchdog

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class AdaptiveGuideCaptureActivity : AppCompatActivity() {
    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val flowId = intent.getStringExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_ANALYSIS_FLOW_ID)
                .orEmpty()
                .trim()
            val currentStateId = intent.getStringExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_ANALYSIS_STATE_ID)
                .orEmpty()
                .trim()
            if (flowId.isBlank()) {
                finish()
                return@registerForActivityResult
            }
            if (result.resultCode == RESULT_OK && result.data != null) {
                AdaptiveGuideAnalysisStore.clear(flowId)
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, AdaptiveGuideCaptureService::class.java).apply {
                        putExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_ANALYSIS_FLOW_ID, flowId)
                        putExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_ANALYSIS_STATE_ID, currentStateId)
                        putExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_CAPTURE_RESULT_CODE, result.resultCode)
                        putExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_CAPTURE_DATA_INTENT, result.data)
                    }
                )
            } else {
                AdaptiveGuideAnalysisBroadcasts.send(
                    context = this,
                    flowId = flowId,
                    status = AdaptiveGuideAnalysisBroadcasts.STATUS_CANCELLED
                )
            }
            finish()
            overridePendingTransition(0, 0)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}
