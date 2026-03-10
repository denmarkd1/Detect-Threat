package com.realyn.watchdog

data class AdaptiveGuideScreenContext(
    val capturedAtEpochMs: Long,
    val recognizedTextLength: Int,
    val normalizedText: String,
    val normalizedTokens: Set<String>
)

enum class AdaptiveGuideAnalysisConfidence {
    EXACT,
    AMBIGUOUS,
    NONE
}

data class AdaptiveGuideAnalysisCandidate(
    val stateId: String,
    val stateStepNumber: Int,
    val stateTarget: String,
    val anchorId: String,
    val anchorLabel: String,
    val nextStateId: String,
    val nextStepNumber: Int,
    val nextTarget: String,
    val score: Int,
    val exactPhraseMatched: Boolean,
    val matchedPhrases: List<String>
)

data class AdaptiveGuideAnalysisResult(
    val flowId: String,
    val capturedAtEpochMs: Long,
    val recognizedTextLength: Int,
    val confidence: AdaptiveGuideAnalysisConfidence,
    val summaryLabel: String,
    val primaryCandidate: AdaptiveGuideAnalysisCandidate?,
    val alternateCandidates: List<AdaptiveGuideAnalysisCandidate>
)

object AdaptiveGuideAnalysisStore {
    private val resultsByFlowId = linkedMapOf<String, AdaptiveGuideAnalysisResult>()

    @Synchronized
    fun put(result: AdaptiveGuideAnalysisResult) {
        resultsByFlowId[result.flowId] = result
    }

    @Synchronized
    fun get(flowId: String): AdaptiveGuideAnalysisResult? {
        return resultsByFlowId[flowId]
    }

    @Synchronized
    fun clear(flowId: String) {
        resultsByFlowId.remove(flowId)
    }
}

object AdaptiveGuideAnalysisBroadcasts {
    const val STATUS_RESULT = "result"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"

    fun send(
        context: android.content.Context,
        flowId: String,
        status: String,
        detail: String = ""
    ) {
        context.sendBroadcast(
            android.content.Intent(WatchdogConfig.ACTION_ADAPTIVE_GUIDE_ANALYSIS_EVENT).apply {
                `package` = context.packageName
                putExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_ANALYSIS_FLOW_ID, flowId)
                putExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_ANALYSIS_STATUS, status)
                putExtra(WatchdogConfig.EXTRA_ADAPTIVE_GUIDE_ANALYSIS_DETAIL, detail)
            }
        )
    }
}
