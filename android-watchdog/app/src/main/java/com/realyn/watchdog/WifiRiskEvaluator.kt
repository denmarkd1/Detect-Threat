package com.realyn.watchdog

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class WifiPostureSignals(
    val ssid: String,
    val bssidMasked: String,
    val securityType: String,
    val openNearbyCount: Int,
    val weakNearbyCount: Int,
    val captivePortalDetected: Boolean,
    val meteredNetwork: Boolean,
    val repeatedSsidChanges: Int,
    val permissionSummary: String
)

data class WifiPostureSnapshot(
    val score: Int,
    val tier: String,
    val findings: List<String>,
    val recommendations: List<String>,
    val scannedAtIso: String,
    val scannedAtEpochMs: Long,
    val ssid: String,
    val bssidMasked: String,
    val securityType: String
)

object WifiRiskEvaluator {

    fun evaluate(signals: WifiPostureSignals, scannedAtEpochMs: Long = System.currentTimeMillis()): WifiPostureSnapshot {
        var score = 100
        val findings = mutableListOf<String>()
        val recommendations = mutableListOf<String>()

        when (signals.securityType.lowercase(Locale.US)) {
            "open" -> {
                score -= 45
                findings += "Connected Wi-Fi appears open (no encryption)."
                recommendations += "Switch to a trusted WPA2/WPA3 network before using sensitive accounts."
            }
            "wep", "wpa" -> {
                score -= 28
                findings += "Connected Wi-Fi uses weak encryption (${signals.securityType.uppercase(Locale.US)})."
                recommendations += "Prefer WPA2/WPA3 networks and avoid credential changes on weak encryption."
            }
            "unknown" -> {
                score -= 8
                findings += "Unable to verify Wi-Fi encryption posture (${signals.permissionSummary})."
                recommendations += "Grant Wi-Fi/location permissions to improve posture verification detail."
            }
        }

        if (signals.openNearbyCount > 0) {
            val deduction = (signals.openNearbyCount * 4).coerceAtMost(16)
            score -= deduction
            findings += "Nearby scan found ${signals.openNearbyCount} open network(s)."
            recommendations += "Avoid auto-join and prefer known SSIDs only."
        }

        if (signals.weakNearbyCount > 0) {
            val deduction = (signals.weakNearbyCount * 3).coerceAtMost(12)
            score -= deduction
            findings += "Nearby scan found ${signals.weakNearbyCount} weak-encryption network(s)."
        }

        if (signals.captivePortalDetected) {
            score -= 20
            findings += "Active network reports captive-portal behavior."
            recommendations += "Do not submit passwords until captive portal legitimacy is verified."
        }

        if (signals.meteredNetwork) {
            score -= 6
            findings += "Current network is marked metered; treat as untrusted public posture unless verified."
        }

        if (signals.repeatedSsidChanges >= 3) {
            score -= 18
            findings += "Recent scans show frequent SSID shifts (${signals.repeatedSsidChanges} changes)."
            recommendations += "Disable opportunistic auto-join and pin trusted home/work networks."
        }

        if (findings.isEmpty()) {
            findings += "Wi-Fi posture appears stable with no immediate red flags."
            recommendations += "Keep Wi-Fi updates current and re-run posture scan before sensitive actions."
        }

        score = score.coerceIn(0, 100)
        val tier = when {
            score >= 85 -> "stable"
            score >= 65 -> "guarded"
            score >= 40 -> "elevated"
            else -> "high_risk"
        }

        return WifiPostureSnapshot(
            score = score,
            tier = tier,
            findings = findings,
            recommendations = recommendations.distinct(),
            scannedAtIso = toIsoUtc(scannedAtEpochMs),
            scannedAtEpochMs = scannedAtEpochMs,
            ssid = signals.ssid,
            bssidMasked = signals.bssidMasked,
            securityType = signals.securityType
        )
    }

    private fun toIsoUtc(epochMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMs))
    }
}
