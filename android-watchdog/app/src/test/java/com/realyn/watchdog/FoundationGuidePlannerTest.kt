package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Test

class FoundationGuidePlannerTest {

    @Test
    fun `miui autofill plan uses resolvable provider path and recheck return`() {
        val plan = FoundationGuidePlanner.plan(
            target = FoundationGuideTarget.AUTOFILL,
            oemPack = AutofillPasskeyFoundation.OemPack.MIUI
        )

        assertEquals(R.string.autofill_guide_search_autofill_term_miui, plan.searchTermRes)
        assertEquals(
            listOf(
                R.string.autofill_guide_autofill_path_miui,
                R.string.autofill_guide_autofill_path_miui_google_fallback,
                R.string.autofill_guide_step_select_provider_miui,
                R.string.autofill_guide_step_return
            ),
            plan.stepResIds
        )
    }

    @Test
    fun `generic autofill plan keeps direct path and recheck return`() {
        val plan = FoundationGuidePlanner.plan(
            target = FoundationGuideTarget.AUTOFILL,
            oemPack = AutofillPasskeyFoundation.OemPack.GENERIC
        )

        assertEquals(R.string.autofill_guide_search_autofill_term, plan.searchTermRes)
        assertEquals(
            listOf(
                R.string.autofill_guide_autofill_path_generic,
                R.string.autofill_guide_step_select_provider,
                R.string.autofill_guide_step_return
            ),
            plan.stepResIds
        )
    }
}
