package com.realyn.watchdog

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object PinFallbackStore {

    private const val KEY_PIN_HASH = "app_lock_pin_hash"
    private const val KEY_PIN_SALT = "app_lock_pin_salt"
    private const val KEY_PIN_UPDATED_AT = "app_lock_pin_updated_at"
    private const val MIN_PIN_LENGTH = 4
    private const val MAX_PIN_LENGTH = 10

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
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = digestPin(value, salt)
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PIN_HASH, Base64.encodeToString(digest, Base64.NO_WRAP))
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
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

        val candidate = digestPin(pin.trim(), storedSalt)
        return MessageDigest.isEqual(storedHash, candidate)
    }

    fun clearPin(context: Context) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_UPDATED_AT)
            .apply()
    }

    private fun digestPin(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }
}
