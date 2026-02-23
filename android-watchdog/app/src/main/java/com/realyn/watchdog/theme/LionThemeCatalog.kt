package com.realyn.watchdog.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.realyn.watchdog.LionThemePrefs
import com.realyn.watchdog.R

enum class LionThemeTier {
    FREE,
    PRO
}

enum class LionThemeVariant(
    val raw: String,
    val tier: LionThemeTier,
    @StringRes val labelRes: Int,
    val adaptiveFromLionAsset: Boolean = false
) {
    FREE_GUARDIAN_BRONZE(
        raw = "free_guardian_bronze",
        tier = LionThemeTier.FREE,
        labelRes = R.string.lion_theme_variant_free_guardian_bronze
    ),
    FREE_GRAPHITE_MINT(
        raw = "free_graphite_mint",
        tier = LionThemeTier.FREE,
        labelRes = R.string.lion_theme_variant_free_graphite_mint
    ),
    PRO_AURORA_CIRCUIT(
        raw = "pro_aurora_circuit",
        tier = LionThemeTier.PRO,
        labelRes = R.string.lion_theme_variant_pro_aurora_circuit
    ),
    PRO_SOLAR_FLARE(
        raw = "pro_solar_flare",
        tier = LionThemeTier.PRO,
        labelRes = R.string.lion_theme_variant_pro_solar_flare
    ),
    PRO_OBSIDIAN_PRISM(
        raw = "pro_obsidian_prism",
        tier = LionThemeTier.PRO,
        labelRes = R.string.lion_theme_variant_pro_obsidian_prism
    ),
    PRO_ASSET_NEBULA(
        raw = "pro_asset_nebula",
        tier = LionThemeTier.PRO,
        labelRes = R.string.lion_theme_variant_pro_asset_nebula,
        adaptiveFromLionAsset = true
    ),
    PRO_ASSET_HARMONIC(
        raw = "pro_asset_harmonic",
        tier = LionThemeTier.PRO,
        labelRes = R.string.lion_theme_variant_pro_asset_harmonic,
        adaptiveFromLionAsset = true
    );

    val requiresPro: Boolean
        get() = tier == LionThemeTier.PRO

    companion object {
        private val FREE_VARIANTS = listOf(
            FREE_GUARDIAN_BRONZE,
            FREE_GRAPHITE_MINT
        )

        private val PRO_VARIANTS = listOf(
            FREE_GUARDIAN_BRONZE,
            FREE_GRAPHITE_MINT,
            PRO_AURORA_CIRCUIT,
            PRO_SOLAR_FLARE,
            PRO_OBSIDIAN_PRISM,
            PRO_ASSET_NEBULA,
            PRO_ASSET_HARMONIC
        )

        fun fromRaw(raw: String?): LionThemeVariant {
            return values().firstOrNull { it.raw == raw } ?: FREE_GUARDIAN_BRONZE
        }

        fun availableFor(paidAccess: Boolean): List<LionThemeVariant> {
            return if (paidAccess) {
                PRO_VARIANTS
            } else {
                FREE_VARIANTS
            }
        }
    }
}

enum class LionThemeToneMode(
    val raw: String,
    @StringRes val labelRes: Int
) {
    SYSTEM("system", R.string.lion_theme_tone_system),
    LIGHT("light", R.string.lion_theme_tone_light),
    DARK("dark", R.string.lion_theme_tone_dark);

    fun next(): LionThemeToneMode {
        val options = values()
        return options[(ordinal + 1) % options.size]
    }

    companion object {
        fun fromRaw(raw: String?): LionThemeToneMode {
            return values().firstOrNull { it.raw == raw } ?: SYSTEM
        }
    }
}

data class LionThemePalette(
    @ColorInt val backgroundStart: Int,
    @ColorInt val backgroundCenter: Int,
    @ColorInt val backgroundEnd: Int,
    @ColorInt val navShellStart: Int,
    @ColorInt val navShellEnd: Int,
    @ColorInt val panel: Int,
    @ColorInt val panelAlt: Int,
    @ColorInt val stroke: Int,
    @ColorInt val accent: Int,
    @ColorInt val alert: Int,
    @ColorInt val textPrimary: Int,
    @ColorInt val textSecondary: Int,
    @ColorInt val textMuted: Int
)

data class LionThemeState(
    val variant: LionThemeVariant,
    val toneMode: LionThemeToneMode,
    val isDark: Boolean,
    val palette: LionThemePalette
)

object LionThemeCatalog {
    fun resolveState(
        context: Context,
        paidAccess: Boolean,
        selectedLionBitmap: Bitmap? = null
    ): LionThemeState {
        val storedVariant = LionThemePrefs.readThemeVariant(context)
        val variant = if (!paidAccess && storedVariant.requiresPro) {
            LionThemeVariant.FREE_GUARDIAN_BRONZE
        } else {
            storedVariant
        }
        val toneMode = LionThemePrefs.readToneMode(context)
        val darkMode = resolveDarkModeEnabled(context, toneMode)
        val seedAccent = LionThemePrefs.resolveAccentSeedColor(context, selectedLionBitmap)
        val darkPalette = resolveDarkPalette(variant, seedAccent)
        val palette = if (darkMode) {
            darkPalette
        } else {
            buildLightCompanionPalette(darkPalette)
        }
        return LionThemeState(
            variant = variant,
            toneMode = toneMode,
            isDark = darkMode,
            palette = palette
        )
    }

    fun nextVariant(current: LionThemeVariant, paidAccess: Boolean): LionThemeVariant {
        val options = LionThemeVariant.availableFor(paidAccess)
        if (options.isEmpty()) {
            return LionThemeVariant.FREE_GUARDIAN_BRONZE
        }
        val index = options.indexOf(current).takeIf { it >= 0 } ?: 0
        return options[(index + 1) % options.size]
    }

    private fun resolveDarkModeEnabled(
        context: Context,
        toneMode: LionThemeToneMode
    ): Boolean {
        return when (toneMode) {
            LionThemeToneMode.DARK -> true
            LionThemeToneMode.LIGHT -> false
            LionThemeToneMode.SYSTEM -> {
                val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMode == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun resolveDarkPalette(
        variant: LionThemeVariant,
        @ColorInt seedAccent: Int
    ): LionThemePalette {
        return when (variant) {
            LionThemeVariant.FREE_GUARDIAN_BRONZE -> palette(
                backgroundStart = 0xFF26180F.toInt(),
                backgroundCenter = 0xFF1A120C.toInt(),
                backgroundEnd = 0xFF110C08.toInt(),
                navShellStart = 0xF03A2718.toInt(),
                navShellEnd = 0xDB2A1C13.toInt(),
                panel = 0xFF231710.toInt(),
                panelAlt = 0xFF2C1D14.toInt(),
                stroke = 0xFF7A5B39.toInt(),
                accent = 0xFFD6A545.toInt(),
                alert = 0xFFC14830.toInt(),
                textPrimary = 0xFFF7EBD5.toInt(),
                textSecondary = 0xFFD8BE92.toInt(),
                textMuted = 0xFFA78E6A.toInt()
            )

            LionThemeVariant.FREE_GRAPHITE_MINT -> palette(
                backgroundStart = 0xFF10181A.toInt(),
                backgroundCenter = 0xFF0C1115.toInt(),
                backgroundEnd = 0xFF090D10.toInt(),
                navShellStart = 0xEE1A272A.toInt(),
                navShellEnd = 0xDA121D20.toInt(),
                panel = 0xFF131D1F.toInt(),
                panelAlt = 0xFF1B2629.toInt(),
                stroke = 0xFF446066.toInt(),
                accent = 0xFF67CCB7.toInt(),
                alert = 0xFFD45A44.toInt(),
                textPrimary = 0xFFE8F6F2.toInt(),
                textSecondary = 0xFFB8D8D1.toInt(),
                textMuted = 0xFF8EAEA7.toInt()
            )

            LionThemeVariant.PRO_AURORA_CIRCUIT -> palette(
                backgroundStart = 0xFF10162B.toInt(),
                backgroundCenter = 0xFF0C1222.toInt(),
                backgroundEnd = 0xFF080D17.toInt(),
                navShellStart = 0xED1A2646.toInt(),
                navShellEnd = 0xD9131B33.toInt(),
                panel = 0xFF131D35.toInt(),
                panelAlt = 0xFF1B2743.toInt(),
                stroke = 0xFF4466A2.toInt(),
                accent = 0xFF64B5FF.toInt(),
                alert = 0xFFD95F5C.toInt(),
                textPrimary = 0xFFEAF4FF.toInt(),
                textSecondary = 0xFFBED6F4.toInt(),
                textMuted = 0xFF90AAC8.toInt()
            )

            LionThemeVariant.PRO_SOLAR_FLARE -> palette(
                backgroundStart = 0xFF2A1212.toInt(),
                backgroundCenter = 0xFF1E0F11.toInt(),
                backgroundEnd = 0xFF150A0D.toInt(),
                navShellStart = 0xEE432119.toInt(),
                navShellEnd = 0xDA2F1613.toInt(),
                panel = 0xFF321713.toInt(),
                panelAlt = 0xFF432016.toInt(),
                stroke = 0xFF916047.toInt(),
                accent = 0xFFFF9A4D.toInt(),
                alert = 0xFFE35B43.toInt(),
                textPrimary = 0xFFFFF0E4.toInt(),
                textSecondary = 0xFFF0C7A9.toInt(),
                textMuted = 0xFFBD987F.toInt()
            )

            LionThemeVariant.PRO_OBSIDIAN_PRISM -> palette(
                backgroundStart = 0xFF1D1328.toInt(),
                backgroundCenter = 0xFF15101E.toInt(),
                backgroundEnd = 0xFF0F0A15.toInt(),
                navShellStart = 0xEE302145.toInt(),
                navShellEnd = 0xDA21152F.toInt(),
                panel = 0xFF22152F.toInt(),
                panelAlt = 0xFF2E1D3F.toInt(),
                stroke = 0xFF7A5A9D.toInt(),
                accent = 0xFFC38BFF.toInt(),
                alert = 0xFFE56467.toInt(),
                textPrimary = 0xFFF5EEFF.toInt(),
                textSecondary = 0xFFD8C6EF.toInt(),
                textMuted = 0xFFA996C2.toInt()
            )

            LionThemeVariant.PRO_ASSET_NEBULA -> buildAdaptivePalette(
                seedAccent = seedAccent,
                hueShift = 26f
            )

            LionThemeVariant.PRO_ASSET_HARMONIC -> buildAdaptivePalette(
                seedAccent = seedAccent,
                hueShift = -33f
            )
        }
    }

    private fun buildAdaptivePalette(
        @ColorInt seedAccent: Int,
        hueShift: Float
    ): LionThemePalette {
        val accent = normalizeAccent(seedAccent)
        val support = normalizeAccent(shiftHue(seedAccent, hueShift), minSaturation = 0.36f)
        val backgroundStart = blend(support, Color.BLACK, 0.82f)
        val backgroundCenter = blend(accent, Color.BLACK, 0.88f)
        val backgroundEnd = blend(accent, Color.BLACK, 0.93f)
        val panel = blend(backgroundCenter, support, 0.24f)
        val panelAlt = blend(backgroundCenter, accent, 0.35f)
        val stroke = blend(accent, Color.WHITE, 0.32f)
        val navShellStart = ColorUtils.setAlphaComponent(blend(panelAlt, accent, 0.18f), 236)
        val navShellEnd = ColorUtils.setAlphaComponent(blend(panel, backgroundEnd, 0.42f), 218)
        return palette(
            backgroundStart = backgroundStart,
            backgroundCenter = backgroundCenter,
            backgroundEnd = backgroundEnd,
            navShellStart = navShellStart,
            navShellEnd = navShellEnd,
            panel = panel,
            panelAlt = panelAlt,
            stroke = stroke,
            accent = accent,
            alert = blend(0xFFE4584E.toInt(), accent, 0.14f),
            textPrimary = blend(accent, Color.WHITE, 0.82f),
            textSecondary = blend(accent, Color.WHITE, 0.63f),
            textMuted = blend(accent, Color.WHITE, 0.43f)
        )
    }

    private fun buildLightCompanionPalette(dark: LionThemePalette): LionThemePalette {
        val accentInk = blend(dark.accent, Color.BLACK, 0.08f)
        val textPrimary = blend(accentInk, Color.BLACK, 0.78f)
        val textSecondary = blend(textPrimary, Color.WHITE, 0.30f)
        val textMuted = blend(textSecondary, Color.WHITE, 0.24f)
        return dark.copy(
            backgroundStart = blend(dark.backgroundStart, Color.WHITE, 0.70f),
            backgroundCenter = blend(dark.backgroundCenter, Color.WHITE, 0.76f),
            backgroundEnd = blend(dark.backgroundEnd, Color.WHITE, 0.84f),
            navShellStart = ColorUtils.setAlphaComponent(blend(dark.navShellStart, Color.WHITE, 0.48f), 248),
            navShellEnd = ColorUtils.setAlphaComponent(blend(dark.navShellEnd, Color.WHITE, 0.55f), 236),
            panel = blend(dark.panel, Color.WHITE, 0.62f),
            panelAlt = blend(dark.panelAlt, Color.WHITE, 0.56f),
            stroke = blend(dark.stroke, Color.BLACK, 0.26f),
            accent = accentInk,
            alert = blend(dark.alert, Color.BLACK, 0.08f),
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            textMuted = textMuted
        )
    }

    @ColorInt
    private fun normalizeAccent(
        @ColorInt color: Int,
        minSaturation: Float = 0.50f,
        minValue: Float = 0.76f
    ): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceIn(minSaturation, 1f)
        hsv[2] = hsv[2].coerceIn(minValue, 0.97f)
        return Color.HSVToColor(hsv)
    }

    @ColorInt
    private fun shiftHue(@ColorInt color: Int, delta: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        var hue = (hsv[0] + delta) % 360f
        if (hue < 0f) {
            hue += 360f
        }
        hsv[0] = hue
        return Color.HSVToColor(hsv)
    }

    @ColorInt
    private fun blend(
        @ColorInt from: Int,
        @ColorInt to: Int,
        fraction: Float
    ): Int {
        return ColorUtils.blendARGB(from, to, fraction.coerceIn(0f, 1f))
    }

    private fun palette(
        @ColorInt backgroundStart: Int,
        @ColorInt backgroundCenter: Int,
        @ColorInt backgroundEnd: Int,
        @ColorInt navShellStart: Int,
        @ColorInt navShellEnd: Int,
        @ColorInt panel: Int,
        @ColorInt panelAlt: Int,
        @ColorInt stroke: Int,
        @ColorInt accent: Int,
        @ColorInt alert: Int,
        @ColorInt textPrimary: Int,
        @ColorInt textSecondary: Int,
        @ColorInt textMuted: Int
    ): LionThemePalette {
        return LionThemePalette(
            backgroundStart = backgroundStart,
            backgroundCenter = backgroundCenter,
            backgroundEnd = backgroundEnd,
            navShellStart = navShellStart,
            navShellEnd = navShellEnd,
            panel = panel,
            panelAlt = panelAlt,
            stroke = stroke,
            accent = accent,
            alert = alert,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            textMuted = textMuted
        )
    }
}
