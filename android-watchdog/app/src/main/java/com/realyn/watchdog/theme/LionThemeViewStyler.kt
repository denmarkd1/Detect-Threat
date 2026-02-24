package com.realyn.watchdog.theme

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton

object LionThemeViewStyler {

    fun applyMaterialButtonPalette(root: View, palette: LionThemePalette) {
        when (root) {
            is MaterialButton -> styleMaterialButton(root, palette)
            is ViewGroup -> {
                for (index in 0 until root.childCount) {
                    applyMaterialButtonPalette(root.getChildAt(index), palette)
                }
            }
        }
    }

    private fun styleMaterialButton(button: MaterialButton, palette: LionThemePalette) {
        button.backgroundTintList = ColorStateList.valueOf(palette.panelAlt)
        button.strokeColor = ColorStateList.valueOf(palette.stroke)
        button.setTextColor(palette.accent)
        button.iconTint = ColorStateList.valueOf(palette.accent)
    }
}
