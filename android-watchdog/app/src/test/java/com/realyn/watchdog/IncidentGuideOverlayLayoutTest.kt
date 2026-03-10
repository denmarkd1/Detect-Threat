package com.realyn.watchdog

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test

class IncidentGuideOverlayLayoutTest {

    @Test
    fun expandedCompactStateKeepsControlsVisible() {
        val state = IncidentGuideOverlayLayout.compactLayoutState(isCollapsed = false)

        assertEquals(R.string.incident_overlay_hide, state.toggleLabelRes)
        assertEquals(View.VISIBLE, state.focusLabelVisibility)
        assertEquals(View.VISIBLE, state.controlsVisibility)
        assertEquals(4, state.targetMaxLines)
        assertEquals(14, state.targetVerticalPaddingDp)
    }

    @Test
    fun collapsedCompactStateShrinksControlsButKeepsTargetVisible() {
        val state = IncidentGuideOverlayLayout.compactLayoutState(isCollapsed = true)

        assertEquals(R.string.incident_overlay_show, state.toggleLabelRes)
        assertEquals(View.GONE, state.focusLabelVisibility)
        assertEquals(View.GONE, state.controlsVisibility)
        assertEquals(2, state.targetMaxLines)
        assertEquals(8, state.targetVerticalPaddingDp)
    }
}
