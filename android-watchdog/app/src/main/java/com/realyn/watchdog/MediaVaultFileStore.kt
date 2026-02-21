package com.realyn.watchdog

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object MediaVaultFileStore {

    private const val MAX_IMPORT_BYTES = 25 * 1024 * 1024L
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val PREVIEW_TTL_MS = 5 * 60 * 1000L

    @Throws(IllegalStateException::class)
    fun importFromUri(context: Context, sourceUri: Uri, ownerRole: String): VaultItem {
        val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
            ?: throw IllegalStateException("source_unreadable")
        if (bytes.isEmpty()) {
            throw IllegalStateException("source_empty")
        }
        if (bytes.size.toLong() > MAX_IMPORT_BYTES) {
            throw IllegalStateException("source_too_large")
        }

        val now = System.currentTimeMillis()
        val itemId = newItemId()
        val blobName = "$itemId.bin"
        val blobFile = blobFile(context, blobName)
        val encrypted = MediaVaultCrypto.encrypt(bytes)

        runCatching {
            blobFile.writeBytes(encrypted)
        }.getOrElse {
            throw IllegalStateException("vault_write_failed")
        }

        val mimeType = context.contentResolver.getType(sourceUri)
            ?.trim()
            ?.ifBlank { null }
            ?: "application/octet-stream"
        val type = if (mimeType.startsWith("image/")) "photo" else "file"
        val iso = toIsoUtc(now)
        val item = VaultItem(
            id = itemId,
            type = type,
            ownerRole = ownerRole.ifBlank { "parent" },
            mimeType = mimeType,
            encryptedBlobName = blobName,
            byteSize = bytes.size.toLong(),
            createdAtIso = iso,
            createdAtEpochMs = now,
            lastAccessIso = iso,
            lastAccessEpochMs = now,
            retentionState = VaultRetentionState.ACTIVE.raw,
            deletedAtEpochMs = 0L
        )

        return runCatching {
            MediaVaultIndexStore.saveImportedItem(context, item)
            item
        }.getOrElse {
            runCatching { blobFile.delete() }
            throw IllegalStateException("index_write_failed")
        }
    }

    @Throws(IllegalStateException::class)
    fun exportToUri(context: Context, itemId: String, destinationUri: Uri): VaultItem {
        val item = requireActiveItem(context, itemId)
        val decrypted = readDecryptedBlob(context, item)
        runCatching {
            context.contentResolver.openOutputStream(destinationUri, "w")?.use { out ->
                out.write(decrypted)
                out.flush()
            } ?: throw IllegalStateException("destination_unavailable")
        }.getOrElse {
            if (it is IllegalStateException) {
                throw it
            }
            throw IllegalStateException("export_write_failed")
        }

        return MediaVaultIndexStore.touchAccess(context, item.id) ?: item
    }

    @Throws(IllegalStateException::class)
    fun openSecureView(context: Context, itemId: String): VaultItem {
        val item = requireActiveItem(context, itemId)
        val decrypted = readDecryptedBlob(context, item)
        val previewFile = writePreviewFile(context, item, decrypted)
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            previewFile
        )

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        runCatching {
            context.startActivity(viewIntent)
        }.getOrElse {
            throw IllegalStateException("viewer_unavailable")
        }

        return MediaVaultIndexStore.touchAccess(context, item.id) ?: item
    }

    fun markDeleted(context: Context, itemId: String): VaultItem? {
        val item = MediaVaultIndexStore.findById(context, itemId) ?: return null
        if (VaultRetentionState.fromRaw(item.retentionState) == VaultRetentionState.DELETED) {
            return item
        }
        return MediaVaultIndexStore.markDeleted(context, itemId)
    }

    fun restore(context: Context, itemId: String, retentionDays: Int): VaultItem? {
        val item = MediaVaultIndexStore.findById(context, itemId) ?: return null
        if (VaultRetentionState.fromRaw(item.retentionState) != VaultRetentionState.DELETED) {
            return null
        }
        if (!blobFile(context, item.encryptedBlobName).exists()) {
            MediaVaultIndexStore.removeItem(context, itemId)
            return null
        }

        val maxAgeMs = retentionDays.coerceAtLeast(1) * DAY_MS
        val deletedAt = item.deletedAtEpochMs
        val expired = deletedAt > 0L && (System.currentTimeMillis() - deletedAt) > maxAgeMs
        if (expired) {
            purgeImmediately(context, itemId)
            return null
        }

        return MediaVaultIndexStore.restore(context, itemId)
    }

    fun purgeExpiredDeleted(context: Context, retentionDays: Int): Int {
        val maxAgeMs = retentionDays.coerceAtLeast(1) * DAY_MS
        val now = System.currentTimeMillis()
        val items = MediaVaultIndexStore.readDeletedItems(context)
        var purged = 0
        items.forEach { item ->
            val missingBlob = !blobFile(context, item.encryptedBlobName).exists()
            val expired = item.deletedAtEpochMs > 0L && (now - item.deletedAtEpochMs) >= maxAgeMs
            if (missingBlob || expired) {
                if (purgeImmediately(context, item.id)) {
                    purged += 1
                }
            }
        }
        return purged
    }

    fun purgeImmediately(context: Context, itemId: String): Boolean {
        val item = MediaVaultIndexStore.findById(context, itemId) ?: return false
        runCatching { blobFile(context, item.encryptedBlobName).delete() }
        return MediaVaultIndexStore.removeItem(context, itemId) != null
    }

    fun defaultExportFileName(item: VaultItem): String {
        val extension = extensionForMime(item.mimeType)
        val suffix = if (extension.isBlank()) "" else ".$extension"
        return "vault-${item.id.take(10)}$suffix"
    }

    fun formatItemLine(item: VaultItem): String {
        val state = VaultRetentionState.fromRaw(item.retentionState)
        val shortId = item.id.take(8)
        val sizeKb = (item.byteSize / 1024L).coerceAtLeast(1L)
        return when (state) {
            VaultRetentionState.ACTIVE -> "$shortId • ${item.type} • ${sizeKb}KB • ${item.createdAtIso}"
            VaultRetentionState.DELETED -> "$shortId • deleted • ${sizeKb}KB • ${item.lastAccessIso}"
        }
    }

    private fun requireActiveItem(context: Context, itemId: String): VaultItem {
        val item = MediaVaultIndexStore.findById(context, itemId) ?: throw IllegalStateException("item_missing")
        if (VaultRetentionState.fromRaw(item.retentionState) != VaultRetentionState.ACTIVE) {
            throw IllegalStateException("item_deleted")
        }
        if (!blobFile(context, item.encryptedBlobName).exists()) {
            MediaVaultIndexStore.removeItem(context, item.id)
            throw IllegalStateException("blob_missing")
        }
        return item
    }

    private fun readDecryptedBlob(context: Context, item: VaultItem): ByteArray {
        val encrypted = runCatching {
            blobFile(context, item.encryptedBlobName).readBytes()
        }.getOrElse {
            throw IllegalStateException("blob_unreadable")
        }
        return runCatching { MediaVaultCrypto.decrypt(encrypted) }.getOrElse {
            throw IllegalStateException("blob_decrypt_failed")
        }
    }

    private fun writePreviewFile(context: Context, item: VaultItem, bytes: ByteArray): File {
        val directory = File(context.cacheDir, WatchdogConfig.MEDIA_VAULT_PREVIEW_DIR).apply { mkdirs() }
        cleanupOldPreviews(directory)
        val extension = extensionForMime(item.mimeType)
        val suffix = if (extension.isBlank()) "" else ".$extension"
        val file = File(directory, "preview-${item.id.take(12)}-${
            shortHash(item.id + System.currentTimeMillis())
        }$suffix")
        file.writeBytes(bytes)
        return file
    }

    private fun cleanupOldPreviews(directory: File) {
        val cutoff = System.currentTimeMillis() - PREVIEW_TTL_MS
        directory.listFiles().orEmpty().forEach { file ->
            if (file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    private fun blobFile(context: Context, blobName: String): File {
        val directory = File(context.filesDir, WatchdogConfig.MEDIA_VAULT_STORAGE_DIR).apply { mkdirs() }
        return File(directory, blobName)
    }

    private fun extensionForMime(mimeType: String): String {
        val normalized = mimeType.trim().lowercase(Locale.US)
        return when {
            normalized == "image/jpeg" -> "jpg"
            normalized == "image/png" -> "png"
            normalized == "image/webp" -> "webp"
            normalized == "image/heic" -> "heic"
            normalized == "application/pdf" -> "pdf"
            normalized == "text/plain" -> "txt"
            normalized.contains("/") -> normalized.substringAfter("/").substringBefore("+").take(8)
            else -> ""
        }
    }

    private fun newItemId(): String {
        return UUID.randomUUID().toString().replace("-", "").take(20)
    }

    private fun shortHash(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)
    }

    private fun toIsoUtc(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMs))
    }
}
