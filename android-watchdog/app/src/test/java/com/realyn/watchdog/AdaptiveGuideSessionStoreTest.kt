package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveGuideSessionStoreTest {

    private val samplePayload = """
        {
          "version": 1,
          "disclosure": "test",
          "flows": [
            {
              "id": "foundation_autofill_miui",
              "title": "Set up autofill first",
              "total_steps": 3,
              "initial_state_id": "search",
              "states": [
                {
                  "id": "search",
                  "step_number": 1,
                  "current_target": "Search for Password Manager or Autofill service.",
                  "assistant_hint": "Tap the matching anchor.",
                  "anchors": [
                    {
                      "id": "preferences",
                      "label": "Preferences",
                      "next_state_id": "prefs",
                      "match_any": ["Preferences"]
                    }
                  ]
                },
                {
                  "id": "prefs",
                  "step_number": 2,
                  "current_target": "Open Preferences.",
                  "assistant_hint": "Keep going.",
                  "anchors": [
                    {
                      "id": "google_password_manager",
                      "label": "Google Password Manager",
                      "next_state_id": "done",
                      "match_any": ["Google Password Manager"]
                    }
                  ]
                },
                {
                  "id": "done",
                  "step_number": 3,
                  "current_target": "Return to DT Guardian and tap Recheck now.",
                  "assistant_hint": "Return when complete.",
                  "complete": true,
                  "anchors": []
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `encode decode roundtrip preserves structured session metadata`() {
        val record = AdaptiveGuideSessionRecord(
            flowId = "foundation_autofill_miui",
            currentStateId = "prefs",
            lastConfirmedStateId = "search",
            lastAppliedAnchorId = "preferences",
            summaryLabel = "Preferences",
            confidence = AdaptiveGuideAnalysisConfidence.EXACT,
            source = AdaptiveGuideSessionSource.MANUAL,
            updatedAtEpochMs = 1_234L
        )

        val encoded = AdaptiveGuideSessionStore.encodeRecord(record)
        val decoded = AdaptiveGuideSessionStore.decodeRecord(encoded)

        assertEquals(record, decoded)
    }

    @Test
    fun `resolve restores current and confirmed states from persisted metadata`() {
        val pack = AdaptiveGuideRulePackParser.parse(samplePayload)
        val record = AdaptiveGuideSessionRecord(
            flowId = "foundation_autofill_miui",
            currentStateId = "prefs",
            lastConfirmedStateId = "search",
            lastAppliedAnchorId = "preferences",
            summaryLabel = "Preferences",
            confidence = AdaptiveGuideAnalysisConfidence.EXACT,
            source = AdaptiveGuideSessionSource.MANUAL,
            updatedAtEpochMs = 10_000L
        )

        val snapshot = AdaptiveGuideSessionStore.resolveRecord(
            pack = pack,
            expectedFlowId = "foundation_autofill_miui",
            record = record,
            nowEpochMs = 10_500L
        )

        assertNotNull(snapshot)
        assertEquals("prefs", snapshot?.currentState?.stateId)
        assertEquals("search", snapshot?.lastConfirmedState?.stateId)
        assertEquals("preferences", snapshot?.lastAppliedAnchor?.id)
        assertEquals("Preferences", snapshot?.record?.summaryLabel)
    }

    @Test
    fun `resolve rejects stale or mismatched sessions`() {
        val pack = AdaptiveGuideRulePackParser.parse(samplePayload)
        val staleRecord = AdaptiveGuideSessionRecord(
            flowId = "foundation_autofill_miui",
            currentStateId = "prefs",
            lastConfirmedStateId = "search",
            lastAppliedAnchorId = "preferences",
            summaryLabel = "Preferences",
            confidence = AdaptiveGuideAnalysisConfidence.EXACT,
            source = AdaptiveGuideSessionSource.MANUAL,
            updatedAtEpochMs = 1_000L
        )

        val staleSnapshot = AdaptiveGuideSessionStore.resolveRecord(
            pack = pack,
            expectedFlowId = "foundation_autofill_miui",
            record = staleRecord,
            nowEpochMs = 1_000L + (31L * 60L * 1000L)
        )
        val wrongFlowSnapshot = AdaptiveGuideSessionStore.resolveRecord(
            pack = pack,
            expectedFlowId = "foundation_passkey_miui",
            record = staleRecord.copy(updatedAtEpochMs = 5_000L),
            nowEpochMs = 5_200L
        )

        assertNull(staleSnapshot)
        assertNull(wrongFlowSnapshot)
    }

    @Test
    fun `clear values removes the stored session keys`() {
        val stored = mutableMapOf<String, Any?>()
        stored.putAll(
            AdaptiveGuideSessionStore.encodeRecord(
                AdaptiveGuideSessionRecord(
                    flowId = "foundation_autofill_miui",
                    currentStateId = "prefs",
                    lastConfirmedStateId = "search",
                    lastAppliedAnchorId = "preferences",
                    summaryLabel = "Preferences",
                    confidence = AdaptiveGuideAnalysisConfidence.EXACT,
                    source = AdaptiveGuideSessionSource.MANUAL,
                    updatedAtEpochMs = 1_234L
                )
            )
        )

        AdaptiveGuideSessionStore.clearValues(stored)

        assertTrue(stored.keys.intersect(AdaptiveGuideSessionStore.storageKeys).isEmpty())
        assertNull(AdaptiveGuideSessionStore.decodeRecord(stored))
    }
}
