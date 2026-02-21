package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class VaultRetentionState(val raw: String) {
    ACTIVE("active"),
    DELETED("deleted");

    companion object {
        fun fromRaw(value: String): VaultRetentionState {
            return entries.firstOrNull { it.raw.equals(value.trim(), ignoreCase = true) }
                ?: ACTIVE
        }
    }
}

data class VaultItem(
    val id: String,
    val type: String,
    val ownerRole: String,
    val mimeType: String,
    val encryptedBlobName: String,
    val byteSize: Long,
    val createdAtIso: String,
    val createdAtEpochMs: Long,
    val lastAccessIso: String,
    val lastAccessEpochMs: Long,
    val retentionState: String,
    val deletedAtEpochMs: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("type", type)
            .put("ownerRole", ownerRole)
            .put("mimeType", mimeType)
            .put("encryptedBlobName", encryptedBlobName)
            .put("byteSize", byteSize)
            .put("createdAtIso", createdAtIso)
            .put("createdAtEpochMs", createdAtEpochMs)
            .put("lastAccessIso", lastAccessIso)
            .put("lastAccessEpochMs", lastAccessEpochMs)
            .put("retentionState", retentionState)
            .put("deletedAtEpochMs", deletedAtEpochMs)
    }

    companion object {
        fun fromJson(payload: JSONObject): VaultItem {
            val now = System.currentTimeMillis()
            val createdAt = payload.optLong("createdAtEpochMs", now).coerceAtLeast(0L)
            val lastAccess = payload.optLong("lastAccessEpochMs", createdAt).coerceAtLeast(0L)
            return VaultItem(
                id = payload.optString("id").trim(),
                type = payload.optString("type", "file").trim().ifBlank { "file" },
                ownerRole = payload.optString("ownerRole", "parent").trim().ifBlank { "parent" },
                mimeType = payload.optString("mimeType", "application/octet-stream")
                    .trim()
                    .ifBlank { "application/octet-stream" },
                encryptedBlobName = payload.optString("encryptedBlobName").trim(),
                byteSize = payload.optLong("byteSize", 0L).coerceAtLeast(0L),
                createdAtIso = payload.optString("createdAtIso", toIsoUtc(createdAt)).trim(),
                createdAtEpochMs = createdAt,
                lastAccessIso = payload.optString("lastAccessIso", toIsoUtc(lastAccess)).trim(),
                lastAccessEpochMs = lastAccess,
                retentionState = VaultRetentionState.fromRaw(
                    payload.optString("retentionState", VaultRetentionState.ACTIVE.raw)
                ).raw,
                deletedAtEpochMs = payload.optLong("deletedAtEpochMs", 0L).coerceAtLeast(0L)
            )
        }

        private fun toIsoUtc(epochMs: Long): String {
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(epochMs))
        }
    }
}

data class MediaVaultSummary(
    val activeCount: Int,
    val deletedCount: Int,
    val lastUpdatedIso: String
)

object MediaVaultIndexStore {

    @Synchronized
    fun readItems(context: Context): List<VaultItem> {
        return loadIndex(context)
            .sortedByDescending { it.createdAtEpochMs }
    }

    @Synchronized
    fun readActiveItems(context: Context): List<VaultItem> {
        return readItems(context)
            .filter { VaultRetentionState.fromRaw(it.retentionState) == VaultRetentionState.ACTIVE }
    }

    @Synchronized
    fun readDeletedItems(context: Context): List<VaultItem> {
        return readItems(context)
            .filter { VaultRetentionState.fromRaw(it.retentionState) == VaultRetentionState.DELETED }
    }

    @Synchronized
    fun summary(context: Context): MediaVaultSummary {
        val items = readItems(context)
        val active = items.count { VaultRetentionState.fromRaw(it.retentionState) == VaultRetentionState.ACTIVE }
        val deleted = items.size - active
        val latestEpoch = items.maxOfOrNull { maxOf(it.lastAccessEpochMs, it.createdAtEpochMs) } ?: 0L
        return MediaVaultSummary(
            activeCount = active,
            deletedCount = deleted,
            lastUpdatedIso = if (latestEpoch > 0L) toIsoUtc(latestEpoch) else ""
        )
    }

    @Synchronized
    fun findById(context: Context, itemId: String): VaultItem? {
        return loadIndex(context).firstOrNull { it.id == itemId }
    }

    @Synchronized
    fun saveImportedItem(context: Context, item: VaultItem) {
        val items = loadIndex(context).toMutableList()
        replaceOrAppend(items, item)
        saveIndex(context, items)
    }

    @Synchronized
    fun touchAccess(context: Context, itemId: String, touchedAtEpochMs: Long = System.currentTimeMillis()): VaultItem? {
        val items = loadIndex(context).toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return null
        }
        val existing = items[index]
        val updated = existing.copy(
            lastAccessEpochMs = touchedAtEpochMs,
            lastAccessIso = toIsoUtc(touchedAtEpochMs)
        )
        items[index] = updated
        saveIndex(context, items)
        return updated
    }

    @Synchronized
    fun markDeleted(context: Context, itemId: String, deletedAtEpochMs: Long = System.currentTimeMillis()): VaultItem? {
        val items = loadIndex(context).toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return null
        }
        val existing = items[index]
        val updated = existing.copy(
            retentionState = VaultRetentionState.DELETED.raw,
            deletedAtEpochMs = deletedAtEpochMs,
            lastAccessEpochMs = deletedAtEpochMs,
            lastAccessIso = toIsoUtc(deletedAtEpochMs)
        )
        items[index] = updated
        saveIndex(context, items)
        return updated
    }

    @Synchronized
    fun restore(context: Context, itemId: String, restoredAtEpochMs: Long = System.currentTimeMillis()): VaultItem? {
        val items = loadIndex(context).toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return null
        }
        val existing = items[index]
        val updated = existing.copy(
            retentionState = VaultRetentionState.ACTIVE.raw,
            deletedAtEpochMs = 0L,
            lastAccessEpochMs = restoredAtEpochMs,
            lastAccessIso = toIsoUtc(restoredAtEpochMs)
        )
        items[index] = updated
        saveIndex(context, items)
        return updated
    }

    @Synchronized
    fun removeItem(context: Context, itemId: String): VaultItem? {
        val items = loadIndex(context).toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) {
            return null
        }
        val removed = items.removeAt(index)
        saveIndex(context, items)
        return removed
    }

    private fun loadIndex(context: Context): List<VaultItem> {
        val file = indexFile(context)
        if (!file.exists()) {
            return emptyList()
        }
        val encrypted = runCatching { file.readBytes() }.getOrNull() ?: return emptyList()
        if (encrypted.isEmpty()) {
            return emptyList()
        }

        val decrypted = runCatching { MediaVaultCrypto.decrypt(encrypted) }.getOrNull() ?: return emptyList()
        val payload = runCatching { JSONObject(String(decrypted, Charsets.UTF_8)) }.getOrNull()
            ?: return emptyList()
        val itemsArray = payload.optJSONArray("items") ?: JSONArray()
        val items = mutableListOf<VaultItem>()
        for (index in 0 until itemsArray.length()) {
            val item = itemsArray.optJSONObject(index) ?: continue
            val parsed = runCatching { VaultItem.fromJson(item) }.getOrNull() ?: continue
            if (parsed.id.isBlank() || parsed.encryptedBlobName.isBlank()) {
                continue
            }
            items += parsed
        }
        return items
    }

    private fun saveIndex(context: Context, items: List<VaultItem>) {
        val payload = JSONObject()
            .put("version", 1)
            .put("updatedAtEpochMs", System.currentTimeMillis())
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        payload.put("items", array)

        val encrypted = MediaVaultCrypto.encrypt(payload.toString(2).toByteArray(Charsets.UTF_8))
        indexFile(context).writeBytes(encrypted)
    }

    private fun replaceOrAppend(items: MutableList<VaultItem>, item: VaultItem) {
        val existingIndex = items.indexOfFirst { it.id == item.id }
        if (existingIndex >= 0) {
            items[existingIndex] = item
        } else {
            items += item
        }
    }

    private fun indexFile(context: Context): File {
        return File(context.filesDir, WatchdogConfig.MEDIA_VAULT_INDEX_FILE)
    }

    private fun toIsoUtc(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMs))
    }
}
