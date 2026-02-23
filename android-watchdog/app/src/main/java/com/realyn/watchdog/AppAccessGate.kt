package com.realyn.watchdog

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

data class AppLockSettings(
    val enabled: Boolean,
    val allowBiometric: Boolean,
    val allowPinFallback: Boolean,
    val idleRelockSeconds: Int
)

object AppAccessGate {

    private val defaults = AppLockSettings(
        enabled = true,
        allowBiometric = true,
        allowPinFallback = true,
        idleRelockSeconds = 120
    )

    private var authInProgress: Boolean = false

    fun ensureUnlocked(
        activity: AppCompatActivity,
        onUnlocked: () -> Unit,
        onDenied: () -> Unit
    ) {
        val settings = loadSettings(activity)
        if (!settings.enabled) {
            SessionUnlockState.markUnlocked()
            onUnlocked()
            return
        }

        val relockSeconds = resolveEffectiveRelockSeconds(activity, settings)
        if (SessionUnlockState.isUnlocked() && !SessionUnlockState.shouldRequireUnlock(relockSeconds)) {
            SessionUnlockState.markUserInteraction()
            onUnlocked()
            return
        }

        if (authInProgress) {
            return
        }

        authInProgress = true
        runUnlockFlow(
            activity = activity,
            settings = settings,
            onUnlocked = {
                SessionUnlockState.markUnlocked()
                authInProgress = false
                onUnlocked()
            },
            onDenied = {
                SessionUnlockState.clear()
                authInProgress = false
                onDenied()
            }
        )
    }

    fun onAppBackgrounded(context: Context? = null) {
        SessionUnlockState.markBackgrounded()
        if (context != null) {
            GuardianOverrideTokenStore.clearAllTokens(context)
        }
    }

    fun onUserInteraction() {
        SessionUnlockState.markUserInteraction()
    }

    private fun runUnlockFlow(
        activity: AppCompatActivity,
        settings: AppLockSettings,
        onUnlocked: () -> Unit,
        onDenied: () -> Unit
    ) {
        val canUseBiometric = settings.allowBiometric && BiometricAuthController.resolveCapability(
            activity = activity,
            allowDeviceCredential = true
        ).available

        if (canUseBiometric) {
            BiometricAuthController.authenticate(
                activity = activity,
                title = activity.getString(R.string.app_lock_prompt_title),
                subtitle = activity.getString(R.string.app_lock_prompt_subtitle),
                allowDeviceCredential = true,
                onSucceeded = onUnlocked,
                onError = { errorCode, _ ->
                    val userCancelled = errorCode == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == androidx.biometric.BiometricPrompt.ERROR_CANCELED
                    if (settings.allowPinFallback) {
                        showPinFallbackFlow(activity, onUnlocked, onDenied)
                    } else if (userCancelled) {
                        onDenied()
                    } else {
                        Toast.makeText(activity, R.string.app_lock_biometric_unavailable, Toast.LENGTH_SHORT).show()
                        onDenied()
                    }
                },
                onFailed = {
                    Toast.makeText(activity, R.string.app_lock_biometric_retry, Toast.LENGTH_SHORT).show()
                }
            )
            return
        }

        if (settings.allowPinFallback) {
            showPinFallbackFlow(activity, onUnlocked, onDenied)
        } else {
            Toast.makeText(activity, R.string.app_lock_no_unlock_method, Toast.LENGTH_LONG).show()
            onDenied()
        }
    }

    private fun showPinFallbackFlow(
        activity: AppCompatActivity,
        onUnlocked: () -> Unit,
        onDenied: () -> Unit
    ) {
        if (!PinFallbackStore.isPinConfigured(activity)) {
            showPinSetupDialog(activity, onUnlocked, onDenied)
        } else {
            showPinEntryDialog(activity, onUnlocked, onDenied)
        }
    }

    private fun showPinSetupDialog(
        activity: AppCompatActivity,
        onUnlocked: () -> Unit,
        onDenied: () -> Unit
    ) {
        val pinInput = EditText(activity).apply {
            hint = activity.getString(R.string.app_lock_pin_input_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }
        val confirmInput = EditText(activity).apply {
            hint = activity.getString(R.string.app_lock_pin_confirm_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(pinInput)
            addView(confirmInput)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.app_lock_pin_setup_title)
            .setMessage(R.string.app_lock_pin_setup_message)
            .setView(layout)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDenied() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = pinInput.text?.toString().orEmpty().trim()
                val confirm = confirmInput.text?.toString().orEmpty().trim()

                if (!PinFallbackStore.isValidPinFormat(pin)) {
                    pinInput.error = activity.getString(R.string.app_lock_pin_invalid_format)
                    return@setOnClickListener
                }
                if (pin != confirm) {
                    confirmInput.error = activity.getString(R.string.app_lock_pin_mismatch)
                    return@setOnClickListener
                }
                if (!PinFallbackStore.savePin(activity, pin)) {
                    Toast.makeText(activity, R.string.app_lock_pin_save_failed, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                dialog.dismiss()
                onUnlocked()
            }
        }

        dialog.setOnCancelListener { onDenied() }
        dialog.show()
    }

    private fun showPinEntryDialog(
        activity: AppCompatActivity,
        onUnlocked: () -> Unit,
        onDenied: () -> Unit
    ) {
        val pinInput = EditText(activity).apply {
            hint = activity.getString(R.string.app_lock_pin_input_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }

        var attemptsRemaining = 5
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.app_lock_pin_entry_title)
            .setMessage(activity.getString(R.string.app_lock_pin_entry_message_template, attemptsRemaining))
            .setView(pinInput)
            .setPositiveButton(R.string.action_confirm, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDenied() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = pinInput.text?.toString().orEmpty().trim()
                if (PinFallbackStore.verifyPin(activity, pin)) {
                    dialog.dismiss()
                    onUnlocked()
                    return@setOnClickListener
                }

                attemptsRemaining -= 1
                if (attemptsRemaining <= 0) {
                    dialog.dismiss()
                    Toast.makeText(activity, R.string.app_lock_pin_attempts_exhausted, Toast.LENGTH_LONG).show()
                    onDenied()
                    return@setOnClickListener
                }

                pinInput.text?.clear()
                pinInput.error = activity.getString(R.string.app_lock_pin_invalid)
                dialog.setMessage(
                    activity.getString(R.string.app_lock_pin_entry_message_template, attemptsRemaining)
                )
            }
        }

        dialog.setOnCancelListener { onDenied() }
        dialog.show()
    }

    private fun loadSettings(context: AppCompatActivity): AppLockSettings {
        val payload = WorkspaceSettingsStore.readPayload(context)
        return parseSettings(payload?.optJSONObject("app_lock"))
    }

    private fun resolveEffectiveRelockSeconds(
        activity: AppCompatActivity,
        appLockSettings: AppLockSettings
    ): Int {
        val profileControl = PricingPolicy.resolveProfileControl(activity)
        if (!profileControl.requiresGuardianApprovalForSensitiveActions) {
            return appLockSettings.idleRelockSeconds
        }

        val childLimit = GuardianOverridePolicy.resolveChildIdleRelockSeconds(activity)
        return minOf(appLockSettings.idleRelockSeconds, childLimit)
    }

    private fun parseSettings(item: JSONObject?): AppLockSettings {
        if (item == null) {
            return defaults
        }

        return AppLockSettings(
            enabled = item.optBoolean("enabled", defaults.enabled),
            allowBiometric = item.optBoolean("allow_biometric", defaults.allowBiometric),
            allowPinFallback = item.optBoolean("allow_pin_fallback", defaults.allowPinFallback),
            idleRelockSeconds = item.optInt("idle_relock_seconds", defaults.idleRelockSeconds)
                .coerceIn(15, 15 * 60)
        )
    }
}
