package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.Locale

object CredentialActionStore {

    private fun queueFile(context: Context): File = File(context.filesDir, WatchdogConfig.CREDENTIAL_ACTION_QUEUE_FILE)

    fun loadQueue(context: Context): List<CredentialAction> {
        val file = queueFile(context)
        if (!file.exists()) {
            return emptyList()
        }

        val raw = runCatching { file.readText() }.getOrDefault("")
        if (raw.isBlank()) {
            return emptyList()
        }

        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val items = mutableListOf<CredentialAction>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            items.add(CredentialAction.fromJson(item))
        }

        return items
    }

    fun appendAction(context: Context, action: CredentialAction): Boolean {
        val queue = loadQueue(context).toMutableList()
        val existingIndex = queue.indexOfFirst { it.actionId == action.actionId }
        if (existingIndex >= 0) {
            val existing = queue[existingIndex]
            val isCompleted = existing.status.equals("completed", ignoreCase = true)
            if (!isCompleted) {
                return false
            }
            queue[existingIndex] = action
            saveQueue(context, queue)
            return true
        }
        queue.add(action)
        saveQueue(context, queue)
        return true
    }

    fun updateActionStatus(context: Context, actionId: String, status: String): Boolean {
        val queue = loadQueue(context).toMutableList()
        val index = queue.indexOfFirst { it.actionId == actionId }
        if (index < 0) {
            return false
        }

        val existing = queue[index]
        queue[index] = existing.copy(
            status = status,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        saveQueue(context, queue)
        return true
    }

    fun completeActionWithReceipt(
        context: Context,
        actionId: String
    ): CredentialAction? {
        val queue = loadQueue(context).toMutableList()
        val index = queue.indexOfFirst { it.actionId == actionId }
        if (index < 0) {
            return null
        }
        val existing = queue[index]
        val now = System.currentTimeMillis()
        val receipt = if (existing.receiptId.isNotBlank()) {
            existing.receiptId
        } else {
            buildReceiptId(existing.actionId, now)
        }
        val updated = existing.copy(
            status = "completed",
            updatedAtEpochMs = now,
            completedAtEpochMs = now,
            receiptId = receipt
        )
        queue[index] = updated
        saveQueue(context, queue)
        return updated
    }

    fun saveQueue(context: Context, queue: List<CredentialAction>) {
        val array = JSONArray()
        queue.forEach { array.put(it.toJson()) }
        queueFile(context).writeText(array.toString(2))
    }

    private fun buildReceiptId(actionId: String, nowEpochMs: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$actionId|$nowEpochMs".toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                String.format(Locale.US, "%02x", byte)
            }
        return "rcpt-${digest.take(10)}"
    }
}
