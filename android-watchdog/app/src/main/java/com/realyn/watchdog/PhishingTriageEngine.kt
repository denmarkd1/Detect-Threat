package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class AntiPhishingConfig(
    val enabled: Boolean,
    val allowManualClipboardIntake: Boolean,
    val highRiskAutoAlert: Boolean
)

data class PhishingTriageResult(
    val riskScore: Int,
    val severity: Severity,
    val reasons: List<String>,
    val suggestedActions: List<String>,
    val sourceRef: String,
    val triagedAtIso: String,
    val triagedAtEpochMs: Long,
    val extractedUrls: List<String>
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("riskScore", riskScore)
            .put("severity", severity.name)
            .put("reasons", JSONArray(reasons))
            .put("suggestedActions", JSONArray(suggestedActions))
            .put("sourceRef", sourceRef)
            .put("triagedAtIso", triagedAtIso)
            .put("triagedAtEpochMs", triagedAtEpochMs)
            .put("extractedUrls", JSONArray(extractedUrls))
    }

    companion object {
        fun fromJson(payload: JSONObject): PhishingTriageResult {
            val reasons = mutableListOf<String>()
            val reasonArray = payload.optJSONArray("reasons") ?: JSONArray()
            for (index in 0 until reasonArray.length()) {
                val value = reasonArray.optString(index).trim()
                if (value.isNotBlank()) {
                    reasons += value
                }
            }

            val actions = mutableListOf<String>()
            val actionArray = payload.optJSONArray("suggestedActions") ?: JSONArray()
            for (index in 0 until actionArray.length()) {
                val value = actionArray.optString(index).trim()
                if (value.isNotBlank()) {
                    actions += value
                }
            }

            val urls = mutableListOf<String>()
            val urlArray = payload.optJSONArray("extractedUrls") ?: JSONArray()
            for (index in 0 until urlArray.length()) {
                val value = urlArray.optString(index).trim()
                if (value.isNotBlank()) {
                    urls += value
                }
            }

            val severity = runCatching {
                Severity.valueOf(payload.optString("severity", Severity.LOW.name))
            }.getOrDefault(Severity.LOW)

            return PhishingTriageResult(
                riskScore = payload.optInt("riskScore", 0).coerceIn(0, 100),
                severity = severity,
                reasons = reasons,
                suggestedActions = actions,
                sourceRef = payload.optString("sourceRef").trim(),
                triagedAtIso = payload.optString("triagedAtIso").trim(),
                triagedAtEpochMs = payload.optLong("triagedAtEpochMs", 0L).coerceAtLeast(0L),
                extractedUrls = urls
            )
        }
    }
}

object PhishingTriageEngine {

    private val defaults = AntiPhishingConfig(
        enabled = true,
        allowManualClipboardIntake = true,
        highRiskAutoAlert = true
    )

    fun config(context: Context): AntiPhishingConfig {
        val payload = WorkspaceSettingsStore.readPayload(context)
            ?.optJSONObject("anti_phishing")
            ?: return defaults

        return AntiPhishingConfig(
            enabled = payload.optBoolean("enabled", defaults.enabled),
            allowManualClipboardIntake = payload.optBoolean(
                "allow_manual_clipboard_intake",
                defaults.allowManualClipboardIntake
            ),
            highRiskAutoAlert = payload.optBoolean(
                "high_risk_auto_alert",
                defaults.highRiskAutoAlert
            )
        )
    }

    fun triage(context: Context, input: String, sourceRef: String): PhishingTriageResult {
        val normalized = input.trim()
        val heuristics = PhishingHeuristics.evaluate(normalized)
        val score = heuristics.riskScore
        val severity = when {
            score >= 75 -> Severity.HIGH
            score >= 45 -> Severity.MEDIUM
            score > 0 -> Severity.LOW
            else -> Severity.INFO
        }

        val suggestedActions = when (severity) {
            Severity.HIGH -> listOf(
                "Do not sign in or submit 2FA codes on this content.",
                "Open the provider app/site manually and verify account status there.",
                "Report and block the sender/source if unsolicited."
            )

            Severity.MEDIUM -> listOf(
                "Verify sender and destination domain through official channels.",
                "Avoid sharing credentials until destination legitimacy is confirmed.",
                "Re-scan after checking the official provider domain."
            )

            Severity.LOW -> listOf(
                "Use caution and verify links before proceeding.",
                "Prefer direct navigation to official provider domains."
            )

            Severity.INFO -> listOf("No explicit phishing indicators detected from this input.")
        }

        val reasons = if (heuristics.reasons.isEmpty()) {
            listOf("no_risky_signals_detected")
        } else {
            heuristics.reasons
        }

        val now = System.currentTimeMillis()
        val result = PhishingTriageResult(
            riskScore = score,
            severity = severity,
            reasons = reasons,
            suggestedActions = suggestedActions,
            sourceRef = sourceRef,
            triagedAtIso = toIsoUtc(now),
            triagedAtEpochMs = now,
            extractedUrls = heuristics.extractedUrls
        )

        PhishingIntakeStore.append(context, normalized, sourceRef, result)
        PhishingGuardianBridge.maybeEmitHighRiskAlert(context, result)
        return result
    }

    private fun toIsoUtc(epochMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMs))
    }
}
