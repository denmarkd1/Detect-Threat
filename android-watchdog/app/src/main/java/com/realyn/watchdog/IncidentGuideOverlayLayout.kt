package com.realyn.watchdog

import android.view.View

internal data class IncidentGuideCompactLayoutState(
    val toggleLabelRes: Int,
    val focusLabelVisibility: Int,
    val controlsVisibility: Int,
    val targetMaxLines: Int,
    val targetVerticalPaddingDp: Int
)

internal data class AdaptiveGuideLayoutState(
    val toggleLabelRes: Int,
    val focusLabelVisibility: Int,
    val matchLabelVisibility: Int,
    val hintVisibility: Int,
    val anchorButtonsVisibility: Int,
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

    fun adaptiveLayoutState(
        isCollapsed: Boolean,
        hasAnchors: Boolean
    ): AdaptiveGuideLayoutState {
        return if (isCollapsed) {
            AdaptiveGuideLayoutState(
                toggleLabelRes = R.string.incident_overlay_show,
                focusLabelVisibility = View.GONE,
                matchLabelVisibility = View.GONE,
                hintVisibility = View.GONE,
                anchorButtonsVisibility = View.GONE,
                controlsVisibility = View.GONE,
                targetMaxLines = 2,
                targetVerticalPaddingDp = 8
            )
        } else {
            AdaptiveGuideLayoutState(
                toggleLabelRes = R.string.incident_overlay_hide,
                focusLabelVisibility = View.VISIBLE,
                matchLabelVisibility = if (hasAnchors) View.VISIBLE else View.GONE,
                hintVisibility = View.VISIBLE,
                anchorButtonsVisibility = if (hasAnchors) View.VISIBLE else View.GONE,
                controlsVisibility = View.VISIBLE,
                targetMaxLines = 4,
                targetVerticalPaddingDp = 14
            )
        }
    }
}
