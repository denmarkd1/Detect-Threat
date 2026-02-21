package com.realyn.watchdog

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthController {

    data class BiometricCapability(
        val available: Boolean,
        val errorCode: Int,
        val reason: String
    )

    fun resolveCapability(
        activity: FragmentActivity,
        allowDeviceCredential: Boolean
    ): BiometricCapability {
        val authenticators = if (allowDeviceCredential) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

        val manager = BiometricManager.from(activity)
        val result = manager.canAuthenticate(authenticators)
        val reason = when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> "available"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "hardware_unavailable"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no_hardware"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none_enrolled"
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "security_update_required"
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "unsupported"
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "unknown"
            else -> "unavailable"
        }
        return BiometricCapability(
            available = result == BiometricManager.BIOMETRIC_SUCCESS,
            errorCode = result,
            reason = reason
        )
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        allowDeviceCredential: Boolean,
        onSucceeded: () -> Unit,
        onError: (code: Int, message: String) -> Unit,
        onFailed: () -> Unit = {}
    ) {
        val authenticators = if (allowDeviceCredential) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSucceeded()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errorCode, errString.toString())
            }

            override fun onAuthenticationFailed() {
                onFailed()
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)

        if (!allowDeviceCredential) {
            promptBuilder.setNegativeButtonText(activity.getString(android.R.string.cancel))
        }

        prompt.authenticate(promptBuilder.build())
    }
}
