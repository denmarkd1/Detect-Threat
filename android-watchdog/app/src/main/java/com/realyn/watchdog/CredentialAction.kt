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
    val updatedAtEpochMs: Long
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
    }

    companion object {
        fun fromJson(item: JSONObject): CredentialAction {
            return CredentialAction(
                actionId = item.optString("actionId"),
                owner = item.optString("owner"),
                category = item.optString("category"),
                service = item.optString("service"),
                username = item.optString("username"),
                url = item.optString("url"),
                actionType = item.optString("actionType", "rotate_password"),
                status = item.optString("status", "pending"),
                createdAtEpochMs = item.optLong("createdAtEpochMs", System.currentTimeMillis()),
                updatedAtEpochMs = item.optLong("updatedAtEpochMs", System.currentTimeMillis())
            )
        }
    }
}
