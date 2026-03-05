package com.realyn.watchdog

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class PinLockoutState(
    val locked: Boolean,
    val attemptsRemaining: Int,
    val lockoutRemainingSeconds: Int
)

data class PinVerificationResult(
    val matched: Boolean,
    val state: PinLockoutState
)

object PinFallbackStore {

    private const val KEY_PIN_HASH = "app_lock_pin_hash"
    private const val KEY_PIN_SALT = "app_lock_pin_salt"
    private const val KEY_PIN_UPDATED_AT = "app_lock_pin_updated_at"
    private const val KEY_PIN_KDF_VERSION = "app_lock_pin_kdf_version"
    private const val KEY_PIN_KDF_ITERATIONS = "app_lock_pin_kdf_iterations"
    private const val KEY_PIN_FAILED_ATTEMPTS = "app_lock_pin_failed_attempts"
    private const val KEY_PIN_LOCKOUT_UNTIL = "app_lock_pin_lockout_until_epoch_ms"
    private const val KEY_PIN_LOCKOUT_LEVEL = "app_lock_pin_lockout_level"
    private const val MIN_PIN_LENGTH = 4
    private const val MAX_PIN_LENGTH = 10
    private const val SALT_BYTES = 16
    private const val MAX_FAILED_ATTEMPTS = 5
    private const val LOCKOUT_BASE_SECONDS = 60
    private const val LOCKOUT_MAX_SECONDS = 15 * 60
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
            .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
            .putInt(KEY_PIN_LOCKOUT_LEVEL, 0)
            .apply()
        return true
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        return verifyPinWithPolicy(context, pin).matched
    }

    fun verifyPinWithPolicy(context: Context, pin: String): PinVerificationResult {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var failedAttempts = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0).coerceAtLeast(0)
        var lockoutUntil = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L).coerceAtLeast(0L)
        var lockoutLevel = prefs.getInt(KEY_PIN_LOCKOUT_LEVEL, 0).coerceAtLeast(0)

        if (lockoutUntil > 0L && now >= lockoutUntil) {
            lockoutUntil = 0L
            failedAttempts = 0
            prefs.edit()
                .putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
                .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
                .apply()
        }

        if (lockoutUntil > now) {
            val remainingSeconds = ((lockoutUntil - now + 999L) / 1000L).toInt().coerceAtLeast(1)
            return PinVerificationResult(
                matched = false,
                state = PinLockoutState(
                    locked = true,
                    attemptsRemaining = 0,
                    lockoutRemainingSeconds = remainingSeconds
                )
            )
        }

        val storedHashB64 = prefs.getString(KEY_PIN_HASH, null).orEmpty()
        val storedSaltB64 = prefs.getString(KEY_PIN_SALT, null).orEmpty()
        if (storedHashB64.isBlank() || storedSaltB64.isBlank()) {
            return PinVerificationResult(
                matched = false,
                state = PinLockoutState(
                    locked = false,
                    attemptsRemaining = MAX_FAILED_ATTEMPTS,
                    lockoutRemainingSeconds = 0
                )
            )
        }

        val storedHash = runCatching { Base64.decode(storedHashB64, Base64.DEFAULT) }.getOrNull()
            ?: return PinVerificationResult(
                matched = false,
                state = PinLockoutState(
                    locked = false,
                    attemptsRemaining = MAX_FAILED_ATTEMPTS,
                    lockoutRemainingSeconds = 0
                )
            )
        val storedSalt = runCatching { Base64.decode(storedSaltB64, Base64.DEFAULT) }.getOrNull()
            ?: return PinVerificationResult(
                matched = false,
                state = PinLockoutState(
                    locked = false,
                    attemptsRemaining = MAX_FAILED_ATTEMPTS,
                    lockoutRemainingSeconds = 0
                )
            )
        val version = prefs.getInt(KEY_PIN_KDF_VERSION, KDF_VERSION_LEGACY_SHA256)
        val storedIterations = prefs.getInt(KEY_PIN_KDF_ITERATIONS, PBKDF2_ITERATIONS)
            .coerceIn(40_000, 500_000)
        val normalizedPin = pin.trim()

        val candidate = when (version) {
            KDF_VERSION_PBKDF2_SHA256 -> derivePinPbkdf2(normalizedPin, storedSalt, storedIterations)
            else -> digestPinLegacy(normalizedPin, storedSalt)
        }
        val matched = MessageDigest.isEqual(storedHash, candidate)
        if (matched) {
            prefs.edit()
                .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
                .putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
                .putInt(KEY_PIN_LOCKOUT_LEVEL, 0)
                .apply()
            if (version != KDF_VERSION_PBKDF2_SHA256) {
                savePin(context, normalizedPin)
            }
            return PinVerificationResult(
                matched = true,
                state = PinLockoutState(
                    locked = false,
                    attemptsRemaining = MAX_FAILED_ATTEMPTS,
                    lockoutRemainingSeconds = 0
                )
            )
        }

        failedAttempts += 1
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            val nextLevel = (lockoutLevel + 1).coerceAtMost(8)
            val seconds = lockoutSecondsForLevel(nextLevel)
            val until = now + (seconds * 1000L)
            prefs.edit()
                .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
                .putInt(KEY_PIN_LOCKOUT_LEVEL, nextLevel)
                .putLong(KEY_PIN_LOCKOUT_UNTIL, until)
                .apply()
            return PinVerificationResult(
                matched = false,
                state = PinLockoutState(
                    locked = true,
                    attemptsRemaining = 0,
                    lockoutRemainingSeconds = seconds
                )
            )
        }

        val attemptsRemaining = (MAX_FAILED_ATTEMPTS - failedAttempts).coerceAtLeast(0)
        prefs.edit()
            .putInt(KEY_PIN_FAILED_ATTEMPTS, failedAttempts)
            .putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
            .putInt(KEY_PIN_LOCKOUT_LEVEL, lockoutLevel)
            .apply()
        return PinVerificationResult(
            matched = false,
            state = PinLockoutState(
                locked = false,
                attemptsRemaining = attemptsRemaining,
                lockoutRemainingSeconds = 0
            )
        )
    }

    fun currentLockoutState(context: Context): PinLockoutState {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lockoutUntil = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L).coerceAtLeast(0L)
        if (lockoutUntil > 0L && now >= lockoutUntil) {
            prefs.edit()
                .putLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
                .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
                .apply()
            return PinLockoutState(
                locked = false,
                attemptsRemaining = MAX_FAILED_ATTEMPTS,
                lockoutRemainingSeconds = 0
            )
        }
        if (lockoutUntil > now) {
            val remainingSeconds = ((lockoutUntil - now + 999L) / 1000L).toInt().coerceAtLeast(1)
            return PinLockoutState(
                locked = true,
                attemptsRemaining = 0,
                lockoutRemainingSeconds = remainingSeconds
            )
        }
        val failedAttempts = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0).coerceAtLeast(0)
        return PinLockoutState(
            locked = false,
            attemptsRemaining = (MAX_FAILED_ATTEMPTS - failedAttempts).coerceAtLeast(0),
            lockoutRemainingSeconds = 0
        )
    }

    fun clearPin(context: Context) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_KDF_VERSION)
            .remove(KEY_PIN_KDF_ITERATIONS)
            .remove(KEY_PIN_UPDATED_AT)
            .remove(KEY_PIN_FAILED_ATTEMPTS)
            .remove(KEY_PIN_LOCKOUT_UNTIL)
            .remove(KEY_PIN_LOCKOUT_LEVEL)
            .apply()
    }

    private fun lockoutSecondsForLevel(level: Int): Int {
        val normalized = level.coerceIn(1, 8)
        val multiplier = 1 shl (normalized - 1)
        return (LOCKOUT_BASE_SECONDS * multiplier).coerceAtMost(LOCKOUT_MAX_SECONDS)
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
