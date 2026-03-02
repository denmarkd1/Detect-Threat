package com.realyn.watchdog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.realyn.watchdog.theme.LionThemeToneMode
import com.realyn.watchdog.theme.LionThemeVariant
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

object LionThemePrefs {
    private const val PREFS_FILE = "dt_lion_theme_prefs"
    private const val KEY_FILL_MODE = "fill_mode"
    private const val KEY_IDENTITY_PROFILE = "identity_profile"
    private const val KEY_SELECTED_PRO_LION_ASSET = "selected_pro_lion_asset"
    private const val KEY_THEME_VARIANT = "theme_variant"
    private const val KEY_THEME_TONE_MODE = "theme_tone_mode"
    private const val LIGHT_MODE_USE_DARK_LION_STANDARD = true
    private const val PROFILE_REGISTRY_ASSET_FILE = "lion_profiles.json"
    private const val DEFAULT_PROFILE_ID = "female"

    const val PRO_LION_ASSET_DIR = "lion_heads"
    const val PRO_LION_ASSET_WORKSPACE_PATH = "android-watchdog/app/src/main/assets/lion_heads/"

    data class LionIdentityProfile(
        val id: String,
        @DrawableRes val drawableRes: Int,
        val drawableName: String,
        val label: String,
        val paidOnly: Boolean,
        val styleKey: String
    )

    @Volatile
    private var cachedProfileRegistry: List<LionIdentityProfile>? = null
    private val profileRegistryLock = Any()

    fun shouldUseDarkLionPresentation(isDarkTone: Boolean): Boolean {
        return isDarkTone || LIGHT_MODE_USE_DARK_LION_STANDARD
    }

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

    fun listIdentityProfiles(
        context: Context,
        paidAccess: Boolean = PricingPolicy.resolveFeatureAccess(context).paidAccess
    ): List<LionIdentityProfile> {
        val profiles = loadIdentityProfileRegistry(context)
        val allowed = profiles.filter { !it.paidOnly || paidAccess }
        if (allowed.isNotEmpty()) {
            return allowed
        }
        val defaultAllowed = defaultIdentityProfiles(context).filter { !it.paidOnly || paidAccess }
        return if (defaultAllowed.isNotEmpty()) {
            defaultAllowed
        } else {
            allowed
        }
    }

    fun readIdentityProfile(
        context: Context,
        paidAccess: Boolean = PricingPolicy.resolveFeatureAccess(context).paidAccess
    ): LionIdentityProfile {
        val allowed = listIdentityProfiles(context, paidAccess)
        if (allowed.isEmpty()) {
            val emergencyProfile = LionIdentityProfile(
                id = DEFAULT_PROFILE_ID,
                drawableRes = R.drawable.lion_icon_non_binary,
                drawableName = "lion_icon_non_binary",
                label = "Lion",
                paidOnly = false,
                styleKey = "default"
            )
            writeIdentityProfile(context, emergencyProfile.id)
            return emergencyProfile
        }
        val fallback = allowed.firstOrNull { it.id == DEFAULT_PROFILE_ID } ?: allowed.first()
        val storedId = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_IDENTITY_PROFILE, DEFAULT_PROFILE_ID)
            ?.trim()
            .orEmpty()
        val selected = allowed.firstOrNull { it.id == storedId } ?: fallback
        if (selected.id != storedId) {
            writeIdentityProfile(context, selected.id)
        }
        return selected
    }

    fun writeIdentityProfile(context: Context, profile: LionIdentityProfile) {
        writeIdentityProfile(context, profile.id)
    }

    fun writeIdentityProfile(context: Context, profileId: String) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_IDENTITY_PROFILE, profileId.trim())
            .apply()
    }

    fun cycleIdentityProfile(context: Context, paidAccess: Boolean): LionIdentityProfile {
        val allowed = listIdentityProfiles(context, paidAccess)
        val current = readIdentityProfile(context, paidAccess)
        val currentIndex = allowed.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val next = allowed[(currentIndex + 1) % allowed.size]
        writeIdentityProfile(context, next.id)
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
        val profile = readIdentityProfile(context, featureAccess.paidAccess)
        return BitmapFactory.decodeResource(context.resources, profile.drawableRes)
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
        val fallbackProfile = listIdentityProfiles(context, paidAccess = true)
            .firstOrNull { it.id == DEFAULT_PROFILE_ID }
            ?: listIdentityProfiles(context, paidAccess = true).firstOrNull()
        val bitmap = selectedBitmap
            ?: resolveSelectedLionBitmap(context)
            ?: fallbackProfile?.let { BitmapFactory.decodeResource(context.resources, it.drawableRes) }
        val extracted = bitmap?.let { extractAccentFromBitmap(it) }
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

    private fun loadIdentityProfileRegistry(context: Context): List<LionIdentityProfile> {
        cachedProfileRegistry?.let { return it }
        return synchronized(profileRegistryLock) {
            cachedProfileRegistry?.let { return it }
            val parsed = runCatching {
                context.assets.open(PROFILE_REGISTRY_ASSET_FILE).bufferedReader().use { it.readText() }
            }.getOrNull()?.let { parseIdentityProfileRegistry(context, it) }.orEmpty()
            val resolved = if (parsed.isNotEmpty()) {
                parsed
            } else {
                defaultIdentityProfiles(context)
            }
            cachedProfileRegistry = resolved
            resolved
        }
    }

    private fun parseIdentityProfileRegistry(
        context: Context,
        payload: String
    ): List<LionIdentityProfile> {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
        val profilesArray = root.optJSONArray("profiles") ?: return emptyList()
        val profiles = mutableListOf<LionIdentityProfile>()
        val seenIds = linkedSetOf<String>()
        for (index in 0 until profilesArray.length()) {
            val item = profilesArray.optJSONObject(index) ?: continue
            val id = item.optString("id").trim().lowercase(Locale.US)
            if (id.isBlank() || !seenIds.add(id)) {
                continue
            }
            val drawableName = item.optString("drawable").trim()
            val drawableRes = resolveDrawableResByName(context, drawableName)
            if (drawableRes == 0) {
                continue
            }
            val label = item.optString("label").trim().ifBlank { humanizeProfileId(id) }
            profiles += LionIdentityProfile(
                id = id,
                drawableRes = drawableRes,
                drawableName = drawableName,
                label = label,
                paidOnly = item.optBoolean("paid_only", false),
                styleKey = item.optString("style_key").trim().ifBlank { "default" }
            )
        }
        return profiles
    }

    private fun defaultIdentityProfiles(context: Context): List<LionIdentityProfile> {
        return listOf(
            LionIdentityProfile(
                id = "male",
                drawableRes = R.drawable.lion_icon,
                drawableName = "lion_icon",
                label = context.getString(R.string.lion_identity_profile_male),
                paidOnly = false,
                styleKey = "default"
            ),
            LionIdentityProfile(
                id = "female",
                drawableRes = R.drawable.lion_icon_non_binary,
                drawableName = "lion_icon_non_binary",
                label = context.getString(R.string.lion_identity_profile_female),
                paidOnly = false,
                styleKey = "default"
            ),
            LionIdentityProfile(
                id = "steampunk_male",
                drawableRes = R.drawable.lion_icon_steampunk_male,
                drawableName = "lion_icon_steampunk_male",
                label = context.getString(R.string.lion_identity_profile_steampunk_male),
                paidOnly = false,
                styleKey = "steampunk_male"
            ),
            LionIdentityProfile(
                id = "steampunk_lioness",
                drawableRes = R.drawable.lion_icon_steampunk_lioness,
                drawableName = "lion_icon_steampunk_lioness",
                label = context.getString(R.string.lion_identity_profile_steampunk_lioness),
                paidOnly = false,
                styleKey = "steampunk_lioness"
            ),
            LionIdentityProfile(
                id = "non_binary",
                drawableRes = R.drawable.lion_icon_female,
                drawableName = "lion_icon_female",
                label = context.getString(R.string.lion_identity_profile_non_binary),
                paidOnly = true,
                styleKey = "default"
            )
        )
    }

    @DrawableRes
    private fun resolveDrawableResByName(context: Context, drawableName: String): Int {
        if (drawableName.isBlank()) {
            return 0
        }
        return context.resources.getIdentifier(drawableName, "drawable", context.packageName)
    }

    private fun humanizeProfileId(profileId: String): String {
        return profileId
            .trim()
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { first ->
                    if (first.isLowerCase()) {
                        first.titlecase(Locale.US)
                    } else {
                        first.toString()
                    }
                }
            }
            .ifBlank { "Lion" }
    }
}
