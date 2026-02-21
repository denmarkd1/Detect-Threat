package com.realyn.watchdog

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class ScamFinding(
    val findingId: String,
    val sourceType: String,
    val sourceRef: String,
    val severity: Severity,
    val score: Int,
    val reasonCodes: Set<String>,
    val title: String,
    val remediation: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("findingId", findingId)
            .put("sourceType", sourceType)
            .put("sourceRef", sourceRef)
            .put("severity", severity.name)
            .put("score", score)
            .put("reasonCodes", JSONArray(reasonCodes.sorted()))
            .put("title", title)
            .put("remediation", remediation)
    }

    fun fingerprintKey(): String {
        return listOf(
            sourceType.lowercase(Locale.US),
            sourceRef.lowercase(Locale.US),
            severity.name,
            score.toString(),
            reasonCodes.sorted().joinToString(",")
        ).joinToString("|")
    }

    companion object {
        fun fromJson(item: JSONObject): ScamFinding {
            val reasons = linkedSetOf<String>()
            val reasonArray = item.optJSONArray("reasonCodes") ?: JSONArray()
            for (index in 0 until reasonArray.length()) {
                val value = reasonArray.optString(index).trim().lowercase(Locale.US)
                if (value.isNotBlank()) {
                    reasons += value
                }
            }
            val severity = runCatching {
                Severity.valueOf(item.optString("severity", Severity.LOW.name))
            }.getOrDefault(Severity.LOW)

            return ScamFinding(
                findingId = item.optString("findingId"),
                sourceType = item.optString("sourceType"),
                sourceRef = item.optString("sourceRef"),
                severity = severity,
                score = item.optInt("score", 0).coerceIn(0, 100),
                reasonCodes = reasons,
                title = item.optString("title"),
                remediation = item.optString("remediation")
            )
        }
    }
}

data class ScamTriageSnapshot(
    val evaluatedAtEpochMs: Long,
    val findings: List<ScamFinding>
) {
    val highCount: Int
        get() = findings.count { it.severity == Severity.HIGH }

    val mediumCount: Int
        get() = findings.count { it.severity == Severity.MEDIUM }

    val lowCount: Int
        get() = findings.count { it.severity == Severity.LOW }

    fun actionableFindings(): List<ScamFinding> {
        return findings.filter { it.severity == Severity.HIGH || it.severity == Severity.MEDIUM }
    }

    fun topFindings(limit: Int = 3): List<ScamFinding> {
        return findings
            .sortedWith(
                compareByDescending<ScamFinding> { severityRank(it.severity) }
                    .thenByDescending { it.score }
            )
            .take(limit.coerceAtLeast(1))
    }

    fun urgentActions(limit: Int = 3): List<String> {
        return actionableFindings()
            .sortedWith(
                compareByDescending<ScamFinding> { severityRank(it.severity) }
                    .thenByDescending { it.score }
            )
            .map { it.remediation.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit.coerceAtLeast(1))
    }

    fun summaryLine(): String {
        return if (findings.isEmpty()) {
            "No scam-shield risks detected."
        } else {
            "Scam risks: high=$highCount medium=$mediumCount low=$lowCount"
        }
    }

    fun toJson(): JSONObject {
        val array = JSONArray()
        findings.forEach { array.put(it.toJson()) }
        return JSONObject()
            .put("evaluatedAtEpochMs", evaluatedAtEpochMs)
            .put("findings", array)
    }

    companion object {
        fun empty(nowEpochMs: Long = System.currentTimeMillis()): ScamTriageSnapshot {
            return ScamTriageSnapshot(
                evaluatedAtEpochMs = nowEpochMs,
                findings = emptyList()
            )
        }

        fun fromJson(root: JSONObject): ScamTriageSnapshot {
            val now = System.currentTimeMillis()
            val evaluatedAt = root.optLong("evaluatedAtEpochMs", now).let { value ->
                if (value <= 0L) now else value
            }
            val findings = mutableListOf<ScamFinding>()
            val array = root.optJSONArray("findings") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                findings += ScamFinding.fromJson(item)
            }
            return ScamTriageSnapshot(
                evaluatedAtEpochMs = evaluatedAt,
                findings = findings.sortedWith(
                    compareByDescending<ScamFinding> { severityRank(it.severity) }
                        .thenByDescending { it.score }
                )
            )
        }

        private fun severityRank(severity: Severity): Int {
            return when (severity) {
                Severity.HIGH -> 3
                Severity.MEDIUM -> 2
                Severity.LOW -> 1
                Severity.INFO -> 0
            }
        }
    }
}

object ScamShield {

    private val riskyAppKeywordWeights = linkedMapOf(
        "keylog" to 55,
        "stalker" to 55,
        "smsforward" to 50,
        "otpreader" to 45,
        "overlay" to 35,
        "remotecontrol" to 45,
        "screenstream" to 40,
        "credential" to 45,
        "banklogin" to 50,
        "phish" to 60,
        "autoclick" to 35,
        "spy" to 45
    )

    private val riskyUrlKeywordWeights = linkedMapOf(
        "verify" to 14,
        "secure" to 10,
        "wallet" to 18,
        "airdrop" to 22,
        "gift" to 18,
        "bonus" to 18,
        "support" to 10,
        "crypto" to 20,
        "bank" to 14,
        "signin" to 12,
        "login" to 10,
        "update" to 10
    )

    private val shortenerDomains = setOf(
        "bit.ly",
        "tinyurl.com",
        "t.co",
        "is.gd",
        "ow.ly",
        "cutt.ly",
        "buff.ly",
        "rebrand.ly",
        "rb.gy"
    )

    private val riskyTlds = setOf(
        "top",
        "xyz",
        "click",
        "work",
        "zip",
        "mov",
        "gq",
        "tk",
        "ml"
    )

    fun analyze(context: Context, installedPackages: Set<String> = queryThirdPartyPackages(context)): ScamTriageSnapshot {
        val now = System.currentTimeMillis()
        val findings = mutableListOf<ScamFinding>()
        findings += analyzePackages(installedPackages)
        findings += analyzeStoredUrls(context)

        return ScamTriageSnapshot(
            evaluatedAtEpochMs = now,
            findings = findings.sortedWith(
                compareByDescending<ScamFinding> { severityRank(it.severity) }
                    .thenByDescending { it.score }
            )
        )
    }

    internal fun analyzeUrl(url: String, sourceRef: String): ScamFinding? {
        val normalized = CredentialPolicy.normalizeUrl(url)
        if (normalized.isBlank()) {
            return null
        }

        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        val scheme = uri.scheme.orEmpty().trim().lowercase(Locale.US)
        val host = uri.host.orEmpty().trim().lowercase(Locale.US)
        val authority = uri.encodedAuthority.orEmpty().trim().lowercase(Locale.US)
        val pathAndQuery = "${uri.path.orEmpty()} ${uri.query.orEmpty()}".lowercase(Locale.US)
        val fullLower = normalized.lowercase(Locale.US)

        var score = 0
        val reasons = linkedSetOf<String>()

        if (scheme != "https") {
            score += 45
            reasons += "non_https_transport"
        }
        if (host.isBlank()) {
            score += 35
            reasons += "url_host_missing"
        }
        if (host.startsWith("xn--") || host.contains(".xn--")) {
            score += 30
            reasons += "punycode_hostname"
        }
        if (authority.contains("@")) {
            score += 35
            reasons += "user_info_in_authority"
        }
        if (isIpv4Host(host)) {
            score += 35
            reasons += "ip_literal_host"
        }
        if (isShortenerHost(host)) {
            score += 25
            reasons += "shortener_domain"
        }
        if (normalized.length >= 120) {
            score += 10
            reasons += "very_long_url"
        }

        val tld = host.substringAfterLast('.', "")
        if (tld in riskyTlds) {
            score += 20
            reasons += "risky_tld"
        }

        val hyphenCount = host.count { it == '-' }
        if (hyphenCount >= 3) {
            score += 10
            reasons += "high_hyphen_host"
        }

        var keywordScore = 0
        riskyUrlKeywordWeights.forEach { (keyword, weight) ->
            if (fullLower.contains(keyword) || pathAndQuery.contains(keyword)) {
                keywordScore += weight
            }
        }
        if (keywordScore > 0) {
            score += keywordScore.coerceAtMost(45)
            reasons += "risky_url_keywords"
        }

        if (score < 25) {
            return null
        }

        val severity = when {
            score >= 70 -> Severity.HIGH
            score >= 45 -> Severity.MEDIUM
            else -> Severity.LOW
        }

        val remediation = when (severity) {
            Severity.HIGH ->
                "Do not sign in on this link. Open the official app/site manually and verify domain ownership first."

            Severity.MEDIUM ->
                "Verify this domain from official support pages before entering credentials or 2FA codes."

            else ->
                "Use caution and re-check link source before proceeding."
        }

        val safeSource = sourceRef.trim().ifBlank { normalized }
        val displayHost = host.ifBlank { "unknown-host" }
        return ScamFinding(
            findingId = stableFindingId("url", "$safeSource|$normalized"),
            sourceType = "url",
            sourceRef = "$safeSource -> $normalized",
            severity = severity,
            score = score.coerceIn(0, 100),
            reasonCodes = reasons,
            title = "Suspicious URL risk: $displayHost",
            remediation = remediation
        )
    }

    internal fun analyzePackage(packageName: String): ScamFinding? {
        val normalized = packageName.trim().lowercase(Locale.US)
        if (normalized.isBlank()) {
            return null
        }

        var score = 0
        val reasons = linkedSetOf<String>()
        riskyAppKeywordWeights.forEach { (keyword, weight) ->
            if (normalized.contains(keyword)) {
                score += weight
                reasons += "risky_app_keyword:$keyword"
            }
        }
        if (score < 30) {
            return null
        }

        val severity = when {
            score >= 70 -> Severity.HIGH
            score >= 45 -> Severity.MEDIUM
            else -> Severity.LOW
        }

        val remediation = when (severity) {
            Severity.HIGH ->
                "Review and uninstall this app unless explicitly trusted. Revoke high-risk permissions immediately."

            Severity.MEDIUM ->
                "Review app legitimacy and permission set before keeping it installed."

            else ->
                "Monitor this app and avoid granting additional sensitive permissions."
        }

        return ScamFinding(
            findingId = stableFindingId("app", normalized),
            sourceType = "app",
            sourceRef = normalized,
            severity = severity,
            score = score.coerceIn(0, 100),
            reasonCodes = reasons,
            title = "Potential scam app pattern",
            remediation = remediation
        )
    }

    private fun analyzePackages(installedPackages: Set<String>): List<ScamFinding> {
        return installedPackages
            .asSequence()
            .mapNotNull { pkg -> analyzePackage(pkg) }
            .toList()
    }

    private fun analyzeStoredUrls(context: Context): List<ScamFinding> {
        val findings = mutableListOf<ScamFinding>()

        val records = runCatching { CredentialVaultStore.loadRecords(context) }.getOrDefault(emptyList())
        records.forEach { record ->
            val label = "vault:${record.service}/${record.username}"
            analyzeUrl(record.url, label)?.let { findings += it }
        }

        val actions = runCatching { CredentialActionStore.loadQueue(context) }.getOrDefault(emptyList())
        actions.forEach { action ->
            val label = "queue:${action.service}/${action.username}"
            analyzeUrl(action.url, label)?.let { findings += it }
        }

        return findings
    }

    private fun queryThirdPartyPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val apps = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }
        }.getOrDefault(emptyList())

        return apps
            .asSequence()
            .filter { app ->
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                !isSystem && !isUpdatedSystem
            }
            .map { it.packageName }
            .toSet()
    }

    private fun isIpv4Host(host: String): Boolean {
        if (host.isBlank()) {
            return false
        }
        val parts = host.split(".")
        if (parts.size != 4) {
            return false
        }
        return parts.all { part ->
            val value = part.toIntOrNull() ?: return@all false
            value in 0..255
        }
    }

    private fun isShortenerHost(host: String): Boolean {
        if (host.isBlank()) {
            return false
        }
        return shortenerDomains.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    private fun severityRank(severity: Severity): Int {
        return when (severity) {
            Severity.HIGH -> 3
            Severity.MEDIUM -> 2
            Severity.LOW -> 1
            Severity.INFO -> 0
        }
    }

    private fun stableFindingId(sourceType: String, key: String): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest("$sourceType|$key".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }
}
