package com.realyn.watchdog

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.credentials.CredentialManager
import java.util.Locale

data class AutofillPasskeyStatus(
    val autofillSupported: Boolean,
    val autofillEnabled: Boolean,
    val credentialManagerReady: Boolean,
    val passkeyReady: Boolean
) {
    fun foundationReady(): Boolean {
        return !autofillSupported || (autofillEnabled && credentialManagerReady && passkeyReady)
    }

    fun summary(): String {
        val autofillState = if (!autofillSupported) {
            "unsupported"
        } else if (autofillEnabled) {
            "enabled"
        } else {
            "disabled"
        }
        val credentialState = if (credentialManagerReady) "ready" else "unavailable"
        val passkeyState = if (passkeyReady) "ready" else "setup_needed"
        return "Autofill: $autofillState | Credential Manager: $credentialState | Passkey: $passkeyState"
    }
}

object AutofillPasskeyFoundation {

    enum class OemPack {
        MIUI,
        SAMSUNG,
        PIXEL,
        GENERIC
    }

    fun evaluate(activity: Activity): AutofillPasskeyStatus {
        val manager = activity.getSystemService(AutofillManager::class.java)
        val autofillSupported = manager?.isAutofillSupported == true
        val autofillEnabled = manager?.hasEnabledAutofillServices() == true
        val credentialReady = runCatching {
            CredentialManager.create(activity)
            true
        }.getOrDefault(false)
        val passkeyReady = credentialReady &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            autofillSupported &&
            autofillEnabled
        return AutofillPasskeyStatus(
            autofillSupported = autofillSupported,
            autofillEnabled = autofillEnabled,
            credentialManagerReady = credentialReady,
            passkeyReady = passkeyReady
        )
    }

    fun resolveOemPack(): OemPack {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.US)
        val brand = Build.BRAND.orEmpty().lowercase(Locale.US)
        return when {
            manufacturer.contains("xiaomi") ||
                manufacturer.contains("redmi") ||
                manufacturer.contains("poco") ||
                brand.contains("xiaomi") ||
                brand.contains("redmi") ||
                brand.contains("poco") -> OemPack.MIUI
            manufacturer.contains("samsung") || brand.contains("samsung") -> OemPack.SAMSUNG
            manufacturer.contains("google") || brand.contains("google") -> OemPack.PIXEL
            else -> OemPack.GENERIC
        }
    }

    fun openDeviceSettingsRoot(activity: Activity): Boolean {
        return launchFirstAvailable(activity, listOf(Intent(Settings.ACTION_SETTINGS)))
    }

    private fun launchFirstAvailable(activity: Activity, candidates: List<Intent>): Boolean {
        candidates.forEach { intent ->
            val safeIntent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val canHandle = safeIntent.resolveActivity(activity.packageManager) != null
            if (!canHandle) {
                return@forEach
            }
            val opened = runCatching { activity.startActivity(safeIntent); true }.getOrDefault(false)
            if (opened) {
                return true
            }
        }
        return false
    }
}
