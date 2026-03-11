package com.realyn.watchdog

import android.os.Build
import java.util.Locale

enum class GuideDeviceFamily {
    MIUI,
    SAMSUNG,
    COLOR_OS,
    FUNTOUCH,
    MAGIC_OS,
    PIXEL,
    GENERIC
}

data class GuideRuntimeProfile(
    val family: GuideDeviceFamily,
    val eagerForegroundGuideLaunch: Boolean,
    val pinnedGuideNotification: Boolean,
    val fallbackGuideNotification: Boolean,
    val overlayVisibilityCaveat: Boolean
)

object GuideRuntimePolicy {

    private val emulatorTokens = setOf(
        "generic",
        "aosp",
        "sdk_gphone",
        "sdk_phone",
        "emulator",
        "simulator",
        "goldfish",
        "ranchu",
        "vbox"
    )

    private val conservativeTokens = setOf(
        "google",
        "pixel"
    )

    private data class GuideFamilyRule(
        val family: GuideDeviceFamily,
        val matchTokens: Set<String>,
        val eagerForegroundGuideLaunch: Boolean,
        val pinnedGuideNotification: Boolean = eagerForegroundGuideLaunch,
        val fallbackGuideNotification: Boolean = pinnedGuideNotification,
        val overlayVisibilityCaveat: Boolean = false
    )

    private val familyRules = listOf(
        GuideFamilyRule(
            family = GuideDeviceFamily.MIUI,
            matchTokens = setOf("xiaomi", "redmi", "poco", "miui", "hyperos"),
            eagerForegroundGuideLaunch = true,
            overlayVisibilityCaveat = true
        ),
        GuideFamilyRule(
            family = GuideDeviceFamily.SAMSUNG,
            matchTokens = setOf("samsung", "one ui", "oneui"),
            eagerForegroundGuideLaunch = true,
            overlayVisibilityCaveat = true
        ),
        GuideFamilyRule(
            family = GuideDeviceFamily.COLOR_OS,
            matchTokens = setOf("oppo", "realme", "oneplus", "coloros"),
            eagerForegroundGuideLaunch = true
        ),
        GuideFamilyRule(
            family = GuideDeviceFamily.FUNTOUCH,
            matchTokens = setOf("vivo", "iqoo", "funtouch"),
            eagerForegroundGuideLaunch = true
        ),
        GuideFamilyRule(
            family = GuideDeviceFamily.MAGIC_OS,
            matchTokens = setOf("huawei", "honor", "magicui", "magic os", "emui"),
            eagerForegroundGuideLaunch = true
        ),
        GuideFamilyRule(
            family = GuideDeviceFamily.PIXEL,
            matchTokens = setOf("google", "pixel"),
            eagerForegroundGuideLaunch = false
        )
    )

    fun currentProfile(): GuideRuntimeProfile {
        val descriptor = buildDeviceDescriptor()
        return resolveProfileForDescriptor(
            descriptor = descriptor,
            manufacturerRaw = Build.MANUFACTURER.orEmpty(),
            brandRaw = Build.BRAND.orEmpty()
        )
    }

    internal fun resolveProfileForDescriptor(
        descriptor: String,
        manufacturerRaw: String,
        brandRaw: String
    ): GuideRuntimeProfile {
        val normalizedDescriptor = descriptor.lowercase(Locale.US)
        if (isLikelyEmulator(normalizedDescriptor)) {
            // Emulator descriptors often contain "google"/"pixel" tokens and can be
            // misclassified into the conservative Pixel profile, causing guide overlays
            // to be background-killed during Settings handoff.
            return aggressiveGenericProfile()
        }
        val matchedRule = familyRules.firstOrNull { rule ->
            rule.matchTokens.any(normalizedDescriptor::contains)
        }
        return if (matchedRule != null) {
            GuideRuntimeProfile(
                family = matchedRule.family,
                eagerForegroundGuideLaunch = matchedRule.eagerForegroundGuideLaunch,
                pinnedGuideNotification = matchedRule.pinnedGuideNotification,
                fallbackGuideNotification = matchedRule.fallbackGuideNotification,
                overlayVisibilityCaveat = matchedRule.overlayVisibilityCaveat
            )
        } else if (looksLikeCommercialOemBuild(normalizedDescriptor, manufacturerRaw, brandRaw)) {
            aggressiveGenericProfile()
        } else {
            GuideRuntimeProfile(
                family = GuideDeviceFamily.GENERIC,
                eagerForegroundGuideLaunch = false,
                pinnedGuideNotification = false,
                fallbackGuideNotification = false,
                overlayVisibilityCaveat = false
            )
        }
    }

    fun shouldStartGuideServiceAsForeground(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            currentProfile().eagerForegroundGuideLaunch
    }

    fun shouldRunGuideAsForegroundService(): Boolean {
        return currentProfile().eagerForegroundGuideLaunch
    }

    fun shouldPinGuideNotification(): Boolean {
        return currentProfile().pinnedGuideNotification
    }

    fun shouldShowFallbackGuideNotification(): Boolean {
        return currentProfile().fallbackGuideNotification
    }

    private fun buildDeviceDescriptor(): String {
        return listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.DEVICE,
            Build.MODEL,
            Build.DISPLAY,
            Build.FINGERPRINT
        )
            .joinToString(" ")
            .lowercase(Locale.US)
    }

    private fun aggressiveGenericProfile(): GuideRuntimeProfile {
        return GuideRuntimeProfile(
            family = GuideDeviceFamily.GENERIC,
            eagerForegroundGuideLaunch = true,
            pinnedGuideNotification = true,
            fallbackGuideNotification = true,
            overlayVisibilityCaveat = false
        )
    }

    private fun isLikelyEmulator(descriptor: String): Boolean {
        return emulatorTokens.any(descriptor::contains)
    }

    private fun looksLikeCommercialOemBuild(
        descriptor: String,
        manufacturerRaw: String,
        brandRaw: String
    ): Boolean {
        if (descriptor.isBlank()) {
            return false
        }
        if (isLikelyEmulator(descriptor)) {
            return false
        }
        if (conservativeTokens.any(descriptor::contains)) {
            return false
        }
        val manufacturer = manufacturerRaw.trim().lowercase(Locale.US)
        val brand = brandRaw.trim().lowercase(Locale.US)
        return manufacturer.isNotBlank() && manufacturer != "unknown" && brand.isNotBlank()
    }
}
