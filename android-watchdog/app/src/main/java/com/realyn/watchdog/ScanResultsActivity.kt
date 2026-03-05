package com.realyn.watchdog

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.realyn.watchdog.databinding.ActivityScanResultsBinding
import com.realyn.watchdog.theme.LionIdentityAccentStyle
import com.realyn.watchdog.theme.LionThemeCatalog
import com.realyn.watchdog.theme.LionThemePalette
import com.realyn.watchdog.theme.LionThemeViewStyler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanResultsActivity : AppCompatActivity() {

    private data class MaintenancePayload(
        val generatedAtEpochMs: Long,
        val appCacheBytes: Long,
        val staleArtifactCount: Int,
        val staleArtifactBytes: Long,
        val staleCompletedQueueCount: Int,
        val safeCleanupBytes: Long,
        val usageAccessGranted: Boolean,
        val inactiveAppCandidateCount: Int,
        val inactiveAppExamples: List<String>,
        val mediaReadAccessGranted: Boolean,
        val duplicateMediaGroupCount: Int,
        val duplicateMediaFileCount: Int,
        val duplicateMediaReclaimableBytes: Long,
        val duplicateMediaExamples: List<String>,
        val installerRemnantCount: Int,
        val installerRemnantBytes: Long,
        val installerRemnantExamples: List<String>
    )

    companion object {
        const val EXTRA_MODE_LABEL = "scan_results.extra.MODE_LABEL"
        const val EXTRA_SUMMARY_LINE = "scan_results.extra.SUMMARY_LINE"
        const val EXTRA_SCOPE_SUMMARY = "scan_results.extra.SCOPE_SUMMARY"
        const val EXTRA_REPORT_TEXT = "scan_results.extra.REPORT_TEXT"
        const val EXTRA_COMPLETED_AT_EPOCH_MS = "scan_results.extra.COMPLETED_AT_EPOCH_MS"
        const val EXTRA_HIGH_COUNT = "scan_results.extra.HIGH_COUNT"
        const val EXTRA_MEDIUM_COUNT = "scan_results.extra.MEDIUM_COUNT"
        const val EXTRA_LOW_COUNT = "scan_results.extra.LOW_COUNT"
        const val EXTRA_INFO_COUNT = "scan_results.extra.INFO_COUNT"
        const val EXTRA_MAINTENANCE_PAYLOAD_JSON = "scan_results.extra.MAINTENANCE_PAYLOAD_JSON"
    }

    private lateinit var binding: ActivityScanResultsBinding
    private var maintenancePayload: MaintenancePayload? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScanResultsTheme()

        val modeLabel = intent.getStringExtra(EXTRA_MODE_LABEL)
            .orEmpty()
            .ifBlank { getString(R.string.scan_results_mode_unknown) }
        val summaryLine = intent.getStringExtra(EXTRA_SUMMARY_LINE)
            .orEmpty()
            .ifBlank { getString(R.string.home_widget_loading) }
        val scopeSummary = intent.getStringExtra(EXTRA_SCOPE_SUMMARY)
            .orEmpty()
            .ifBlank { getString(R.string.home_widget_loading) }
        val reportText = intent.getStringExtra(EXTRA_REPORT_TEXT)
            .orEmpty()
            .ifBlank { getString(R.string.home_widget_loading) }
        val completedAtMs = intent.getLongExtra(EXTRA_COMPLETED_AT_EPOCH_MS, System.currentTimeMillis())
        val highCount = intent.getIntExtra(EXTRA_HIGH_COUNT, 0).coerceAtLeast(0)
        val mediumCount = intent.getIntExtra(EXTRA_MEDIUM_COUNT, 0).coerceAtLeast(0)
        val lowCount = intent.getIntExtra(EXTRA_LOW_COUNT, 0).coerceAtLeast(0)
        val infoCount = intent.getIntExtra(EXTRA_INFO_COUNT, 0).coerceAtLeast(0)
        maintenancePayload = parseMaintenancePayload(
            intent.getStringExtra(EXTRA_MAINTENANCE_PAYLOAD_JSON).orEmpty()
        )

        binding.scanResultsModeLabel.text = getString(R.string.scan_results_mode_template, modeLabel)
        binding.scanResultsCompletedLabel.text = getString(
            R.string.scan_results_completed_template,
            formatDisplayTime(completedAtMs)
        )
        binding.scanResultsScopeLabel.text = getString(R.string.scan_results_scope_template, scopeSummary)
        binding.scanResultsSummaryLabel.text = summaryLine
        binding.scanResultsCountsLabel.text = getString(
            R.string.scan_results_counts_template,
            highCount,
            mediumCount,
            lowCount,
            infoCount
        )
        binding.scanResultsRecommendationsLabel.text = buildRecommendations(
            highCount = highCount,
            mediumCount = mediumCount,
            lowCount = lowCount,
            infoCount = infoCount
        )
        binding.scanResultsReportTextLabel.text = reportText
        renderMaintenanceActions(maintenancePayload)

        binding.scanResultsStartIncidentButton.setOnClickListener {
            startIncidentGuidanceFlow()
        }
        binding.scanResultsOpenCredentialButton.setOnClickListener {
            startActivity(Intent(this, CredentialDefenseActivity::class.java))
        }
        binding.scanResultsBackHomeButton.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
            finish()
        }
        binding.scanResultsReviewDuplicatesButton.setOnClickListener {
            openDuplicateReviewDialog()
        }
        binding.scanResultsReviewUnusedAppsButton.setOnClickListener {
            openUnusedAppsDialog()
        }
        binding.scanResultsCleanSafeClutterButton.setOnClickListener {
            openSafeCleanupDialog()
        }
        binding.scanResultsReviewInstallerRemnantsButton.setOnClickListener {
            openInstallerRemnantsDialog()
        }
        binding.scanResultsOpenStorageSettingsButton.setOnClickListener {
            openStorageSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        applyScanResultsTheme()
    }

    private fun buildRecommendations(
        highCount: Int,
        mediumCount: Int,
        lowCount: Int,
        infoCount: Int
    ): String {
        val lines = mutableListOf<String>()
        if (highCount > 0) {
            lines += getString(R.string.scan_results_reco_high)
        }
        if (mediumCount > 0) {
            lines += getString(R.string.scan_results_reco_medium)
        }
        if (lowCount > 0 || infoCount > 0) {
            lines += getString(R.string.scan_results_reco_low_info)
        }
        if (lines.isEmpty()) {
            lines += getString(R.string.scan_results_reco_clear)
        }
        return lines.joinToString(separator = "\n")
    }

    private fun formatDisplayTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
    }

    private data class IncidentGuidance(
        val quickActionLabelResId: Int,
        val quickActionIntents: List<Intent>,
        val quickActionAuditTag: String,
        val confidence: String,
        val whyLine: String,
        val stepSignalMap: List<String>,
        val steps: List<String>
    )

    private data class IncidentContext(
        val moduleLabel: String,
        val score: Int?,
        val tier: String,
        val packageName: String,
        val network: String,
        val path: String,
        val finding: String,
        val recommendation: String,
        val signals: List<String>
    )

    private fun startIncidentGuidanceFlow() {
        val next = IncidentStore.nextUnresolvedForWork(this)
        if (next == null) {
            Toast.makeText(this, getString(R.string.incident_no_open), Toast.LENGTH_SHORT).show()
            return
        }
        val active = if (next.status == IncidentStatus.OPEN) {
            IncidentStore.markInProgress(this, next.incidentId) ?: next
        } else {
            next
        }
        showIncidentGuidanceDialog(active)
    }

    private fun showIncidentGuidanceDialog(incident: IncidentRecord) {
        val unresolved = IncidentStore.loadIncidents(this)
            .filter { it.status == IncidentStatus.OPEN || it.status == IncidentStatus.IN_PROGRESS }
        val highRemaining = unresolved.count { it.severity == Severity.HIGH }
        val mediumRemaining = unresolved.count { it.severity == Severity.MEDIUM }
        val lowRemaining = unresolved.count { it.severity == Severity.LOW }
        val guidance = buildIncidentGuidance(incident)
        val detailsPreview = incident.details
            .trim()
            .replace("\r", "")
            .ifBlank { getString(R.string.incident_guidance_details_unavailable) }
            .let { value ->
                if (value.length <= 420) value else "${value.take(417)}..."
            }
        val message = buildString {
            appendLine(
                getString(
                    R.string.incident_guidance_queue_template,
                    highRemaining,
                    mediumRemaining,
                    lowRemaining
                )
            )
            appendLine()
            appendLine(
                getString(
                    R.string.incident_guidance_incident_header_template,
                    incident.severity.name,
                    incident.title
                )
            )
            appendLine(detailsPreview)
            appendLine()
            appendLine(
                getString(
                    R.string.incident_guidance_confidence_template,
                    guidance.confidence
                )
            )
            appendLine(
                getString(
                    R.string.incident_guidance_why_template,
                    guidance.whyLine
                )
            )
            if (guidance.stepSignalMap.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.incident_guidance_signal_map_title))
                guidance.stepSignalMap.forEachIndexed { index, line ->
                    appendLine("${index + 1}. $line")
                }
            }
            appendLine()
            appendLine(getString(R.string.incident_guidance_steps_title))
            guidance.steps.forEachIndexed { index, step ->
                appendLine("${index + 1}. $step")
            }
        }.trim()
        val dialog = LionAlertDialogBuilder(this)
            .setTitle(R.string.incident_guidance_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.incident_guidance_mark_fixed_next) { _, _ ->
                val resolved = IncidentStore.markResolved(this, incident.incidentId)
                if (resolved == null) {
                    Toast.makeText(
                        this,
                        getString(R.string.incident_no_active),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                val remaining = IncidentStore.nextUnresolvedForWork(this)
                if (remaining == null) {
                    Toast.makeText(
                        this,
                        getString(R.string.incident_guidance_queue_complete),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                val candidate = if (remaining.status == IncidentStatus.OPEN) {
                    IncidentStore.markInProgress(this, remaining.incidentId) ?: remaining
                } else {
                    remaining
                }
                showIncidentGuidanceDialog(candidate)
            }
            .setNeutralButton(guidance.quickActionLabelResId) { _, _ ->
                val opened = guidance.quickActionIntents.any { intent ->
                    runCatching {
                        startActivity(intent)
                        true
                    }.getOrDefault(false)
                }
                if (!opened) {
                    Toast.makeText(
                        this,
                        getString(R.string.incident_guidance_open_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setNeutralButton
                }
                SafeHygieneToolkit.logMaintenanceAction(
                    context = this,
                    action = guidance.quickActionAuditTag,
                    detail = JSONObject()
                        .put("incidentId", incident.incidentId)
                        .put("severity", incident.severity.name)
                        .put("title", incident.title.take(120))
                )
            }
            .setNegativeButton(R.string.scan_results_cancel, null)
            .create()
        showStyledDialog(dialog)
    }

    private fun buildIncidentGuidance(incident: IncidentRecord): IncidentGuidance {
        val context = parseIncidentContext(incident)
        val module = context.moduleLabel.lowercase(Locale.US)
        if (module.contains("startup persistence")) {
            return buildStartupModuleGuidance(incident, context)
        }
        if (module.contains("storage")) {
            return buildStorageModuleGuidance(incident, context)
        }
        if (module.contains("embedded path probe")) {
            return buildEmbeddedModuleGuidance(incident, context)
        }
        if (module.contains("wi-fi posture") || module.contains("wifi posture")) {
            return buildWifiModuleGuidance(incident, context)
        }
        val lower = "${incident.title}\n${incident.details}".lowercase(Locale.US)
        if (lower.contains("accessibility")) {
            return IncidentGuidance(
                quickActionLabelResId = R.string.incident_guidance_open_accessibility,
                quickActionIntents = listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                quickActionAuditTag = "incident_guidance_open_accessibility",
                confidence = confidenceLabel(
                    incident = incident,
                    moduleDetected = false,
                    contextualSignals = 2
                ),
                whyLine = "Accessibility indicator parsed from incident title/details.",
                stepSignalMap = listOf(
                    "Step 1 maps to accessibility-service indicator.",
                    "Step 2 maps to app-origin containment for the same service.",
                    "Step 3 verifies the signal clears on re-scan.",
                    "Step 4 enforces queue progression only after verification."
                ),
                steps = listOf(
                    "Open Settings > Accessibility and disable suspicious services.",
                    "Open Settings > Apps > See all apps and uninstall unknown tools tied to the service.",
                    "Re-run deep scan and verify accessibility findings are cleared.",
                    "Use Mark fixed + next only after the finding no longer returns."
                )
            )
        }
        return buildCoreGuidance(incident, context)
    }

    private fun parseIncidentContext(incident: IncidentRecord): IncidentContext {
        val lines = incident.details
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        var moduleLabel = ""
        var score: Int? = null
        var tier = ""
        var packageName = ""
        var network = ""
        var path = ""
        var finding = ""
        var recommendation = ""
        val signals = mutableListOf<String>()

        lines.forEach { line ->
            when {
                line.startsWith("Module:", ignoreCase = true) -> {
                    val payload = line.substringAfter(":", "").trim()
                    moduleLabel = payload.substringBefore("|").trim()
                    score = Regex("""(?i)\bscore\s*:\s*(\d{1,3})\b""")
                        .find(payload)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?.coerceIn(0, 100)
                    tier = Regex("""(?i)\btier\s*:\s*([a-z_]+)\b""")
                        .find(payload)
                        ?.groupValues
                        ?.getOrNull(1)
                        .orEmpty()
                }
                line.startsWith("Package:", ignoreCase = true) -> {
                    packageName = line.substringAfter(":", "").trim()
                }
                line.startsWith("Path:", ignoreCase = true) -> {
                    path = line.substringAfter(":", "").trim()
                }
                line.startsWith("Network:", ignoreCase = true) -> {
                    network = line.substringAfter(":", "").trim()
                }
                line.startsWith("Resolved path:", ignoreCase = true) -> {
                    path = line.substringAfter(":", "").trim()
                }
                line.startsWith("Signals:", ignoreCase = true) -> {
                    val rawSignals = line.substringAfter(":", "").trim()
                    val splitSignals = if (rawSignals.contains(";")) {
                        rawSignals.split(";")
                    } else {
                        rawSignals.split(",")
                    }
                    splitSignals
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { signals += it }
                }
                line.startsWith("Finding:", ignoreCase = true) -> {
                    finding = line.substringAfter(":", "").trim()
                }
                line.startsWith("Recommendation:", ignoreCase = true) -> {
                    recommendation = line.substringAfter(":", "").trim()
                }
            }
        }

        if (packageName.isBlank()) {
            packageName = Regex("""(?im)package:\s*([a-zA-Z0-9._]+)""")
                .find(incident.details)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        }
        if (path.isBlank()) {
            path = Regex("""(?im)path:\s*([^\n]+)""")
                .find(incident.details)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        }
        if (packageName.isBlank()) {
            packageName = extractPackageFromPath(path)
        }
        if (finding.isBlank()) {
            finding = incident.title
        }
        return IncidentContext(
            moduleLabel = moduleLabel,
            score = score,
            tier = tier,
            packageName = packageName,
            network = network,
            path = path,
            finding = finding,
            recommendation = recommendation,
            signals = signals
        )
    }

    private fun extractPackageFromPath(path: String): String {
        val value = path.trim()
        if (value.isBlank()) {
            return ""
        }
        val candidates = listOf(
            Regex("""(?i)/android/data/([a-zA-Z0-9._]+)/"""),
            Regex("""(?i)/android/data/([a-zA-Z0-9._]+)$"""),
            Regex("""(?i)/data/data/([a-zA-Z0-9._]+)/"""),
            Regex("""(?i)/data/data/([a-zA-Z0-9._]+)$""")
        )
        candidates.forEach { pattern ->
            val match = pattern.find(value)
            val pkg = match?.groupValues?.getOrNull(1).orEmpty()
            if (pkg.contains(".")) {
                return pkg
            }
        }
        return ""
    }

    private fun buildStartupModuleGuidance(
        incident: IncidentRecord,
        context: IncidentContext
    ): IncidentGuidance {
        val packageRef = context.packageName.ifBlank { "flagged app" }
        val lowerSignals = context.signals.map { it.lowercase(Locale.US) }
        val hasAccessibility = lowerSignals.any { it.contains("accessibility") } ||
            incident.title.contains("accessibility", ignoreCase = true)
        val hasDeviceAdmin = lowerSignals.any { it.contains("device-admin") || it.contains("device admin") } ||
            incident.title.contains("device-admin", ignoreCase = true)
        val hasOverlay = lowerSignals.any { it.contains("overlay") } ||
            incident.title.contains("overlay", ignoreCase = true)
        val riskyPermissionSignal = lowerSignals.firstOrNull { it.contains("high-risk permissions") }.orEmpty()

        val intents = mutableListOf<Intent>()
        if (context.packageName.isNotBlank()) {
            intents += appDetailsIntent(context.packageName)
        }
        if (hasAccessibility) {
            intents += Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        if (hasOverlay) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context.packageName.isNotBlank()) {
                intents += Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intents += Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            }
        }
        if (hasDeviceAdmin) {
            intents += Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        if (intents.isEmpty()) {
            intents += Intent(Settings.ACTION_APPLICATION_SETTINGS)
        }

        val quickActionLabel = when {
            context.packageName.isNotBlank() -> R.string.incident_guidance_open_app_settings
            hasAccessibility -> R.string.incident_guidance_open_accessibility
            hasOverlay -> R.string.incident_guidance_open_overlay
            else -> R.string.incident_guidance_open_security
        }
        val matchedSignals = buildList {
            if (hasAccessibility) add("accessibility service component")
            if (hasDeviceAdmin) add("device-admin receiver")
            if (hasOverlay) add("overlay permission")
            if (riskyPermissionSignal.isNotBlank()) add(riskyPermissionSignal)
            if (isEmpty()) add("startup persistence profile match")
        }
        val signalsLine = if (riskyPermissionSignal.isBlank()) {
            "Review risky permissions for $packageRef and keep only what is required."
        } else {
            "Target permission signal: $riskyPermissionSignal"
        }
        return IncidentGuidance(
            quickActionLabelResId = quickActionLabel,
            quickActionIntents = intents.distinctBy { "${it.action}|${it.dataString.orEmpty()}" },
            quickActionAuditTag = "incident_guidance_startup_playbook",
            confidence = confidenceLabel(
                incident = incident,
                moduleDetected = true,
                contextualSignals = matchedSignals.size
            ),
            whyLine = "Parsed startup persistence indicators for $packageRef: ${matchedSignals.joinToString(", ")}.",
            stepSignalMap = buildList {
                add("Step 1 targets package context from parsed package/signal metadata.")
                add("Step 2 is driven by high-risk permission indicators.")
                add(
                    if (hasAccessibility) {
                        "Step 3 is triggered by accessibility-service detection."
                    } else {
                        "Step 3 enforces startup/autostart hardening because persistence behavior was detected."
                    }
                )
                add(
                    if (hasOverlay) {
                        "Step 4 is triggered by overlay permission signal."
                    } else {
                        "Step 4 verifies overlay is not silently enabled for this app."
                    }
                )
                add(
                    if (hasDeviceAdmin) {
                        "Step 5 is triggered by device-admin receiver signal."
                    } else {
                        "Step 5 provides containment when ownership/source trust is low."
                    }
                )
                add("Step 6 validates closure by rescanning for the same startup signal.")
            },
            steps = listOf(
                "Open Settings > Apps > See all apps > $packageRef.",
                "Tap Permissions and set unnecessary high-risk permissions to Deny. $signalsLine",
                if (hasAccessibility) "Open Settings > Accessibility and turn off services linked to $packageRef." else "Review special app access for autostart/background privileges and disable non-essential access.",
                if (hasOverlay) "Open Settings > Special app access > Display over other apps and disable overlay for $packageRef." else "Verify the app cannot draw over other apps unless explicitly required.",
                if (hasDeviceAdmin) "Open Settings > Security > Device admin apps and remove admin rights from $packageRef." else "If the app is unknown or unmanaged, uninstall it from Settings > Apps.",
                "Run deep scan again and move to the next incident only when this startup finding is cleared."
            )
        )
    }

    private fun buildStorageModuleGuidance(
        incident: IncidentRecord,
        context: IncidentContext
    ): IncidentGuidance {
        val pathRef = context.path.ifBlank { "recent Downloads/Files entries" }
        val packageFromPath = context.packageName
        val lower = "${incident.title}\n${incident.details}".lowercase(Locale.US)
        val pointsToDownloads = lower.contains("download") || pathRef.lowercase(Locale.US).contains("download")
        val intents = mutableListOf<Intent>()
        val quickActionLabel: Int
        if (packageFromPath.isNotBlank() && pathRef.lowercase(Locale.US).contains("/android/data/")) {
            quickActionLabel = R.string.incident_guidance_open_app_settings
            intents += appDetailsIntent(packageFromPath)
            intents += Intent(Settings.ACTION_APPLICATION_SETTINGS)
        } else if (pointsToDownloads) {
            quickActionLabel = R.string.scan_results_open_downloads
            intents += Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            intents += storageSettingsIntents()
        } else {
            quickActionLabel = R.string.scan_results_open_storage
            intents += storageSettingsIntents()
            intents += Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        }
        val packageStep = if (packageFromPath.isBlank()) {
            "If the artifact keeps returning, identify the responsible app and uninstall it from Settings > Apps."
        } else {
            "Open Settings > Apps > $packageFromPath and uninstall if suspicious artifacts keep regenerating."
        }
        return IncidentGuidance(
            quickActionLabelResId = quickActionLabel,
            quickActionIntents = intents.distinctBy { "${it.action}|${it.dataString.orEmpty()}" },
            quickActionAuditTag = "incident_guidance_storage_playbook",
            confidence = confidenceLabel(
                incident = incident,
                moduleDetected = true,
                contextualSignals = listOf(pathRef, packageFromPath, context.finding)
                    .count { it.isNotBlank() }
            ),
            whyLine = buildString {
                append("Parsed storage artifact signal")
                if (context.finding.isNotBlank()) {
                    append(": ${context.finding}")
                }
                if (pathRef.isNotBlank()) {
                    append(" | path=$pathRef")
                }
            },
            stepSignalMap = listOf(
                "Step 1 is driven by parsed artifact path/location.",
                "Step 2 maps to suspicious extension/keyword signals in storage finding.",
                "Step 3 removes artifacts associated with the exact storage indicator.",
                "Step 4 targets source-app containment when artifacts regenerate.",
                "Step 5 confirms remediation by rerunning deep storage sweep."
            ),
            steps = listOf(
                "Open Downloads/Files and navigate to: $pathRef.",
                "Inspect suspicious file names, extensions, and signals before opening anything.",
                "Delete untrusted payloads/scripts/APKs from this location and empty trash/recycle bin.",
                packageStep,
                "Run deep scan again and mark fixed only after the storage artifact signal no longer appears."
            )
        )
    }

    private fun buildEmbeddedModuleGuidance(
        incident: IncidentRecord,
        context: IncidentContext
    ): IncidentGuidance {
        val lower = "${incident.title}\n${incident.details}".lowercase(Locale.US)
        val hasFrida = lower.contains("frida")
        val intents = mutableListOf<Intent>()
        val quickActionLabel: Int
        if (hasFrida) {
            quickActionLabel = R.string.incident_guidance_open_developer_options
            intents += Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intents += Intent(Settings.ACTION_SECURITY_SETTINGS)
        } else {
            quickActionLabel = R.string.incident_guidance_open_security
            intents += Intent(Settings.ACTION_SECURITY_SETTINGS)
            intents += Intent(Settings.ACTION_APPLICATION_SETTINGS)
        }
        val pathLine = if (context.path.isBlank()) {
            "Review rooted tool artifacts referenced in the finding details."
        } else {
            "Investigate and remove the flagged artifact path if present: ${context.path}"
        }
        val fridaStep = if (hasFrida) {
            "Open Settings > System > Developer options and disable USB debugging/Wireless debugging when not needed."
        } else {
            "Verify Unknown app installs are disabled for browsers/file managers in Settings > Security."
        }
        return IncidentGuidance(
            quickActionLabelResId = quickActionLabel,
            quickActionIntents = intents.distinctBy { "${it.action}|${it.dataString.orEmpty()}" },
            quickActionAuditTag = "incident_guidance_embedded_playbook",
            confidence = confidenceLabel(
                incident = incident,
                moduleDetected = true,
                contextualSignals = listOf(context.finding, context.path)
                    .count { it.isNotBlank() } + if (hasFrida) 1 else 0
            ),
            whyLine = buildString {
                append("Embedded-path/root indicator parsed")
                if (context.finding.isNotBlank()) {
                    append(": ${context.finding}")
                } else {
                    append(": ${incident.title}")
                }
                if (context.path.isNotBlank()) {
                    append(" | path=${context.path}")
                }
            },
            stepSignalMap = listOf(
                "Step 1 is triggered by root/instrumentation indicator severity.",
                if (hasFrida) {
                    "Step 2 specifically maps to Frida/instrumentation signal."
                } else {
                    "Step 2 maps to generic root/injection hardening requirements."
                },
                "Step 3 maps to removal of associated toolchain apps.",
                "Step 4 maps to parsed path artifact inspection/removal.",
                "Step 5 confirms closure by reboot + deep rescan."
            ),
            steps = listOf(
                "Open Settings > Security and run Play Protect scan immediately.",
                fridaStep,
                "Open Settings > Apps and remove unknown root-management/instrumentation apps linked to this signal.",
                pathLine,
                "Reboot device, run deep scan again, and only then mark fixed if embedded-path findings clear."
            )
        )
    }

    private fun buildWifiModuleGuidance(
        incident: IncidentRecord,
        context: IncidentContext
    ): IncidentGuidance {
        val finding = context.finding.ifBlank { incident.title }
        val recommendation = context.recommendation.ifBlank {
            "Use trusted WPA2/WPA3 networks before any sensitive account actions."
        }
        val networkLabel = buildString {
            if (context.network.isNotBlank()) {
                append(context.network)
            }
            if (context.tier.isNotBlank() || context.score != null) {
                if (isNotBlank()) {
                    append(" | ")
                }
                append("tier=${context.tier.ifBlank { "unknown" }} score=${context.score ?: -1}")
            }
        }
        return IncidentGuidance(
            quickActionLabelResId = R.string.incident_guidance_open_wifi,
            quickActionIntents = listOf(Intent(Settings.ACTION_WIFI_SETTINGS)),
            quickActionAuditTag = "incident_guidance_wifi_playbook",
            confidence = confidenceLabel(
                incident = incident,
                moduleDetected = true,
                contextualSignals = listOf(context.finding, context.recommendation, context.network)
                    .count { it.isNotBlank() }
            ),
            whyLine = buildString {
                append("Wi-Fi posture finding parsed: $finding")
                if (context.network.isNotBlank()) {
                    append(" | network=${context.network}")
                }
                if (context.score != null) {
                    append(" | score=${context.score}")
                }
            },
            stepSignalMap = listOf(
                "Step 1 opens Wi-Fi controls because finding source is Wi-Fi posture module.",
                "Step 2 maps to open/weak/captive-network indicators.",
                "Step 3 maps to repeated-SSID/open-nearby risk controls.",
                "Step 4 maps to secure-channel requirement before sensitive actions.",
                "Step 5 validates that the same Wi-Fi finding is cleared on re-scan.",
                "Step 6 enforces module recommendation generated from posture evaluator."
            ),
            steps = listOf(
                "Open Settings > Network & internet > Wi-Fi.",
                "Tap current SSID details and disconnect/forget networks that are open, weak, or captive-portal based.",
                "Disable auto-join for unknown/open hotspots and keep only trusted networks saved.",
                "Reconnect on a verified WPA2/WPA3 network before credentials, banking, or email actions.",
                "Re-run Wi-Fi posture scan. Finding: $finding",
                "Apply recommendation: $recommendation${if (networkLabel.isBlank()) "" else " ($networkLabel)"}"
            )
        )
    }

    private fun buildCoreGuidance(
        incident: IncidentRecord,
        context: IncidentContext
    ): IncidentGuidance {
        val packageName = if (context.packageName.isBlank()) {
            Regex("""(?i)new high-risk permissions:\s*([a-zA-Z0-9._]+)""")
                .find(incident.title)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        } else {
            context.packageName
        }
        val intents = mutableListOf<Intent>()
        if (packageName.isNotBlank()) {
            intents += appDetailsIntent(packageName)
        }
        intents += Intent(Settings.ACTION_SECURITY_SETTINGS)
        val quickActionLabel = if (packageName.isNotBlank()) {
            R.string.incident_guidance_open_app_settings
        } else {
            R.string.incident_guidance_open_security
        }
        return IncidentGuidance(
            quickActionLabelResId = quickActionLabel,
            quickActionIntents = intents.distinctBy { "${it.action}|${it.dataString.orEmpty()}" },
            quickActionAuditTag = "incident_guidance_core_playbook",
            confidence = confidenceLabel(
                incident = incident,
                moduleDetected = false,
                contextualSignals = listOf(packageName, context.finding, context.path)
                    .count { it.isNotBlank() }
            ),
            whyLine = buildString {
                append("Core incident routing matched title/details")
                if (packageName.isNotBlank()) {
                    append(" | package=$packageName")
                }
                if (context.finding.isNotBlank()) {
                    append(" | finding=${context.finding}")
                }
            },
            stepSignalMap = listOf(
                "Step 1 uses parsed package context when available.",
                "Step 2 maps to high-risk permission/capability indicators.",
                "Step 3 maps to unresolved trust signals after permission hardening.",
                "Step 4 verifies remediation by rescanning for the same core finding."
            ),
            steps = listOf(
                if (packageName.isBlank()) {
                    "Open Settings > Security and review protections related to this finding."
                } else {
                    "Open Settings > Apps > $packageName and inspect granted permissions."
                },
                "Disable risky capabilities that are not required (SMS, call log, contacts, overlay, admin).",
                "Uninstall unknown/suspicious apps if behavior persists after permission cleanup.",
                "Run scan again and mark fixed only when this core finding no longer appears."
            )
        )
    }

    private fun appDetailsIntent(packageName: String): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
    }

    private fun storageSettingsIntents(): List<Intent> {
        return listOf(
            Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        )
    }

    private fun confidenceLabel(
        incident: IncidentRecord,
        moduleDetected: Boolean,
        contextualSignals: Int
    ): String {
        var score = 0
        if (moduleDetected) {
            score += 50
        }
        score += (contextualSignals * 12).coerceAtMost(36)
        score += when (incident.severity) {
            Severity.HIGH -> 14
            Severity.MEDIUM -> 8
            Severity.LOW -> 4
            Severity.INFO -> 0
        }
        val bounded = score.coerceIn(0, 100)
        val tier = when {
            bounded >= 80 -> "high"
            bounded >= 60 -> "medium"
            else -> "low"
        }
        return "$tier ($bounded/100)"
    }

    private fun renderMaintenanceActions(payload: MaintenancePayload?) {
        if (payload == null) {
            binding.scanResultsMaintenanceActionsTitleLabel.visibility = android.view.View.GONE
            binding.scanResultsMaintenanceRowOne.visibility = android.view.View.GONE
            binding.scanResultsMaintenanceRowTwo.visibility = android.view.View.GONE
            binding.scanResultsOpenStorageSettingsButton.visibility = android.view.View.GONE
            return
        }

        val showDuplicates = payload.duplicateMediaGroupCount > 0 || !payload.mediaReadAccessGranted
        val showUnusedApps = payload.inactiveAppCandidateCount > 0 || !payload.usageAccessGranted
        val showSafeCleanup = payload.appCacheBytes > 0L ||
            payload.staleArtifactCount > 0 ||
            payload.staleCompletedQueueCount > 0
        val showInstallerRemnants = payload.installerRemnantCount > 0 || !payload.mediaReadAccessGranted
        binding.scanResultsReviewDuplicatesButton.visibility = if (showDuplicates) android.view.View.VISIBLE else android.view.View.GONE
        binding.scanResultsReviewUnusedAppsButton.visibility = if (showUnusedApps) android.view.View.VISIBLE else android.view.View.GONE
        binding.scanResultsCleanSafeClutterButton.visibility = if (showSafeCleanup) android.view.View.VISIBLE else android.view.View.GONE
        binding.scanResultsReviewInstallerRemnantsButton.visibility = if (showInstallerRemnants) android.view.View.VISIBLE else android.view.View.GONE

        val showRowOne = showDuplicates || showUnusedApps
        val showRowTwo = showSafeCleanup || showInstallerRemnants
        val showAny = showRowOne || showRowTwo
        binding.scanResultsMaintenanceActionsTitleLabel.visibility = if (showAny) android.view.View.VISIBLE else android.view.View.GONE
        binding.scanResultsMaintenanceRowOne.visibility = if (showRowOne) android.view.View.VISIBLE else android.view.View.GONE
        binding.scanResultsMaintenanceRowTwo.visibility = if (showRowTwo) android.view.View.VISIBLE else android.view.View.GONE
        binding.scanResultsOpenStorageSettingsButton.visibility = if (showAny) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun parseMaintenancePayload(raw: String): MaintenancePayload? {
        val normalized = raw.trim()
        if (normalized.isBlank()) {
            return null
        }
        val payload = runCatching { JSONObject(normalized) }.getOrNull() ?: return null
        return MaintenancePayload(
            generatedAtEpochMs = payload.optLong("generatedAtEpochMs", 0L).coerceAtLeast(0L),
            appCacheBytes = payload.optLong("appCacheBytes", 0L).coerceAtLeast(0L),
            staleArtifactCount = payload.optInt("staleArtifactCount", 0).coerceAtLeast(0),
            staleArtifactBytes = payload.optLong("staleArtifactBytes", 0L).coerceAtLeast(0L),
            staleCompletedQueueCount = payload.optInt("staleCompletedQueueCount", 0).coerceAtLeast(0),
            safeCleanupBytes = payload.optLong("safeCleanupBytes", 0L).coerceAtLeast(0L),
            usageAccessGranted = payload.optBoolean("usageAccessGranted", false),
            inactiveAppCandidateCount = payload.optInt("inactiveAppCandidateCount", 0).coerceAtLeast(0),
            inactiveAppExamples = payload.optJSONArray("inactiveAppExamples").toStringList(),
            mediaReadAccessGranted = payload.optBoolean("mediaReadAccessGranted", false),
            duplicateMediaGroupCount = payload.optInt("duplicateMediaGroupCount", 0).coerceAtLeast(0),
            duplicateMediaFileCount = payload.optInt("duplicateMediaFileCount", 0).coerceAtLeast(0),
            duplicateMediaReclaimableBytes = payload.optLong("duplicateMediaReclaimableBytes", 0L).coerceAtLeast(0L),
            duplicateMediaExamples = payload.optJSONArray("duplicateMediaExamples").toStringList(),
            installerRemnantCount = payload.optInt("installerRemnantCount", 0).coerceAtLeast(0),
            installerRemnantBytes = payload.optLong("installerRemnantBytes", 0L).coerceAtLeast(0L),
            installerRemnantExamples = payload.optJSONArray("installerRemnantExamples").toStringList()
        )
    }

    private fun openDuplicateReviewDialog() {
        val payload = maintenancePayload ?: return showMaintenanceUnavailableToast()
        SafeHygieneToolkit.logMaintenanceAction(
            context = this,
            action = "review_duplicates_opened",
            detail = JSONObject()
                .put("groupCount", payload.duplicateMediaGroupCount)
                .put("fileCount", payload.duplicateMediaFileCount)
        )
        val message = buildString {
            appendLine(getString(R.string.scan_results_hygiene_health_title))
            appendLine(
                getString(
                    R.string.scan_results_hygiene_duplicates_template,
                    payload.duplicateMediaGroupCount,
                    payload.duplicateMediaFileCount,
                    SafeHygieneToolkit.formatBytes(payload.duplicateMediaReclaimableBytes)
                )
            )
            if (!payload.mediaReadAccessGranted) {
                appendLine()
                appendLine(getString(R.string.hygiene_health_duplicate_permission_missing))
            }
            appendLine()
            appendLine(getString(R.string.scan_results_hygiene_examples_title))
            if (payload.duplicateMediaExamples.isEmpty()) {
                append(getString(R.string.scan_results_hygiene_none))
            } else {
                payload.duplicateMediaExamples.forEachIndexed { index, example ->
                    appendLine("${index + 1}. $example")
                }
            }
        }.trim()
        showStyledDialog(
            LionAlertDialogBuilder(this)
                .setTitle(R.string.scan_results_duplicates_dialog_title)
                .setMessage(message)
                .setPositiveButton(R.string.scan_results_open_storage) { _, _ ->
                    openStorageSettings()
                }
                .setNegativeButton(R.string.scan_results_cancel, null)
                .create()
        )
    }

    private fun openUnusedAppsDialog() {
        val payload = maintenancePayload ?: return showMaintenanceUnavailableToast()
        SafeHygieneToolkit.logMaintenanceAction(
            context = this,
            action = "review_unused_apps_opened",
            detail = JSONObject().put("candidateCount", payload.inactiveAppCandidateCount)
        )
        val message = buildString {
            appendLine(getString(R.string.scan_results_hygiene_health_title))
            if (payload.usageAccessGranted) {
                appendLine(
                    getString(
                        R.string.scan_results_hygiene_unused_template,
                        payload.inactiveAppCandidateCount
                    )
                )
            } else {
                appendLine(getString(R.string.scan_results_hygiene_unused_permission_missing))
            }
            appendLine()
            appendLine(getString(R.string.scan_results_hygiene_examples_title))
            if (payload.inactiveAppExamples.isEmpty()) {
                append(getString(R.string.scan_results_hygiene_none))
            } else {
                payload.inactiveAppExamples.forEachIndexed { index, example ->
                    appendLine("${index + 1}. $example")
                }
            }
        }.trim()
        showStyledDialog(
            LionAlertDialogBuilder(this)
                .setTitle(R.string.scan_results_unused_dialog_title)
                .setMessage(message)
                .setPositiveButton(R.string.scan_results_open_usage_access) { _, _ ->
                    openUsageAccessSettings()
                }
                .setNeutralButton(R.string.scan_results_open_storage) { _, _ ->
                    openStorageSettings()
                }
                .setNegativeButton(R.string.scan_results_cancel, null)
                .create()
        )
    }

    private fun openInstallerRemnantsDialog() {
        val payload = maintenancePayload ?: return showMaintenanceUnavailableToast()
        SafeHygieneToolkit.logMaintenanceAction(
            context = this,
            action = "review_installer_remnants_opened",
            detail = JSONObject()
                .put("count", payload.installerRemnantCount)
                .put("bytes", payload.installerRemnantBytes)
        )
        val message = buildString {
            appendLine(getString(R.string.scan_results_hygiene_health_title))
            if (payload.mediaReadAccessGranted) {
                appendLine(
                    getString(
                        R.string.scan_results_hygiene_installer_template,
                        payload.installerRemnantCount,
                        SafeHygieneToolkit.formatBytes(payload.installerRemnantBytes)
                    )
                )
            } else {
                appendLine(getString(R.string.scan_results_hygiene_installer_permission_missing))
            }
            appendLine()
            appendLine(getString(R.string.scan_results_hygiene_examples_title))
            if (payload.installerRemnantExamples.isEmpty()) {
                append(getString(R.string.scan_results_hygiene_none))
            } else {
                payload.installerRemnantExamples.forEachIndexed { index, example ->
                    appendLine("${index + 1}. $example")
                }
            }
        }.trim()
        showStyledDialog(
            LionAlertDialogBuilder(this)
                .setTitle(R.string.scan_results_installer_dialog_title)
                .setMessage(message)
                .setPositiveButton(R.string.scan_results_open_downloads) { _, _ ->
                    openDownloadsManager()
                }
                .setNeutralButton(R.string.scan_results_open_storage) { _, _ ->
                    openStorageSettings()
                }
                .setNegativeButton(R.string.scan_results_cancel, null)
                .create()
        )
    }

    private fun openSafeCleanupDialog() {
        val payload = maintenancePayload ?: return showMaintenanceUnavailableToast()
        val options = arrayOf(
            getString(R.string.scan_results_cleanup_option_cache),
            getString(R.string.scan_results_cleanup_option_artifacts),
            getString(R.string.scan_results_cleanup_option_queue)
        )
        val checks = booleanArrayOf(
            payload.appCacheBytes > 0L,
            payload.staleArtifactCount > 0,
            payload.staleCompletedQueueCount > 0
        )
        if (!checks.any { it }) {
            checks[0] = true
        }
        val summary = buildString {
            appendLine(getString(R.string.scan_results_hygiene_health_title))
            appendLine(
                getString(
                    R.string.hygiene_health_safe_reclaim_template,
                    SafeHygieneToolkit.formatBytes(payload.safeCleanupBytes)
                )
            )
            appendLine(
                getString(
                    R.string.scan_results_hygiene_installer_template,
                    payload.installerRemnantCount,
                    SafeHygieneToolkit.formatBytes(payload.installerRemnantBytes)
                )
            )
            appendLine()
            append(getString(R.string.scan_results_cleanup_dialog_message_template))
        }.trim()
        val dialog = LionAlertDialogBuilder(this)
            .setTitle(R.string.scan_results_cleanup_dialog_title)
            .setMessage(summary)
            .setMultiChoiceItems(options, checks) { _, which, isChecked ->
                checks[which] = isChecked
            }
            .setPositiveButton(R.string.scan_results_confirm) { _, _ ->
                val selection = HygieneCleanupSelection(
                    clearCache = checks[0],
                    removeStaleArtifacts = checks[1],
                    trimCompletedQueue = checks[2]
                )
                if (!selection.hasAnySelection()) {
                    Toast.makeText(
                        this,
                        R.string.scan_results_cleanup_selection_required,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                applySafeCleanup(selection)
            }
            .setNegativeButton(R.string.scan_results_cancel, null)
            .create()
        showStyledDialog(dialog)
    }

    private fun applySafeCleanup(selection: HygieneCleanupSelection) {
        SafeHygieneToolkit.logMaintenanceAction(
            context = this,
            action = "safe_cleanup_requested_from_scan_results",
            detail = JSONObject()
                .put("clearCache", selection.clearCache)
                .put("removeStaleArtifacts", selection.removeStaleArtifacts)
                .put("trimCompletedQueue", selection.trimCompletedQueue)
        )
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SafeHygieneToolkit.runSafeCleanup(
                    context = this@ScanResultsActivity,
                    selection = selection
                )
            }
            val changed = result.reclaimedCacheBytes > 0L ||
                result.reclaimedArtifactBytes > 0L ||
                result.removedArtifactCount > 0 ||
                result.removedCompletedQueueActions > 0
            Toast.makeText(
                this@ScanResultsActivity,
                if (changed) {
                    getString(
                        R.string.scan_results_cleanup_complete_template,
                        SafeHygieneToolkit.formatBytes(result.reclaimedCacheBytes),
                        SafeHygieneToolkit.formatBytes(result.reclaimedArtifactBytes),
                        result.removedArtifactCount,
                        result.removedCompletedQueueActions
                    )
                } else {
                    getString(R.string.scan_results_cleanup_no_changes)
                },
                Toast.LENGTH_LONG
            ).show()
            val refreshed = withContext(Dispatchers.IO) {
                SafeHygieneToolkit.runAudit(this@ScanResultsActivity)
            }
            maintenancePayload = parseMaintenancePayload(
                JSONObject()
                    .put("generatedAtEpochMs", refreshed.generatedAtEpochMs)
                    .put("appCacheBytes", refreshed.appCacheBytes)
                    .put("staleArtifactCount", refreshed.staleArtifactCount)
                    .put("staleArtifactBytes", refreshed.staleArtifactBytes)
                    .put("staleCompletedQueueCount", refreshed.staleCompletedQueueCount)
                    .put("safeCleanupBytes", refreshed.healthReport.safeCleanupBytes)
                    .put("usageAccessGranted", refreshed.healthReport.usageAccessGranted)
                    .put("inactiveAppCandidateCount", refreshed.healthReport.inactiveAppCandidateCount)
                    .put("inactiveAppExamples", JSONArray(refreshed.healthReport.inactiveAppExamples))
                    .put("mediaReadAccessGranted", refreshed.healthReport.mediaReadAccessGranted)
                    .put("duplicateMediaGroupCount", refreshed.healthReport.duplicateMediaGroupCount)
                    .put("duplicateMediaFileCount", refreshed.healthReport.duplicateMediaFileCount)
                    .put("duplicateMediaReclaimableBytes", refreshed.healthReport.duplicateMediaReclaimableBytes)
                    .put("duplicateMediaExamples", JSONArray(refreshed.healthReport.duplicateMediaExamples))
                    .put("installerRemnantCount", refreshed.healthReport.installerRemnantCount)
                    .put("installerRemnantBytes", refreshed.healthReport.installerRemnantBytes)
                    .put("installerRemnantExamples", JSONArray(refreshed.healthReport.installerRemnantExamples))
                    .toString()
            )
            renderMaintenanceActions(maintenancePayload)
        }
    }

    private fun openUsageAccessSettings() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            true
        }.getOrDefault(false)
        if (!opened) {
            Toast.makeText(this, R.string.scan_results_usage_access_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        SafeHygieneToolkit.logMaintenanceAction(
            context = this,
            action = "open_usage_access_settings"
        )
    }

    private fun openStorageSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        )
        val opened = intents.any { intent ->
            runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
        }
        if (!opened) {
            Toast.makeText(this, R.string.scan_results_storage_settings_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        SafeHygieneToolkit.logMaintenanceAction(
            context = this,
            action = "open_storage_settings"
        )
    }

    private fun openDownloadsManager() {
        val opened = runCatching {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            true
        }.getOrDefault(false)
        if (!opened) {
            Toast.makeText(this, R.string.scan_results_downloads_open_failed, Toast.LENGTH_SHORT).show()
            openStorageSettings()
            return
        }
        SafeHygieneToolkit.logMaintenanceAction(
            context = this,
            action = "open_downloads_manager"
        )
    }

    private fun showMaintenanceUnavailableToast() {
        Toast.makeText(this, R.string.scan_results_maintenance_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null || this.length() <= 0) {
            return emptyList()
        }
        val values = mutableListOf<String>()
        for (index in 0 until this.length()) {
            val value = this.optString(index).trim()
            if (value.isNotBlank()) {
                values += value
            }
        }
        return values
    }

    private fun showStyledDialog(dialog: AlertDialog) {
        dialog.show()
        LionDialogStyler.applyForActivity(this, dialog)
    }

    private fun applyScanResultsTheme() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val selectedBitmap = LionThemePrefs.resolveSelectedLionBitmap(this)
        val themeState = LionThemeCatalog.resolveState(
            context = this,
            paidAccess = access.paidAccess,
            selectedLionBitmap = selectedBitmap
        )
        applyThemePalette(
            palette = themeState.palette,
            isDarkTone = themeState.isDark,
            accentStyle = themeState.accentStyle
        )
    }

    private fun applyThemePalette(
        palette: LionThemePalette,
        isDarkTone: Boolean,
        accentStyle: LionIdentityAccentStyle
    ) {
        window.statusBarColor = palette.backgroundEnd
        window.navigationBarColor = palette.backgroundEnd
        val systemBarController = WindowCompat.getInsetsController(window, binding.root)
        systemBarController.isAppearanceLightStatusBars = !isDarkTone
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemBarController.isAppearanceLightNavigationBars = !isDarkTone
        }

        binding.root.background = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(
                palette.backgroundStart,
                palette.backgroundCenter,
                palette.backgroundEnd
            )
        }

        binding.scanResultsTitleLabel.setTextColor(palette.textPrimary)
        binding.scanResultsSubtitleLabel.setTextColor(palette.textSecondary)
        binding.scanResultsModeLabel.setTextColor(palette.textPrimary)
        binding.scanResultsCompletedLabel.setTextColor(palette.textSecondary)
        binding.scanResultsScopeLabel.setTextColor(palette.textSecondary)
        binding.scanResultsSummaryLabel.setTextColor(palette.textPrimary)
        binding.scanResultsCountsLabel.setTextColor(palette.textSecondary)
        binding.scanResultsRecommendationsLabel.setTextColor(palette.textPrimary)
        binding.scanResultsMaintenanceActionsTitleLabel.setTextColor(palette.textPrimary)
        binding.scanResultsReportTitleLabel.setTextColor(palette.textPrimary)
        binding.scanResultsReportTextLabel.setTextColor(palette.textSecondary)

        applyDepthCardPalette(
            card = binding.scanResultsSummaryCard,
            palette = palette,
            accentStyle = accentStyle
        )
        applyDepthCardPalette(
            card = binding.scanResultsReportCard,
            palette = palette,
            accentStyle = accentStyle
        )
        LionThemeViewStyler.applyMaterialButtonPalette(
            root = binding.root,
            palette = palette,
            accentStyle = accentStyle
        )
        LionThemeViewStyler.installMaterialButtonTouchFeedback(
            root = binding.root,
            accentStyle = accentStyle
        )
    }

    private fun applyDepthCardPalette(
        card: MaterialCardView,
        palette: LionThemePalette,
        accentStyle: LionIdentityAccentStyle
    ) {
        val rawCornerRadiusDp = card.radius / resources.displayMetrics.density
        val cornerRadiusDp = (if (rawCornerRadiusDp > 0f) rawCornerRadiusDp else 14f) * accentStyle.cornerScale
        val topColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(palette.panelAlt, Color.WHITE, 0.13f),
            236
        )
        val bottomColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(palette.panelAlt, palette.backgroundEnd, 0.28f),
            222
        )
        card.background = createDepthSurfaceDrawable(
            topColor = topColor,
            bottomColor = bottomColor,
            strokeColor = ColorUtils.setAlphaComponent(
                ColorUtils.blendARGB(
                    palette.stroke,
                    palette.accent,
                    accentStyle.buttonStrokeAccentBlend.coerceIn(0f, 1f) * 0.78f
                ),
                216
            ),
            cornerRadiusDp = cornerRadiusDp,
            glossAlpha = 58,
            shadowAlpha = 82,
            innerStrokeAlpha = 38
        )
        card.setCardBackgroundColor(Color.TRANSPARENT)
        card.strokeWidth = 0
        card.cardElevation = dpToPx(3f).toFloat()
        card.translationZ = dpToPx(1f).toFloat()
        card.preventCornerOverlap = false
    }

    private fun createDepthSurfaceDrawable(
        @androidx.annotation.ColorInt topColor: Int,
        @androidx.annotation.ColorInt bottomColor: Int,
        @androidx.annotation.ColorInt strokeColor: Int,
        cornerRadiusDp: Float,
        glossAlpha: Int = 52,
        shadowAlpha: Int = 76,
        innerStrokeAlpha: Int = 32
    ): LayerDrawable {
        val cornerRadiusPx = dpToPx(cornerRadiusDp).toFloat()
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, bottomColor)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }
        val shadow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(Color.BLACK, shadowAlpha.coerceIn(0, 255))
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, glossAlpha.coerceIn(0, 255)),
                Color.TRANSPARENT
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }
        val outerRim = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(Color.TRANSPARENT)
            setStroke(dpToPx(1f), strokeColor)
        }
        val innerRim = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (cornerRadiusPx - dpToPx(1f).toFloat()).coerceAtLeast(0f)
            setColor(Color.TRANSPARENT)
            setStroke(
                dpToPx(1f),
                ColorUtils.setAlphaComponent(Color.WHITE, innerStrokeAlpha.coerceIn(0, 255))
            )
        }
        return LayerDrawable(arrayOf(base, shadow, gloss, outerRim, innerRim)).apply {
            val inset = dpToPx(1f)
            setLayerInset(4, inset, inset, inset, inset)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
