package com.realyn.watchdog

import android.content.Context
import java.util.Locale

enum class TranslationPackLevel {
    FULL,
    CORE,
    FALLBACK
}

data class TranslationProfile(
    val localeTag: String,
    val localeDisplayName: String,
    val coveragePercent: Int,
    val packLevel: TranslationPackLevel
)

object LocalizationSupport {

    private val fullPackLanguages = setOf("en", "es")
    private val corePackLanguages = emptySet<String>()

    fun resolveProfile(context: Context): TranslationProfile {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val languageCode = locale.language.lowercase(Locale.US)
        val packLevel = when {
            fullPackLanguages.contains(languageCode) -> TranslationPackLevel.FULL
            corePackLanguages.contains(languageCode) -> TranslationPackLevel.CORE
            else -> TranslationPackLevel.FALLBACK
        }

        val coveragePercent = when (packLevel) {
            TranslationPackLevel.FULL -> 100
            TranslationPackLevel.CORE -> 72
            TranslationPackLevel.FALLBACK -> 48
        }
        val displayName = locale.getDisplayName(locale).replaceFirstChar {
            if (it.isLowerCase()) {
                it.titlecase(locale)
            } else {
                it.toString()
            }
        }

        return TranslationProfile(
            localeTag = locale.toLanguageTag(),
            localeDisplayName = displayName,
            coveragePercent = coveragePercent,
            packLevel = packLevel
        )
    }
}
