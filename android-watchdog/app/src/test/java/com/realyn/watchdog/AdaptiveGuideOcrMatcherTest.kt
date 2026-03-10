package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveGuideOcrMatcherTest {

    private val flow = AdaptiveGuideFlow(
        id = "foundation_autofill_miui",
        title = "Set up autofill first",
        totalSteps = 4,
        initialStateId = "search",
        states = linkedMapOf(
            "search" to AdaptiveGuideState(
                id = "search",
                stepNumber = 1,
                currentTarget = "Search for the autofill route.",
                assistantHint = "Find the next settings page.",
                complete = false,
                anchors = listOf(
                    AdaptiveGuideAnchorOption(
                        id = "preferences",
                        label = "Preferences",
                        nextStateId = "prefs",
                        matchAny = listOf("Preferences")
                    ),
                    AdaptiveGuideAnchorOption(
                        id = "google_password_manager",
                        label = "Google Password Manager",
                        nextStateId = "gpm",
                        matchAny = listOf("Google Password Manager")
                    ),
                    AdaptiveGuideAnchorOption(
                        id = "password_manager",
                        label = "Password Manager",
                        nextStateId = "pm",
                        matchAny = listOf("Password Manager")
                    )
                )
            ),
            "prefs" to AdaptiveGuideState(
                id = "prefs",
                stepNumber = 2,
                currentTarget = "Open Preferences.",
                assistantHint = "Continue.",
                complete = false,
                anchors = emptyList()
            ),
            "gpm" to AdaptiveGuideState(
                id = "gpm",
                stepNumber = 2,
                currentTarget = "Open Google Password Manager.",
                assistantHint = "Continue.",
                complete = false,
                anchors = emptyList()
            ),
            "pm" to AdaptiveGuideState(
                id = "pm",
                stepNumber = 2,
                currentTarget = "Open Password Manager.",
                assistantHint = "Continue.",
                complete = false,
                anchors = emptyList()
            )
        )
    )

    @Test
    fun `exact phrase match returns exact confidence`() {
        val screenContext = AdaptiveGuideOcrMatcher.buildScreenContext(
            "Settings > Preferences"
        )

        val result = AdaptiveGuideOcrMatcher.match(
            flow = flow,
            currentStateId = "search",
            screenContext = screenContext
        )

        assertEquals(AdaptiveGuideAnalysisConfidence.EXACT, result.confidence)
        assertEquals("preferences", result.primaryCandidate?.anchorId)
        assertEquals("Preferences", result.summaryLabel)
    }

    @Test
    fun `token-only overlap returns ambiguous candidates`() {
        val screenContext = AdaptiveGuideOcrMatcher.buildScreenContext(
            "Google manager password"
        )

        val result = AdaptiveGuideOcrMatcher.match(
            flow = flow,
            currentStateId = "search",
            screenContext = screenContext
        )

        assertEquals(AdaptiveGuideAnalysisConfidence.AMBIGUOUS, result.confidence)
        assertNotNull(result.primaryCandidate)
        assertTrue(result.alternateCandidates.isNotEmpty())
        assertTrue(result.summaryLabel.contains("Password Manager"))
    }

    @Test
    fun `unknown screen returns no reliable match`() {
        val screenContext = AdaptiveGuideOcrMatcher.buildScreenContext(
            "Privacy and safety"
        )

        val result = AdaptiveGuideOcrMatcher.match(
            flow = flow,
            currentStateId = "search",
            screenContext = screenContext
        )

        assertEquals(AdaptiveGuideAnalysisConfidence.NONE, result.confidence)
        assertEquals(null, result.primaryCandidate)
        assertTrue(result.alternateCandidates.isEmpty())
    }
}
