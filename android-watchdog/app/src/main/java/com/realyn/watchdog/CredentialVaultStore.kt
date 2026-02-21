package com.realyn.watchdog

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PasswordHistoryEntry(
    val password: String,
    val label: String,
    val createdAtEpochMs: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("password", password)
            .put("label", label)
            .put("createdAtEpochMs", createdAtEpochMs)
    }

    companion object {
        fun fromJson(item: JSONObject): PasswordHistoryEntry {
            return PasswordHistoryEntry(
                password = item.optString("password"),
                label = item.optString("label"),
                createdAtEpochMs = item.optLong("createdAtEpochMs", System.currentTimeMillis())
            )
        }
    }
}

data class StoredCredential(
    val recordId: String,
    val owner: String,
    val category: String,
    val service: String,
    val username: String,
    val url: String,
    val currentPassword: String,
    val pendingPassword: String?,
    val history: List<PasswordHistoryEntry>,
    val compromised: Boolean,
    val breachCount: Int,
    val lastCheckedAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    fun toJson(): JSONObject {
        val historyArray = JSONArray()
        history.forEach { historyArray.put(it.toJson()) }

        val root = JSONObject()
            .put("recordId", recordId)
            .put("owner", owner)
            .put("category", category)
            .put("service", service)
            .put("username", username)
            .put("url", url)
            .put("currentPassword", currentPassword)
            .put("history", historyArray)
            .put("compromised", compromised)
            .put("breachCount", breachCount)
            .put("lastCheckedAtEpochMs", lastCheckedAtEpochMs)
            .put("updatedAtEpochMs", updatedAtEpochMs)

        if (!pendingPassword.isNullOrBlank()) {
            root.put("pendingPassword", pendingPassword)
        }

        return root
    }

    companion object {
        fun fromJson(item: JSONObject): StoredCredential {
            val historyItems = mutableListOf<PasswordHistoryEntry>()
            val historyArray = item.optJSONArray("history") ?: JSONArray()
            for (index in 0 until historyArray.length()) {
                val historyItem = historyArray.optJSONObject(index) ?: continue
                historyItems += PasswordHistoryEntry.fromJson(historyItem)
            }

            return StoredCredential(
                recordId = item.optString("recordId"),
                owner = CredentialPolicy.canonicalOwnerId(item.optString("owner")),
                category = item.optString("category", "other"),
                service = item.optString("service"),
                username = item.optString("username"),
                url = item.optString("url"),
                currentPassword = item.optString("currentPassword"),
                pendingPassword = item.optString("pendingPassword").takeIf { it.isNotBlank() },
                history = historyItems,
                compromised = item.optBoolean("compromised", false),
                breachCount = item.optInt("breachCount", 0),
                lastCheckedAtEpochMs = item.optLong("lastCheckedAtEpochMs", 0L),
                updatedAtEpochMs = item.optLong("updatedAtEpochMs", System.currentTimeMillis())
            )
        }
    }
}

object CredentialVaultStore {

    private const val VAULT_ALIAS = "dt_credential_vault_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    private const val MAX_HISTORY_ENTRIES = 20

    private fun vaultFile(context: Context): File = File(context.filesDir, WatchdogConfig.CREDENTIAL_SECRET_VAULT_FILE)

    @Synchronized
    fun loadRecords(context: Context): List<StoredCredential> {
        return loadVault(context).sortedWith(
            compareBy<StoredCredential> { it.category }
                .thenBy { it.service.lowercase(Locale.US) }
                .thenBy { it.username.lowercase(Locale.US) }
        )
    }

    @Synchronized
    fun findRecord(context: Context, service: String, username: String): StoredCredential? {
        val normalizedService = normalizeService(service)
        val normalizedUsername = normalizeUsername(username)
        return loadVault(context).firstOrNull {
            normalizeService(it.service) == normalizedService && normalizeUsername(it.username) == normalizedUsername
        }
    }

    @Synchronized
    fun saveCurrentCredential(
        context: Context,
        owner: String,
        category: String,
        service: String,
        username: String,
        url: String,
        currentPassword: String
    ): StoredCredential {
        val now = System.currentTimeMillis()
        val records = loadVault(context).toMutableList()
        val existing = findByIdentity(records, service, username)

        val updated = if (existing == null) {
            val recordId = stableRecordId(owner, service, username)
            StoredCredential(
                recordId = recordId,
                owner = owner,
                category = category,
                service = service.trim(),
                username = username.trim(),
                url = url.trim(),
                currentPassword = currentPassword,
                pendingPassword = null,
                history = listOf(PasswordHistoryEntry(currentPassword, "saved_current", now)),
                compromised = false,
                breachCount = 0,
                lastCheckedAtEpochMs = 0L,
                updatedAtEpochMs = now
            )
        } else {
            existing.copy(
                owner = owner,
                category = category,
                service = service.trim(),
                username = username.trim(),
                url = url.trim(),
                currentPassword = currentPassword,
                history = appendHistory(existing.history, currentPassword, "saved_current", now),
                updatedAtEpochMs = now
            )
        }

        replaceRecord(records, updated)
        saveVault(context, records)
        return updated
    }

    @Synchronized
    fun prepareRotation(
        context: Context,
        owner: String,
        category: String,
        service: String,
        username: String,
        url: String,
        currentPassword: String,
        nextPassword: String
    ): StoredCredential {
        val now = System.currentTimeMillis()
        val records = loadVault(context).toMutableList()
        val existing = findByIdentity(records, service, username)
        val recordId = existing?.recordId ?: stableRecordId(owner, service, username)

        val currentHistory = appendHistory(existing?.history.orEmpty(), currentPassword, "saved_current", now)
        val finalHistory = appendHistory(currentHistory, nextPassword, "generated_for_rotation", now)

        val updated = StoredCredential(
            recordId = recordId,
            owner = owner,
            category = category,
            service = service.trim(),
            username = username.trim(),
            url = url.trim(),
            currentPassword = currentPassword,
            pendingPassword = nextPassword,
            history = finalHistory,
            compromised = existing?.compromised ?: false,
            breachCount = existing?.breachCount ?: 0,
            lastCheckedAtEpochMs = existing?.lastCheckedAtEpochMs ?: 0L,
            updatedAtEpochMs = now
        )

        replaceRecord(records, updated)
        saveVault(context, records)
        return updated
    }

    @Synchronized
    fun updateBreachStatus(
        context: Context,
        recordId: String,
        pwnedCount: Int,
        checkedAtEpochMs: Long
    ) {
        val records = loadVault(context).toMutableList()
        val index = records.indexOfFirst { it.recordId == recordId }
        if (index < 0) {
            return
        }

        val existing = records[index]
        records[index] = existing.copy(
            compromised = pwnedCount > 0,
            breachCount = pwnedCount,
            lastCheckedAtEpochMs = checkedAtEpochMs,
            updatedAtEpochMs = checkedAtEpochMs
        )

        saveVault(context, records)
    }

    fun latestDistinctPreviousPassword(record: StoredCredential): String? {
        val current = record.currentPassword
        return record.history
            .asReversed()
            .map { it.password }
            .firstOrNull { it.isNotBlank() && it != current }
    }

    @Synchronized
    fun promotePendingToCurrent(
        context: Context,
        service: String,
        username: String
    ): StoredCredential? {
        val records = loadVault(context).toMutableList()
        val existing = findByIdentity(records, service, username) ?: return null
        val pending = existing.pendingPassword?.takeIf { it.isNotBlank() } ?: return null
        val now = System.currentTimeMillis()

        val updated = existing.copy(
            currentPassword = pending,
            pendingPassword = null,
            history = appendHistory(existing.history, pending, "rotation_confirmed", now),
            compromised = false,
            updatedAtEpochMs = now
        )

        replaceRecord(records, updated)
        saveVault(context, records)
        return updated
    }

    fun formatRecordSummary(record: StoredCredential): String {
        val checkedAt = if (record.lastCheckedAtEpochMs > 0L) {
            formatDisplayTime(record.lastCheckedAtEpochMs)
        } else {
            "never"
        }
        val pendingState = if (record.pendingPassword.isNullOrBlank()) "none" else "queued"
        return "Stored: yes\nPending rotation: $pendingState\nBreach count: ${record.breachCount}\nLast breach scan: $checkedAt\nHistory entries: ${record.history.size}"
    }

    private fun findByIdentity(
        records: List<StoredCredential>,
        service: String,
        username: String
    ): StoredCredential? {
        val normalizedService = normalizeService(service)
        val normalizedUsername = normalizeUsername(username)
        return records.firstOrNull {
            normalizeService(it.service) == normalizedService && normalizeUsername(it.username) == normalizedUsername
        }
    }

    private fun replaceRecord(records: MutableList<StoredCredential>, record: StoredCredential) {
        val existingIndex = records.indexOfFirst { it.recordId == record.recordId }
        if (existingIndex >= 0) {
            records[existingIndex] = record
            return
        }

        val identityIndex = records.indexOfFirst {
            normalizeService(it.service) == normalizeService(record.service) &&
                normalizeUsername(it.username) == normalizeUsername(record.username)
        }

        if (identityIndex >= 0) {
            records[identityIndex] = record
        } else {
            records += record
        }
    }

    private fun appendHistory(
        history: List<PasswordHistoryEntry>,
        password: String,
        label: String,
        now: Long
    ): List<PasswordHistoryEntry> {
        if (password.isBlank()) {
            return history.takeLast(MAX_HISTORY_ENTRIES)
        }

        val last = history.lastOrNull()
        if (last != null && last.password == password) {
            val updated = history.dropLast(1) + last.copy(label = label, createdAtEpochMs = now)
            return updated.takeLast(MAX_HISTORY_ENTRIES)
        }

        return (history + PasswordHistoryEntry(password, label, now)).takeLast(MAX_HISTORY_ENTRIES)
    }

    private fun loadVault(context: Context): List<StoredCredential> {
        val file = vaultFile(context)
        if (!file.exists()) {
            return emptyList()
        }

        val encrypted = runCatching { file.readBytes() }.getOrNull() ?: return emptyList()
        if (encrypted.isEmpty()) {
            return emptyList()
        }

        val decryptedBytes = runCatching { decrypt(encrypted) }.getOrNull() ?: return emptyList()
        val payload = runCatching { JSONObject(String(decryptedBytes, Charsets.UTF_8)) }.getOrNull()
            ?: return emptyList()

        val recordsArray = payload.optJSONArray("records") ?: JSONArray()
        val items = mutableListOf<StoredCredential>()
        for (index in 0 until recordsArray.length()) {
            val item = recordsArray.optJSONObject(index) ?: continue
            items += StoredCredential.fromJson(item)
        }
        return items
    }

    private fun saveVault(context: Context, records: List<StoredCredential>) {
        val payload = JSONObject()
            .put("version", 1)
            .put("updatedAtEpochMs", System.currentTimeMillis())

        val recordsArray = JSONArray()
        records.forEach { recordsArray.put(it.toJson()) }
        payload.put("records", recordsArray)

        val plaintext = payload.toString(2).toByteArray(Charsets.UTF_8)
        val encrypted = encrypt(plaintext)
        vaultFile(context).writeBytes(encrypted)
    }

    private fun encrypt(plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainBytes)

        val buffer = ByteBuffer.allocate(4 + iv.size + cipherBytes.size)
        buffer.putInt(iv.size)
        buffer.put(iv)
        buffer.put(cipherBytes)
        return buffer.array()
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(payload)
        val ivSize = buffer.int
        if (ivSize <= 0 || ivSize > 32) {
            throw IllegalStateException("invalid iv size")
        }

        val iv = ByteArray(ivSize)
        buffer.get(iv)

        val cipherBytes = ByteArray(buffer.remaining())
        buffer.get(cipherBytes)

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val params = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), params)
        return cipher.doFinal(cipherBytes)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(VAULT_ALIAS, null)
        if (existing is SecretKey) {
            return existing
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                VAULT_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun stableRecordId(owner: String, service: String, username: String): String {
        val ownerHashKey = CredentialPolicy.ownerHashKey(owner)
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest("$ownerHashKey|${normalizeService(service)}|${normalizeUsername(username)}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    private fun normalizeService(service: String): String {
        return service.trim().lowercase(Locale.US)
    }

    private fun normalizeUsername(username: String): String {
        return username.trim().lowercase(Locale.US)
    }

    private fun formatDisplayTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(epochMs))
    }
}
