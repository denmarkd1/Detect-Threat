package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WifiScanSnapshotRecord(
    val score: Int,
    val tier: String,
    val findings: List<String>,
    val recommendations: List<String>,
    val scannedAtIso: String,
    val scannedAtEpochMs: Long,
    val ssid: String,
    val bssidMasked: String,
    val securityType: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("score", score)
            .put("tier", tier)
            .put("findings", JSONArray(findings))
            .put("recommendations", JSONArray(recommendations))
            .put("scannedAtIso", scannedAtIso)
            .put("scannedAtEpochMs", scannedAtEpochMs)
            .put("ssid", ssid)
            .put("bssidMasked", bssidMasked)
            .put("securityType", securityType)
    }

    companion object {
        fun fromJson(payload: JSONObject): WifiScanSnapshotRecord {
            val findings = mutableListOf<String>()
            val findingsArray = payload.optJSONArray("findings") ?: JSONArray()
            for (index in 0 until findingsArray.length()) {
                val value = findingsArray.optString(index).trim()
                if (value.isNotBlank()) {
                    findings += value
                }
            }

            val recommendations = mutableListOf<String>()
            val recommendationArray = payload.optJSONArray("recommendations") ?: JSONArray()
            for (index in 0 until recommendationArray.length()) {
                val value = recommendationArray.optString(index).trim()
                if (value.isNotBlank()) {
                    recommendations += value
                }
            }

            return WifiScanSnapshotRecord(
                score = payload.optInt("score", 0).coerceIn(0, 100),
                tier = payload.optString("tier").trim(),
                findings = findings,
                recommendations = recommendations,
                scannedAtIso = payload.optString("scannedAtIso").trim(),
                scannedAtEpochMs = payload.optLong("scannedAtEpochMs", 0L).coerceAtLeast(0L),
                ssid = payload.optString("ssid").trim(),
                bssidMasked = payload.optString("bssidMasked").trim(),
                securityType = payload.optString("securityType", "unknown").trim()
            )
        }
    }
}

object WifiScanSnapshotStore {

    private const val MAX_HISTORY_ROWS = 1000

    @Synchronized
    fun append(context: Context, snapshot: WifiPostureSnapshot) {
        val record = WifiScanSnapshotRecord(
            score = snapshot.score,
            tier = snapshot.tier,
            findings = snapshot.findings,
            recommendations = snapshot.recommendations,
            scannedAtIso = snapshot.scannedAtIso,
            scannedAtEpochMs = snapshot.scannedAtEpochMs,
            ssid = snapshot.ssid,
            bssidMasked = snapshot.bssidMasked,
            securityType = snapshot.securityType
        )
        val file = historyFile(context)
        file.appendText(record.toJson().toString() + "\n")
        trimHistory(file)
    }

    @Synchronized
    fun latest(context: Context): WifiScanSnapshotRecord? {
        return readRecent(context, 1).firstOrNull()
    }

    @Synchronized
    fun readRecent(context: Context, limit: Int = 20): List<WifiScanSnapshotRecord> {
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
                        WifiScanSnapshotRecord.fromJson(JSONObject(line))
                    }.getOrNull()
                }
                .take(targetLimit)
                .toList()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun countRecentSsidChanges(context: Context, sampleSize: Int = 12): Int {
        val rows = readRecent(context, sampleSize.coerceIn(2, 50))
            .asReversed()
            .map { it.ssid.trim() }
            .filter { it.isNotBlank() && it != "<unknown ssid>" }

        if (rows.size < 2) {
            return 0
        }

        var changes = 0
        var last = rows.first()
        rows.drop(1).forEach { ssid ->
            if (ssid != last) {
                changes += 1
            }
            last = ssid
        }
        return changes
    }

    private fun historyFile(context: Context): File {
        return File(context.filesDir, WatchdogConfig.WIFI_POSTURE_HISTORY_FILE)
    }

    private fun trimHistory(file: File) {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.size <= MAX_HISTORY_ROWS) {
            return
        }
        val trimmed = lines.takeLast(MAX_HISTORY_ROWS)
        file.writeText(trimmed.joinToString("\n") + "\n")
    }
}
