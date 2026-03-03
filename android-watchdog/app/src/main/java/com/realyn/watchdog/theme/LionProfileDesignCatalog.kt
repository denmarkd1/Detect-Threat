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
    private const val STYLE_FULL_COLOR_MALE = "full_color_male"
    private const val STYLE_FULL_COLOR_LIONESS = "full_color_lioness"
    private const val STYLE_CHILD_GOLD_DEFAULT = "child_gold_default"
    private const val STYLE_CHILD_BLUE_MALE_ALT = "child_blue_male_alt"
    private const val STYLE_CHILD_PINK_FEMALE_ALT = "child_pink_female_alt"
    private const val STYLE_CHILD_RAINBOW_FEMALE2_NONBINARY = "child_rainbow_female2_nonbinary"
    private const val STYLE_CANNABIS_TOP_HAT = "cannabis_top_hat"
    private const val STYLE_CANNABIS_PINK_CROWN = "cannabis_pink_crown"

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

    private val fullColorMaleSpec = LionProfileDesignSpec(
        paletteInfluence = LionProfilePaletteInfluence(
            darkBiasColor = 0xFFC98334.toInt(),
            lightBiasColor = 0xFF9A4924.toInt(),
            accentBlend = 0.24f,
            strokeBlend = 0.36f,
            panelBlend = 0.15f,
            textBlend = 0.12f,
            navBlend = 0.20f
        ),
        accentStyle = LionIdentityAccentStyle(
            cornerScale = 0.90f,
            strokeWidthDp = 1.28f,
            buttonStrokeAccentBlend = 0.48f,
            navButtonCornerDp = 9.8f,
            navShellCornerScale = 0.91f,
            navButtonFillLift = 0.06f,
            navButtonStrokeAccentBlend = 0.56f,
            navButtonStrokeAlpha = 210,
            lionRingStrokeAccentBlend = 0.58f,
            lionRingStrokeAlpha = 232,
            lionRingFillAlphaDark = 40,
            lionRingFillAlphaLight = 70
        )
    )

    private val fullColorLionessSpec = LionProfileDesignSpec(
        paletteInfluence = LionProfilePaletteInfluence(
            darkBiasColor = 0xFFD99A5E.toInt(),
            lightBiasColor = 0xFFB86D3E.toInt(),
            accentBlend = 0.26f,
            strokeBlend = 0.30f,
            panelBlend = 0.16f,
            textBlend = 0.13f,
            navBlend = 0.21f
        ),
        accentStyle = LionIdentityAccentStyle(
            cornerScale = 1.14f,
            strokeWidthDp = 1.12f,
            buttonStrokeAccentBlend = 0.40f,
            navButtonCornerDp = 15.2f,
            navShellCornerScale = 1.11f,
            navButtonFillLift = 0.12f,
            navButtonStrokeAccentBlend = 0.46f,
            navButtonStrokeAlpha = 204,
            lionRingStrokeAccentBlend = 0.50f,
            lionRingStrokeAlpha = 228,
            lionRingFillAlphaDark = 38,
            lionRingFillAlphaLight = 72
        )
    )

    private val childGoldDefaultSpec = LionProfileDesignSpec(
        paletteInfluence = LionProfilePaletteInfluence(
            darkBiasColor = 0xFFD9A858.toInt(),
            lightBiasColor = 0xFFBB7D34.toInt(),
            accentBlend = 0.20f,
            strokeBlend = 0.26f,
            panelBlend = 0.11f,
            textBlend = 0.08f,
            navBlend = 0.13f
        ),
        accentStyle = LionIdentityAccentStyle(
            cornerScale = 1.03f,
            strokeWidthDp = 1.04f,
            buttonStrokeAccentBlend = 0.24f,
            navButtonCornerDp = 13.2f,
            navShellCornerScale = 1.04f,
            navButtonFillLift = 0.09f,
            navButtonStrokeAccentBlend = 0.30f,
            navButtonStrokeAlpha = 186,
            lionRingStrokeAccentBlend = 0.32f,
            lionRingStrokeAlpha = 212,
            lionRingFillAlphaDark = 31,
            lionRingFillAlphaLight = 58
        )
    )

    private val childBlueMaleAltSpec = LionProfileDesignSpec(
        paletteInfluence = LionProfilePaletteInfluence(
            darkBiasColor = 0xFF3B9CFF.toInt(),
            lightBiasColor = 0xFF2C6FDF.toInt(),
            accentBlend = 0.26f,
            strokeBlend = 0.34f,
            panelBlend = 0.14f,
            textBlend = 0.11f,
            navBlend = 0.20f
        ),
        accentStyle = LionIdentityAccentStyle(
            cornerScale = 0.96f,
            strokeWidthDp = 1.18f,
            buttonStrokeAccentBlend = 0.42f,
            navButtonCornerDp = 11.3f,
            navShellCornerScale = 0.96f,
            navButtonFillLift = 0.08f,
            navButtonStrokeAccentBlend = 0.50f,
            navButtonStrokeAlpha = 202,
            lionRingStrokeAccentBlend = 0.54f,
            lionRingStrokeAlpha = 228,
            lionRingFillAlphaDark = 36,
            lionRingFillAlphaLight = 66
        )
    )

    private val childPinkFemaleAltSpec = LionProfileDesignSpec(
        paletteInfluence = LionProfilePaletteInfluence(
            darkBiasColor = 0xFFE66EA8.toInt(),
            lightBiasColor = 0xFFC54F8A.toInt(),
            accentBlend = 0.25f,
            strokeBlend = 0.31f,
            panelBlend = 0.14f,
            textBlend = 0.11f,
            navBlend = 0.19f
        ),
        accentStyle = LionIdentityAccentStyle(
            cornerScale = 1.10f,
            strokeWidthDp = 1.08f,
            buttonStrokeAccentBlend = 0.38f,
            navButtonCornerDp = 14.8f,
            navShellCornerScale = 1.09f,
            navButtonFillLift = 0.11f,
            navButtonStrokeAccentBlend = 0.44f,
            navButtonStrokeAlpha = 198,
            lionRingStrokeAccentBlend = 0.48f,
            lionRingStrokeAlpha = 224,
            lionRingFillAlphaDark = 35,
            lionRingFillAlphaLight = 64
        )
    )

    private val childRainbowFemale2NonbinarySpec = LionProfileDesignSpec(
        paletteInfluence = LionProfilePaletteInfluence(
            darkBiasColor = 0xFF6CBB4A.toInt(),
            lightBiasColor = 0xFF5B56DD.toInt(),
            accentBlend = 0.28f,
            strokeBlend = 0.36f,
            panelBlend = 0.16f,
            textBlend = 0.13f,
            navBlend = 0.22f
        ),
        accentStyle = LionIdentityAccentStyle(
            cornerScale = 1.08f,
            strokeWidthDp = 1.20f,
            buttonStrokeAccentBlend = 0.46f,
            navButtonCornerDp = 14.6f,
            navShellCornerScale = 1.07f,
            navButtonFillLift = 0.12f,
            navButtonStrokeAccentBlend = 0.54f,
            navButtonStrokeAlpha = 210,
            lionRingStrokeAccentBlend = 0.58f,
            lionRingStrokeAlpha = 232,
            lionRingFillAlphaDark = 38,
            lionRingFillAlphaLight = 70
        )
    )

    private val cannabisTopHatSpec = LionProfileDesignSpec(
        paletteInfluence = LionProfilePaletteInfluence(
            darkBiasColor = 0xFF3B8F41.toInt(),
            lightBiasColor = 0xFF276F34.toInt(),
            accentBlend = 0.20f,
            strokeBlend = 0.32f,
            panelBlend = 0.12f,
            textBlend = 0.08f,
            navBlend = 0.14f
        ),
        accentStyle = LionIdentityAccentStyle(
            cornerScale = 0.96f,
            strokeWidthDp = 1.18f,
            buttonStrokeAccentBlend = 0.36f,
            navButtonCornerDp = 10.8f,
            navShellCornerScale = 0.96f,
            navButtonFillLift = 0.05f,
            navButtonStrokeAccentBlend = 0.44f,
            navButtonStrokeAlpha = 198,
            lionRingStrokeAccentBlend = 0.48f,
            lionRingStrokeAlpha = 226,
            lionRingFillAlphaDark = 36,
            lionRingFillAlphaLight = 62
        )
    )

    private val cannabisPinkCrownSpec = LionProfileDesignSpec(
        paletteInfluence = LionProfilePaletteInfluence(
            darkBiasColor = 0xFFB95B91.toInt(),
            lightBiasColor = 0xFF9A3D73.toInt(),
            accentBlend = 0.23f,
            strokeBlend = 0.26f,
            panelBlend = 0.12f,
            textBlend = 0.09f,
            navBlend = 0.15f
        ),
        accentStyle = LionIdentityAccentStyle(
            cornerScale = 1.10f,
            strokeWidthDp = 1.02f,
            buttonStrokeAccentBlend = 0.28f,
            navButtonCornerDp = 14.0f,
            navShellCornerScale = 1.08f,
            navButtonFillLift = 0.10f,
            navButtonStrokeAccentBlend = 0.30f,
            navButtonStrokeAlpha = 188,
            lionRingStrokeAccentBlend = 0.34f,
            lionRingStrokeAlpha = 214,
            lionRingFillAlphaDark = 34,
            lionRingFillAlphaLight = 60
        )
    )

    fun resolve(profile: LionThemePrefs.LionIdentityProfile): LionProfileDesignSpec {
        val base = when (profile.styleKey.trim().lowercase()) {
            STYLE_STEAMPUNK_MALE -> steampunkMaleSpec
            STYLE_STEAMPUNK_LIONESS -> steampunkLionessSpec
            STYLE_FULL_COLOR_MALE -> fullColorMaleSpec
            STYLE_FULL_COLOR_LIONESS -> fullColorLionessSpec
            STYLE_CHILD_GOLD_DEFAULT -> childGoldDefaultSpec
            STYLE_CHILD_BLUE_MALE_ALT -> childBlueMaleAltSpec
            STYLE_CHILD_PINK_FEMALE_ALT -> childPinkFemaleAltSpec
            STYLE_CHILD_RAINBOW_FEMALE2_NONBINARY -> childRainbowFemale2NonbinarySpec
            STYLE_CANNABIS_TOP_HAT -> cannabisTopHatSpec
            STYLE_CANNABIS_PINK_CROWN -> cannabisPinkCrownSpec
            STYLE_DEFAULT -> defaultSpec
            else -> defaultSpec
        }
        return applyThemeIntensity(base, profile.themeIntensity)
    }

    private fun applyThemeIntensity(
        spec: LionProfileDesignSpec,
        rawIntensity: Float
    ): LionProfileDesignSpec {
        val intensity = rawIntensity.coerceIn(0.80f, 1.60f)
        if (kotlin.math.abs(intensity - 1f) < 0.01f) {
            return spec
        }
        val blendFactor = (1f + ((intensity - 1f) * 0.44f)).coerceIn(0.78f, 1.32f)
        val blendDelta = ((intensity - 1f) * 0.18f).coerceIn(-0.08f, 0.22f)
        val strokeScale = (1f + ((intensity - 1f) * 0.24f)).coerceIn(0.86f, 1.34f)
        val cornerShift = ((intensity - 1f) * 0.08f).coerceIn(-0.08f, 0.10f)

        val adjustedInfluence = spec.paletteInfluence?.let { influence ->
            influence.copy(
                accentBlend = scaleBlend(influence.accentBlend, blendFactor, maxValue = 0.36f),
                strokeBlend = scaleBlend(influence.strokeBlend, blendFactor, maxValue = 0.40f),
                panelBlend = scaleBlend(influence.panelBlend, blendFactor, maxValue = 0.18f),
                textBlend = scaleBlend(influence.textBlend, blendFactor, maxValue = 0.16f),
                navBlend = scaleBlend(influence.navBlend, blendFactor, maxValue = 0.22f)
            )
        }
        val accent = spec.accentStyle
        val adjustedAccent = accent.copy(
            cornerScale = (accent.cornerScale + cornerShift).coerceIn(0.88f, 1.16f),
            strokeWidthDp = (accent.strokeWidthDp * strokeScale).coerceIn(0.90f, 1.48f),
            buttonStrokeAccentBlend = (accent.buttonStrokeAccentBlend + blendDelta).coerceIn(0f, 0.58f),
            navButtonCornerDp = (accent.navButtonCornerDp + ((intensity - 1f) * 1.2f)).coerceIn(9.6f, 16f),
            navShellCornerScale = (accent.navShellCornerScale + (cornerShift * 0.72f)).coerceIn(0.90f, 1.18f),
            navButtonFillLift = (accent.navButtonFillLift + ((intensity - 1f) * 0.05f)).coerceIn(0.03f, 0.18f),
            navButtonStrokeAccentBlend = (accent.navButtonStrokeAccentBlend + (blendDelta * 0.85f)).coerceIn(0.16f, 0.60f),
            lionRingStrokeAccentBlend = (accent.lionRingStrokeAccentBlend + (blendDelta * 0.76f)).coerceIn(0.20f, 0.62f)
        )
        return spec.copy(
            paletteInfluence = adjustedInfluence,
            accentStyle = adjustedAccent
        )
    }

    private fun scaleBlend(
        value: Float,
        factor: Float,
        maxValue: Float
    ): Float {
        return (value * factor).coerceIn(0f, maxValue)
    }
}
