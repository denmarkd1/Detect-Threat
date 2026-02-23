package com.realyn.watchdog

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinFallbackStore {

    private const val KEY_PIN_HASH = "app_lock_pin_hash"
    private const val KEY_PIN_SALT = "app_lock_pin_salt"
    private const val KEY_PIN_UPDATED_AT = "app_lock_pin_updated_at"
    private const val KEY_PIN_KDF_VERSION = "app_lock_pin_kdf_version"
    private const val KEY_PIN_KDF_ITERATIONS = "app_lock_pin_kdf_iterations"
    private const val MIN_PIN_LENGTH = 4
    private const val MAX_PIN_LENGTH = 10
    private const val SALT_BYTES = 16
    private const val KDF_VERSION_LEGACY_SHA256 = 1
    private const val KDF_VERSION_PBKDF2_SHA256 = 2
    private const val PBKDF2_ITERATIONS = 120_000
    private const val PBKDF2_KEY_LENGTH_BITS = 256

    fun isPinConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PIN_HASH, null).isNullOrBlank().not() &&
            prefs.getString(KEY_PIN_SALT, null).isNullOrBlank().not()
    }

    fun isValidPinFormat(pin: String): Boolean {
        val value = pin.trim()
        if (value.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
            return false
        }
        return value.all { it.isDigit() }
    }

    fun savePin(context: Context, pin: String): Boolean {
        val value = pin.trim()
        if (!isValidPinFormat(value)) {
            return false
        }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val digest = derivePinPbkdf2(value, salt, PBKDF2_ITERATIONS)
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PIN_HASH, Base64.encodeToString(digest, Base64.NO_WRAP))
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt(KEY_PIN_KDF_VERSION, KDF_VERSION_PBKDF2_SHA256)
            .putInt(KEY_PIN_KDF_ITERATIONS, PBKDF2_ITERATIONS)
            .putLong(KEY_PIN_UPDATED_AT, System.currentTimeMillis())
            .apply()
        return true
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val storedHashB64 = prefs.getString(KEY_PIN_HASH, null).orEmpty()
        val storedSaltB64 = prefs.getString(KEY_PIN_SALT, null).orEmpty()
        if (storedHashB64.isBlank() || storedSaltB64.isBlank()) {
            return false
        }

        val storedHash = runCatching { Base64.decode(storedHashB64, Base64.DEFAULT) }.getOrNull() ?: return false
        val storedSalt = runCatching { Base64.decode(storedSaltB64, Base64.DEFAULT) }.getOrNull() ?: return false
        val version = prefs.getInt(KEY_PIN_KDF_VERSION, KDF_VERSION_LEGACY_SHA256)
        val storedIterations = prefs.getInt(KEY_PIN_KDF_ITERATIONS, PBKDF2_ITERATIONS)
            .coerceIn(40_000, 500_000)
        val normalizedPin = pin.trim()

        val candidate = when (version) {
            KDF_VERSION_PBKDF2_SHA256 -> derivePinPbkdf2(normalizedPin, storedSalt, storedIterations)
            else -> digestPinLegacy(normalizedPin, storedSalt)
        }
        val matched = MessageDigest.isEqual(storedHash, candidate)
        if (!matched) {
            return false
        }

        if (version != KDF_VERSION_PBKDF2_SHA256) {
            savePin(context, normalizedPin)
        }
        return true
    }

    fun clearPin(context: Context) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_KDF_VERSION)
            .remove(KEY_PIN_KDF_ITERATIONS)
            .remove(KEY_PIN_UPDATED_AT)
            .apply()
    }

    private fun digestPinLegacy(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    private fun derivePinPbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, PBKDF2_KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }
}
