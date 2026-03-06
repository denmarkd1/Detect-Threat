package com.realyn.watchdog

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
import kotlin.math.roundToInt

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
        const val EXTRA_SCREEN_MODE = "scan_results.extra.SCREEN_MODE"
        const val SCREEN_MODE_INCIDENT_ASSISTANT = "incident_assistant"
    }

    private lateinit var binding: ActivityScanResultsBinding
    private var maintenancePayload: MaintenancePayload? = null
    private var incidentAssistantOnlyMode: Boolean = false
    private var pendingRecommendedPermissionRequest: PendingRecommendedPermissionRequest? = null
    private var pendingIncidentOverlayLaunch: PendingIncidentOverlayLaunch? = null

    private val incidentOverlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val pending = pendingIncidentOverlayLaunch ?: return@registerForActivityResult
            pendingIncidentOverlayLaunch = null
            if (!Settings.canDrawOverlays(this)) {
                logIncidentAssistantEvent(
                    incident = pending.incident,
                    action = "incident_assistant_overlay_permission_denied"
                )
                Toast.makeText(
                    this,
                    getString(R.string.incident_assistant_recommended_overlay_permission_denied),
                    Toast.LENGTH_LONG
                ).show()
                launchRecommendedSettingsWithOverlayOption(
                    incident = pending.incident,
                    guidance = pending.guidance,
                    launchActions = pending.launchActions,
                    continueWithContainmentAfterLaunch = pending.continueWithContainmentAfterLaunch,
                    useOverlayGuide = false
                )
                return@registerForActivityResult
            }
            logIncidentAssistantEvent(
                incident = pending.incident,
                action = "incident_assistant_overlay_permission_granted"
            )
            launchRecommendedSettingsWithOverlayOption(
                incident = pending.incident,
                guidance = pending.guidance,
                launchActions = pending.launchActions,
                continueWithContainmentAfterLaunch = pending.continueWithContainmentAfterLaunch,
                useOverlayGuide = true
            )
        }

    private val recommendedSettingsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val pending = pendingRecommendedPermissionRequest ?: return@registerForActivityResult
            pendingRecommendedPermissionRequest = null
            val granted = grants.values.all { it }
            if (!granted) {
                logIncidentAssistantEvent(
                    incident = pending.incident,
                    action = "incident_assistant_recommended_permission_denied",
                    detail = JSONObject().put("requestedCount", grants.size)
                )
                Toast.makeText(
                    this,
                    getString(R.string.incident_assistant_recommended_permission_denied),
                    Toast.LENGTH_LONG
                ).show()
                continueWithContainmentOrGuidance(
                    incident = pending.incident,
                    guidance = pending.guidance
                )
                return@registerForActivityResult
            }
            logIncidentAssistantEvent(
                incident = pending.incident,
                action = "incident_assistant_recommended_permission_granted",
                detail = JSONObject().put("requestedCount", grants.size)
            )
            applyRecommendedSettingsThenContinue(
                incident = pending.incident,
                guidance = pending.guidance,
                recommendedActions = pending.recommendedActions
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityScanResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureResponsiveLayout()
        applyScanResultsTheme()
        incidentAssistantOnlyMode = intent.getStringExtra(EXTRA_SCREEN_MODE)
            .orEmpty()
            .equals(SCREEN_MODE_INCIDENT_ASSISTANT, ignoreCase = true)
        if (incidentAssistantOnlyMode) {
            configureIncidentAssistantOnlyScreen()
            return
        }

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
        renderScanReportSections(reportText)
        renderMaintenanceActions(maintenancePayload)
        applyScanResultsTheme()

        binding.scanResultsStartIncidentButton.setOnClickListener {
            startActivity(
                Intent(this, ScanResultsActivity::class.java).apply {
                    putExtra(EXTRA_SCREEN_MODE, SCREEN_MODE_INCIDENT_ASSISTANT)
                }
            )
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

    private fun configureIncidentAssistantOnlyScreen() {
        binding.scanResultsTitleLabel.text = getString(R.string.incident_guidance_dialog_title)
        binding.scanResultsSubtitleLabel.text = getString(R.string.incident_assistant_screen_subtitle)
        binding.scanResultsSummaryCard.visibility = View.GONE
        binding.scanResultsPrimaryActionsRow.visibility = View.GONE
        binding.scanResultsMaintenanceActionsTitleLabel.visibility = View.GONE
        binding.scanResultsMaintenanceRowOne.visibility = View.GONE
        binding.scanResultsMaintenanceRowTwo.visibility = View.GONE
        binding.scanResultsOpenStorageSettingsButton.visibility = View.GONE
        binding.scanResultsReportCard.visibility = View.GONE
        binding.scanResultsBackHomeButton.text = getString(R.string.incident_assistant_back_to_scan_results)
        binding.scanResultsBackHomeButton.setOnClickListener {
            finish()
        }
        applyScanResultsTheme()
        if (!startIncidentGuidanceFlow()) {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        stopIncidentGuideOverlay()
        applyScanResultsTheme()
    }

    private fun configureResponsiveLayout() {
        applySystemBarInsets()
        binding.scanResultsScrollView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateResponsiveLayoutForWidth()
        }
        binding.scanResultsScrollView.post {
            updateResponsiveLayoutForWidth()
        }
    }

    private fun applySystemBarInsets() {
        val baseRootStart = binding.root.paddingStart
        val baseRootTop = binding.root.paddingTop
        val baseRootEnd = binding.root.paddingEnd
        val baseRootBottom = binding.root.paddingBottom

        val baseScrollStart = binding.scanResultsScrollView.paddingStart
        val baseScrollTop = binding.scanResultsScrollView.paddingTop
        val baseScrollEnd = binding.scanResultsScrollView.paddingEnd
        val baseScrollBottom = binding.scanResultsScrollView.paddingBottom
        val extraBottomSpacing = resources.getDimensionPixelSize(
            R.dimen.scan_results_bottom_inset_extra_padding
        )

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.root.setPaddingRelative(
                baseRootStart + systemBars.left,
                baseRootTop + systemBars.top,
                baseRootEnd + systemBars.right,
                baseRootBottom
            )
            binding.scanResultsScrollView.setPaddingRelative(
                baseScrollStart,
                baseScrollTop,
                baseScrollEnd,
                baseScrollBottom + systemBars.bottom + extraBottomSpacing
            )
            updateResponsiveLayoutForWidth()
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateResponsiveLayoutForWidth() {
        val availableWidth = binding.scanResultsScrollView.width -
            binding.scanResultsScrollView.paddingLeft -
            binding.scanResultsScrollView.paddingRight
        if (availableWidth <= 0) {
            return
        }
        val maxContentWidth = resources.getDimensionPixelSize(R.dimen.scan_results_content_max_width)
        val targetContentWidth = minOf(availableWidth, maxContentWidth)
        val contentLayoutParams = binding.scanResultsContentContainer.layoutParams as FrameLayout.LayoutParams
        val layoutChanged = contentLayoutParams.width != targetContentWidth ||
            contentLayoutParams.gravity != Gravity.CENTER_HORIZONTAL
        if (layoutChanged) {
            contentLayoutParams.width = targetContentWidth
            contentLayoutParams.gravity = Gravity.CENTER_HORIZONTAL
            binding.scanResultsContentContainer.layoutParams = contentLayoutParams
        }
        updateButtonRowsForWidth(targetContentWidth)
    }

    private fun updateButtonRowsForWidth(contentWidthPx: Int) {
        val stackThreshold = resources.getDimensionPixelSize(R.dimen.scan_results_button_stack_threshold)
        val shouldStackRows = contentWidthPx < stackThreshold
        updateButtonPairRow(
            row = binding.scanResultsPrimaryActionsRow,
            firstButton = binding.scanResultsStartIncidentButton,
            secondButton = binding.scanResultsOpenCredentialButton,
            spacer = binding.scanResultsPrimaryActionsSpacer,
            stacked = shouldStackRows
        )
        updateButtonPairRow(
            row = binding.scanResultsMaintenanceRowOne,
            firstButton = binding.scanResultsReviewDuplicatesButton,
            secondButton = binding.scanResultsReviewUnusedAppsButton,
            spacer = binding.scanResultsMaintenanceRowOneSpacer,
            stacked = shouldStackRows
        )
        updateButtonPairRow(
            row = binding.scanResultsMaintenanceRowTwo,
            firstButton = binding.scanResultsCleanSafeClutterButton,
            secondButton = binding.scanResultsReviewInstallerRemnantsButton,
            spacer = binding.scanResultsMaintenanceRowTwoSpacer,
            stacked = shouldStackRows
        )
    }

    private fun updateButtonPairRow(
        row: LinearLayout,
        firstButton: MaterialButton,
        secondButton: MaterialButton,
        spacer: View,
        stacked: Boolean
    ) {
        row.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        spacer.visibility = if (stacked) View.GONE else View.VISIBLE
        val stackedSpacing = resources.getDimensionPixelSize(R.dimen.scan_results_button_stack_spacing)
        updateButtonPairLayout(
            button = firstButton,
            stacked = stacked,
            topMarginPx = 0
        )
        updateButtonPairLayout(
            button = secondButton,
            stacked = stacked,
            topMarginPx = if (stacked) stackedSpacing else 0
        )
    }

    private fun updateButtonPairLayout(
        button: MaterialButton,
        stacked: Boolean,
        topMarginPx: Int
    ) {
        val layoutParams = button.layoutParams as LinearLayout.LayoutParams
        if (stacked) {
            layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            layoutParams.weight = 0f
            layoutParams.topMargin = topMarginPx
        } else {
            layoutParams.width = 0
            layoutParams.weight = 1f
            layoutParams.topMargin = 0
        }
        button.layoutParams = layoutParams
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

    private fun renderScanReportSections(reportText: String) {
        val sections = parseScanReportSections(reportText)
        binding.scanResultsReportSectionsContainer.removeAllViews()
        if (sections.isEmpty()) {
            binding.scanResultsReportSectionsContainer.visibility = View.GONE
            binding.scanResultsReportTextLabel.visibility = View.VISIBLE
            binding.scanResultsReportTextLabel.text = reportText
            return
        }

        binding.scanResultsReportTextLabel.visibility = View.GONE
        binding.scanResultsReportSectionsContainer.visibility = View.VISIBLE
        renderCollapsibleSections(
            container = binding.scanResultsReportSectionsContainer,
            sections = sections
        )
    }

    private fun parseScanReportSections(reportText: String): List<CollapsibleSection> {
        val normalized = reportText
            .replace("\r", "")
            .trim()
        if (normalized.isBlank()) {
            return emptyList()
        }

        val knownHeaders = listOf(
            "What happened",
            "What to do now",
            "Technical details (optional)",
            "Detailed findings (optional)"
        )
        val sections = linkedMapOf<String, MutableList<String>>()
        val summaryLines = mutableListOf<String>()
        var activeHeader: String? = null

        normalized.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val matchedHeader = knownHeaders.firstOrNull { it.equals(line.trim(), ignoreCase = true) }
            if (matchedHeader != null) {
                activeHeader = matchedHeader
                sections.getOrPut(matchedHeader) { mutableListOf() }
                return@forEach
            }
            if (activeHeader == null) {
                summaryLines += line
            } else {
                sections.getOrPut(activeHeader.orEmpty()) { mutableListOf() } += line
            }
        }

        val rendered = mutableListOf<CollapsibleSection>()
        val summaryBody = summaryLines.joinToString("\n").trim()
        if (summaryBody.isNotBlank()) {
            val summaryTitleCandidate = summaryLines.firstOrNull().orEmpty().trim()
            val summaryTitle = if (summaryTitleCandidate.isBlank()) {
                getString(R.string.scan_results_report_title)
            } else {
                summaryTitleCandidate
            }
            val summaryDetailBody = summaryLines.drop(1).joinToString("\n").trim()
            val body = if (summaryDetailBody.isBlank()) summaryBody else summaryDetailBody
            rendered += CollapsibleSection(
                title = summaryTitle,
                body = body,
                expandedByDefault = true
            )
        }

        knownHeaders.forEach { header ->
            val body = sections[header]
                .orEmpty()
                .joinToString("\n")
                .trim()
            if (body.isNotBlank()) {
                rendered += CollapsibleSection(
                    title = header,
                    body = body
                )
            }
        }
        return rendered
    }

    private fun renderCollapsibleSections(
        container: LinearLayout,
        sections: List<CollapsibleSection>
    ) {
        sections.forEach { section ->
            val sectionView = layoutInflater.inflate(
                R.layout.view_expandable_link_section,
                container,
                false
            )
            val headerView = sectionView.findViewById<TextView>(R.id.expandableSectionHeader)
            val bodyView = sectionView.findViewById<TextView>(R.id.expandableSectionBody)
            bindExpandableSection(
                headerView = headerView,
                bodyView = bodyView,
                title = section.title,
                body = section.body,
                expandedByDefault = section.expandedByDefault
            )
            container.addView(sectionView)
        }
    }

    private fun bindExpandableSection(
        headerView: TextView,
        bodyView: TextView,
        title: String,
        body: CharSequence,
        expandedByDefault: Boolean
    ) {
        var expanded = expandedByDefault
        bodyView.text = body

        fun refresh() {
            val stateRes = if (expanded) {
                R.string.expandable_section_state_expanded
            } else {
                R.string.expandable_section_state_collapsed
            }
            val indicator = if (expanded) "▾" else "▸"
            headerView.text = "$indicator $title (${getString(stateRes)})"
            bodyView.visibility = if (expanded) View.VISIBLE else View.GONE
        }

        headerView.setOnClickListener {
            expanded = !expanded
            refresh()
        }
        refresh()
    }

    private fun buildWorkNowSectionBody(
        incident: IncidentRecord,
        severityLabel: String
    ): CharSequence {
        val baseLine = "$severityLabel risk: ${incident.title}"
        val appSummary = resolveIncidentAppSummary(incident) ?: return baseLine
        val styled = SpannableStringBuilder(baseLine)
        styled.append(" | App: ")
        val iconStart = styled.length
        styled.append('\uFFFC')
        val iconEnd = styled.length

        val iconSizePx = (14f * resources.displayMetrics.density).roundToInt().coerceAtLeast(12)
        val iconDrawable = appSummary.icon.constantState?.newDrawable()?.mutate()
            ?: appSummary.icon.mutate()
        iconDrawable.setBounds(0, 0, iconSizePx, iconSizePx)
        styled.setSpan(
            ImageSpan(iconDrawable, ImageSpan.ALIGN_BOTTOM),
            iconStart,
            iconEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        styled.append(" ")
        val labelStart = styled.length
        styled.append(appSummary.displayName)
        styled.setSpan(
            StyleSpan(Typeface.BOLD),
            labelStart,
            styled.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return styled
    }

    private fun resolveIncidentAppSummary(incident: IncidentRecord): IncidentAppSummary? {
        val packageName = parseIncidentContext(incident).packageName.trim()
        if (packageName.isBlank()) {
            return null
        }
        val pm = packageManager
        val appInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        }.getOrNull()
        val displayName = appInfo?.let { info ->
            runCatching {
                pm.getApplicationLabel(info).toString().trim().ifBlank { packageName }
            }.getOrDefault(packageName)
        } ?: packageName
        val icon = appInfo?.let { info ->
            runCatching { pm.getApplicationIcon(info) }.getOrNull()
        } ?: runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
            ?: ContextCompat.getDrawable(this, android.R.drawable.sym_def_app_icon)
            ?: return null
        return IncidentAppSummary(
            displayName = displayName,
            icon = icon
        )
    }

    private fun formatDisplayTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
    }

    private enum class IncidentActionExecution {
        OPEN_INTENTS,
        REQUEST_APP_UNINSTALL
    }

    private data class IncidentAction(
        val actionId: String,
        val title: String,
        val impact: String,
        val manualInstruction: String,
        val automatable: Boolean,
        val reversible: Boolean,
        val destructive: Boolean,
        val auditTag: String,
        val execution: IncidentActionExecution,
        val intents: List<Intent> = emptyList(),
        val packageName: String = ""
    )

    private data class IncidentGuidance(
        val confidence: String,
        val whyLine: String,
        val stepSignalMap: List<String>,
        val steps: List<String>,
        val actions: List<IncidentAction>
    )

    private data class IncidentActionApplyResult(
        val successCount: Int,
        val failedCount: Int
    )

    private data class PendingRecommendedPermissionRequest(
        val incident: IncidentRecord,
        val guidance: IncidentGuidance,
        val recommendedActions: List<IncidentAction>
    )

    private data class PendingIncidentOverlayLaunch(
        val incident: IncidentRecord,
        val guidance: IncidentGuidance,
        val launchActions: List<IncidentAction>,
        val continueWithContainmentAfterLaunch: Boolean
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

    private data class CollapsibleSection(
        val title: String,
        val body: CharSequence,
        val expandedByDefault: Boolean = false
    )

    private data class IncidentAppSummary(
        val displayName: String,
        val icon: Drawable
    )

    private fun startIncidentGuidanceFlow(): Boolean {
        val next = IncidentStore.nextUnresolvedForWork(this)
        if (next == null) {
            Toast.makeText(this, getString(R.string.incident_no_open), Toast.LENGTH_SHORT).show()
            return false
        }
        val active = if (next.status == IncidentStatus.OPEN) {
            IncidentStore.markInProgress(this, next.incidentId) ?: next
        } else {
            next
        }
        showIncidentDecisionDialog(active)
        return true
    }

    private fun showIncidentDecisionDialog(incident: IncidentRecord) {
        val unresolved = IncidentStore.loadIncidents(this)
            .filter { it.status == IncidentStatus.OPEN || it.status == IncidentStatus.IN_PROGRESS }
        val highRemaining = unresolved.count { it.severity == Severity.HIGH }
        val mediumRemaining = unresolved.count { it.severity == Severity.MEDIUM }
        val lowRemaining = unresolved.count { it.severity == Severity.LOW }
        val guidance = buildIncidentGuidance(incident)
        val autoCount = guidance.actions.count { it.automatable }
        val severityLabel = userSeverityLabel(incident.severity)
        val whyLine = compactTechnicalLine(guidance.whyLine, maxLen = 180)
        val recommendedSettings = buildRecommendedSettings(incident, guidance)
        val optionSummary = buildString {
            appendLine(
                if (autoCount > 0) {
                    getString(R.string.incident_assistant_tip_auto_template, autoCount)
                } else {
                    getString(R.string.incident_assistant_tip_manual)
                }
            )
            appendLine("1. ${getString(R.string.incident_assistant_apply_choice)}")
            appendLine("2. ${getString(R.string.incident_assistant_guide_choice)}")
            append("3. ${getString(R.string.incident_assistant_skip_choice)}")
        }.trim()
        val sectionModels = listOf(
            CollapsibleSection(
                title = getString(R.string.incident_assistant_section_work_now),
                body = buildWorkNowSectionBody(
                    incident = incident,
                    severityLabel = severityLabel
                ),
                expandedByDefault = true
            ),
            CollapsibleSection(
                title = getString(R.string.incident_assistant_section_why),
                body = whyLine.ifBlank { "This incident matched known risk signals from the scan." }
            ),
            CollapsibleSection(
                title = getString(R.string.incident_assistant_section_choose),
                body = optionSummary
            ),
            CollapsibleSection(
                title = getString(R.string.incident_assistant_section_recommended),
                body = recommendedSettings.joinToString("\n") { "- $it" }
            )
        )

        val dialogView = layoutInflater.inflate(R.layout.dialog_incident_assistant, null)
        dialogView.findViewById<TextView>(R.id.incidentAssistantQueueLabel).text = getString(
            R.string.incident_guidance_queue_template,
            highRemaining,
            mediumRemaining,
            lowRemaining
        )
        val sectionContainer = dialogView.findViewById<LinearLayout>(R.id.incidentAssistantSectionsContainer)
        sectionContainer.removeAllViews()
        renderCollapsibleSections(
            container = sectionContainer,
            sections = sectionModels
        )

        val dialog = LionAlertDialogBuilder(this)
            .setTitle(R.string.incident_guidance_dialog_title)
            .setView(dialogView)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.incidentAssistantApplyButton).setOnClickListener {
            logIncidentAssistantEvent(
                incident = incident,
                action = "incident_assistant_apply_requested",
                detail = JSONObject().put("actionCount", guidance.actions.size)
            )
            dialog.dismiss()
            showRecommendedSettingsDecisionDialog(
                incident = incident,
                guidance = guidance,
                entryPoint = RecommendedSettingsEntryPoint.APPLY
            )
        }
        dialogView.findViewById<MaterialButton>(R.id.incidentAssistantGuideButton).setOnClickListener {
            logIncidentAssistantEvent(
                incident = incident,
                action = "incident_assistant_manual_requested",
                detail = JSONObject().put("actionCount", guidance.actions.size)
            )
            dialog.dismiss()
            showRecommendedSettingsDecisionDialog(
                incident = incident,
                guidance = guidance,
                entryPoint = RecommendedSettingsEntryPoint.GUIDE
            )
        }
        dialogView.findViewById<MaterialButton>(R.id.incidentAssistantSkipButton).setOnClickListener {
            skipIncidentAndContinue(incident, guidance, dialog)
        }

        showStyledDialog(dialog)
    }

    private fun buildRecommendedSettings(
        incident: IncidentRecord,
        guidance: IncidentGuidance
    ): List<String> {
        val context = parseIncidentContext(incident)
        val module = context.moduleLabel.lowercase(Locale.US)
        val recommendations = mutableListOf<String>()

        when {
            module.contains("startup persistence") -> {
                recommendations += "Disable Accessibility, overlay, and device-admin access for untrusted apps."
                recommendations += "Set risky permissions to Deny unless the app cannot function without them."
            }
            module.contains("storage") -> {
                recommendations += "Keep Downloads and shared storage free of unknown installers/scripts."
                recommendations += "Remove suspicious files before opening them."
            }
            module.contains("embedded path probe") -> {
                recommendations += "Disable debugging and unknown install sources when not actively needed."
                recommendations += "Keep Security settings locked to trusted install sources only."
            }
            module.contains("wi-fi posture") || module.contains("wifi posture") -> {
                recommendations += "Use trusted WPA2/WPA3 networks for sensitive account actions."
                recommendations += "Disable auto-join for open or unknown hotspots."
            }
            else -> {
                recommendations += getString(R.string.incident_assistant_recommended_fallback)
            }
        }

        guidance.actions
            .take(2)
            .forEach { action ->
                recommendations += "Quick setting route: ${action.title}"
            }
        recommendations += getString(R.string.incident_assistant_recommended_followup)
        return recommendations
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
    }

    private fun skipIncidentAndContinue(
        incident: IncidentRecord,
        guidance: IncidentGuidance,
        dialog: AlertDialog
    ) {
        logIncidentAssistantEvent(
            incident = incident,
            action = "incident_assistant_skipped",
            detail = JSONObject().put("actionCount", guidance.actions.size)
        )
        val next = nextUnresolvedIncidentExcluding(incident.incidentId)
        if (next == null) {
            Toast.makeText(
                this,
                getString(R.string.incident_assistant_skip_no_other),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val active = if (next.status == IncidentStatus.OPEN) {
            IncidentStore.markInProgress(this, next.incidentId) ?: next
        } else {
            next
        }
        dialog.dismiss()
        showIncidentDecisionDialog(active)
    }

    private fun nextUnresolvedIncidentExcluding(incidentId: String): IncidentRecord? {
        return IncidentStore.loadIncidents(this)
            .filter {
                (it.status == IncidentStatus.OPEN || it.status == IncidentStatus.IN_PROGRESS) &&
                    it.incidentId != incidentId
            }
            .sortedWith(
                compareByDescending<IncidentRecord> { incidentSeverityRankForWork(it.severity) }
                    .thenBy { incidentStatusWorkOrder(it.status) }
                    .thenByDescending { it.lastSeenAtEpochMs }
            )
            .firstOrNull()
    }

    private fun incidentStatusWorkOrder(status: IncidentStatus): Int {
        return when (status) {
            IncidentStatus.IN_PROGRESS -> 0
            IncidentStatus.OPEN -> 1
            IncidentStatus.RESOLVED -> 2
        }
    }

    private fun incidentSeverityRankForWork(severity: Severity): Int {
        return when (severity) {
            Severity.HIGH -> 4
            Severity.MEDIUM -> 3
            Severity.LOW -> 2
            Severity.INFO -> 1
        }
    }

    private enum class RecommendedSettingsEntryPoint {
        APPLY,
        GUIDE
    }

    private enum class OemStepPack {
        MIUI,
        SAMSUNG,
        PIXEL,
        GENERIC
    }

    private fun showRecommendedSettingsDecisionDialog(
        incident: IncidentRecord,
        guidance: IncidentGuidance,
        entryPoint: RecommendedSettingsEntryPoint
    ) {
        val recommendedSettings = buildRecommendedSettings(incident, guidance)
        val supportsDirectAutoApply = hasDirectRecommendedAutoApply(guidance)
        val introRes = when (entryPoint) {
            RecommendedSettingsEntryPoint.APPLY -> R.string.incident_assistant_recommended_decision_apply_message
            RecommendedSettingsEntryPoint.GUIDE -> R.string.incident_assistant_recommended_decision_guide_message
        }
        val message = buildString {
            appendLine(getString(introRes))
            appendLine()
            appendLine(
                getString(
                    if (supportsDirectAutoApply) {
                        R.string.incident_assistant_recommended_decision_auto_capability
                    } else {
                        R.string.incident_assistant_recommended_decision_manual_only_capability
                    }
                )
            )
            appendLine()
            appendLine(getString(R.string.incident_assistant_section_recommended))
            recommendedSettings.forEachIndexed { index, line ->
                appendLine("${index + 1}. $line")
            }
        }.trim()

        LionAlertDialogBuilder(this)
            .setTitle(R.string.incident_assistant_recommended_decision_title)
            .setMessage(message)
            .setPositiveButton(R.string.incident_assistant_recommended_decision_yes) { _, _ ->
                logIncidentAssistantEvent(
                    incident = incident,
                    action = "incident_assistant_recommended_selected",
                    detail = JSONObject().put("entryPoint", entryPoint.name.lowercase(Locale.US))
                )
                when (entryPoint) {
                    RecommendedSettingsEntryPoint.APPLY -> startAutoRecommendedSettingsFlow(incident, guidance)
                    RecommendedSettingsEntryPoint.GUIDE -> showManualRecommendedSettingsGuideDialog(incident, guidance)
                }
            }
            .setNegativeButton(R.string.incident_assistant_recommended_decision_no) { _, _ ->
                logIncidentAssistantEvent(
                    incident = incident,
                    action = "incident_assistant_recommended_skipped",
                    detail = JSONObject().put("entryPoint", entryPoint.name.lowercase(Locale.US))
                )
                when (entryPoint) {
                    RecommendedSettingsEntryPoint.APPLY -> showIncidentApplyConfirmationDialog(incident, guidance)
                    RecommendedSettingsEntryPoint.GUIDE -> showIncidentGuidanceDialog(incident, guidance)
                }
            }
            .setNeutralButton(R.string.scan_results_cancel, null)
            .show()
    }

    private fun showManualRecommendedSettingsGuideDialog(
        incident: IncidentRecord,
        guidance: IncidentGuidance,
        launchActions: List<IncidentAction> = emptyList(),
        continueWithContainmentAfterLaunch: Boolean = false
    ) {
        val canDrawOverlay = Settings.canDrawOverlays(this)
        val oemPack = resolveOemStepPack()
        val recommendedSettings = buildRecommendedSettings(incident, guidance)
        val tapTargets = buildRecommendedTapTargets(incident, guidance, oemPack)
        val manualSettingsActions = guidance.actions
            .filter { !it.destructive }
            .take(2)
        val message = buildString {
            appendLine(getString(R.string.incident_assistant_recommended_manual_intro))
            appendLine(
                getString(
                    R.string.incident_assistant_recommended_tap_pack_template,
                    oemStepPackLabel(oemPack)
                )
            )
            appendLine()
            recommendedSettings.forEachIndexed { index, line ->
                appendLine("${index + 1}. $line")
            }
            if (manualSettingsActions.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.incident_assistant_actions_title))
                manualSettingsActions.forEachIndexed { index, action ->
                    appendLine("${index + 1}. ${action.manualInstruction}")
                }
            }
            if (tapTargets.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.incident_assistant_recommended_tap_targets_title))
                tapTargets.forEachIndexed { index, target ->
                    appendLine("${index + 1}. $target")
                }
            }
            if (launchActions.isNotEmpty()) {
                appendLine()
                appendLine(
                    getString(
                        if (canDrawOverlay) {
                            R.string.incident_assistant_recommended_overlay_hint_ready
                        } else {
                            R.string.incident_assistant_recommended_overlay_hint_permission
                        }
                    )
                )
            }
            appendLine()
            append(getString(R.string.incident_assistant_recommended_manual_continue))
        }.trim()
        val dialogBuilder = LionAlertDialogBuilder(this)
            .setTitle(R.string.incident_assistant_recommended_manual_title)
            .setMessage(message)
        if (launchActions.isNotEmpty()) {
            dialogBuilder
                .setPositiveButton(R.string.incident_assistant_recommended_open_with_overlay) { _, _ ->
                    startRecommendedSettingsWithOverlayFlow(
                        incident = incident,
                        guidance = guidance,
                        launchActions = launchActions,
                        continueWithContainmentAfterLaunch = continueWithContainmentAfterLaunch
                    )
                }
                .setNeutralButton(R.string.incident_assistant_recommended_open_settings_now) { _, _ ->
                    launchRecommendedSettingsWithOverlayOption(
                        incident = incident,
                        guidance = guidance,
                        launchActions = launchActions,
                        continueWithContainmentAfterLaunch = continueWithContainmentAfterLaunch,
                        useOverlayGuide = false
                    )
                }
                .setNegativeButton(R.string.scan_results_cancel, null)
        } else {
            dialogBuilder
                .setPositiveButton(R.string.incident_assistant_guide_choice) { _, _ ->
                    showIncidentGuidanceDialog(incident, guidance)
                }
                .setNegativeButton(R.string.scan_results_cancel, null)
        }
        dialogBuilder.show()
    }

    private fun startRecommendedSettingsWithOverlayFlow(
        incident: IncidentRecord,
        guidance: IncidentGuidance,
        launchActions: List<IncidentAction>,
        continueWithContainmentAfterLaunch: Boolean
    ) {
        if (Settings.canDrawOverlays(this)) {
            launchRecommendedSettingsWithOverlayOption(
                incident = incident,
                guidance = guidance,
                launchActions = launchActions,
                continueWithContainmentAfterLaunch = continueWithContainmentAfterLaunch,
                useOverlayGuide = true
            )
            return
        }
        pendingIncidentOverlayLaunch = PendingIncidentOverlayLaunch(
            incident = incident,
            guidance = guidance,
            launchActions = launchActions,
            continueWithContainmentAfterLaunch = continueWithContainmentAfterLaunch
        )
        incidentOverlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun launchRecommendedSettingsWithOverlayOption(
        incident: IncidentRecord,
        guidance: IncidentGuidance,
        launchActions: List<IncidentAction>,
        continueWithContainmentAfterLaunch: Boolean,
        useOverlayGuide: Boolean
    ) {
        if (useOverlayGuide) {
            startIncidentGuideOverlay(incident, guidance)
        } else {
            stopIncidentGuideOverlay()
        }
        val result = executeIncidentActions(incident, launchActions)
        showRecommendedSettingsApplyResultToast(result)
        if (continueWithContainmentAfterLaunch) {
            continueWithContainmentOrGuidance(
                incident = incident,
                guidance = guidance
            )
        } else {
            showIncidentGuidanceDialog(incident, guidance)
        }
    }

    private fun startIncidentGuideOverlay(
        incident: IncidentRecord,
        guidance: IncidentGuidance
    ) {
        val steps = buildRecommendedTapTargets(
            incident = incident,
            guidance = guidance,
            oemPack = resolveOemStepPack()
        )
            .ifEmpty { guidance.steps.take(5) }
        startService(
            Intent(this, IncidentGuideOverlayService::class.java).apply {
                action = WatchdogConfig.ACTION_SHOW_INCIDENT_OVERLAY
                putExtra(
                    WatchdogConfig.EXTRA_INCIDENT_OVERLAY_TITLE,
                    getString(R.string.incident_overlay_title)
                )
                putExtra(
                    WatchdogConfig.EXTRA_INCIDENT_OVERLAY_COMPACT_MODE,
                    true
                )
                putStringArrayListExtra(
                    WatchdogConfig.EXTRA_INCIDENT_OVERLAY_STEPS,
                    ArrayList(steps)
                )
            }
        )
        logIncidentAssistantEvent(
            incident = incident,
            action = "incident_assistant_overlay_started",
            detail = JSONObject().put("stepCount", steps.size)
        )
        Toast.makeText(this, R.string.incident_overlay_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopIncidentGuideOverlay() {
        startService(
            Intent(this, IncidentGuideOverlayService::class.java).apply {
                action = WatchdogConfig.ACTION_HIDE_INCIDENT_OVERLAY
            }
        )
    }

    private fun startAutoRecommendedSettingsFlow(
        incident: IncidentRecord,
        guidance: IncidentGuidance
    ) {
        val recommendedActions = guidance.actions.filter { it.automatable && !it.destructive }
        if (recommendedActions.isEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.incident_assistant_no_auto_actions),
                Toast.LENGTH_SHORT
            ).show()
            showIncidentApplyConfirmationDialog(incident, guidance)
            return
        }

        if (!hasDirectRecommendedAutoApply(guidance)) {
            logIncidentAssistantEvent(
                incident = incident,
                action = "incident_assistant_recommended_manual_only_fallback",
                detail = JSONObject().put("recommendedActionCount", recommendedActions.size)
            )
            showManualRecommendedSettingsGuideDialog(
                incident = incident,
                guidance = guidance,
                launchActions = recommendedActions,
                continueWithContainmentAfterLaunch = true
            )
            return
        }

        val requiredPermissions = requiredPermissionsForRecommendedSettings(incident, guidance)
        if (requiredPermissions.isEmpty()) {
            applyRecommendedSettingsThenContinue(
                incident = incident,
                guidance = guidance,
                recommendedActions = recommendedActions
            )
            return
        }

        showRecommendedSettingsPermissionDialog(
            incident = incident,
            guidance = guidance,
            recommendedActions = recommendedActions,
            requiredPermissions = requiredPermissions
        )
    }

    private fun requiredPermissionsForRecommendedSettings(
        incident: IncidentRecord,
        guidance: IncidentGuidance
    ): Array<String> {
        val context = parseIncidentContext(incident)
        val module = context.moduleLabel.lowercase(Locale.US)
        val requiresWifiPermission = module.contains("wi-fi posture") ||
            module.contains("wifi posture") ||
            guidance.actions.any { it.actionId.startsWith("wifi_") }
        if (!requiresWifiPermission) {
            return emptyArray()
        }
        return WifiPermissionGate.requiredRuntimePermissions(this)
            .toList()
            .distinct()
            .toTypedArray()
    }

    private fun showRecommendedSettingsPermissionDialog(
        incident: IncidentRecord,
        guidance: IncidentGuidance,
        recommendedActions: List<IncidentAction>,
        requiredPermissions: Array<String>
    ) {
        val permissionSummary = requiredPermissions
            .map { it.substringAfterLast('.') }
            .joinToString(", ")
            .ifBlank { Manifest.permission.ACCESS_FINE_LOCATION.substringAfterLast('.') }
        LionAlertDialogBuilder(this)
            .setTitle(R.string.incident_assistant_recommended_permission_title)
            .setMessage(
                getString(
                    R.string.incident_assistant_recommended_permission_message,
                    permissionSummary
                )
            )
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                logIncidentAssistantEvent(
                    incident = incident,
                    action = "incident_assistant_recommended_permission_requested",
                    detail = JSONObject().put("requestedCount", requiredPermissions.size)
                )
                pendingRecommendedPermissionRequest = PendingRecommendedPermissionRequest(
                    incident = incident,
                    guidance = guidance,
                    recommendedActions = recommendedActions
                )
                recommendedSettingsPermissionLauncher.launch(requiredPermissions)
            }
            .setNegativeButton(R.string.incident_assistant_guide_choice) { _, _ ->
                logIncidentAssistantEvent(
                    incident = incident,
                    action = "incident_assistant_recommended_permission_request_skipped",
                    detail = JSONObject().put("requestedCount", requiredPermissions.size)
                )
                showIncidentGuidanceDialog(incident, guidance)
            }
            .show()
    }

    private fun hasDirectRecommendedAutoApply(guidance: IncidentGuidance): Boolean {
        return guidance.actions
            .any { action ->
                action.automatable &&
                    !action.destructive &&
                    action.execution != IncidentActionExecution.OPEN_INTENTS
            }
    }

    private fun resolveOemStepPack(): OemStepPack {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.US)
        val brand = Build.BRAND.orEmpty().lowercase(Locale.US)
        return when {
            manufacturer.contains("xiaomi") ||
                manufacturer.contains("redmi") ||
                manufacturer.contains("poco") ||
                brand.contains("xiaomi") ||
                brand.contains("redmi") ||
                brand.contains("poco") -> OemStepPack.MIUI
            manufacturer.contains("samsung") || brand.contains("samsung") -> OemStepPack.SAMSUNG
            manufacturer.contains("google") || brand.contains("google") -> OemStepPack.PIXEL
            else -> OemStepPack.GENERIC
        }
    }

    private fun oemStepPackLabel(oemPack: OemStepPack): String {
        return when (oemPack) {
            OemStepPack.MIUI -> "MIUI (Xiaomi/Redmi/POCO)"
            OemStepPack.SAMSUNG -> "Samsung One UI"
            OemStepPack.PIXEL -> "Google Pixel"
            OemStepPack.GENERIC -> "Generic Android"
        }
    }

    private fun buildRecommendedTapTargets(
        incident: IncidentRecord,
        guidance: IncidentGuidance,
        oemPack: OemStepPack = resolveOemStepPack()
    ): List<String> {
        val context = parseIncidentContext(incident)
        val module = context.moduleLabel.lowercase(Locale.US)
        val packageRef = context.packageName.ifBlank { "the flagged app" }

        val targets = when {
            module.contains("startup persistence") -> startupTapTargets(oemPack, packageRef)
            module.contains("storage") -> storageTapTargets(oemPack)
            module.contains("embedded path probe") -> embeddedTapTargets(oemPack)
            module.contains("wi-fi posture") || module.contains("wifi posture") -> wifiTapTargets(oemPack)
            else -> coreTapTargets(oemPack, packageRef)
        }.toMutableList()

        if (targets.isEmpty()) {
            guidance.actions
                .take(2)
                .forEach { action ->
                    targets += "Open route: ${action.title}"
                }
        }
        return targets
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(7)
    }

    private fun startupTapTargets(oemPack: OemStepPack, packageRef: String): List<String> {
        return when (oemPack) {
            OemStepPack.MIUI -> listOf(
                "Tap \"Permissions\".",
                "Tap \"Other permissions\" and set risky access to \"Deny\".",
                "Tap \"Display pop-up windows while running in background\" and set to \"Deny\".",
                "Tap \"Autostart\" and switch it Off for $packageRef.",
                "Open Settings > Privacy protection > Special permissions > Device admin apps, then remove admin access for $packageRef.",
                "If $packageRef is untrusted, tap \"Uninstall\"."
            )
            OemStepPack.SAMSUNG -> listOf(
                "Tap \"Permissions\".",
                "Set risky permissions to \"Don't allow\" unless the app needs them.",
                "Tap \"Appear on top\" and switch it Off.",
                "Tap \"Install unknown apps\" and switch it Off.",
                "Open Settings > Security and privacy > Other security settings > Device admin apps, then disable $packageRef.",
                "If $packageRef is untrusted, tap \"Uninstall\"."
            )
            OemStepPack.PIXEL -> listOf(
                "Tap \"Permissions\".",
                "Set risky permissions to \"Don't allow\" unless the app needs them.",
                "Tap \"Display over other apps\" and set it to \"Not allowed\".",
                "Tap \"Modify system settings\" and set it to \"Not allowed\".",
                "Open Settings > Security and privacy > More security settings > Device admin apps, then disable $packageRef.",
                "If $packageRef is untrusted, tap \"Uninstall\"."
            )
            OemStepPack.GENERIC -> listOf(
                "Tap \"Permissions\".",
                "Tap \"App permissions\" and set risky access to \"Deny\".",
                "Tap \"Display over other apps\" and set it to \"Not allowed\".",
                "If shown, tap \"Device admin apps\" and remove admin access for $packageRef.",
                "If $packageRef is untrusted, tap \"Uninstall\"."
            )
        }
    }

    private fun storageTapTargets(oemPack: OemStepPack): List<String> {
        return when (oemPack) {
            OemStepPack.MIUI -> listOf(
                "Tap \"Storage\".",
                "Open \"File Manager\" > \"Downloads\".",
                "Select suspicious installers/scripts and tap \"Delete\"."
            )
            OemStepPack.SAMSUNG -> listOf(
                "Tap \"Storage\".",
                "Open \"My Files\" > \"Downloads\".",
                "Select suspicious installers/scripts and tap \"Delete\"."
            )
            OemStepPack.PIXEL -> listOf(
                "Tap \"Storage & cache\".",
                "Open the \"Files\" app > \"Downloads\".",
                "Select suspicious installers/scripts and tap \"Delete\"."
            )
            OemStepPack.GENERIC -> listOf(
                "Tap \"Storage\" or \"Files\".",
                "Open the flagged location and select suspicious files.",
                "Tap \"Delete\" for unknown installers/scripts."
            )
        }
    }

    private fun embeddedTapTargets(oemPack: OemStepPack): List<String> {
        return when (oemPack) {
            OemStepPack.MIUI -> listOf(
                "Open Settings > Additional settings > Developer options.",
                "Set \"USB debugging\" and \"Wireless debugging\" to Off.",
                "Open Security settings and disable unknown install sources."
            )
            OemStepPack.SAMSUNG -> listOf(
                "Open Settings > Developer options.",
                "Set \"USB debugging\" and \"Wireless debugging\" to Off.",
                "Open Security and privacy and disable unknown app installs."
            )
            OemStepPack.PIXEL -> listOf(
                "Open Settings > System > Developer options.",
                "Set \"USB debugging\" and \"Wireless debugging\" to Off.",
                "Open Security and privacy and disable unknown app installs."
            )
            OemStepPack.GENERIC -> listOf(
                "Tap \"Developer options\".",
                "Tap \"USB debugging\" and \"Wireless debugging\" to disable when not needed.",
                "Tap \"Security\" and disable unknown install sources."
            )
        }
    }

    private fun wifiTapTargets(oemPack: OemStepPack): List<String> {
        return when (oemPack) {
            OemStepPack.MIUI -> listOf(
                "Open Settings > WLAN or Wi-Fi.",
                "Tap the risky SSID.",
                "Tap \"Forget network\" or \"Disconnect\"."
            )
            OemStepPack.SAMSUNG -> listOf(
                "Open Settings > Connections > Wi-Fi.",
                "Tap the risky SSID.",
                "Tap \"Forget\" or \"Disconnect\"."
            )
            OemStepPack.PIXEL -> listOf(
                "Open Settings > Network & internet > Internet.",
                "Tap the risky SSID.",
                "Tap \"Forget\" or \"Disconnect\"."
            )
            OemStepPack.GENERIC -> listOf(
                "Tap \"Wi-Fi\".",
                "Tap the risky SSID.",
                "Tap \"Forget\" or \"Disconnect\"."
            )
        }
    }

    private fun coreTapTargets(oemPack: OemStepPack, packageRef: String): List<String> {
        return when (oemPack) {
            OemStepPack.MIUI -> listOf(
                "Tap \"Permissions\".",
                "Tap \"Other permissions\" and set risky access to \"Deny\".",
                "If shown, tap \"Display pop-up windows\" and set to \"Deny\".",
                "If $packageRef is untrusted, tap \"Uninstall\"."
            )
            OemStepPack.SAMSUNG -> listOf(
                "Tap \"Permissions\".",
                "Set risky permissions to \"Don't allow\" unless required.",
                "Tap \"Appear on top\" and switch it Off when not required.",
                "If $packageRef is untrusted, tap \"Uninstall\"."
            )
            OemStepPack.PIXEL -> listOf(
                "Tap \"Permissions\".",
                "Set risky permissions to \"Don't allow\" unless required.",
                "Tap \"Display over other apps\" and set to \"Not allowed\" when not required.",
                "If $packageRef is untrusted, tap \"Uninstall\"."
            )
            OemStepPack.GENERIC -> listOf(
                "Tap \"Permissions\".",
                "Review high-risk permissions and set each one to \"Deny\" unless required.",
                "If the app is untrusted, tap \"Uninstall\"."
            )
        }
    }

    private fun applyRecommendedSettingsThenContinue(
        incident: IncidentRecord,
        guidance: IncidentGuidance,
        recommendedActions: List<IncidentAction>
    ) {
        val result = executeIncidentActions(incident, recommendedActions)
        showRecommendedSettingsApplyResultToast(result)
        continueWithContainmentOrGuidance(
            incident = incident,
            guidance = guidance
        )
    }

    private fun showRecommendedSettingsApplyResultToast(result: IncidentActionApplyResult) {
        when {
            result.successCount > 0 && result.failedCount == 0 -> Toast.makeText(
                this,
                getString(
                    R.string.incident_assistant_recommended_apply_success_template,
                    result.successCount
                ),
                Toast.LENGTH_LONG
            ).show()
            result.successCount > 0 -> Toast.makeText(
                this,
                getString(
                    R.string.incident_assistant_recommended_apply_partial_template,
                    result.successCount,
                    result.failedCount
                ),
                Toast.LENGTH_LONG
            ).show()
            else -> Toast.makeText(
                this,
                getString(R.string.incident_assistant_recommended_apply_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun continueWithContainmentOrGuidance(
        incident: IncidentRecord,
        guidance: IncidentGuidance
    ) {
        val containmentActions = guidance.actions.filter { it.automatable && it.destructive }
        if (containmentActions.isNotEmpty()) {
            showIncidentApplyConfirmationDialog(
                incident = incident,
                guidance = guidance,
                autoActionsOverride = containmentActions
            )
            return
        }
        showIncidentGuidanceDialog(incident, guidance)
    }

    private fun showIncidentGuidanceDialog(
        incident: IncidentRecord,
        guidance: IncidentGuidance = buildIncidentGuidance(incident)
    ) {
        val unresolved = IncidentStore.loadIncidents(this)
            .filter { it.status == IncidentStatus.OPEN || it.status == IncidentStatus.IN_PROGRESS }
        val highRemaining = unresolved.count { it.severity == Severity.HIGH }
        val mediumRemaining = unresolved.count { it.severity == Severity.MEDIUM }
        val lowRemaining = unresolved.count { it.severity == Severity.LOW }
        val severityLabel = userSeverityLabel(incident.severity)
        val detailsPreview = incident.details
            .trim()
            .replace("\r", "")
            .ifBlank { getString(R.string.incident_guidance_details_unavailable) }
            .let { value ->
                if (value.length <= 220) value else "${value.take(217)}..."
            }
        val shownSteps = guidance.steps.take(5)
        val hiddenSteps = (guidance.steps.size - shownSteps.size).coerceAtLeast(0)
        val shownActions = guidance.actions.take(3)
        val issueLineToken = "{{CURRENT_ISSUE_LINE}}"
        val messageTemplate = buildString {
            appendLine("Remaining incidents: high $highRemaining, medium $mediumRemaining, low $lowRemaining")
            appendLine()
            appendLine("Current issue")
            appendLine(issueLineToken)
            appendLine()
            appendLine("What to do now")
            shownSteps.forEachIndexed { index, step ->
                appendLine("${index + 1}. $step")
            }
            if (hiddenSteps > 0) {
                appendLine("${shownSteps.size + 1}. Continue for $hiddenSteps more step(s) after these.")
            }
            if (shownActions.isNotEmpty()) {
                appendLine()
                appendLine("Quick actions")
                shownActions.forEachIndexed { index, action ->
                    val modeLabel = if (action.automatable) {
                        "app can open"
                    } else {
                        "manual"
                    }
                    appendLine("${index + 1}. ${action.title} [$modeLabel]")
                }
                val hiddenActions = (guidance.actions.size - shownActions.size).coerceAtLeast(0)
                if (hiddenActions > 0) {
                    appendLine("+ $hiddenActions more action(s) available.")
                }
            }
            appendLine()
            appendLine("Technical details (optional)")
            appendLine(getString(R.string.incident_guidance_confidence_template, guidance.confidence))
            appendLine(getString(R.string.incident_guidance_why_template, guidance.whyLine))
            appendLine("Detection detail: ${compactTechnicalLine(detailsPreview, maxLen = 180)}")
            if (guidance.stepSignalMap.isNotEmpty()) {
                appendLine(getString(R.string.incident_guidance_signal_map_title))
                guidance.stepSignalMap.take(3).forEach { line ->
                    appendLine("- ${compactTechnicalLine(line, maxLen = 140)}")
                }
                val hiddenMapLines = (guidance.stepSignalMap.size - 3).coerceAtLeast(0)
                if (hiddenMapLines > 0) {
                    appendLine("- +$hiddenMapLines more mapping item(s).")
                }
            }
        }.trim()
        val message = SpannableStringBuilder(messageTemplate).apply {
            val tokenStart = messageTemplate.indexOf(issueLineToken)
            if (tokenStart >= 0) {
                replace(
                    tokenStart,
                    tokenStart + issueLineToken.length,
                    buildWorkNowSectionBody(
                        incident = incident,
                        severityLabel = severityLabel
                    )
                )
            } else {
                appendLine()
                append(
                    buildWorkNowSectionBody(
                        incident = incident,
                        severityLabel = severityLabel
                    )
                )
            }
        }
        val dialog = LionAlertDialogBuilder(this)
            .setTitle(R.string.incident_guidance_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.incident_guidance_mark_fixed_next) { _, _ ->
                resolveIncidentAndContinue(incident)
            }
            .setNeutralButton(R.string.incident_assistant_apply_choice) { _, _ ->
                showIncidentApplyConfirmationDialog(incident, guidance)
            }
            .setNegativeButton(R.string.scan_results_cancel, null)
            .create()
        showStyledDialog(dialog)
    }

    private fun showIncidentApplyConfirmationDialog(
        incident: IncidentRecord,
        guidance: IncidentGuidance,
        autoActionsOverride: List<IncidentAction>? = null
    ) {
        val autoActions = autoActionsOverride ?: guidance.actions.filter { it.automatable }
        if (autoActions.isEmpty()) {
            Toast.makeText(this, getString(R.string.incident_assistant_no_auto_actions), Toast.LENGTH_SHORT)
                .show()
            showIncidentGuidanceDialog(incident, guidance)
            return
        }
        val manualCount = guidance.actions.count { !it.automatable }
        val message = buildString {
            appendLine("The app can try these actions now:")
            appendLine()
            autoActions.forEachIndexed { index, action ->
                appendLine("${index + 1}. ${action.title}")
                appendLine("What this does: ${action.impact}")
                appendLine(
                    "Can you undo it: ${
                        if (action.reversible) getString(R.string.feedback_recommend_yes)
                        else getString(R.string.feedback_recommend_no)
                    }"
                )
                if (action.destructive) {
                    appendLine("Warning: this can remove or uninstall app data.")
                }
                appendLine()
            }
            if (manualCount > 0) {
                appendLine("$manualCount manual step(s) still need your review afterward.")
                appendLine()
            }
            append("Continue now?")
        }.trim()
        val dialog = LionAlertDialogBuilder(this)
            .setTitle(R.string.incident_assistant_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                logIncidentAssistantEvent(
                    incident = incident,
                    action = "incident_assistant_apply_confirmed",
                    detail = JSONObject().put("autoActionCount", autoActions.size)
                )
                applyIncidentActions(incident, guidance, autoActions)
            }
            .setNeutralButton(R.string.incident_assistant_guide_choice) { _, _ ->
                logIncidentAssistantEvent(
                    incident = incident,
                    action = "incident_assistant_apply_manual_redirect",
                    detail = JSONObject().put("autoActionCount", autoActions.size)
                )
                showIncidentGuidanceDialog(incident, guidance)
            }
            .setNegativeButton(R.string.scan_results_cancel) { _, _ ->
                logIncidentAssistantEvent(
                    incident = incident,
                    action = "incident_assistant_apply_canceled",
                    detail = JSONObject().put("autoActionCount", autoActions.size)
                )
            }
            .create()
        showStyledDialog(dialog)
    }

    private fun applyIncidentActions(
        incident: IncidentRecord,
        guidance: IncidentGuidance,
        autoActions: List<IncidentAction>
    ) {
        val result = executeIncidentActions(incident, autoActions)
        when {
            result.successCount > 0 && result.failedCount == 0 -> Toast.makeText(
                this,
                getString(R.string.incident_assistant_apply_success_template, result.successCount),
                Toast.LENGTH_LONG
            ).show()
            result.successCount > 0 -> Toast.makeText(
                this,
                getString(
                    R.string.incident_assistant_apply_partial_template,
                    result.successCount,
                    result.failedCount
                ),
                Toast.LENGTH_LONG
            ).show()
            else -> Toast.makeText(
                this,
                getString(R.string.incident_assistant_apply_failed),
                Toast.LENGTH_LONG
            ).show()
        }
        showIncidentGuidanceDialog(incident, guidance)
    }

    private fun executeIncidentActions(
        incident: IncidentRecord,
        actions: List<IncidentAction>
    ): IncidentActionApplyResult {
        var successCount = 0
        var failedCount = 0
        actions.forEach { action ->
            logIncidentAssistantEvent(
                incident = incident,
                action = "incident_action_attempted",
                detail = JSONObject()
                    .put("actionId", action.actionId)
                    .put("auditTag", action.auditTag)
                    .put("destructive", action.destructive)
            )
            val success = executeIncidentAction(action)
            if (success) {
                successCount += 1
            } else {
                failedCount += 1
            }
            logIncidentAssistantEvent(
                incident = incident,
                action = if (success) "incident_action_succeeded" else "incident_action_failed",
                detail = JSONObject()
                    .put("actionId", action.actionId)
                    .put("auditTag", action.auditTag)
                    .put("destructive", action.destructive)
            )
        }
        return IncidentActionApplyResult(
            successCount = successCount,
            failedCount = failedCount
        )
    }

    private fun executeIncidentAction(action: IncidentAction): Boolean {
        return when (action.execution) {
            IncidentActionExecution.OPEN_INTENTS -> action.intents.any { intent ->
                runCatching {
                    startActivity(intent)
                    true
                }.getOrDefault(false)
            }
            IncidentActionExecution.REQUEST_APP_UNINSTALL -> {
                val packageName = action.packageName.trim()
                if (packageName.isBlank()) {
                    false
                } else {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                        true
                    }.getOrDefault(false)
                }
            }
        }
    }

    private fun resolveIncidentAndContinue(incident: IncidentRecord) {
        val resolved = IncidentStore.markResolved(this, incident.incidentId)
        if (resolved == null) {
            Toast.makeText(this, getString(R.string.incident_no_active), Toast.LENGTH_SHORT).show()
            return
        }
        logIncidentAssistantEvent(
            incident = incident,
            action = "incident_assistant_marked_fixed",
            detail = JSONObject().put("incidentId", incident.incidentId)
        )
        val remaining = IncidentStore.nextUnresolvedForWork(this)
        if (remaining == null) {
            Toast.makeText(
                this,
                getString(R.string.incident_guidance_queue_complete),
                Toast.LENGTH_LONG
            ).show()
            if (incidentAssistantOnlyMode) {
                finish()
            }
            return
        }
        val candidate = if (remaining.status == IncidentStatus.OPEN) {
            IncidentStore.markInProgress(this, remaining.incidentId) ?: remaining
        } else {
            remaining
        }
        showIncidentDecisionDialog(candidate)
    }

    private fun logIncidentAssistantEvent(
        incident: IncidentRecord,
        action: String,
        detail: JSONObject = JSONObject()
    ) {
        SafeHygieneToolkit.logMaintenanceAction(
            context = this,
            action = action,
            detail = JSONObject()
                .put("incidentId", incident.incidentId)
                .put("severity", incident.severity.name)
                .put("title", incident.title.take(120))
                .put("detail", detail)
        )
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
            val actions = mutableListOf<IncidentAction>()
            actions += openIntentAction(
                actionId = "open_accessibility",
                title = getString(R.string.incident_guidance_open_accessibility),
                impact = "Open Accessibility settings so suspicious services can be disabled quickly.",
                manualInstruction = "Open Settings > Accessibility and turn off suspicious services.",
                auditTag = "incident_action_open_accessibility",
                intents = listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                reversible = true
            )
            if (context.packageName.isNotBlank()) {
                requestUninstallAction(context.packageName)?.let { actions += it }
            }
            return IncidentGuidance(
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
                ),
                actions = actions
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

    private fun userSeverityLabel(severity: Severity): String {
        return when (severity) {
            Severity.HIGH -> "High"
            Severity.MEDIUM -> "Medium"
            Severity.LOW -> "Low"
            Severity.INFO -> "Info"
        }
    }

    private fun compactTechnicalLine(value: String, maxLen: Int): String {
        val normalized = value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length <= maxLen) {
            return normalized
        }
        if (maxLen <= 3) {
            return normalized.take(maxLen)
        }
        return normalized.take(maxLen - 3) + "..."
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
        val hasActiveRuntimeAbuseSignal = lowerSignals.any {
            it.contains("active accessibility service enabled") ||
                it.contains("active device-admin privilege enabled")
        }
        val trustedRule = StartupPersistencePolicyGate.load(this).ruleFor(context.packageName)
        val suppressUninstallForTrusted = trustedRule?.suppressUninstallAction == true &&
            !hasActiveRuntimeAbuseSignal

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
            if (suppressUninstallForTrusted) add("trusted startup integration profile")
            if (isEmpty()) add("startup persistence profile match")
        }
        val signalsLine = if (riskyPermissionSignal.isBlank()) {
            "Review risky permissions for $packageRef and keep only what is required."
        } else {
            "Target permission signal: $riskyPermissionSignal"
        }
        val primaryIntents = intents.distinctBy { "${it.action}|${it.dataString.orEmpty()}" }
        val actions = mutableListOf<IncidentAction>()
        actions += openIntentAction(
            actionId = "startup_open_primary",
            title = getString(quickActionLabel),
            impact = "Open the highest-priority settings screen for this startup persistence finding.",
            manualInstruction = "Open the listed startup persistence settings manually if launch fails.",
            auditTag = "incident_action_startup_open_primary",
            intents = primaryIntents,
            reversible = true
        )
        if (context.packageName.isNotBlank() && !suppressUninstallForTrusted) {
            requestUninstallAction(context.packageName)?.let { actions += it }
        }
        return IncidentGuidance(
            confidence = confidenceLabel(
                incident = incident,
                moduleDetected = true,
                contextualSignals = matchedSignals.size
            ),
            whyLine = buildString {
                append("Parsed startup persistence indicators for $packageRef: ${matchedSignals.joinToString(", ")}.")
                if (suppressUninstallForTrusted) {
                    append(" Trusted integration profile is active; monitor mode kept uninstall as manual-only.")
                }
            },
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
                    } else if (suppressUninstallForTrusted) {
                        "Step 5 shifts to monitor mode because this package is trusted in startup integration policy."
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
                if (hasDeviceAdmin) {
                    "Open Settings > Security > Device admin apps and remove admin rights from $packageRef."
                } else if (suppressUninstallForTrusted) {
                    "This package is marked trusted by startup integration policy. Keep it installed, but monitor permissions and rerun deep scan after any permission/state change."
                } else {
                    "If the app is unknown or unmanaged, uninstall it from Settings > Apps."
                },
                "Run deep scan again and move to the next incident only when this startup finding is cleared."
            ),
            actions = actions
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
        val primaryIntents = intents.distinctBy { "${it.action}|${it.dataString.orEmpty()}" }
        val actions = mutableListOf<IncidentAction>()
        actions += openIntentAction(
            actionId = "storage_open_primary",
            title = getString(quickActionLabel),
            impact = "Open the storage location or app settings tied to this storage artifact finding.",
            manualInstruction = "Open Downloads/Storage settings manually and inspect the flagged path.",
            auditTag = "incident_action_storage_open_primary",
            intents = primaryIntents,
            reversible = true
        )
        if (packageFromPath.isNotBlank()) {
            requestUninstallAction(packageFromPath)?.let { actions += it }
        }
        return IncidentGuidance(
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
            ),
            actions = actions
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
        val primaryIntents = intents.distinctBy { "${it.action}|${it.dataString.orEmpty()}" }
        val actions = mutableListOf<IncidentAction>()
        actions += openIntentAction(
            actionId = "embedded_open_primary",
            title = getString(quickActionLabel),
            impact = "Open the security or developer controls needed for embedded-path/root hardening.",
            manualInstruction = "Open Security/Developer options manually to disable risky debug surfaces.",
            auditTag = "incident_action_embedded_open_primary",
            intents = primaryIntents,
            reversible = true
        )
        if (context.packageName.isNotBlank()) {
            requestUninstallAction(context.packageName)?.let { actions += it }
        }
        return IncidentGuidance(
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
            ),
            actions = actions
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
        val actions = listOf(
            openIntentAction(
                actionId = "wifi_open_settings",
                title = getString(R.string.incident_guidance_open_wifi),
                impact = "Open Wi-Fi settings to disconnect risky networks and lock trusted posture.",
                manualInstruction = "Open Wi-Fi settings manually and remove risky/open networks.",
                auditTag = "incident_action_wifi_open_settings",
                intents = listOf(Intent(Settings.ACTION_WIFI_SETTINGS)),
                reversible = true
            )
        )
        return IncidentGuidance(
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
            ),
            actions = actions
        )
    }

    private fun buildCoreGuidance(
        incident: IncidentRecord,
        context: IncidentContext
    ): IncidentGuidance {
        val detectedPackageName = if (context.packageName.isBlank()) {
            Regex("""(?i)new high-risk permissions:\s*([a-zA-Z0-9._]+)""")
                .find(incident.title)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        } else {
            context.packageName
        }
        val packageName = detectedPackageName.takeUnless { isProtectedPackage(it) }.orEmpty()
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
        val primaryIntents = intents.distinctBy { "${it.action}|${it.dataString.orEmpty()}" }
        val actions = mutableListOf<IncidentAction>()
        actions += openIntentAction(
            actionId = "core_open_primary",
            title = getString(quickActionLabel),
            impact = "Open the core settings surface tied to this incident so permissions can be tightened.",
            manualInstruction = "Open Security or App settings manually and remove risky capabilities.",
            auditTag = "incident_action_core_open_primary",
            intents = primaryIntents,
            reversible = true
        )
        if (packageName.isNotBlank()) {
            requestUninstallAction(packageName)?.let { actions += it }
        }
        return IncidentGuidance(
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
            ),
            actions = actions
        )
    }

    private fun openIntentAction(
        actionId: String,
        title: String,
        impact: String,
        manualInstruction: String,
        auditTag: String,
        intents: List<Intent>,
        reversible: Boolean
    ): IncidentAction {
        return IncidentAction(
            actionId = actionId,
            title = title,
            impact = impact,
            manualInstruction = manualInstruction,
            automatable = intents.isNotEmpty(),
            reversible = reversible,
            destructive = false,
            auditTag = auditTag,
            execution = IncidentActionExecution.OPEN_INTENTS,
            intents = intents.distinctBy { "${it.action}|${it.dataString.orEmpty()}" }
        )
    }

    private fun requestUninstallAction(packageName: String): IncidentAction? {
        val normalized = packageName.trim()
        if (normalized.isBlank() || isProtectedPackage(normalized)) {
            return null
        }
        return IncidentAction(
            actionId = "request_uninstall_${normalized.lowercase(Locale.US)}",
            title = getString(R.string.incident_assistant_action_request_uninstall, normalized),
            impact = "Launch Android uninstall confirmation for this package.",
            manualInstruction = "Open Settings > Apps > $normalized and uninstall if this app is untrusted.",
            automatable = true,
            reversible = false,
            destructive = true,
            auditTag = "incident_action_request_uninstall",
            execution = IncidentActionExecution.REQUEST_APP_UNINSTALL,
            packageName = normalized
        )
    }

    private fun isProtectedPackage(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase(Locale.US)
        if (normalized.isBlank()) {
            return false
        }
        val protectedRoots = setOf(
            this.packageName.lowercase(Locale.US),
            "com.realyn.watchdog"
        )
        return protectedRoots.any { root ->
            normalized == root || normalized.startsWith("$root.")
        }
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
            appendLine("Duplicate media summary")
            if (payload.mediaReadAccessGranted) {
                appendLine(
                    "You could potentially free ${
                        SafeHygieneToolkit.formatBytes(payload.duplicateMediaReclaimableBytes)
                    } by removing duplicate files."
                )
            } else {
                appendLine("Duplicate scan is limited because media access is not granted.")
            }
            appendLine()
            appendLine("What to do now")
            appendLine("1. Open Storage settings and review duplicate groups.")
            appendLine("2. Keep one known-good copy before deleting others.")
            if (!payload.mediaReadAccessGranted) {
                appendLine("3. Grant media access for a more complete duplicate scan.")
            }
            appendLine()
            appendLine("Technical details (optional)")
            appendLine("- Duplicate groups: ${payload.duplicateMediaGroupCount}")
            appendLine("- Duplicate files: ${payload.duplicateMediaFileCount}")
            appendLine("- Potential reclaim: ${SafeHygieneToolkit.formatBytes(payload.duplicateMediaReclaimableBytes)}")
            appendLine("- Media access: ${if (payload.mediaReadAccessGranted) "granted" else "not granted"}")
            appendLine()
            appendLine("Examples")
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
            appendLine("Unused apps summary")
            if (payload.usageAccessGranted) {
                appendLine("Found ${payload.inactiveAppCandidateCount} app(s) that may be unused.")
            } else {
                appendLine("Unused-app review is limited because Usage Access is not granted.")
            }
            appendLine()
            appendLine("What to do now")
            appendLine("1. Open Usage Access and enable it for fuller inactivity checks.")
            appendLine("2. Review listed apps and remove only apps you no longer need.")
            appendLine()
            appendLine("Technical details (optional)")
            appendLine("- Candidate apps: ${payload.inactiveAppCandidateCount}")
            appendLine("- Usage Access: ${if (payload.usageAccessGranted) "granted" else "not granted"}")
            appendLine()
            appendLine("Examples")
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
            appendLine("Installer files summary")
            if (payload.mediaReadAccessGranted) {
                appendLine(
                    "Found ${payload.installerRemnantCount} installer file(s), using ${
                        SafeHygieneToolkit.formatBytes(payload.installerRemnantBytes)
                    }."
                )
            } else {
                appendLine("Installer file review is limited because media access is not granted.")
            }
            appendLine()
            appendLine("What to do now")
            appendLine("1. Open Downloads or Storage settings and remove old installer files.")
            appendLine("2. Keep installers only if you still need them for re-install.")
            if (!payload.mediaReadAccessGranted) {
                appendLine("3. Grant media access for fuller installer detection.")
            }
            appendLine()
            appendLine("Technical details (optional)")
            appendLine("- Installer files: ${payload.installerRemnantCount}")
            appendLine("- Estimated size: ${SafeHygieneToolkit.formatBytes(payload.installerRemnantBytes)}")
            appendLine("- Media access: ${if (payload.mediaReadAccessGranted) "granted" else "not granted"}")
            appendLine()
            appendLine("Examples")
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
            appendLine("Safe cleanup summary")
            appendLine(
                "Estimated space you can safely reclaim now: ${
                    SafeHygieneToolkit.formatBytes(payload.safeCleanupBytes)
                }."
            )
            appendLine()
            appendLine("What to do now")
            appendLine("1. Keep the cleanup options you want checked below.")
            appendLine("2. Confirm cleanup to remove cache, stale local logs, and old queue records.")
            appendLine()
            appendLine("Technical details (optional)")
            appendLine("- Safe reclaim estimate: ${SafeHygieneToolkit.formatBytes(payload.safeCleanupBytes)}")
            appendLine("- Stale artifacts: ${payload.staleArtifactCount} file(s), ${SafeHygieneToolkit.formatBytes(payload.staleArtifactBytes)}")
            appendLine("- Installer files left behind: ${payload.installerRemnantCount} file(s), ${SafeHygieneToolkit.formatBytes(payload.installerRemnantBytes)}")
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
        applyExpandableSectionPalette(binding.scanResultsReportSectionsContainer, palette)

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

    private fun applyExpandableSectionPalette(
        container: LinearLayout,
        palette: LionThemePalette
    ) {
        for (index in 0 until container.childCount) {
            val sectionView = container.getChildAt(index)
            sectionView.findViewById<TextView>(R.id.expandableSectionHeader)?.setTextColor(palette.accent)
            sectionView.findViewById<TextView>(R.id.expandableSectionBody)?.setTextColor(palette.textSecondary)
        }
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
