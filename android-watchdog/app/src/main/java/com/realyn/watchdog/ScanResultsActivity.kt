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
        val quickActionIntent: Intent,
        val quickActionAuditTag: String,
        val steps: List<String>
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
                val opened = runCatching {
                    startActivity(guidance.quickActionIntent)
                    true
                }.getOrDefault(false)
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
        val lower = "${incident.title}\n${incident.details}".lowercase(Locale.US)
        val packageName = Regex("""(?im)package:\s*([a-zA-Z0-9._]+)""")
            .find(incident.details)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val path = Regex("""(?im)path:\s*([^\n]+)""")
            .find(incident.details)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        if (lower.contains("accessibility")) {
            return IncidentGuidance(
                quickActionLabelResId = R.string.incident_guidance_open_accessibility,
                quickActionIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                quickActionAuditTag = "incident_guidance_open_accessibility",
                steps = listOf(
                    "Open Android Accessibility settings.",
                    "Locate the suspicious service and disable it.",
                    "If tied to an unknown app, uninstall that app from App settings.",
                    "Run a deep scan again and mark this incident fixed only if it no longer appears."
                )
            )
        }
        if (lower.contains("device-admin") || lower.contains("device admin")) {
            return IncidentGuidance(
                quickActionLabelResId = R.string.incident_guidance_open_security,
                quickActionIntent = Intent(Settings.ACTION_SECURITY_SETTINGS),
                quickActionAuditTag = "incident_guidance_open_security",
                steps = listOf(
                    "Open Android Security settings.",
                    "Review device-admin apps and remove admin rights from unknown apps.",
                    "Uninstall any app that should not have admin control.",
                    "Run a deep scan again and mark this incident fixed when the signal is gone."
                )
            )
        }
        if (lower.contains("overlay")) {
            val overlayIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && packageName.isNotBlank()) {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            } else {
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            }
            val packageNote = if (packageName.isBlank()) {
                "Review apps with overlay permission and disable any app you do not trust."
            } else {
                "Focus on package $packageName and remove overlay permission if not required."
            }
            return IncidentGuidance(
                quickActionLabelResId = R.string.incident_guidance_open_overlay,
                quickActionIntent = overlayIntent,
                quickActionAuditTag = "incident_guidance_open_overlay",
                steps = listOf(
                    "Open overlay permission controls.",
                    packageNote,
                    "Uninstall apps that re-enable risky overlays unexpectedly.",
                    "Run another scan and confirm this incident no longer appears."
                )
            )
        }
        if (
            lower.contains("magisk") ||
            lower.contains("xposed") ||
            lower.contains("frida") ||
            lower.contains("su binary") ||
            lower.contains("root")
        ) {
            return IncidentGuidance(
                quickActionLabelResId = R.string.incident_guidance_open_security,
                quickActionIntent = Intent(Settings.ACTION_SECURITY_SETTINGS),
                quickActionAuditTag = "incident_guidance_open_security",
                steps = listOf(
                    "Open Android Security settings and confirm Play Protect is enabled.",
                    "If this is an expected rooted device, isolate sensitive apps (banking/email) to a clean device.",
                    "If root tooling is unexpected, remove suspicious root frameworks and unknown modules.",
                    "Reboot, run deep scan again, then mark fixed when high-risk root signals disappear."
                )
            )
        }
        if (
            lower.contains("storage artifact") ||
            lower.contains("download") ||
            lower.contains("payload") ||
            lower.contains("suspicious storage")
        ) {
            val pathHint = if (path.isBlank()) {
                "Use Downloads and file managers to locate suspicious artifacts."
            } else {
                "Locate and inspect: $path"
            }
            return IncidentGuidance(
                quickActionLabelResId = R.string.scan_results_open_downloads,
                quickActionIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
                quickActionAuditTag = "incident_guidance_open_downloads",
                steps = listOf(
                    "Open Downloads and recent files.",
                    pathHint,
                    "Delete suspicious scripts/binaries/APKs that are not trusted or expected.",
                    "Run deep scan again and mark fixed once the artifact signal clears."
                )
            )
        }
        if (lower.contains("wifi")) {
            return IncidentGuidance(
                quickActionLabelResId = R.string.incident_guidance_open_wifi,
                quickActionIntent = Intent(Settings.ACTION_WIFI_SETTINGS),
                quickActionAuditTag = "incident_guidance_open_wifi",
                steps = listOf(
                    "Open Wi-Fi settings and disconnect from risky/open networks.",
                    "Forget suspicious SSIDs and reconnect only to trusted encrypted networks.",
                    "Disable auto-join for unknown hotspots.",
                    "Run posture/deep scan again and mark fixed when the warning is no longer present."
                )
            )
        }
        return IncidentGuidance(
            quickActionLabelResId = R.string.incident_guidance_open_security,
            quickActionIntent = Intent(Settings.ACTION_SECURITY_SETTINGS),
            quickActionAuditTag = "incident_guidance_open_security",
            steps = listOf(
                "Open Security settings and review related app permissions and protections.",
                "Remove or disable unknown/high-risk app capabilities tied to this incident.",
                "Uninstall suspicious apps if remediation is unclear or behavior persists.",
                "Run scan again and mark fixed when this finding no longer appears."
            )
        )
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
