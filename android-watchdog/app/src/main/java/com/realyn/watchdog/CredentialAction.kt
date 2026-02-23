package com.realyn.watchdog

import org.json.JSONObject

data class CredentialAction(
    val actionId: String,
    val owner: String,
    val category: String,
    val service: String,
    val username: String,
    val url: String,
    val actionType: String,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val dueAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val receiptId: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("actionId", actionId)
            .put("owner", owner)
            .put("category", category)
            .put("service", service)
            .put("username", username)
            .put("url", url)
            .put("actionType", actionType)
            .put("status", status)
            .put("createdAtEpochMs", createdAtEpochMs)
            .put("updatedAtEpochMs", updatedAtEpochMs)
            .put("dueAtEpochMs", dueAtEpochMs)
            .put("completedAtEpochMs", completedAtEpochMs)
            .put("receiptId", receiptId)
    }

    companion object {
        fun fromJson(item: JSONObject): CredentialAction {
            val createdAt = item.optLong("createdAtEpochMs", System.currentTimeMillis())
            val updatedAt = item.optLong("updatedAtEpochMs", createdAt)
            return CredentialAction(
                actionId = item.optString("actionId"),
                owner = CredentialPolicy.canonicalOwnerId(item.optString("owner")),
                category = item.optString("category"),
                service = item.optString("service"),
                username = item.optString("username"),
                url = item.optString("url"),
                actionType = item.optString("actionType", "rotate_password"),
                status = item.optString("status", "pending"),
                createdAtEpochMs = createdAt,
                updatedAtEpochMs = updatedAt,
                dueAtEpochMs = item.optLong("dueAtEpochMs", createdAt + (3L * 24L * 60L * 60L * 1000L)),
                completedAtEpochMs = item.optLong("completedAtEpochMs", 0L).coerceAtLeast(0L),
                receiptId = item.optString("receiptId").trim()
            )
        }
    }
}
