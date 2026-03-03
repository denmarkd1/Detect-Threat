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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max

object LionThemePrefs {
    private const val PREFS_FILE = "dt_lion_theme_prefs"
    private const val KEY_FILL_MODE = "fill_mode"
    private const val KEY_IDENTITY_PROFILE = "identity_profile"
    private const val KEY_ALLOW_CHILD_PROFILES_AS_MAIN = "allow_child_profiles_as_main"
    private const val KEY_SELECTED_PRO_LION_ASSET = "selected_pro_lion_asset"
    private const val KEY_THEME_VARIANT = "theme_variant"
    private const val KEY_THEME_TONE_MODE = "theme_tone_mode"
    private const val KEY_CANNABIS_US_STATE_OVERRIDE = "cannabis_us_state_override"
    private const val LIGHT_MODE_USE_DARK_LION_STANDARD = true
    private const val PROFILE_REGISTRY_ASSET_FILE = "lion_profiles.json"
    private const val WORKSPACE_SETTINGS_FILE = "workspace_settings.json"
    private const val CANNABIS_THEME_POLICY_KEY = "cannabis_theme_policy"
    private const val DEFAULT_PROFILE_ID = "female"
    private const val DEFAULT_CHILD_PROFILE_ID = "child_gold_default"
    private const val PROFILE_ROLE_CHILD = "child"
    private val CHILD_PROFILE_CYCLE_ORDER = listOf(
        "child_gold_default",
        "child_blue_male_alt",
        "child_pink_female_alt",
        "child_rainbow_female2_nonbinary"
    )

    const val PRO_LION_ASSET_DIR = "lion_heads"
    const val PRO_LION_ASSET_WORKSPACE_PATH = "android-watchdog/app/src/main/assets/lion_heads/"

    enum class LionIdentityAudience(val raw: String) {
        MALE("male"),
        FEMALE("female"),
        NON_BINARY("non_binary");

        companion object {
            fun fromRaw(raw: String?): LionIdentityAudience? {
                return when (raw?.trim()?.lowercase(Locale.US)) {
                    MALE.raw -> MALE
                    FEMALE.raw -> FEMALE
                    NON_BINARY.raw, "non-binary", "nonbinary" -> NON_BINARY
                    else -> null
                }
            }
        }
    }

    data class LionIdentityProfile(
        val id: String,
        @DrawableRes val drawableRes: Int,
        val drawableName: String,
        val label: String,
        val paidOnly: Boolean,
        val styleKey: String,
        val audiences: Set<LionIdentityAudience>,
        val requiresCannabisLegal: Boolean,
        val themeIntensity: Float = 1f,
        val isChildProfile: Boolean = false
    )

    private data class CannabisThemePolicy(
        val enabled: Boolean,
        val allowedCountries: Set<String>,
        val allowedUsStates: Set<String>,
        val blockWhenUsStateUnknown: Boolean,
        val usStateOverride: String?
    )

    @Volatile
    private var cachedProfileRegistry: List<LionIdentityProfile>? = null
    private val profileRegistryLock = Any()
    @Volatile
    private var cachedCannabisThemePolicy: CannabisThemePolicy? = null
    private val cannabisThemePolicyLock = Any()

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
        val roleCode = PricingPolicy.resolveProfileControl(context).roleCode
        val allowChildOverride = readAllowChildProfilesAsMain(context)
        val profiles = loadIdentityProfileRegistry(context)
        val allowed = profiles.filter { profile ->
            isProfileVisibleForContext(
                context = context,
                profile = profile,
                paidAccess = paidAccess,
                roleCode = roleCode,
                allowChildOverride = allowChildOverride
            )
        }
        if (allowed.isNotEmpty()) {
            return allowed
        }
        val defaultAllowed = defaultIdentityProfiles(context).filter { profile ->
            isProfileVisibleForContext(
                context = context,
                profile = profile,
                paidAccess = paidAccess,
                roleCode = roleCode,
                allowChildOverride = allowChildOverride
            )
        }
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
                drawableRes = R.drawable.lion_icon_female,
                drawableName = "lion_icon_female",
                label = "Lion",
                paidOnly = false,
                styleKey = "default",
                audiences = setOf(LionIdentityAudience.FEMALE),
                requiresCannabisLegal = false,
                themeIntensity = 1f
            )
            writeIdentityProfile(context, emergencyProfile.id)
            return emergencyProfile
        }
        val defaultProfileId = resolveDefaultProfileId(context)
        val fallback = allowed.firstOrNull { it.id == defaultProfileId }
            ?: allowed.firstOrNull { it.id == DEFAULT_PROFILE_ID }
            ?: allowed.first()
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

    fun readAllowChildProfilesAsMain(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALLOW_CHILD_PROFILES_AS_MAIN, false)
    }

    fun writeAllowChildProfilesAsMain(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALLOW_CHILD_PROFILES_AS_MAIN, enabled)
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

    fun listIdentityProfilesForAudience(
        context: Context,
        audience: LionIdentityAudience,
        paidAccess: Boolean = PricingPolicy.resolveFeatureAccess(context).paidAccess
    ): List<LionIdentityProfile> {
        val filtered = listIdentityProfiles(context, paidAccess)
            .filter { it.audiences.contains(audience) }
        if (filtered.isEmpty()) {
            return filtered
        }
        val nonChild = filtered.filterNot { it.isChildProfile }
        val child = filtered.filter { it.isChildProfile }
            .sortedBy { resolveChildProfileCycleRank(it.id) }
        return nonChild + child
    }

    fun cycleIdentityProfileForAudience(
        context: Context,
        audience: LionIdentityAudience,
        paidAccess: Boolean
    ): LionIdentityProfile? {
        val allowed = listIdentityProfilesForAudience(context, audience, paidAccess)
        if (allowed.isEmpty()) {
            return null
        }
        val current = readIdentityProfile(context, paidAccess)
        val currentIndex = allowed.indexOfFirst { it.id == current.id }
        val next = if (currentIndex >= 0) {
            allowed[(currentIndex + 1) % allowed.size]
        } else {
            allowed.first()
        }
        writeIdentityProfile(context, next.id)
        return next
    }

    fun readCannabisUsStateOverride(context: Context): String? {
        val stored = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_CANNABIS_US_STATE_OVERRIDE, null)
        return normalizeCode(stored, expectedLength = 2)
    }

    fun writeCannabisUsStateOverride(context: Context, stateCode: String?) {
        val normalized = normalizeCode(stateCode, expectedLength = 2)
        val editor = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
        if (normalized == null) {
            editor.remove(KEY_CANNABIS_US_STATE_OVERRIDE)
        } else {
            editor.putString(KEY_CANNABIS_US_STATE_OVERRIDE, normalized)
        }
        editor.apply()
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
                styleKey = item.optString("style_key").trim().ifBlank { "default" },
                audiences = parseAudiences(item.optJSONArray("audiences"), id),
                requiresCannabisLegal = item.optBoolean("requires_cannabis_legal", false),
                themeIntensity = normalizeThemeIntensity(
                    item.optDouble("theme_intensity", 1.0)
                ),
                isChildProfile = item.optBoolean("child_profile", false)
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
                styleKey = "default",
                audiences = setOf(LionIdentityAudience.MALE),
                requiresCannabisLegal = false,
                themeIntensity = 1f,
                isChildProfile = false
            ),
            LionIdentityProfile(
                id = "female",
                drawableRes = R.drawable.lion_icon_female,
                drawableName = "lion_icon_female",
                label = context.getString(R.string.lion_identity_profile_female),
                paidOnly = false,
                styleKey = "default",
                audiences = setOf(LionIdentityAudience.FEMALE),
                requiresCannabisLegal = false,
                themeIntensity = 1f,
                isChildProfile = false
            ),
            LionIdentityProfile(
                id = "steampunk_male",
                drawableRes = R.drawable.lion_icon_steampunk_male,
                drawableName = "lion_icon_steampunk_male",
                label = context.getString(R.string.lion_identity_profile_steampunk_male),
                paidOnly = false,
                styleKey = "steampunk_male",
                audiences = setOf(LionIdentityAudience.MALE),
                requiresCannabisLegal = false,
                themeIntensity = 1.08f,
                isChildProfile = false
            ),
            LionIdentityProfile(
                id = "steampunk_lioness",
                drawableRes = R.drawable.lion_icon_steampunk_lioness,
                drawableName = "lion_icon_steampunk_lioness",
                label = context.getString(R.string.lion_identity_profile_steampunk_lioness),
                paidOnly = false,
                styleKey = "steampunk_lioness",
                audiences = setOf(LionIdentityAudience.FEMALE),
                requiresCannabisLegal = false,
                themeIntensity = 1.08f,
                isChildProfile = false
            ),
            LionIdentityProfile(
                id = "full_color_male",
                drawableRes = R.drawable.lion_icon_full_color_male,
                drawableName = "lion_icon_full_color_male",
                label = context.getString(R.string.lion_identity_profile_full_color_male),
                paidOnly = false,
                styleKey = "full_color_male",
                audiences = setOf(LionIdentityAudience.MALE),
                requiresCannabisLegal = false,
                themeIntensity = 1.30f,
                isChildProfile = false
            ),
            LionIdentityProfile(
                id = "full_color_lioness",
                drawableRes = R.drawable.lion_icon_full_color_lioness,
                drawableName = "lion_icon_full_color_lioness",
                label = context.getString(R.string.lion_identity_profile_full_color_lioness),
                paidOnly = false,
                styleKey = "full_color_lioness",
                audiences = setOf(LionIdentityAudience.FEMALE),
                requiresCannabisLegal = false,
                themeIntensity = 1.30f,
                isChildProfile = false
            ),
            LionIdentityProfile(
                id = "child_gold_default",
                drawableRes = R.drawable.lion_icon_child_gold_default,
                drawableName = "lion_icon_child_gold_default",
                label = context.getString(R.string.lion_identity_profile_child_gold_default),
                paidOnly = false,
                styleKey = "child_gold_default",
                audiences = setOf(LionIdentityAudience.MALE, LionIdentityAudience.FEMALE),
                requiresCannabisLegal = false,
                themeIntensity = 1.12f,
                isChildProfile = true
            ),
            LionIdentityProfile(
                id = "child_blue_male_alt",
                drawableRes = R.drawable.lion_icon_child_blue_male_alt,
                drawableName = "lion_icon_child_blue_male_alt",
                label = context.getString(R.string.lion_identity_profile_child_blue_male_alt),
                paidOnly = true,
                styleKey = "child_blue_male_alt",
                audiences = setOf(LionIdentityAudience.MALE),
                requiresCannabisLegal = false,
                themeIntensity = 1.24f,
                isChildProfile = true
            ),
            LionIdentityProfile(
                id = "child_pink_female_alt",
                drawableRes = R.drawable.lion_icon_child_pink_female_alt,
                drawableName = "lion_icon_child_pink_female_alt",
                label = context.getString(R.string.lion_identity_profile_child_pink_female_alt),
                paidOnly = true,
                styleKey = "child_pink_female_alt",
                audiences = setOf(LionIdentityAudience.FEMALE),
                requiresCannabisLegal = false,
                themeIntensity = 1.24f,
                isChildProfile = true
            ),
            LionIdentityProfile(
                id = "child_rainbow_female2_nonbinary",
                drawableRes = R.drawable.lion_icon_child_rainbow_female2_nonbinary,
                drawableName = "lion_icon_child_rainbow_female2_nonbinary",
                label = context.getString(R.string.lion_identity_profile_child_rainbow_female2_nonbinary),
                paidOnly = true,
                styleKey = "child_rainbow_female2_nonbinary",
                audiences = setOf(LionIdentityAudience.FEMALE, LionIdentityAudience.NON_BINARY),
                requiresCannabisLegal = false,
                themeIntensity = 1.28f,
                isChildProfile = true
            ),
            LionIdentityProfile(
                id = "non_binary",
                drawableRes = R.drawable.lion_icon_non_binary,
                drawableName = "lion_icon_non_binary",
                label = context.getString(R.string.lion_identity_profile_non_binary),
                paidOnly = true,
                styleKey = "default",
                audiences = setOf(LionIdentityAudience.NON_BINARY),
                requiresCannabisLegal = false,
                themeIntensity = 1f,
                isChildProfile = false
            ),
            LionIdentityProfile(
                id = "top_hat_lion",
                drawableRes = R.drawable.lion_icon_top_hat,
                drawableName = "lion_icon_top_hat",
                label = context.getString(R.string.lion_identity_profile_top_hat),
                paidOnly = false,
                styleKey = "cannabis_top_hat",
                audiences = setOf(LionIdentityAudience.MALE),
                requiresCannabisLegal = true,
                themeIntensity = 1.36f,
                isChildProfile = false
            ),
            LionIdentityProfile(
                id = "pink_crown_lion",
                drawableRes = R.drawable.lion_icon_pink_crown,
                drawableName = "lion_icon_pink_crown",
                label = context.getString(R.string.lion_identity_profile_pink_crown),
                paidOnly = false,
                styleKey = "cannabis_pink_crown",
                audiences = setOf(LionIdentityAudience.FEMALE, LionIdentityAudience.NON_BINARY),
                requiresCannabisLegal = true,
                themeIntensity = 1.36f,
                isChildProfile = false
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

    private fun parseAudiences(
        array: JSONArray?,
        profileId: String
    ): Set<LionIdentityAudience> {
        if (array != null) {
            val parsed = linkedSetOf<LionIdentityAudience>()
            for (index in 0 until array.length()) {
                val audience = LionIdentityAudience.fromRaw(array.optString(index))
                if (audience != null) {
                    parsed += audience
                }
            }
            if (parsed.isNotEmpty()) {
                return parsed
            }
        }
        return inferAudiencesFromProfileId(profileId)
    }

    private fun inferAudiencesFromProfileId(profileId: String): Set<LionIdentityAudience> {
        val normalized = profileId.trim().lowercase(Locale.US)
        return when {
            normalized.contains("non_binary") || normalized.contains("nonbinary") -> {
                setOf(LionIdentityAudience.NON_BINARY)
            }
            normalized.contains("female") || normalized.contains("lioness") -> {
                setOf(LionIdentityAudience.FEMALE)
            }
            else -> setOf(LionIdentityAudience.MALE)
        }
    }

    private fun resolveDefaultProfileId(context: Context): String {
        val roleCode = PricingPolicy.resolveProfileControl(context).roleCode
        return if (roleCode == PROFILE_ROLE_CHILD) {
            DEFAULT_CHILD_PROFILE_ID
        } else {
            DEFAULT_PROFILE_ID
        }
    }

    private fun resolveChildProfileCycleRank(profileId: String): Int {
        val index = CHILD_PROFILE_CYCLE_ORDER.indexOf(profileId.trim().lowercase(Locale.US))
        return if (index >= 0) {
            index
        } else {
            CHILD_PROFILE_CYCLE_ORDER.size + 1
        }
    }

    private fun isProfileVisibleForContext(
        context: Context,
        profile: LionIdentityProfile,
        paidAccess: Boolean,
        roleCode: String,
        allowChildOverride: Boolean
    ): Boolean {
        if ((profile.paidOnly && !paidAccess) || !isProfileAllowedForRegion(context, profile)) {
            return false
        }
        if (!profile.isChildProfile) {
            return true
        }
        if (roleCode == PROFILE_ROLE_CHILD) {
            return true
        }
        if (!paidAccess) {
            return false
        }
        return allowChildOverride
    }

    private fun isProfileAllowedForRegion(
        context: Context,
        profile: LionIdentityProfile
    ): Boolean {
        if (!profile.requiresCannabisLegal) {
            return true
        }
        val policy = loadCannabisThemePolicy(context)
        if (!policy.enabled) {
            return true
        }
        val country = resolveCountryCode()
        if (!policy.allowedCountries.contains(country)) {
            return false
        }
        if (country != "US") {
            return true
        }
        val usState = resolveUsStateCode(context, policy)
        if (usState == null) {
            return !policy.blockWhenUsStateUnknown
        }
        return policy.allowedUsStates.contains(usState)
    }

    private fun loadCannabisThemePolicy(context: Context): CannabisThemePolicy {
        cachedCannabisThemePolicy?.let { return it }
        return synchronized(cannabisThemePolicyLock) {
            cachedCannabisThemePolicy?.let { return it }
            val policy = readWorkspaceSettingsJson(context)
                ?.optJSONObject(CANNABIS_THEME_POLICY_KEY)
                ?.let { parseCannabisThemePolicy(it) }
                ?: defaultCannabisThemePolicy()
            cachedCannabisThemePolicy = policy
            policy
        }
    }

    private fun readWorkspaceSettingsJson(context: Context): JSONObject? {
        val localOverride = File(context.filesDir, WORKSPACE_SETTINGS_FILE)
        val payload = when {
            localOverride.exists() -> runCatching { localOverride.readText() }.getOrNull()
            else -> runCatching {
                context.assets.open(WORKSPACE_SETTINGS_FILE).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return null
        return runCatching { JSONObject(payload) }.getOrNull()
    }

    private fun parseCannabisThemePolicy(item: JSONObject): CannabisThemePolicy {
        return CannabisThemePolicy(
            enabled = item.optBoolean("enabled", true),
            allowedCountries = parseCodeSet(item.optJSONArray("allowed_countries"), expectedLength = 2),
            allowedUsStates = parseCodeSet(item.optJSONArray("allowed_us_states"), expectedLength = 2),
            blockWhenUsStateUnknown = item.optBoolean("block_when_us_state_unknown", true),
            usStateOverride = normalizeCode(item.optString("us_state_override"), expectedLength = 2)
        )
    }

    private fun defaultCannabisThemePolicy(): CannabisThemePolicy {
        return CannabisThemePolicy(
            enabled = true,
            allowedCountries = setOf("US"),
            allowedUsStates = emptySet(),
            blockWhenUsStateUnknown = true,
            usStateOverride = null
        )
    }

    private fun resolveCountryCode(): String {
        val primary = normalizeCode(Locale.getDefault().country, expectedLength = 2)
        return primary ?: "US"
    }

    private fun resolveUsStateCode(
        context: Context,
        policy: CannabisThemePolicy
    ): String? {
        readCannabisUsStateOverride(context)?.let { return it }
        policy.usStateOverride?.let { return it }
        val locales = context.resources.configuration.locales
        for (index in 0 until locales.size()) {
            val locale = locales[index]
            parseUsStateFromLocale(locale)?.let { return it }
        }
        return parseUsStateFromLocale(Locale.getDefault())
    }

    private fun parseUsStateFromLocale(locale: Locale): String? {
        val subdivision = locale.getUnicodeLocaleType("sd")
        val fromSubdivision = normalizeUsSubdivision(subdivision)
        if (fromSubdivision != null) {
            return fromSubdivision
        }
        val regionOverride = locale.getUnicodeLocaleType("rg")
        val fromRegionOverride = normalizeUsSubdivision(regionOverride)
        if (fromRegionOverride != null) {
            return fromRegionOverride
        }
        val variant = normalizeCode(locale.variant, expectedLength = 2)
        if (variant != null) {
            return variant
        }
        return null
    }

    private fun normalizeUsSubdivision(raw: String?): String? {
        val normalized = raw?.trim()?.lowercase(Locale.US).orEmpty()
        if (normalized.length < 4) {
            return null
        }
        if (!normalized.startsWith("us")) {
            return null
        }
        return normalizeCode(normalized.substring(2, 4), expectedLength = 2)
    }

    private fun parseCodeSet(
        array: JSONArray?,
        expectedLength: Int
    ): Set<String> {
        if (array == null) {
            return emptySet()
        }
        val values = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            normalizeCode(array.optString(index), expectedLength)?.let { values += it }
        }
        return values
    }

    private fun normalizeCode(raw: String?, expectedLength: Int): String? {
        val value = raw?.trim()?.uppercase(Locale.US).orEmpty()
        return if (value.length == expectedLength) {
            value
        } else {
            null
        }
    }

    private fun normalizeThemeIntensity(raw: Double): Float {
        val value = raw.toFloat()
        if (value.isNaN() || value.isInfinite()) {
            return 1f
        }
        return value.coerceIn(0.80f, 1.60f)
    }
}
