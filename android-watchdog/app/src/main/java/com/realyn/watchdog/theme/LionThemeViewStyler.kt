package com.realyn.watchdog.theme

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import kotlin.math.max
import kotlin.math.roundToInt

object LionThemeViewStyler {

    fun applyMaterialButtonPalette(
        root: View,
        palette: LionThemePalette,
        accentStyle: LionIdentityAccentStyle = LionIdentityAccentStyle()
    ) {
        when (root) {
            is MaterialButton -> styleMaterialButton(root, palette, accentStyle)
            is ViewGroup -> {
                for (index in 0 until root.childCount) {
                    applyMaterialButtonPalette(root.getChildAt(index), palette, accentStyle)
                }
            }
        }
    }

    private fun styleMaterialButton(
        button: MaterialButton,
        palette: LionThemePalette,
        accentStyle: LionIdentityAccentStyle
    ) {
        button.backgroundTintList = ColorStateList.valueOf(palette.panelAlt)
        button.strokeColor = ColorStateList.valueOf(
            ColorUtils.blendARGB(
                palette.stroke,
                palette.accent,
                accentStyle.buttonStrokeAccentBlend.coerceIn(0f, 1f)
            )
        )
        val density = button.resources.displayMetrics.density
        val defaultCornerPx = max((12f * density).roundToInt(), 1)
        val baseCornerPx = if (button.cornerRadius > 0) {
            button.cornerRadius
        } else {
            defaultCornerPx
        }
        button.cornerRadius = max((baseCornerPx * accentStyle.cornerScale).roundToInt(), 1)
        button.strokeWidth = max((accentStyle.strokeWidthDp * density).roundToInt(), 1)
        button.setTextColor(palette.accent)
        button.iconTint = ColorStateList.valueOf(palette.accent)
    }
}
