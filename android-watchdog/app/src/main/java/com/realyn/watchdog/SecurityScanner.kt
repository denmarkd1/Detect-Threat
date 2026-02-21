package com.realyn.watchdog

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class Severity {
    HIGH,
    MEDIUM,
    LOW,
    INFO
}

data class WatchdogAlert(
    val severity: Severity,
    val title: String,
    val details: String
)

data class WatchdogSnapshot(
    val scannedAtEpochMs: Long,
    val thirdPartyPackages: Set<String>,
    val accessibilityServices: Set<String>,
    val deviceAdminPackages: Set<String>,
    val highRiskPermissions: Map<String, Set<String>>,
    val suspiciousPackageNames: Set<String>,
    val securitySignals: Map<String, String>
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("scannedAtEpochMs", scannedAtEpochMs)
        root.put("thirdPartyPackages", JSONArray(thirdPartyPackages.sorted()))
        root.put("accessibilityServices", JSONArray(accessibilityServices.sorted()))
        root.put("deviceAdminPackages", JSONArray(deviceAdminPackages.sorted()))

        val permissionsJson = JSONObject()
        highRiskPermissions.keys.sorted().forEach { pkg ->
            permissionsJson.put(pkg, JSONArray(highRiskPermissions[pkg]!!.sorted()))
        }
        root.put("highRiskPermissions", permissionsJson)

        root.put("suspiciousPackageNames", JSONArray(suspiciousPackageNames.sorted()))

        val signalsJson = JSONObject()
        securitySignals.keys.sorted().forEach { key ->
            signalsJson.put(key, securitySignals[key])
        }
        root.put("securitySignals", signalsJson)
        return root
    }

    companion object {
        fun fromJson(root: JSONObject): WatchdogSnapshot {
            val highRisk = mutableMapOf<String, Set<String>>()
            val highRiskJson = root.optJSONObject("highRiskPermissions") ?: JSONObject()
            highRiskJson.keys().forEach { pkg ->
                val values = mutableSetOf<String>()
                val arr = highRiskJson.optJSONArray(pkg) ?: JSONArray()
                for (i in 0 until arr.length()) {
                    values.add(arr.getString(i))
                }
                highRisk[pkg] = values
            }

            val signals = mutableMapOf<String, String>()
            val signalsJson = root.optJSONObject("securitySignals") ?: JSONObject()
            signalsJson.keys().forEach { key ->
                signals[key] = signalsJson.optString(key, "unknown")
            }

            return WatchdogSnapshot(
                scannedAtEpochMs = root.optLong("scannedAtEpochMs", System.currentTimeMillis()),
                thirdPartyPackages = arrayToSet(root.optJSONArray("thirdPartyPackages")),
                accessibilityServices = arrayToSet(root.optJSONArray("accessibilityServices")),
                deviceAdminPackages = arrayToSet(root.optJSONArray("deviceAdminPackages")),
                highRiskPermissions = highRisk,
                suspiciousPackageNames = arrayToSet(root.optJSONArray("suspiciousPackageNames")),
                securitySignals = signals
            )
        }

        private fun arrayToSet(array: JSONArray?): Set<String> {
            if (array == null) {
                return emptySet()
            }
            val out = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                out.add(array.getString(i))
            }
            return out
        }
    }
}

data class ScanResult(
    val snapshot: WatchdogSnapshot,
    val alerts: List<WatchdogAlert>,
    val baselineCreated: Boolean
)

object SecurityScanner {

    private val watchedPermissions = setOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.BODY_SENSORS"
    )

    private val suspiciousKeywords = setOf(
        "spy",
        "stalker",
        "track",
        "monitor",
        "stealth",
        "mspy",
        "flexispy",
        "hoverwatch",
        "truthspy",
        "mobiletracker"
    )

    fun runScan(context: Context, createBaselineIfMissing: Boolean = true): ScanResult {
        val snapshot = collectSnapshot(context)
        val baseline = loadBaseline(context)

        val result = if (baseline == null) {
            if (createBaselineIfMissing) {
                saveBaseline(context, snapshot)
            }
            val info = if (createBaselineIfMissing) {
                listOf(
                    WatchdogAlert(
                        severity = Severity.INFO,
                        title = "Baseline initialized",
                        details = "First trusted state was saved. Future scans compare against this baseline."
                    )
                )
            } else {
                emptyList()
            }
            ScanResult(snapshot = snapshot, alerts = info, baselineCreated = createBaselineIfMissing)
        } else {
            val alerts = compareSnapshots(baseline, snapshot)
            ScanResult(snapshot = snapshot, alerts = alerts, baselineCreated = false)
        }

        appendHistory(context, result)
        IncidentStore.syncFromScan(context, result)
        return result
    }

    fun summaryLine(result: ScanResult): String {
        if (result.baselineCreated) {
            return "Baseline created on this device."
        }
        val high = result.alerts.count { it.severity == Severity.HIGH }
        val medium = result.alerts.count { it.severity == Severity.MEDIUM }
        val low = result.alerts.count { it.severity == Severity.LOW }
        return if (result.alerts.isEmpty()) {
            "No suspicious changes detected against baseline."
        } else {
            "Alerts: high=$high, medium=$medium, low=$low"
        }
    }

    fun formatReport(result: ScanResult): String {
        val sb = StringBuilder()
        sb.appendLine("DT Scanner (Detect Treat) Report")
        sb.appendLine("Scanned: ${formatDisplayTime(result.snapshot.scannedAtEpochMs)}")
        sb.appendLine("Apps scanned: ${result.snapshot.thirdPartyPackages.size}")
        sb.appendLine("Accessibility services: ${result.snapshot.accessibilityServices.size}")
        sb.appendLine("Device-admin apps: ${result.snapshot.deviceAdminPackages.size}")
        sb.appendLine("Suspicious package names: ${result.snapshot.suspiciousPackageNames.size}")
        sb.appendLine()

        if (result.alerts.isEmpty()) {
            sb.appendLine("No suspicious baseline deviations detected.")
        } else {
            result.alerts.forEachIndexed { index, alert ->
                sb.appendLine("${index + 1}. [${alert.severity}] ${alert.title}")
                if (alert.details.isNotBlank()) {
                    sb.appendLine(alert.details)
                }
                sb.appendLine()
            }
        }

        return sb.toString().trim()
    }

    fun alertFingerprint(alerts: List<WatchdogAlert>): String {
        return alerts
            .map { "${it.severity}:${it.title}:${it.details}" }
            .sorted()
            .joinToString("|")
    }

    fun isContinuousModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getBoolean(WatchdogConfig.KEY_CONTINUOUS_MODE, false)
    }

    fun setContinuousModeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(WatchdogConfig.KEY_CONTINUOUS_MODE, enabled).apply()
    }

    fun readLastAlertFingerprint(context: Context): String {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getString(WatchdogConfig.KEY_LAST_ALERT_FINGERPRINT, "") ?: ""
    }

    fun readLastScanTimestamp(context: Context): String {
        val file = File(context.filesDir, WatchdogConfig.HISTORY_FILE)
        if (!file.exists()) {
            return "never"
        }

        val lastLine = runCatching {
            file.useLines { lines ->
                lines.lastOrNull { it.isNotBlank() }
            }
        }.getOrNull() ?: return "never"

        return try {
            val payload = JSONObject(lastLine)
            payload.optString("scannedAtIso").takeIf { it.isNotBlank() }
                ?: formatDisplayTime(payload.optLong("scannedAtEpochMs", 0L))
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun writeLastAlertFingerprint(context: Context, fingerprint: String) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(WatchdogConfig.KEY_LAST_ALERT_FINGERPRINT, fingerprint).apply()
    }

    private fun collectSnapshot(context: Context): WatchdogSnapshot {
        val packages = collectThirdPartyPackages(context)
        val accessibility = collectAccessibilityServices(context)
        val admins = collectDeviceAdmins(context)
        val risky = collectHighRiskPermissions(context, packages)
        val suspicious = packages.filter { pkg -> suspiciousKeywords.any { it in pkg.lowercase(Locale.US) } }.toSet()

        return WatchdogSnapshot(
            scannedAtEpochMs = System.currentTimeMillis(),
            thirdPartyPackages = packages,
            accessibilityServices = accessibility,
            deviceAdminPackages = admins,
            highRiskPermissions = risky,
            suspiciousPackageNames = suspicious,
            securitySignals = collectSecuritySignals(context)
        )
    }

    private fun compareSnapshots(base: WatchdogSnapshot, current: WatchdogSnapshot): List<WatchdogAlert> {
        val alerts = mutableListOf<WatchdogAlert>()

        val addedApps = (current.thirdPartyPackages - base.thirdPartyPackages).sorted()
        val removedApps = (base.thirdPartyPackages - current.thirdPartyPackages).sorted()
        if (addedApps.isNotEmpty()) {
            alerts += WatchdogAlert(
                severity = Severity.MEDIUM,
                title = "New third-party apps detected",
                details = addedApps.joinToString("\n") { "- $it" }
            )
        }
        if (removedApps.isNotEmpty()) {
            alerts += WatchdogAlert(
                severity = Severity.LOW,
                title = "Third-party apps removed",
                details = removedApps.joinToString("\n") { "- $it" }
            )
        }

        val newAccessibility = (current.accessibilityServices - base.accessibilityServices).sorted()
        if (newAccessibility.isNotEmpty()) {
            alerts += WatchdogAlert(
                severity = Severity.HIGH,
                title = "New accessibility services enabled",
                details = newAccessibility.joinToString("\n") { "- $it" }
            )
        }

        val newAdmins = (current.deviceAdminPackages - base.deviceAdminPackages).sorted()
        if (newAdmins.isNotEmpty()) {
            alerts += WatchdogAlert(
                severity = Severity.HIGH,
                title = "New device-admin apps enabled",
                details = newAdmins.joinToString("\n") { "- $it" }
            )
        }

        val suspiciousNow = current.suspiciousPackageNames.sorted()
        if (suspiciousNow.isNotEmpty()) {
            alerts += WatchdogAlert(
                severity = Severity.HIGH,
                title = "Suspicious package names found",
                details = suspiciousNow.joinToString("\n") { "- $it" }
            )
        }

        current.securitySignals.forEach { (key, value) ->
            val oldValue = base.securitySignals[key]
            if (oldValue != null && oldValue != value) {
                alerts += WatchdogAlert(
                    severity = Severity.MEDIUM,
                    title = "Security setting changed: $key",
                    details = "baseline=$oldValue current=$value"
                )
            }
        }

        current.highRiskPermissions.forEach { (pkg, permissionsNow) ->
            val previous = base.highRiskPermissions[pkg] ?: emptySet()
            val newlyGranted = (permissionsNow - previous).sorted()
            if (newlyGranted.isNotEmpty()) {
                val severity = if (pkg in addedApps) Severity.HIGH else Severity.MEDIUM
                alerts += WatchdogAlert(
                    severity = severity,
                    title = "New high-risk permissions: $pkg",
                    details = newlyGranted.joinToString("\n") { "- $it" }
                )
            }
        }

        return alerts
    }

    private fun collectThirdPartyPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }

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

    private fun collectHighRiskPermissions(
        context: Context,
        packages: Set<String>
    ): Map<String, Set<String>> {
        val pm = context.packageManager
        val result = mutableMapOf<String, Set<String>>()

        packages.forEach { pkg ->
            val info = getPackageInfoCompat(pm, pkg)
            val requested = info?.requestedPermissions ?: return@forEach
            val flags = info.requestedPermissionsFlags ?: IntArray(requested.size)

            val granted = mutableSetOf<String>()
            requested.forEachIndexed { index, permission ->
                if (permission !in watchedPermissions) {
                    return@forEachIndexed
                }

                val flag = flags.getOrNull(index) ?: 0
                val isGranted = (flag and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                if (isGranted) {
                    granted.add(permission)
                }
            }

            if (granted.isNotEmpty()) {
                result[pkg] = granted
            }
        }

        return result
    }

    private fun getPackageInfoCompat(pm: PackageManager, packageName: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun collectAccessibilityServices(context: Context): Set<String> {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        if (raw.isBlank()) {
            return emptySet()
        }

        return raw.split(":")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun collectDeviceAdmins(context: Context): Set<String> {
        val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return emptySet()
        return try {
            manager.activeAdmins
                ?.map { it.packageName }
                ?.toSet()
                ?: emptySet()
        } catch (_: SecurityException) {
            emptySet()
        }
    }

    @SuppressLint("HardwareIds")
    private fun collectSecuritySignals(context: Context): Map<String, String> {
        val out = mutableMapOf<String, String>()
        out["adb_enabled"] = readGlobalSetting(context, Settings.Global.ADB_ENABLED)
        out["dev_options_enabled"] = readGlobalSetting(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        out["install_non_market_apps"] = readSecureSetting(context, Settings.Secure.INSTALL_NON_MARKET_APPS)
        out["android_version"] = Build.VERSION.RELEASE ?: "unknown"
        out["sdk_int"] = Build.VERSION.SDK_INT.toString()
        return out
    }

    private fun readGlobalSetting(context: Context, name: String): String {
        return try {
            Settings.Global.getString(context.contentResolver, name) ?: "unset"
        } catch (_: SecurityException) {
            "restricted"
        }
    }

    private fun readSecureSetting(context: Context, name: String): String {
        return try {
            Settings.Secure.getString(context.contentResolver, name) ?: "unset"
        } catch (_: SecurityException) {
            "restricted"
        }
    }

    private fun loadBaseline(context: Context): WatchdogSnapshot? {
        val file = File(context.filesDir, WatchdogConfig.BASELINE_FILE)
        if (!file.exists()) {
            return null
        }
        return try {
            WatchdogSnapshot.fromJson(JSONObject(file.readText()))
        } catch (_: Exception) {
            null
        }
    }

    fun saveBaseline(context: Context, snapshot: WatchdogSnapshot) {
        val file = File(context.filesDir, WatchdogConfig.BASELINE_FILE)
        file.writeText(snapshot.toJson().toString(2))
    }

    private fun appendHistory(context: Context, result: ScanResult) {
        val file = File(context.filesDir, WatchdogConfig.HISTORY_FILE)
        val row = JSONObject()
        val scannedAtIso = formatIsoTime(result.snapshot.scannedAtEpochMs)
        row.put("scannedAtEpochMs", result.snapshot.scannedAtEpochMs)
        row.put("scannedAtIso", scannedAtIso)
        row.put("baselineCreated", result.baselineCreated)
        row.put("summary", summaryLine(result))

        val alerts = JSONArray()
        result.alerts.forEach { alert ->
            val item = JSONObject()
            item.put("severity", alert.severity.name)
            item.put("title", alert.title)
            item.put("details", alert.details)
            alerts.put(item)
        }
        row.put("alerts", alerts)

        file.appendText(row.toString() + "\n")
        appendAuditLine(context, result, scannedAtIso)
    }

    private fun appendAuditLine(context: Context, result: ScanResult, scannedAtIso: String) {
        val file = File(context.filesDir, WatchdogConfig.AUDIT_LOG_FILE)
        val high = result.alerts.count { it.severity == Severity.HIGH }
        val medium = result.alerts.count { it.severity == Severity.MEDIUM }
        val low = result.alerts.count { it.severity == Severity.LOW }
        val sanitizedSummary = summaryLine(result).replace("\n", " ").replace("\"", "'")
        val line = "$scannedAtIso summary=\"$sanitizedSummary\" alerts_total=${result.alerts.size} high=$high medium=$medium low=$low baseline_created=${result.baselineCreated}"
        file.appendText(line + "\n")
    }

    private fun formatDisplayTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
    }

    private fun formatIsoTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMs))
    }

}
