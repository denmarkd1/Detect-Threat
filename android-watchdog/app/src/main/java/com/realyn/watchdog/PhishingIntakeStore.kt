package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class PhishingIntakeEntry(
    val triagedAtEpochMs: Long,
    val triagedAtIso: String,
    val sourceRef: String,
    val inputPreview: String,
    val inputSha256: String,
    val result: PhishingTriageResult
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("triagedAtEpochMs", triagedAtEpochMs)
            .put("triagedAtIso", triagedAtIso)
            .put("sourceRef", sourceRef)
            .put("inputPreview", inputPreview)
            .put("inputSha256", inputSha256)
            .put("result", result.toJson())
    }

    companion object {
        fun fromJson(payload: JSONObject): PhishingIntakeEntry {
            return PhishingIntakeEntry(
                triagedAtEpochMs = payload.optLong("triagedAtEpochMs", 0L).coerceAtLeast(0L),
                triagedAtIso = payload.optString("triagedAtIso").trim(),
                sourceRef = payload.optString("sourceRef").trim(),
                inputPreview = payload.optString("inputPreview").trim(),
                inputSha256 = payload.optString("inputSha256").trim(),
                result = PhishingTriageResult.fromJson(
                    payload.optJSONObject("result") ?: JSONObject()
                )
            )
        }
    }
}

object PhishingIntakeStore {

    private const val MAX_ROWS = 1000

    @Synchronized
    fun append(context: Context, input: String, sourceRef: String, result: PhishingTriageResult) {
        val normalized = input.trim()
        val preview = normalized.replace("\n", " ").replace("\r", " ").trim()
            .take(180)
        val entry = PhishingIntakeEntry(
            triagedAtEpochMs = result.triagedAtEpochMs,
            triagedAtIso = result.triagedAtIso,
            sourceRef = sourceRef,
            inputPreview = preview,
            inputSha256 = sha256(normalized),
            result = result
        )

        val file = historyFile(context)
        file.appendText(entry.toJson().toString() + "\n")
        trimHistory(file)
    }

    @Synchronized
    fun readRecent(context: Context, limit: Int = 30): List<PhishingIntakeEntry> {
        val targetLimit = limit.coerceIn(1, 200)
        val file = historyFile(context)
        if (!file.exists()) {
            return emptyList()
        }

        return runCatching {
            file.readLines()
                .asReversed()
                .asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching {
                        PhishingIntakeEntry.fromJson(JSONObject(line))
                    }.getOrNull()
                }
                .take(targetLimit)
                .toList()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun latest(context: Context): PhishingIntakeEntry? {
        return readRecent(context, 1).firstOrNull()
    }

    @Synchronized
    fun summarizeRecent(context: Context, lookback: Int = 20): Triple<Int, Int, Int> {
        val rows = readRecent(context, lookback)
        var high = 0
        var medium = 0
        var low = 0
        rows.forEach { entry ->
            when (entry.result.severity) {
                Severity.HIGH -> high += 1
                Severity.MEDIUM -> medium += 1
                Severity.LOW, Severity.INFO -> low += 1
            }
        }
        return Triple(high, medium, low)
    }

    private fun historyFile(context: Context): File {
        return File(context.filesDir, WatchdogConfig.PHISHING_TRIAGE_HISTORY_FILE)
    }

    private fun trimHistory(file: File) {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.size <= MAX_ROWS) {
            return
        }
        file.writeText(lines.takeLast(MAX_ROWS).joinToString("\n") + "\n")
    }

    private fun sha256(value: String): String {
        if (value.isBlank()) {
            return ""
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }
}
