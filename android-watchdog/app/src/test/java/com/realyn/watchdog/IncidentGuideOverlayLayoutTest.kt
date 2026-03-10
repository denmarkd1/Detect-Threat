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

    @Test
    fun expandedAdaptiveStateRestoresAnchorControlsWhenAvailable() {
        val state = IncidentGuideOverlayLayout.adaptiveLayoutState(
            isCollapsed = false,
            hasAnchors = true
        )

        assertEquals(R.string.incident_overlay_hide, state.toggleLabelRes)
        assertEquals(View.VISIBLE, state.focusLabelVisibility)
        assertEquals(View.VISIBLE, state.matchLabelVisibility)
        assertEquals(View.VISIBLE, state.hintVisibility)
        assertEquals(View.VISIBLE, state.anchorButtonsVisibility)
        assertEquals(View.VISIBLE, state.controlsVisibility)
        assertEquals(4, state.targetMaxLines)
        assertEquals(14, state.targetVerticalPaddingDp)
    }

    @Test
    fun collapsedAdaptiveStateHidesManualControlsButKeepsTargetReadable() {
        val state = IncidentGuideOverlayLayout.adaptiveLayoutState(
            isCollapsed = true,
            hasAnchors = true
        )

        assertEquals(R.string.incident_overlay_show, state.toggleLabelRes)
        assertEquals(View.GONE, state.focusLabelVisibility)
        assertEquals(View.GONE, state.matchLabelVisibility)
        assertEquals(View.GONE, state.hintVisibility)
        assertEquals(View.GONE, state.anchorButtonsVisibility)
        assertEquals(View.GONE, state.controlsVisibility)
        assertEquals(2, state.targetMaxLines)
        assertEquals(8, state.targetVerticalPaddingDp)
    }
}
