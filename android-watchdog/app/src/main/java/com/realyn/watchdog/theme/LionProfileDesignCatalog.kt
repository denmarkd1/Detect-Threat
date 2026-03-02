package com.realyn.watchdog.theme

import androidx.annotation.ColorInt
import com.realyn.watchdog.LionThemePrefs

data class LionIdentityAccentStyle(
    val cornerScale: Float = 1f,
    val strokeWidthDp: Float = 1f,
    val buttonStrokeAccentBlend: Float = 0f,
    val navButtonCornerDp: Float = 12f,
    val navShellCornerScale: Float = 1f,
    val navButtonFillLift: Float = 0.06f,
    val navButtonStrokeAccentBlend: Float = 0.24f,
    val navButtonStrokeAlpha: Int = 176,
    val lionRingStrokeAccentBlend: Float = 0.30f,
    val lionRingStrokeAlpha: Int = 208,
    val lionRingFillAlphaDark: Int = 26,
    val lionRingFillAlphaLight: Int = 52
)

data class LionProfilePaletteInfluence(
    @ColorInt val darkBiasColor: Int,
    @ColorInt val lightBiasColor: Int,
    val accentBlend: Float,
    val strokeBlend: Float,
    val panelBlend: Float,
    val textBlend: Float,
    val navBlend: Float
)

data class LionProfileDesignSpec(
    val paletteInfluence: LionProfilePaletteInfluence? = null,
    val accentStyle: LionIdentityAccentStyle = LionIdentityAccentStyle()
)

object LionProfileDesignCatalog {
    private const val STYLE_DEFAULT = "default"
    private const val STYLE_STEAMPUNK_MALE = "steampunk_male"
    private const val STYLE_STEAMPUNK_LIONESS = "steampunk_lioness"

    private val defaultSpec = LionProfileDesignSpec()

    private val steampunkMaleSpec = LionProfileDesignSpec(
        paletteInfluence = LionProfilePaletteInfluence(
            darkBiasColor = 0xFFC58A44.toInt(),
            lightBiasColor = 0xFFAA6A36.toInt(),
            accentBlend = 0.16f,
            strokeBlend = 0.30f,
            panelBlend = 0.08f,
            textBlend = 0.06f,
            navBlend = 0.10f
        ),
        accentStyle = LionIdentityAccentStyle(
            cornerScale = 0.93f,
            strokeWidthDp = 1.15f,
            buttonStrokeAccentBlend = 0.34f,
            navButtonCornerDp = 10.5f,
            navShellCornerScale = 0.94f,
            navButtonFillLift = 0.04f,
            navButtonStrokeAccentBlend = 0.42f,
            navButtonStrokeAlpha = 196,
            lionRingStrokeAccentBlend = 0.46f,
            lionRingStrokeAlpha = 224,
            lionRingFillAlphaDark = 34,
            lionRingFillAlphaLight = 62
        )
    )

    private val steampunkLionessSpec = LionProfileDesignSpec(
        paletteInfluence = LionProfilePaletteInfluence(
            darkBiasColor = 0xFFD39B62.toInt(),
            lightBiasColor = 0xFFB97848.toInt(),
            accentBlend = 0.18f,
            strokeBlend = 0.24f,
            panelBlend = 0.10f,
            textBlend = 0.07f,
            navBlend = 0.12f
        ),
        accentStyle = LionIdentityAccentStyle(
            cornerScale = 1.07f,
            strokeWidthDp = 1.0f,
            buttonStrokeAccentBlend = 0.22f,
            navButtonCornerDp = 13.5f,
            navShellCornerScale = 1.06f,
            navButtonFillLift = 0.09f,
            navButtonStrokeAccentBlend = 0.26f,
            navButtonStrokeAlpha = 182,
            lionRingStrokeAccentBlend = 0.28f,
            lionRingStrokeAlpha = 206,
            lionRingFillAlphaDark = 30,
            lionRingFillAlphaLight = 56
        )
    )

    fun resolve(profile: LionThemePrefs.LionIdentityProfile): LionProfileDesignSpec {
        return when (profile.styleKey.trim().lowercase()) {
            STYLE_STEAMPUNK_MALE -> steampunkMaleSpec
            STYLE_STEAMPUNK_LIONESS -> steampunkLionessSpec
            STYLE_DEFAULT -> defaultSpec
            else -> defaultSpec
        }
    }
}
