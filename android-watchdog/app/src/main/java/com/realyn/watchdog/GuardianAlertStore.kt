package com.realyn.watchdog

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GuardianAlertEntry(
    val recordedAtEpochMs: Long,
    val recordedAtIso: String,
    val scanEpochMs: Long,
    val findingId: String,
    val severity: Severity,
    val score: Int,
    val title: String,
    val sourceType: String,
    val sourceRef: String,
    val remediation: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("recordedAtEpochMs", recordedAtEpochMs)
            .put("recordedAtIso", recordedAtIso)
            .put("scanEpochMs", scanEpochMs)
            .put("findingId", findingId)
            .put("severity", severity.name)
            .put("score", score)
            .put("title", title)
            .put("sourceType", sourceType)
            .put("sourceRef", sourceRef)
            .put("remediation", remediation)
    }

    companion object {
        fun fromJson(item: JSONObject): GuardianAlertEntry {
            val severity = runCatching {
                Severity.valueOf(item.optString("severity", Severity.MEDIUM.name))
            }.getOrDefault(Severity.MEDIUM)
            val now = System.currentTimeMillis()
            return GuardianAlertEntry(
                recordedAtEpochMs = item.optLong("recordedAtEpochMs", now),
                recordedAtIso = item.optString("recordedAtIso"),
                scanEpochMs = item.optLong("scanEpochMs", now),
                findingId = item.optString("findingId"),
                severity = severity,
                score = item.optInt("score", 0).coerceIn(0, 100),
                title = item.optString("title"),
                sourceType = item.optString("sourceType"),
                sourceRef = item.optString("sourceRef"),
                remediation = item.optString("remediation")
            )
        }
    }
}

object GuardianAlertStore {

    private const val MAX_FEED_LINES = 500

    private fun feedFile(context: Context): File = File(context.filesDir, WatchdogConfig.GUARDIAN_ALERT_FEED_FILE)

    @Synchronized
    fun syncFromScan(context: Context, result: ScanResult): Boolean {
        val actionable = result.scamTriage.actionableFindings()
        val fingerprint = findingsFingerprint(actionable)
        val previous = readLastFingerprint(context)
        if (fingerprint == previous) {
            return false
        }
        writeLastFingerprint(context, fingerprint)
        if (actionable.isEmpty()) {
            return false
        }

        val now = System.currentTimeMillis()
        val nowIso = formatIsoTime(now)
        val file = feedFile(context)

        actionable
            .sortedWith(
                compareByDescending<ScamFinding> { severityRank(it.severity) }
                    .thenByDescending { it.score }
            )
            .forEach { finding ->
                val entry = GuardianAlertEntry(
                    recordedAtEpochMs = now,
                    recordedAtIso = nowIso,
                    scanEpochMs = result.snapshot.scannedAtEpochMs,
                    findingId = finding.findingId,
                    severity = finding.severity,
                    score = finding.score,
                    title = finding.title,
                    sourceType = finding.sourceType,
                    sourceRef = finding.sourceRef.replace("\n", " ").trim(),
                    remediation = finding.remediation
                )
                file.appendText(entry.toJson().toString() + "\n")
            }

        trimFeed(file)
        return true
    }

    @Synchronized
    fun appendManualEntry(
        context: Context,
        severity: Severity,
        score: Int,
        title: String,
        sourceType: String,
        sourceRef: String,
        remediation: String
    ) {
        val now = System.currentTimeMillis()
        val entry = GuardianAlertEntry(
            recordedAtEpochMs = now,
            recordedAtIso = formatIsoTime(now),
            scanEpochMs = now,
            findingId = manualFindingId(sourceType, sourceRef, title),
            severity = severity,
            score = score.coerceIn(0, 100),
            title = title,
            sourceType = sourceType,
            sourceRef = sourceRef.replace("\n", " ").trim(),
            remediation = remediation.replace("\n", " ").trim()
        )

        val file = feedFile(context)
        file.appendText(entry.toJson().toString() + "\n")
        trimFeed(file)
    }

    @Synchronized
    fun readRecent(context: Context, limit: Int = 20): List<GuardianAlertEntry> {
        val targetLimit = limit.coerceIn(1, 100)
        val file = feedFile(context)
        if (!file.exists()) {
            return emptyList()
        }
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.isEmpty()) {
            return emptyList()
        }

        return lines
            .asReversed()
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching {
                    GuardianAlertEntry.fromJson(JSONObject(line))
                }.getOrNull()
            }
            .take(targetLimit)
            .toList()
    }

    internal fun findingsFingerprint(findings: List<ScamFinding>): String {
        if (findings.isEmpty()) {
            return ""
        }
        val raw = findings
            .map { it.fingerprintKey() }
            .sorted()
            .joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    private fun readLastFingerprint(context: Context): String {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getString(WatchdogConfig.KEY_LAST_GUARDIAN_ALERT_FINGERPRINT, "") ?: ""
    }

    private fun writeLastFingerprint(context: Context, fingerprint: String) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(WatchdogConfig.KEY_LAST_GUARDIAN_ALERT_FINGERPRINT, fingerprint).apply()
    }

    private fun trimFeed(file: File) {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.size <= MAX_FEED_LINES) {
            return
        }
        val trimmed = lines.takeLast(MAX_FEED_LINES).joinToString("\n")
        file.writeText("$trimmed\n")
    }

    private fun severityRank(severity: Severity): Int {
        return when (severity) {
            Severity.HIGH -> 3
            Severity.MEDIUM -> 2
            Severity.LOW -> 1
            Severity.INFO -> 0
        }
    }

    private fun manualFindingId(sourceType: String, sourceRef: String, title: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest("$sourceType|$sourceRef|$title".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    private fun formatIsoTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMs))
    }
}
