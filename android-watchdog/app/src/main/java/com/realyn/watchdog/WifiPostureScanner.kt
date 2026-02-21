package com.realyn.watchdog

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import java.util.Locale

data class WifiPostureConfig(
    val enabled: Boolean,
    val scanIntervalMinutes: Int,
    val guardianAlertThreshold: Int
)

object WifiPostureScanner {

    private val defaults = WifiPostureConfig(
        enabled = true,
        scanIntervalMinutes = 15,
        guardianAlertThreshold = 65
    )

    fun config(context: Context): WifiPostureConfig {
        val payload = WorkspaceSettingsStore.readPayload(context)
            ?.optJSONObject("wifi_posture")
            ?: return defaults

        return WifiPostureConfig(
            enabled = payload.optBoolean("enabled", defaults.enabled),
            scanIntervalMinutes = payload.optInt("scan_interval_minutes", defaults.scanIntervalMinutes)
                .coerceIn(1, 24 * 60),
            guardianAlertThreshold = payload.optInt("guardian_alert_threshold", defaults.guardianAlertThreshold)
                .coerceIn(1, 100)
        )
    }

    @SuppressLint("MissingPermission")
    fun runPostureScan(context: Context): WifiPostureSnapshot {
        val appContext = context.applicationContext
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val permission = WifiPermissionGate.resolve(appContext)

        val connectionInfo = runCatching { wifiManager?.connectionInfo }.getOrNull()
        val ssid = normalizeSsid(connectionInfo?.ssid)
        val bssidMasked = maskBssid(connectionInfo?.bssid)

        val scanResults = if (permission.canReadNearbyPosture) {
            runCatching { wifiManager?.scanResults.orEmpty() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val currentSecurity = resolveCurrentSecurity(ssid, connectionInfo?.bssid, scanResults)
        val openNearbyCount = scanResults.count { isOpenNetwork(it.capabilities.orEmpty()) }
        val weakNearbyCount = scanResults.count { isWeakNetwork(it.capabilities.orEmpty()) }
        val repeatedSsidChanges = WifiScanSnapshotStore.countRecentSsidChanges(appContext)

        val networkCapabilities = connectivity?.activeNetwork?.let { network ->
            runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
        }
        val captivePortal = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        val metered = runCatching { connectivity?.isActiveNetworkMetered == true }.getOrDefault(false)

        val snapshot = WifiRiskEvaluator.evaluate(
            signals = WifiPostureSignals(
                ssid = ssid,
                bssidMasked = bssidMasked,
                securityType = currentSecurity,
                openNearbyCount = openNearbyCount,
                weakNearbyCount = weakNearbyCount,
                captivePortalDetected = captivePortal,
                meteredNetwork = metered,
                repeatedSsidChanges = repeatedSsidChanges,
                permissionSummary = WifiPermissionGate.missingPermissionSummary(appContext)
            )
        )

        WifiScanSnapshotStore.append(appContext, snapshot)
        maybeEmitGuardianAlert(context, snapshot)
        return snapshot
    }

    private fun maybeEmitGuardianAlert(context: Context, snapshot: WifiPostureSnapshot) {
        val profileControl = PricingPolicy.resolveProfileControl(context)
        if (!profileControl.requiresGuardianApprovalForSensitiveActions) {
            return
        }

        val cfg = config(context)
        if (snapshot.score > cfg.guardianAlertThreshold) {
            return
        }

        GuardianAlertStore.appendManualEntry(
            context = context,
            severity = if (snapshot.score <= 40) Severity.HIGH else Severity.MEDIUM,
            score = (100 - snapshot.score).coerceIn(0, 100),
            title = "Wi-Fi posture risk detected",
            sourceType = "wifi_posture",
            sourceRef = snapshot.ssid.ifBlank { "unknown_ssid" },
            remediation = snapshot.recommendations.firstOrNull()
                ?: "Switch to a trusted WPA2/WPA3 Wi-Fi network before sensitive actions."
        )
    }

    private fun resolveCurrentSecurity(ssid: String, bssid: String?, scanResults: List<ScanResult>): String {
        if (scanResults.isEmpty()) {
            return "unknown"
        }

        val normalizedBssid = bssid.orEmpty().trim().lowercase(Locale.US)
        val byBssid = scanResults.firstOrNull {
            it.BSSID.orEmpty().trim().lowercase(Locale.US) == normalizedBssid && normalizedBssid.isNotBlank()
        }
        val target = byBssid ?: scanResults.firstOrNull {
            normalizeSsid(it.SSID) == ssid && ssid.isNotBlank()
        }

        val capabilities = target?.capabilities.orEmpty()
        return when {
            capabilities.contains("WPA3", ignoreCase = true) ||
                capabilities.contains("SAE", ignoreCase = true) -> "wpa3"

            capabilities.contains("WPA2", ignoreCase = true) -> "wpa2"
            capabilities.contains("WPA", ignoreCase = true) -> "wpa"
            capabilities.contains("WEP", ignoreCase = true) -> "wep"
            capabilities.isBlank() || isOpenNetwork(capabilities) -> "open"
            else -> "unknown"
        }
    }

    private fun isOpenNetwork(capabilities: String): Boolean {
        val value = capabilities.uppercase(Locale.US)
        if (value.isBlank()) {
            return true
        }
        return listOf("WPA", "WEP", "SAE", "EAP", "OWE").none { value.contains(it) }
    }

    private fun isWeakNetwork(capabilities: String): Boolean {
        val value = capabilities.uppercase(Locale.US)
        return value.contains("WEP") ||
            (value.contains("WPA") && !value.contains("WPA2") && !value.contains("WPA3"))
    }

    private fun normalizeSsid(raw: String?): String {
        val value = raw.orEmpty().trim().removePrefix("\"").removeSuffix("\"")
        if (value.isBlank() || value == "<unknown ssid>") {
            return ""
        }
        return value
    }

    private fun maskBssid(raw: String?): String {
        val value = raw.orEmpty().trim()
        if (value.isBlank()) {
            return ""
        }
        val parts = value.split(":")
        if (parts.size < 2) {
            return "**:**"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return "${parts.firstOrNull().orEmpty()}:**:**:**:**:${parts.lastOrNull().orEmpty()}"
        }
        return "${parts.firstOrNull().orEmpty()}:**:**:**:**:${parts.lastOrNull().orEmpty()}"
    }
}
