package com.realyn.watchdog

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

data class FeedbackSyncResult(
    val submittedNow: Int,
    val remainingQueue: Int,
    val lastFeedbackId: String,
    val lastTicketId: String,
    val endpoint: String
)

object SupportFeedbackReporter {

    private const val SETTINGS_FILE_NAME = "workspace_settings.json"
    private const val QUEUE_FILE_NAME = "dt_support_feedback_queue.json"
    private const val DEFAULT_SUPPORT_API_BASE = "http://127.0.0.1:8787"

    @Synchronized
    fun submitFeedback(
        context: Context,
        rating: Int,
        recommendToFriends: Boolean,
        tier: String,
        selectedPlan: String,
        createTicket: Boolean
    ): FeedbackSyncResult {
        val queue = loadQueue(context).toMutableList()
        queue += JSONObject()
            .put("queue_id", "fbq-${UUID.randomUUID().toString().replace("-", "").take(12)}")
            .put("queued_at_epoch_ms", System.currentTimeMillis())
            .put(
                "payload",
                JSONObject()
                    .put("rating", rating.coerceIn(1, 5))
                    .put("recommend_to_friends", recommendToFriends)
                    .put("tier", tier.trim().ifBlank { "unknown" })
                    .put("selected_plan", selectedPlan.trim().ifBlank { "none" })
                    .put("create_ticket", createTicket)
                    .put("source", "android_app")
                    .put("platform", "android")
                    .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    .put("region", Locale.getDefault().country.ifBlank { "unknown" })
                    .put("app_version", installedVersionName(context))
                    .put("build_code", installedVersionCode(context))
            )
        saveQueue(context, queue)
        return flushPending(context)
    }

    @Synchronized
    fun flushPending(context: Context): FeedbackSyncResult {
        val queue = loadQueue(context).toMutableList()
        val endpoint = resolveFeedbackEndpoint(context)
        if (queue.isEmpty()) {
            return FeedbackSyncResult(
                submittedNow = 0,
                remainingQueue = 0,
                lastFeedbackId = "",
                lastTicketId = "",
                endpoint = endpoint
            )
        }

        var submitted = 0
        var lastFeedbackId = ""
        var lastTicketId = ""
        val remaining = mutableListOf<JSONObject>()

        for (index in queue.indices) {
            val payload = queue[index].optJSONObject("payload")
            if (payload == null) {
                continue
            }

            val response = runCatching { postFeedback(endpoint, payload) }.getOrNull()
            if (response == null) {
                remaining += queue.subList(index, queue.size)
                break
            }

            submitted += 1
            val feedback = response.optJSONObject("feedback")
            if (feedback != null) {
                lastFeedbackId = feedback.optString("feedback_id", lastFeedbackId)
            }
            val ticketId = response.optString("ticket_id", "")
            if (ticketId.isNotBlank()) {
                lastTicketId = ticketId
            }
        }

        saveQueue(context, remaining)
        return FeedbackSyncResult(
            submittedNow = submitted,
            remainingQueue = remaining.size,
            lastFeedbackId = lastFeedbackId,
            lastTicketId = lastTicketId,
            endpoint = endpoint
        )
    }

    private fun postFeedback(endpoint: String, payload: JSONObject): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            connection.outputStream.use { stream ->
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveFeedbackEndpoint(context: Context): String {
        val configuredBase = readConfiguredSupportApiBase(context)
        val fallbackBase = context.getString(R.string.support_api_base_url)
            .trim()
            .ifBlank { DEFAULT_SUPPORT_API_BASE }
        val base = configuredBase.ifBlank { fallbackBase }
            .trim()
            .ifBlank { DEFAULT_SUPPORT_API_BASE }
            .removeSuffix("/")

        return if (base.endsWith("/api/support")) {
            "$base/feedback"
        } else {
            "$base/api/support/feedback"
        }
    }

    private fun readConfiguredSupportApiBase(context: Context): String {
        val payload = readSettingsPayload(context) ?: return ""
        return payload
            .optJSONObject("support")
            ?.optString("api_base_url", "")
            ?.trim()
            .orEmpty()
    }

    private fun readSettingsPayload(context: Context): JSONObject? {
        val localOverride = File(context.filesDir, SETTINGS_FILE_NAME)
        val content = when {
            localOverride.exists() -> runCatching { localOverride.readText() }.getOrNull()
            else -> runCatching {
                context.assets.open(SETTINGS_FILE_NAME).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return null

        return runCatching { JSONObject(content) }.getOrNull()
    }

    private fun queueFile(context: Context): File = File(context.filesDir, QUEUE_FILE_NAME)

    private fun installedVersionName(context: Context): String {
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName ?: "unknown"
    }

    private fun installedVersionCode(context: Context): Long {
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun loadQueue(context: Context): List<JSONObject> {
        val file = queueFile(context)
        if (!file.exists()) {
            return emptyList()
        }

        val raw = runCatching { file.readText() }.getOrDefault("")
        if (raw.isBlank()) {
            return emptyList()
        }

        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val rows = mutableListOf<JSONObject>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            rows += item
        }
        return rows
    }

    private fun saveQueue(context: Context, rows: List<JSONObject>) {
        val array = JSONArray()
        rows.forEach { array.put(it) }
        queueFile(context).writeText(array.toString(2))
    }
}
