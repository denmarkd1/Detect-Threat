package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import java.io.File

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

    fun appendAction(context: Context, action: CredentialAction) {
        val queue = loadQueue(context).toMutableList()
        if (queue.any { it.actionId == action.actionId }) {
            return
        }
        queue.add(action)
        saveQueue(context, queue)
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

    fun saveQueue(context: Context, queue: List<CredentialAction>) {
        val array = JSONArray()
        queue.forEach { array.put(it.toJson()) }
        queueFile(context).writeText(array.toString(2))
    }
}
