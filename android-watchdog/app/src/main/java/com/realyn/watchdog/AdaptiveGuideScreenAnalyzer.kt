package com.realyn.watchdog

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object AdaptiveGuideScreenAnalyzer {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun analyzeBitmap(
        flow: AdaptiveGuideFlow,
        currentStateId: String,
        bitmap: Bitmap,
        onComplete: (AdaptiveGuideAnalysisResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { recognizedText ->
                val screenContext = AdaptiveGuideOcrMatcher.buildScreenContext(recognizedText.text)
                onComplete(
                    AdaptiveGuideOcrMatcher.match(
                        flow = flow,
                        currentStateId = currentStateId,
                        screenContext = screenContext
                    )
                )
            }
            .addOnFailureListener { error ->
                onFailure(error)
            }
    }
}
