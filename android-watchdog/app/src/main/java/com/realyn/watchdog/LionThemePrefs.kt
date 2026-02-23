package com.realyn.watchdog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.realyn.watchdog.theme.LionThemeToneMode
import com.realyn.watchdog.theme.LionThemeVariant
import java.io.IOException
import kotlin.math.max

object LionThemePrefs {
    private const val PREFS_FILE = "dt_lion_theme_prefs"
    private const val KEY_FILL_MODE = "fill_mode"
    private const val KEY_SELECTED_PRO_LION_ASSET = "selected_pro_lion_asset"
    private const val KEY_THEME_VARIANT = "theme_variant"
    private const val KEY_THEME_TONE_MODE = "theme_tone_mode"

    const val PRO_LION_ASSET_DIR = "lion_heads"
    const val PRO_LION_ASSET_WORKSPACE_PATH = "android-watchdog/app/src/main/assets/lion_heads/"

    fun readFillMode(context: Context): LionFillMode {
        val raw = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_FILL_MODE, LionFillMode.LEFT_TO_RIGHT.raw)
        return LionFillMode.fromRaw(raw)
    }

    fun writeFillMode(context: Context, mode: LionFillMode) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FILL_MODE, mode.raw)
            .apply()
    }

    fun readThemeVariant(context: Context): LionThemeVariant {
        val raw = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_THEME_VARIANT, LionThemeVariant.FREE_GUARDIAN_BRONZE.raw)
        return LionThemeVariant.fromRaw(raw)
    }

    fun writeThemeVariant(context: Context, variant: LionThemeVariant) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_VARIANT, variant.raw)
            .apply()
    }

    fun cycleThemeVariant(context: Context, paidAccess: Boolean): LionThemeVariant {
        val options = LionThemeVariant.availableFor(paidAccess)
        val current = readThemeVariant(context)
        val currentIndex = options.indexOf(current).coerceAtLeast(0)
        val next = options[(currentIndex + 1) % options.size]
        writeThemeVariant(context, next)
        return next
    }

    fun readToneMode(context: Context): LionThemeToneMode {
        val raw = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_THEME_TONE_MODE, LionThemeToneMode.SYSTEM.raw)
        return LionThemeToneMode.fromRaw(raw)
    }

    fun writeToneMode(context: Context, mode: LionThemeToneMode) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_TONE_MODE, mode.raw)
            .apply()
    }

    fun cycleToneMode(context: Context): LionThemeToneMode {
        val next = readToneMode(context).next()
        writeToneMode(context, next)
        return next
    }

    fun listAvailableProLionAssets(context: Context): List<String> {
        val fileNames = runCatching {
            context.assets.list(PRO_LION_ASSET_DIR)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        return fileNames
            .filter { fileName ->
                val lower = fileName.lowercase()
                lower.endsWith(".png") || lower.endsWith(".webp") ||
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            }
            .sorted()
    }

    fun readSelectedProLionAsset(context: Context): String? {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_PRO_LION_ASSET, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun writeSelectedProLionAsset(context: Context, assetFileName: String?) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_PRO_LION_ASSET, assetFileName?.trim())
            .apply()
    }

    fun cycleProLionAsset(context: Context): String? {
        val available = listAvailableProLionAssets(context)
        if (available.isEmpty()) {
            return null
        }
        val current = readSelectedProLionAsset(context)?.takeIf { available.contains(it) } ?: available.first()
        val currentIndex = available.indexOf(current).coerceAtLeast(0)
        val next = available[(currentIndex + 1) % available.size]
        writeSelectedProLionAsset(context, next)
        return next
    }

    fun resolveSelectedLionBitmap(context: Context): Bitmap? {
        val featureAccess = PricingPolicy.resolveFeatureAccess(context)
        if (!featureAccess.paidAccess) {
            return null
        }

        val available = listAvailableProLionAssets(context)
        if (available.isEmpty()) {
            return null
        }

        val selected = readSelectedProLionAsset(context)?.takeIf { available.contains(it) }
            ?: available.first()
        if (selected != readSelectedProLionAsset(context)) {
            writeSelectedProLionAsset(context, selected)
        }

        val assetPath = "$PRO_LION_ASSET_DIR/$selected"
        return try {
            context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: IOException) {
            null
        }
    }

    @ColorInt
    fun resolveAccentColor(context: Context): Int {
        return resolveAccentSeedColor(context)
    }

    @ColorInt
    fun resolveAccentSeedColor(
        context: Context,
        selectedBitmap: Bitmap? = null
    ): Int {
        val fallback = ContextCompat.getColor(context, R.color.brand_accent)
        val bitmap = selectedBitmap
            ?: resolveSelectedLionBitmap(context)
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.lion_icon)
        val extracted = extractAccentFromBitmap(bitmap)
        return extracted ?: fallback
    }

    @ColorInt
    private fun extractAccentFromBitmap(bitmap: Bitmap): Int? {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }

        val hsv = FloatArray(3)
        var bestScore = -1f
        var bestHue = 0f
        var bestSat = 0f
        var bestValue = 0f

        val stepX = max(1, bitmap.width / 56)
        val stepY = max(1, bitmap.height / 56)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= 120) {
                    Color.colorToHSV(pixel, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]
                    if (sat >= 0.22f && value >= 0.25f) {
                        val score = sat * 0.72f + value * 0.28f
                        if (score > bestScore) {
                            bestScore = score
                            bestHue = hsv[0]
                            bestSat = sat
                            bestValue = value
                        }
                    }
                }
                x += stepX
            }
            y += stepY
        }

        if (bestScore < 0f) {
            return null
        }

        return Color.HSVToColor(
            floatArrayOf(
                bestHue,
                max(0.55f, bestSat),
                max(0.70f, bestValue)
            )
        )
    }
}
