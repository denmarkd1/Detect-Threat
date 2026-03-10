package com.realyn.watchdog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class AdaptiveGuideEngineTest {

    private val samplePayload = """
        {
          "version": 1,
          "disclosure": "test",
          "flows": [
            {
              "id": "foundation_autofill_miui",
              "title": "Set up autofill first",
              "total_steps": 5,
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
                  "complete": false,
                  "anchors": []
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parser loads adaptive flow definitions`() {
        val pack = AdaptiveGuideRulePackParser.parse(samplePayload)

        assertEquals(1, pack.version)
        assertNotNull(pack.flows["foundation_autofill_miui"])
        assertEquals("Set up autofill first", pack.flows["foundation_autofill_miui"]?.title)
    }

    @Test
    fun `engine transitions deterministically from anchor selection`() {
        val pack = AdaptiveGuideRulePackParser.parse(samplePayload)
        val start = AdaptiveGuideEngine.start(pack, "foundation_autofill_miui")
        val next = AdaptiveGuideEngine.transition(
            pack = pack,
            flowId = "foundation_autofill_miui",
            stateId = start!!.stateId,
            anchorId = "preferences"
        )

        assertEquals("search", start.stateId)
        assertEquals("prefs", next?.stateId)
        assertEquals("Open Preferences.", next?.currentTarget)
    }

    @Test
    fun `validator accepts shipped adaptive guide rule pack`() {
        val payload = sequenceOf(
            File("src/main/assets/android_guide_rules.json"),
            File("../src/main/assets/android_guide_rules.json"),
            File("../config/android_guide_rules.json")
        ).firstOrNull { it.exists() }?.readText()
            ?: error("Unable to locate the shipped adaptive guide rule pack")

        val pack = AdaptiveGuideRulePackParser.parse(payload)
        val issues = AdaptiveGuideRulePackValidator.validate(pack)

        assertTrue("Expected shipped guide pack to validate cleanly: $issues", issues.isEmpty())
    }
}
