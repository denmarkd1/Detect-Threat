package com.realyn.watchdog

import android.content.Context
import java.util.Calendar
import java.util.Locale

data class IdentityProfile(
    val identityLabel: String,
    val primaryEmail: String,
    val emailLinkedAtEpochMs: Long,
    val twoFactorMethod: String,
    val twoFactorPhone: String,
    val twoFactorAuthApp: String,
    val familyRole: String,
    val guardianEmail: String,
    val childBirthYear: Int,
    val onboardingComplete: Boolean
)

object PrimaryIdentityStore {

    private const val KEY_PRIMARY_EMAIL = "primary_email"
    private const val KEY_IDENTITY_LABEL = "identity_label"
    private const val KEY_IDENTITY_EMAIL_LINKED_AT = "identity_email_linked_at_epoch_ms"
    private const val KEY_IDENTITY_2FA_METHOD = "identity_2fa_method"
    private const val KEY_IDENTITY_2FA_PHONE = "identity_2fa_phone"
    private const val KEY_IDENTITY_2FA_AUTH_APP = "identity_2fa_auth_app"
    private const val KEY_IDENTITY_FAMILY_ROLE = "identity_family_role"
    private const val KEY_IDENTITY_GUARDIAN_EMAIL = "identity_guardian_email"
    private const val KEY_IDENTITY_CHILD_BIRTH_YEAR = "identity_child_birth_year"
    private const val KEY_IDENTITY_ONBOARDING_COMPLETE = "identity_onboarding_complete"

    private const val TWO_FACTOR_EMAIL = "email"
    private const val TWO_FACTOR_SMS = "sms"
    private const val TWO_FACTOR_AUTH_APP = "auth_app"
    private const val ROLE_PARENT = "parent"
    private const val ROLE_CHILD = "child"

    fun readPrimaryEmail(context: Context): String {
        return readProfile(context).primaryEmail
    }

    fun writePrimaryEmail(context: Context, email: String) {
        val existing = readProfile(context)
        writeProfile(
            context = context,
            identityLabel = existing.identityLabel,
            primaryEmail = email,
            twoFactorMethod = existing.twoFactorMethod,
            twoFactorPhone = existing.twoFactorPhone,
            twoFactorAuthApp = existing.twoFactorAuthApp,
            familyRole = existing.familyRole,
            guardianEmail = existing.guardianEmail,
            childBirthYear = existing.childBirthYear,
            onboardingComplete = existing.onboardingComplete
        )
    }

    fun readProfile(context: Context): IdentityProfile {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val storedEmail = prefs.getString(KEY_PRIMARY_EMAIL, "")?.trim().orEmpty()
        val label = prefs.getString(KEY_IDENTITY_LABEL, "")?.trim().orEmpty()
        val method = normalizeTwoFactorMethod(
            prefs.getString(KEY_IDENTITY_2FA_METHOD, TWO_FACTOR_EMAIL).orEmpty()
        )
        return IdentityProfile(
            identityLabel = label,
            primaryEmail = storedEmail.lowercase(Locale.US),
            emailLinkedAtEpochMs = prefs.getLong(KEY_IDENTITY_EMAIL_LINKED_AT, 0L).coerceAtLeast(0L),
            twoFactorMethod = method,
            twoFactorPhone = prefs.getString(KEY_IDENTITY_2FA_PHONE, "")?.trim().orEmpty(),
            twoFactorAuthApp = prefs.getString(KEY_IDENTITY_2FA_AUTH_APP, "")?.trim().orEmpty(),
            familyRole = normalizeFamilyRole(
                prefs.getString(KEY_IDENTITY_FAMILY_ROLE, ROLE_PARENT).orEmpty()
            ),
            guardianEmail = prefs.getString(KEY_IDENTITY_GUARDIAN_EMAIL, "")?.trim()?.lowercase(Locale.US).orEmpty(),
            childBirthYear = normalizeChildBirthYear(
                prefs.getInt(KEY_IDENTITY_CHILD_BIRTH_YEAR, 0)
            ),
            onboardingComplete = prefs.getBoolean(KEY_IDENTITY_ONBOARDING_COMPLETE, false)
        )
    }

    fun writeProfile(
        context: Context,
        identityLabel: String,
        primaryEmail: String,
        twoFactorMethod: String,
        twoFactorPhone: String,
        twoFactorAuthApp: String,
        familyRole: String = ROLE_PARENT,
        guardianEmail: String = "",
        childBirthYear: Int = 0,
        onboardingComplete: Boolean = true
    ): IdentityProfile {
        val normalized = primaryEmail.trim().lowercase(Locale.US)
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val normalizedMethod = normalizeTwoFactorMethod(twoFactorMethod)
        val normalizedRole = normalizeFamilyRole(familyRole)
        val normalizedGuardian = guardianEmail.trim().lowercase(Locale.US)
        val normalizedBirthYear = normalizeChildBirthYear(childBirthYear)
        val profile = IdentityProfile(
            identityLabel = identityLabel.trim(),
            primaryEmail = normalized,
            emailLinkedAtEpochMs = prefs.getLong(KEY_IDENTITY_EMAIL_LINKED_AT, 0L).coerceAtLeast(0L),
            twoFactorMethod = normalizedMethod,
            twoFactorPhone = twoFactorPhone.trim(),
            twoFactorAuthApp = twoFactorAuthApp.trim(),
            familyRole = normalizedRole,
            guardianEmail = if (normalizedRole == ROLE_CHILD) normalizedGuardian else "",
            childBirthYear = if (normalizedRole == ROLE_CHILD) normalizedBirthYear else 0,
            onboardingComplete = onboardingComplete
        )
        prefs.edit()
            .putString(KEY_IDENTITY_LABEL, profile.identityLabel)
            .putString(KEY_PRIMARY_EMAIL, profile.primaryEmail)
            .putString(KEY_IDENTITY_2FA_METHOD, profile.twoFactorMethod)
            .putString(KEY_IDENTITY_2FA_PHONE, profile.twoFactorPhone)
            .putString(KEY_IDENTITY_2FA_AUTH_APP, profile.twoFactorAuthApp)
            .putString(KEY_IDENTITY_FAMILY_ROLE, profile.familyRole)
            .putString(KEY_IDENTITY_GUARDIAN_EMAIL, profile.guardianEmail)
            .putInt(KEY_IDENTITY_CHILD_BIRTH_YEAR, profile.childBirthYear)
            .putBoolean(KEY_IDENTITY_ONBOARDING_COMPLETE, profile.onboardingComplete)
            .apply()
        return profile
    }

    fun markEmailLinkedNow(context: Context, email: String) {
        val profile = readProfile(context)
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PRIMARY_EMAIL, email.trim().lowercase(Locale.US))
            .putLong(KEY_IDENTITY_EMAIL_LINKED_AT, now)
            .putBoolean(KEY_IDENTITY_ONBOARDING_COMPLETE, true)
            .apply()
        if (profile.identityLabel.isBlank()) {
            prefs.edit().putString(KEY_IDENTITY_LABEL, defaultIdentityLabel(now)).apply()
        }
    }

    fun clearEmailLinkedAt(context: Context) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_IDENTITY_EMAIL_LINKED_AT, 0L).apply()
    }

    fun hasCompletedOnboarding(context: Context): Boolean {
        val profile = readProfile(context)
        return profile.onboardingComplete
    }

    fun defaultIdentityLabel(seed: Long = System.currentTimeMillis()): String {
        return "device-${seed.toString(16).takeLast(6)}"
    }

    private fun normalizeTwoFactorMethod(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            TWO_FACTOR_SMS -> TWO_FACTOR_SMS
            TWO_FACTOR_AUTH_APP -> TWO_FACTOR_AUTH_APP
            else -> TWO_FACTOR_EMAIL
        }
    }

    fun normalizeFamilyRole(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            "son", "kid", ROLE_CHILD -> ROLE_CHILD
            else -> ROLE_PARENT
        }
    }

    fun resolveAgeYears(profile: IdentityProfile, nowEpochMs: Long = System.currentTimeMillis()): Int {
        if (normalizeFamilyRole(profile.familyRole) != ROLE_CHILD) {
            return -1
        }
        val birthYear = normalizeChildBirthYear(profile.childBirthYear)
        if (birthYear <= 0) {
            return -1
        }
        val currentYear = Calendar.getInstance().apply { timeInMillis = nowEpochMs }.get(Calendar.YEAR)
        return (currentYear - birthYear).coerceAtLeast(0)
    }

    private fun normalizeChildBirthYear(rawYear: Int): Int {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        if (rawYear < 1900 || rawYear > currentYear) {
            return 0
        }
        return rawYear
    }
}
