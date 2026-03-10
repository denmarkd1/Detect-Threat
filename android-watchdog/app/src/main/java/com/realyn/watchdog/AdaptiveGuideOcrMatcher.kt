package com.realyn.watchdog

import java.util.Locale
import kotlin.math.roundToInt

object AdaptiveGuideOcrMatcher {
    private val nonAlphaNumericRegex = Regex("[^a-z0-9]+")

    fun buildScreenContext(
        recognizedText: String,
        capturedAtEpochMs: Long = System.currentTimeMillis()
    ): AdaptiveGuideScreenContext {
        val normalizedText = normalize(recognizedText)
        val normalizedTokens = normalizedText
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        return AdaptiveGuideScreenContext(
            capturedAtEpochMs = capturedAtEpochMs,
            recognizedTextLength = recognizedText.length,
            normalizedText = normalizedText,
            normalizedTokens = normalizedTokens
        )
    }

    fun match(
        flow: AdaptiveGuideFlow,
        currentStateId: String,
        screenContext: AdaptiveGuideScreenContext
    ): AdaptiveGuideAnalysisResult {
        val candidates = flow.states.values
            .flatMap { state ->
                state.anchors.mapNotNull { anchor ->
                    scoreAnchor(
                        flow = flow,
                        state = state,
                        anchor = anchor,
                        currentStateId = currentStateId,
                        screenContext = screenContext
                    )
                }
            }
            .sortedWith(
                compareByDescending<AdaptiveGuideAnalysisCandidate> { it.score }
                    .thenByDescending { it.exactPhraseMatched }
                    .thenBy { it.stateStepNumber }
                    .thenBy { it.anchorLabel }
            )

        val topCandidate = candidates.firstOrNull()
        val secondCandidate = candidates.getOrNull(1)
        val confidence = when {
            topCandidate == null -> AdaptiveGuideAnalysisConfidence.NONE
            topCandidate.exactPhraseMatched &&
                (secondCandidate == null || topCandidate.score - secondCandidate.score >= 35) ->
                AdaptiveGuideAnalysisConfidence.EXACT
            topCandidate.score >= 170 && secondCandidate == null -> AdaptiveGuideAnalysisConfidence.EXACT
            topCandidate.score >= 90 -> AdaptiveGuideAnalysisConfidence.AMBIGUOUS
            else -> AdaptiveGuideAnalysisConfidence.NONE
        }
        val summaryLabel = when (confidence) {
            AdaptiveGuideAnalysisConfidence.EXACT -> topCandidate?.anchorLabel.orEmpty()
            AdaptiveGuideAnalysisConfidence.AMBIGUOUS -> {
                candidates.take(3).joinToString(", ") { it.anchorLabel }
            }
            AdaptiveGuideAnalysisConfidence.NONE -> ""
        }
        return AdaptiveGuideAnalysisResult(
            flowId = flow.id,
            capturedAtEpochMs = screenContext.capturedAtEpochMs,
            recognizedTextLength = screenContext.recognizedTextLength,
            confidence = confidence,
            summaryLabel = summaryLabel,
            primaryCandidate = topCandidate?.takeIf { confidence != AdaptiveGuideAnalysisConfidence.NONE },
            alternateCandidates = when (confidence) {
                AdaptiveGuideAnalysisConfidence.EXACT -> emptyList()
                AdaptiveGuideAnalysisConfidence.AMBIGUOUS -> candidates.drop(1).take(2)
                AdaptiveGuideAnalysisConfidence.NONE -> emptyList()
            }
        )
    }

    private fun scoreAnchor(
        flow: AdaptiveGuideFlow,
        state: AdaptiveGuideState,
        anchor: AdaptiveGuideAnchorOption,
        currentStateId: String,
        screenContext: AdaptiveGuideScreenContext
    ): AdaptiveGuideAnalysisCandidate? {
        if (anchor.matchAny.isEmpty()) {
            return null
        }
        var bestScore = 0
        var exactPhraseMatched = false
        val matchedPhrases = mutableListOf<String>()
        anchor.matchAny.forEach { phrase ->
            val normalizedPhrase = normalize(phrase)
            if (normalizedPhrase.isBlank()) {
                return@forEach
            }
            val phraseTokens = normalizedPhrase.split(" ").filter { it.isNotBlank() }.distinct()
            if (phraseTokens.isEmpty()) {
                return@forEach
            }
            val directPhraseMatch = screenContext.normalizedText.contains(normalizedPhrase)
            val matchedTokenCount = phraseTokens.count { token ->
                screenContext.normalizedTokens.contains(token)
            }
            val tokenCoverage = matchedTokenCount.toDouble() / phraseTokens.size.toDouble()
            val phraseScore = when {
                directPhraseMatch -> 180 + (phraseTokens.size * 12)
                tokenCoverage >= 1.0 -> 140 + (phraseTokens.size * 8)
                tokenCoverage >= 0.6 -> 70 + (tokenCoverage * 40).roundToInt()
                else -> 0
            }
            if (phraseScore > 0) {
                matchedPhrases += phrase
            }
            if (phraseScore > bestScore) {
                bestScore = phraseScore
                exactPhraseMatched = directPhraseMatch
            }
        }
        if (bestScore <= 0) {
            return null
        }
        val currentStateBonus = if (state.id == currentStateId) 25 else 0
        val nextState = flow.states[anchor.nextStateId] ?: return null
        return AdaptiveGuideAnalysisCandidate(
            stateId = state.id,
            stateStepNumber = state.stepNumber,
            stateTarget = state.currentTarget,
            anchorId = anchor.id,
            anchorLabel = anchor.label,
            nextStateId = nextState.id,
            nextStepNumber = nextState.stepNumber,
            nextTarget = nextState.currentTarget,
            score = bestScore + currentStateBonus,
            exactPhraseMatched = exactPhraseMatched,
            matchedPhrases = matchedPhrases.distinct()
        )
    }

    private fun normalize(value: String): String {
        return value
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace(nonAlphaNumericRegex, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
