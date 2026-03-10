package com.realyn.watchdog

import android.view.View

internal data class IncidentGuideCompactLayoutState(
    val toggleLabelRes: Int,
    val focusLabelVisibility: Int,
    val controlsVisibility: Int,
    val targetMaxLines: Int,
    val targetVerticalPaddingDp: Int
)

internal object IncidentGuideOverlayLayout {

    fun compactLayoutState(isCollapsed: Boolean): IncidentGuideCompactLayoutState {
        return if (isCollapsed) {
            IncidentGuideCompactLayoutState(
                toggleLabelRes = R.string.incident_overlay_show,
                focusLabelVisibility = View.GONE,
                controlsVisibility = View.GONE,
                targetMaxLines = 2,
                targetVerticalPaddingDp = 8
            )
        } else {
            IncidentGuideCompactLayoutState(
                toggleLabelRes = R.string.incident_overlay_hide,
                focusLabelVisibility = View.VISIBLE,
                controlsVisibility = View.VISIBLE,
                targetMaxLines = 4,
                targetVerticalPaddingDp = 14
            )
        }
    }
}
