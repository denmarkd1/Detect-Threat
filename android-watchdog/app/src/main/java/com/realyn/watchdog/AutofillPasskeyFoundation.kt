package com.realyn.watchdog

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.credentials.CredentialManager

data class AutofillPasskeyStatus(
    val autofillSupported: Boolean,
    val autofillEnabled: Boolean,
    val credentialManagerReady: Boolean,
    val passkeyReady: Boolean
) {
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

    fun openAutofillSettings(activity: Activity): Boolean {
        val candidates = listOf(
            Intent("android.settings.AUTOFILL_SETTINGS"),
            Intent(Settings.ACTION_PRIVACY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        return launchFirstAvailable(activity, candidates)
    }

    fun openPasskeyProviderSettings(activity: Activity): Boolean {
        val candidates = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            candidates += Intent("android.settings.CREDENTIAL_PROVIDER")
        }
        candidates += Intent("android.settings.PASSWORDS_SETTINGS")
        candidates += Intent("android.settings.AUTOFILL_SETTINGS")
        candidates += Intent(Settings.ACTION_SETTINGS)
        return launchFirstAvailable(activity, candidates)
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
