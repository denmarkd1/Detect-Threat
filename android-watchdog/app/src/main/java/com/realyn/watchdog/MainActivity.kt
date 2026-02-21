package com.realyn.watchdog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.realyn.watchdog.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class SecurityActionRoute {
        RUN_SCAN,
        RUN_WIFI_POSTURE_SCAN,
        FIX_NOTIFICATIONS,
        FIX_OVERLAY,
        RUN_PHISHING_TRIAGE,
        START_INCIDENT,
        OPEN_SUPPORT,
        OPEN_CREDENTIAL_CENTER
    }

    private data class SecurityHeroAction(
        val label: String,
        val route: SecurityActionRoute
    )

    private data class SecurityHeroState(
        val score: Int,
        val tierLabel: String,
        val actions: List<SecurityHeroAction>,
        val details: List<String>
    )

    private lateinit var binding: ActivityMainBinding
    private var latestCopilotBrief: CopilotBrief? = null
    private var latestHygieneAudit: HygieneAuditResult? = null
    private var latestSecurityHeroState: SecurityHeroState? = null
    private var latestRiskCards: List<RiskCardModel> = emptyList()
    private var latestCopilotFromConnectedAi: Boolean = false
    private var latestCopilotSourceLabel: String = ""
    private var copilotRequestInFlight: Boolean = false
    private var hygieneAuditInFlight: Boolean = false
    private var advancedControlsVisible: Boolean = false
    private var appUnlockBootstrapDone: Boolean = false
    private var latestWifiSnapshot: WifiScanSnapshotRecord? = null
    private var pendingWifiPermissionScan: Boolean = false
    private var pendingVaultExportItemId: String = ""

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // No-op. App still functions if user denies notifications.
        }

    private val wifiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants.values.all { it }
            if (pendingWifiPermissionScan) {
                pendingWifiPermissionScan = false
                if (!granted) {
                    binding.subStatusLabel.text = getString(R.string.wifi_posture_permission_denied)
                }
                runWifiPostureScan()
            }
        }

    private val mediaVaultImportLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                importMediaIntoVault(uri)
            }
        }

    private val mediaVaultExportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val destination = if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data
            } else {
                null
            }
            completePendingVaultExport(destination)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.oneTimeScanButton.setOnClickListener { runOneTimeScan() }
        binding.continuousScanButton.setOnClickListener { toggleContinuousMode() }
        binding.refreshButton.setOnClickListener { refreshUiState() }
        binding.supportButton.setOnClickListener { openSupportCenter() }
        binding.credentialCenterButton.setOnClickListener { openCredentialCenter() }
        binding.checkUpdatesButton.setOnClickListener { runUpdateCheck() }
        binding.fixNotificationButton.setOnClickListener { openNotificationPermissionFix() }
        binding.fixOverlayButton.setOnClickListener { openOverlayPermissionFix() }
        binding.incidentStartButton.setOnClickListener { startNextIncident() }
        binding.incidentResolveButton.setOnClickListener { resolveNextIncident() }
        binding.incidentReopenButton.setOnClickListener { reopenResolvedIncident() }
        binding.wifiScanButton.setOnClickListener { ensureWifiScanPermissionsAndRun() }
        binding.wifiFindingsButton.setOnClickListener { openWifiFindingsDialog() }
        binding.mediaVaultImportButton.setOnClickListener { startMediaVaultImport() }
        binding.mediaVaultOpenButton.setOnClickListener { openMediaVaultDialog() }
        binding.securityTopActionButton.setOnClickListener { runTopSecurityAction() }
        binding.securityActionDetailsButton.setOnClickListener { openSecurityDetailsDialog() }
        binding.advancedControlsToggleButton.setOnClickListener { toggleAdvancedControls() }
        binding.pricingManageButton.setOnClickListener { openPlanSelectionDialog() }
        binding.pricingFeedbackButton.setOnClickListener { openPricingFeedbackDialog() }
        binding.scamRefreshButton.setOnClickListener { openPhishingTriageDialog() }
        binding.guardianFeedButton.setOnClickListener { openGuardianFeedDialog() }
        binding.translationSettingsButton.setOnClickListener { openLanguageSettings() }
        binding.translationFeedbackButton.setOnClickListener { reportTranslationIssue() }
        binding.hygieneAuditButton.setOnClickListener { runHygieneAudit(forceRefresh = true) }
        binding.hygieneCleanupButton.setOnClickListener { openHygieneCleanupDialog() }
        binding.copilotRefreshButton.setOnClickListener {
            refreshCopilotPanel(forceConnectedRefresh = true)
            binding.subStatusLabel.text = getString(R.string.copilot_refreshed_status)
        }
        binding.copilotPlaybookButton.setOnClickListener { openCopilotPlaybookDialog() }
        binding.copilotConnectButton.setOnClickListener { openConnectedAiDialog() }

        maybeRequestNotificationPermission()
        applyAdvancedControlsVisibility()
        enforceAccessGate()
    }

    override fun onResume() {
        super.onResume()
        enforceAccessGate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            AppAccessGate.onAppBackgrounded()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        AppAccessGate.onUserInteraction()
    }

    private fun enforceAccessGate() {
        AppAccessGate.ensureUnlocked(
            activity = this,
            onUnlocked = {
                if (!appUnlockBootstrapDone) {
                    appUnlockBootstrapDone = true
                    flushPendingFeedbackSync()
                }
                refreshUiState()
            },
            onDenied = {
                finish()
            }
        )
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun runOneTimeScan() {
        setBusy(true, "Running one-time local scan...")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                SecurityScanner.runScan(this@MainActivity, createBaselineIfMissing = true)
            }

            binding.statusLabel.text = SecurityScanner.summaryLine(result)
            binding.subStatusLabel.text = getString(R.string.scan_completed_substatus)
            binding.resultText.text = SecurityScanner.formatReport(result)
            setBusy(false)
            refreshUiState()
        }
    }

    private fun toggleContinuousMode() {
        if (SecurityScanner.isContinuousModeEnabled(this)) {
            val stopIntent = Intent(this, ContinuousWatchdogService::class.java).apply {
                action = WatchdogConfig.ACTION_STOP_CONTINUOUS
            }
            startService(stopIntent)
            binding.subStatusLabel.text = getString(R.string.continuous_stopping)
        } else {
            val access = PricingPolicy.resolveFeatureAccess(this)
            if (!access.features.continuousScanEnabled) {
                showFeatureLockedDialog(
                    getString(R.string.feature_locked_continuous_title),
                    getString(R.string.feature_locked_continuous_message)
                )
                return
            }

            val posture = SecurityScanner.currentRootPosture(this)
            val hardening = RootDefense.resolveHardeningPolicy(posture)
            if (!hardening.allowContinuousMode) {
                showRootHardeningBlockedDialog(
                    getString(
                        R.string.root_hardening_continuous_blocked_message,
                        formatRootReasons(posture.reasonCodes)
                    )
                )
                return
            }

            val startAction = {
                val startIntent = Intent(this, ContinuousWatchdogService::class.java).apply {
                    action = WatchdogConfig.ACTION_START_CONTINUOUS
                }
                ContextCompat.startForegroundService(this, startIntent)
                binding.subStatusLabel.text = getString(R.string.continuous_starting)
            }
            if (hardening.requireSensitiveActionConfirmation) {
                showRootSensitiveConfirmation(
                    posture = posture,
                    actionLabel = getString(R.string.action_start_continuous),
                    onConfirmed = startAction
                )
            } else {
                startAction()
            }
        }

        refreshUiState()
    }

    private fun runUpdateCheck() {
        setBusy(true, getString(R.string.update_check_running))

        lifecycleScope.launch {
            val result = AppUpdateChecker.checkForUpdate(this@MainActivity)
            binding.updateStatusLabel.text = result.message

            if (result.status == UpdateStatus.UPDATE_AVAILABLE && !result.downloadUrl.isNullOrBlank()) {
                val notes = result.releaseNotes ?: getString(R.string.update_release_notes_default)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.update_available_title)
                    .setMessage("${result.message}\n\n$notes")
                    .setPositiveButton(R.string.update_open_download) { _, _ ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))
                        runCatching { startActivity(intent) }
                            .onFailure { binding.updateStatusLabel.text = getString(R.string.overlay_open_site_failed) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }

            setBusy(false)
        }
    }

    private fun openCredentialCenter() {
        val intent = Intent(this, CredentialDefenseActivity::class.java)
        startActivity(intent)
    }

    private fun refreshUiState() {
        val active = SecurityScanner.isContinuousModeEnabled(this)
        val access = PricingPolicy.resolveFeatureAccess(this)
        val profileControl = PricingPolicy.resolveProfileControl(this, access)
        binding.continuousScanButton.text = if (active) {
            getString(R.string.action_stop_continuous)
        } else if (!access.features.continuousScanEnabled) {
            getString(R.string.action_start_continuous_paid)
        } else {
            getString(R.string.action_start_continuous)
        }

        if (active) {
            binding.statusLabel.text = getString(R.string.active_mode_on)
            binding.subStatusLabel.text = getString(R.string.active_mode_substatus)
        } else if (binding.statusLabel.text.isBlank()) {
            binding.statusLabel.text = getString(R.string.status_ready)
            binding.subStatusLabel.text = getString(R.string.substatus_default)
        }

        binding.pricingManageButton.isEnabled = profileControl.canManagePlan
        binding.guardianFeedButton.visibility = if (profileControl.canViewGuardianFeed) {
            View.VISIBLE
        } else {
            View.GONE
        }

        refreshWifiPanel()
        refreshMediaVaultPanel()
        refreshReadinessPanel()
        refreshScamPanel()
        refreshCopilotPanel()
        refreshHygienePanel()
        refreshIncidentPanel()
        refreshPricingPanel()
        refreshTranslationPanel()
        refreshSecurityHero()
        applyAdvancedControlsVisibility()
    }

    private fun setBusy(busy: Boolean, status: String? = null) {
        val profileControl = PricingPolicy.resolveProfileControl(this)
        binding.oneTimeScanButton.isEnabled = !busy
        binding.continuousScanButton.isEnabled = !busy
        binding.refreshButton.isEnabled = !busy
        binding.supportButton.isEnabled = !busy
        binding.credentialCenterButton.isEnabled = !busy
        binding.checkUpdatesButton.isEnabled = !busy
        binding.fixNotificationButton.isEnabled = !busy
        binding.fixOverlayButton.isEnabled = !busy
        binding.incidentStartButton.isEnabled = !busy
        binding.incidentResolveButton.isEnabled = !busy
        binding.incidentReopenButton.isEnabled = !busy
        binding.wifiScanButton.isEnabled = !busy
        binding.wifiFindingsButton.isEnabled = !busy
        binding.mediaVaultImportButton.isEnabled = !busy
        binding.mediaVaultOpenButton.isEnabled = !busy
        binding.securityTopActionButton.isEnabled = !busy
        binding.securityActionDetailsButton.isEnabled = !busy
        binding.advancedControlsToggleButton.isEnabled = !busy
        binding.pricingManageButton.isEnabled = !busy && profileControl.canManagePlan
        binding.pricingFeedbackButton.isEnabled = !busy
        binding.scamRefreshButton.isEnabled = !busy
        binding.guardianFeedButton.isEnabled = !busy && profileControl.canViewGuardianFeed
        binding.translationSettingsButton.isEnabled = !busy
        binding.translationFeedbackButton.isEnabled = !busy
        binding.hygieneAuditButton.isEnabled = !busy
        binding.hygieneCleanupButton.isEnabled = !busy
        binding.copilotRefreshButton.isEnabled = !busy
        binding.copilotPlaybookButton.isEnabled = !busy
        binding.copilotConnectButton.isEnabled = !busy

        if (status != null) {
            binding.statusLabel.text = status
        }
    }

    private fun openSupportCenter() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val profileControl = PricingPolicy.resolveProfileControl(this, access)
        val supportTier = if (access.paidAccess) "paid" else "free"
        val aiEnabled = if (access.features.aiHotlineEnabled) "1" else "0"
        val profileRole = profileControl.roleCode
        val baseUrl = getString(R.string.support_center_url)
        val joined = if (baseUrl.contains("?")) "&" else "?"
        val ageCode = profileControl.ageBandCode
        val supportUrl = "$baseUrl${joined}tier=$supportTier&ai_hotline=$aiEnabled&profile_role=$profileRole&age_band=$ageCode"
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(supportUrl)
        )
        runCatching { startActivity(intent) }
            .onFailure { binding.updateStatusLabel.text = getString(R.string.overlay_open_site_failed) }
    }

    private fun refreshPricingPanel() {
        val model = PricingPolicy.load(this)
        val access = PricingPolicy.resolveFeatureAccess(this, model)
        val profileControl = PricingPolicy.resolveProfileControl(this, access)
        val regional = PricingPolicy.resolveForCurrentRegion(this, model)
        val trial = PricingPolicy.ensureTrial(this)
        val entitlement = PricingPolicy.entitlement(this)
        val feedback = PricingPolicy.feedbackStatus(this)
        val selectedPlan = PricingPolicy.selectedPlan(this)
        val nextDue = PricingPolicy.nextPaymentDue(this, model)

        val summary = if (entitlement.isLifetimePro) {
            getString(
                R.string.pricing_lifetime_active_template,
                entitlementSourceLabel(entitlement.source)
            )
        } else if (trial.inTrial) {
            getString(R.string.pricing_trial_active_template, trial.daysRemaining, model.freeTrialDays)
        } else {
            if (selectedPlan == "none") {
                getString(R.string.pricing_trial_ended_no_plan)
            } else {
                getString(
                    R.string.pricing_trial_ended_plan_template,
                    planLabel(selectedPlan, regional)
                )
            }
        }
        val profileRoleLabel = when (profileControl.roleCode) {
            "child" -> getString(R.string.profile_role_child)
            "family_single" -> getString(R.string.profile_role_family_single)
            else -> getString(R.string.profile_role_parent)
        }
        val roleLine = when {
            profileControl.canManagePlan -> getString(R.string.pricing_profile_role_template, profileRoleLabel)
            profileControl.roleCode == "family_single" -> getString(
                R.string.pricing_profile_family_single_controls_template,
                profileRoleLabel
            )
            else -> getString(R.string.pricing_profile_child_controls_template, profileRoleLabel)
        }
        val ageValue = if (profileControl.ageYears >= 0) {
            profileControl.ageYears.toString()
        } else {
            getString(R.string.profile_age_unknown)
        }
        val ageLine = getString(
            R.string.pricing_profile_age_band_template,
            profileControl.ageBandLabel,
            ageValue
        )
        val graduationLine = if (profileControl.graduatedToFamilySingle) {
            getString(R.string.pricing_family_graduation_note)
        } else {
            ""
        }
        val familyPriceLine = if (selectedPlan == "family") {
            getString(R.string.pricing_family_flat_rate_note)
        } else {
            ""
        }
        binding.pricingSummaryLabel.text = listOf(summary, roleLine, ageLine, graduationLine, familyPriceLine)
            .filter { it.isNotBlank() }
            .joinToString("\n")

        val feedbackContext = when {
            feedback.completed -> getString(
                R.string.pricing_feedback_summary_template,
                feedback.performanceRating,
                if (feedback.recommendToFriends) {
                    getString(R.string.feedback_recommend_yes)
                } else {
                    getString(R.string.feedback_recommend_no)
                }
            )
            else -> getString(R.string.pricing_feedback_missing)
        }

        binding.pricingNextDueLabel.text = when (nextDue.statusCode) {
            "lifetime" -> getString(R.string.pricing_next_due_lifetime)
            "trial" -> getString(
                R.string.pricing_next_due_trial_template,
                formatShortDate(nextDue.dueAtEpochMs)
            )
            "scheduled" -> getString(
                R.string.pricing_next_due_scheduled_template,
                formatShortDate(nextDue.dueAtEpochMs),
                shortPlanLabel(nextDue.planId)
            )
            else -> getString(R.string.pricing_next_due_none)
        }

        binding.pricingFeatureAccessLabel.text = getString(
            R.string.pricing_included_functions_template,
            includedFeaturesSummary(access)
        )
        binding.pricingFeedbackLabel.text = feedbackContext
        binding.pricingUpgradeReasonLabel.text = when {
            entitlement.isLifetimePro -> getString(R.string.pricing_upgrade_reason_lifetime)
            trial.inTrial -> getString(R.string.pricing_upgrade_reason_trial)
            access.paidAccess -> getString(R.string.pricing_upgrade_reason_paid)
            else -> getString(R.string.pricing_upgrade_reason_free)
        }
    }

    private fun openPlanSelectionDialog() {
        val currentAccess = PricingPolicy.resolveFeatureAccess(this)
        val profileControl = PricingPolicy.resolveProfileControl(this, currentAccess)
        if (!profileControl.canManagePlan) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pricing_manage_restricted_title)
                .setMessage(R.string.pricing_manage_restricted_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val entitlement = PricingPolicy.entitlement(this)
        if (entitlement.isLifetimePro) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pricing_dialog_title)
                .setMessage(
                    getString(
                        R.string.pricing_lifetime_dialog_message,
                        entitlementSourceLabel(entitlement.source)
                    )
                )
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.action_open_billing) { _, _ ->
                    openSupportCenter()
                }
                .show()
            return
        }

        val model = PricingPolicy.load(this)
        val baseRegional = PricingPolicy.resolveForCurrentRegion(this, model)
        val feedback = PricingPolicy.feedbackStatus(this)
        val discountPercent = PricingPolicy.resolveReferralDiscountPercent(model, feedback)
        val regional = PricingPolicy.applyReferralDiscount(baseRegional, discountPercent)
        val familyMonthly = PricingPolicy.resolveFamilyMonthlyPrice(this, model, regional)
        val options = arrayOf(
            getString(
                R.string.pricing_option_weekly_template,
                PricingPolicy.formatMoney(regional.currencyCode, regional.weekly)
            ),
            getString(
                R.string.pricing_option_monthly_template,
                PricingPolicy.formatMoney(regional.currencyCode, regional.monthly)
            ),
            getString(
                R.string.pricing_option_yearly_template,
                PricingPolicy.formatMoney(regional.currencyCode, regional.yearly)
            ),
            getString(
                R.string.pricing_option_family_template,
                PricingPolicy.formatMoney(regional.currencyCode, familyMonthly)
            )
        )
        val planIds = arrayOf("weekly", "monthly", "yearly", "family")
        var selectedIndex = planIds.indexOf(PricingPolicy.selectedPlan(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.pricing_dialog_title)
            .setMessage(getString(R.string.pricing_dialog_note))
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selectedPlan = planIds[selectedIndex]
                PricingPolicy.saveSelectedPlan(this, selectedPlan)
                refreshPricingPanel()
                binding.subStatusLabel.text = getString(
                    R.string.plan_saved_template,
                    planLabel(selectedPlan, regional)
                )
            }
            .setNeutralButton(R.string.action_open_billing) { _, _ ->
                openSupportCenter()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openPricingFeedbackDialog() {
        val feedback = PricingPolicy.feedbackStatus(this)
        val ratingOptions = arrayOf(
            getString(R.string.feedback_rating_1),
            getString(R.string.feedback_rating_2),
            getString(R.string.feedback_rating_3),
            getString(R.string.feedback_rating_4),
            getString(R.string.feedback_rating_5)
        )
        var selectedRatingIndex = (feedback.performanceRating.coerceIn(1, 5) - 1).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.feedback_dialog_title)
            .setMessage(R.string.feedback_dialog_message)
            .setSingleChoiceItems(ratingOptions, selectedRatingIndex) { _, which ->
                selectedRatingIndex = which
            }
            .setPositiveButton(R.string.action_next) { _, _ ->
                openRecommendationDialog(selectedRatingIndex + 1)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openRecommendationDialog(performanceRating: Int) {
        val existing = PricingPolicy.feedbackStatus(this)
        val recommendOptions = arrayOf(
            getString(R.string.feedback_recommend_yes),
            getString(R.string.feedback_recommend_no)
        )
        var selectedRecommendation = if (existing.recommendToFriends) 0 else 1

        AlertDialog.Builder(this)
            .setTitle(R.string.feedback_recommend_title)
            .setMessage(R.string.feedback_recommend_message)
            .setSingleChoiceItems(recommendOptions, selectedRecommendation) { _, which ->
                selectedRecommendation = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val recommendToFriends = selectedRecommendation == 0
                PricingPolicy.saveFeedback(this, performanceRating, recommendToFriends)
                refreshPricingPanel()

                val model = PricingPolicy.load(this)
                val entitlement = PricingPolicy.entitlement(this)
                val discountPercent = if (entitlement.isLifetimePro) {
                    0.0
                } else {
                    PricingPolicy.resolveReferralDiscountPercent(
                        model,
                        PricingPolicy.feedbackStatus(this)
                    )
                }
                val baseStatus = when {
                    entitlement.isLifetimePro -> getString(
                        R.string.feedback_saved_lifetime_template,
                        performanceRating
                    )
                    discountPercent > 0.0 -> getString(
                        R.string.feedback_saved_discount_template,
                        performanceRating,
                        formatDiscountPercent(discountPercent)
                    )
                    else -> getString(R.string.feedback_saved_template, performanceRating)
                }
                binding.subStatusLabel.text = baseStatus
                submitFeedbackToSupport(
                    performanceRating = performanceRating,
                    recommendToFriends = recommendToFriends,
                    baseStatus = baseStatus
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun planLabel(planId: String, regional: ResolvedRegionalPricing): String {
        return when (planId) {
            "weekly" -> getString(
                R.string.pricing_plan_weekly_template,
                PricingPolicy.formatMoney(regional.currencyCode, regional.weekly)
            )
            "monthly" -> getString(
                R.string.pricing_plan_monthly_template,
                PricingPolicy.formatMoney(regional.currencyCode, regional.monthly)
            )
            "family" -> getString(
                R.string.pricing_plan_family_template,
                PricingPolicy.formatMoney(
                    regional.currencyCode,
                    PricingPolicy.resolveFamilyMonthlyPrice(this, regional = regional)
                )
            )
            "yearly" -> getString(
                R.string.pricing_plan_yearly_template,
                PricingPolicy.formatMoney(regional.currencyCode, regional.yearly)
            )
            "lifetime" -> getString(R.string.pricing_plan_lifetime)
            else -> getString(R.string.pricing_plan_none)
        }
    }

    private fun entitlementSourceLabel(source: String): String {
        return when (source.lowercase(Locale.US)) {
            "device_allowlist" -> getString(R.string.pricing_lifetime_source_allowlist)
            else -> getString(R.string.pricing_lifetime_source_manual)
        }
    }

    private fun formatDiscountPercent(value: Double): String {
        return if (value % 1.0 == 0.0) {
            String.format(Locale.US, "%.0f%%", value)
        } else {
            String.format(Locale.US, "%.1f%%", value)
        }
    }

    private fun includedFeaturesSummary(access: ResolvedFeatureAccess): String {
        val items = mutableListOf(
            getString(R.string.pricing_feature_one_time_scan),
            getString(R.string.pricing_feature_breach_scan),
            getString(R.string.pricing_feature_password_vault)
        )
        if (access.features.rotationQueueEnabled) {
            items += getString(R.string.pricing_feature_rotation_queue)
        }
        if (access.features.overlayAssistantEnabled) {
            items += getString(R.string.pricing_feature_overlay_assistant)
        }
        if (access.features.continuousScanEnabled) {
            items += getString(R.string.pricing_feature_continuous_scan)
        }
        if (access.features.aiHotlineEnabled) {
            items += getString(R.string.pricing_feature_ai_hotline)
        }

        val breachLimit = if (access.features.breachScansPerDay < 0) {
            getString(R.string.pricing_feature_unlimited)
        } else {
            access.features.breachScansPerDay.toString()
        }
        items += getString(R.string.pricing_feature_breach_limit_template, breachLimit)
        return items.joinToString(getString(R.string.pricing_feature_list_separator))
    }

    private fun shortPlanLabel(planId: String): String {
        return when (planId.lowercase(Locale.US)) {
            "weekly" -> getString(R.string.pricing_plan_weekly_short)
            "monthly" -> getString(R.string.pricing_plan_monthly_short)
            "family" -> getString(R.string.pricing_plan_family_short)
            "yearly" -> getString(R.string.pricing_plan_yearly_short)
            else -> getString(R.string.pricing_plan_none)
        }
    }

    private fun formatShortDate(epochMs: Long): String {
        if (epochMs <= 0L) {
            return "-"
        }
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return formatter.format(Date(epochMs))
    }

    private fun showFeatureLockedDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.action_manage_plan) { _, _ ->
                openPlanSelectionDialog()
            }
            .setNeutralButton(R.string.action_open_billing) { _, _ ->
                openSupportCenter()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun submitFeedbackToSupport(
        performanceRating: Int,
        recommendToFriends: Boolean,
        baseStatus: String
    ) {
        lifecycleScope.launch {
            val accessTier = PricingPolicy.resolveFeatureAccess(this@MainActivity).tierCode
            val selectedPlan = PricingPolicy.selectedPlan(this@MainActivity)
            val profileControl = PricingPolicy.resolveProfileControl(this@MainActivity)
            val profileRole = profileControl.roleCode
            val sync = withContext(Dispatchers.IO) {
                SupportFeedbackReporter.submitFeedback(
                    context = this@MainActivity,
                    rating = performanceRating,
                    recommendToFriends = recommendToFriends,
                    tier = accessTier,
                    selectedPlan = selectedPlan,
                    profileRole = profileRole,
                    ageBandCode = profileControl.ageBandCode,
                    createTicket = true
                )
            }

            val syncMessage = when {
                sync.submittedNow > 0 && sync.lastTicketId.isNotBlank() -> getString(
                    R.string.feedback_support_logged_ticket_template,
                    sync.lastTicketId
                )
                sync.submittedNow > 0 -> getString(
                    R.string.feedback_support_logged_template,
                    sync.submittedNow,
                    sync.remainingQueue
                )
                else -> getString(
                    R.string.feedback_support_queued_template,
                    sync.remainingQueue
                )
            }
            binding.subStatusLabel.text = "$baseStatus $syncMessage"
        }
    }

    private fun flushPendingFeedbackSync() {
        lifecycleScope.launch {
            val sync = withContext(Dispatchers.IO) {
                SupportFeedbackReporter.flushPending(this@MainActivity)
            }
            if (sync.submittedNow <= 0) {
                return@launch
            }
            binding.updateStatusLabel.text = if (sync.remainingQueue > 0) {
                getString(
                    R.string.feedback_support_flush_partial_template,
                    sync.submittedNow,
                    sync.remainingQueue
                )
            } else {
                getString(
                    R.string.feedback_support_flush_complete_template,
                    sync.submittedNow
                )
            }
        }
    }

    private fun ensureWifiScanPermissionsAndRun() {
        val config = WifiPostureScanner.config(this)
        if (!config.enabled) {
            binding.subStatusLabel.text = getString(R.string.wifi_posture_policy_disabled)
            return
        }

        val required = WifiPermissionGate.requiredRuntimePermissions(this)
        if (required.isEmpty()) {
            runWifiPostureScan()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.wifi_posture_permission_required_title)
            .setMessage(R.string.wifi_posture_permission_required_message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                pendingWifiPermissionScan = true
                wifiPermissionLauncher.launch(required)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runWifiPostureScan() {
        setBusy(true, getString(R.string.wifi_posture_scan_running))
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.Default) {
                WifiPostureScanner.runPostureScan(this@MainActivity)
            }
            latestWifiSnapshot = WifiScanSnapshotStore.latest(this@MainActivity)
            binding.subStatusLabel.text = getString(R.string.wifi_posture_scan_completed)
            binding.resultText.text = buildString {
                appendLine(getString(R.string.wifi_posture_findings_title))
                appendLine(
                    getString(
                        R.string.wifi_posture_summary_template,
                        snapshot.scannedAtIso,
                        snapshot.score,
                        snapshot.tier,
                        snapshot.ssid.ifBlank { "unknown" },
                        snapshot.securityType
                    )
                )
                appendLine()
                appendLine(getString(R.string.wifi_posture_actions_title))
                snapshot.recommendations.forEachIndexed { index, line ->
                    appendLine("${index + 1}. $line")
                }
            }.trim()
            setBusy(false)
            refreshUiState()
        }
    }

    private fun refreshWifiPanel() {
        val latest = WifiScanSnapshotStore.latest(this)
        latestWifiSnapshot = latest
        if (latest == null) {
            binding.wifiSummaryLabel.text = getString(R.string.wifi_posture_no_snapshot)
            binding.wifiActionsLabel.text = getString(R.string.wifi_posture_actions_placeholder)
            return
        }

        binding.wifiSummaryLabel.text = getString(
            R.string.wifi_posture_summary_template,
            latest.scannedAtIso.ifBlank { getString(R.string.scam_shield_unknown_time) },
            latest.score,
            latest.tier,
            latest.ssid.ifBlank { "unknown" },
            latest.securityType.ifBlank { "unknown" }
        )

        binding.wifiActionsLabel.text = if (latest.recommendations.isEmpty()) {
            getString(R.string.wifi_posture_actions_placeholder)
        } else {
            val lines = latest.recommendations.take(3).mapIndexed { index, item ->
                "${index + 1}. $item"
            }.joinToString("\n")
            "${getString(R.string.wifi_posture_actions_title)}:\n$lines"
        }
    }

    private fun openWifiFindingsDialog() {
        val latest = latestWifiSnapshot ?: WifiScanSnapshotStore.latest(this)
        if (latest == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.wifi_posture_findings_title)
                .setMessage(R.string.wifi_posture_findings_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val message = buildString {
            appendLine(
                getString(
                    R.string.wifi_posture_summary_template,
                    latest.scannedAtIso.ifBlank { getString(R.string.scam_shield_unknown_time) },
                    latest.score,
                    latest.tier,
                    latest.ssid.ifBlank { "unknown" },
                    latest.securityType.ifBlank { "unknown" }
                )
            )
            appendLine()
            if (latest.findings.isNotEmpty()) {
                appendLine(getString(R.string.wifi_posture_findings_section_title))
                latest.findings.forEachIndexed { index, line ->
                    appendLine("${index + 1}. $line")
                }
                appendLine()
            }
            appendLine(getString(R.string.wifi_posture_actions_title))
            latest.recommendations.forEachIndexed { index, line ->
                appendLine("${index + 1}. $line")
            }
        }.trim()

        AlertDialog.Builder(this)
            .setTitle(R.string.wifi_posture_findings_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refreshMediaVaultPanel() {
        val accessCheck = MediaVaultPolicyGate.canAccessVault(this)
        if (!accessCheck.allowed) {
            binding.mediaVaultSummaryLabel.text = when (accessCheck.reasonCode) {
                "disabled" -> getString(R.string.media_vault_policy_disabled)
                "locked" -> getString(R.string.media_vault_locked)
                else -> getString(R.string.media_vault_loading)
            }
            binding.mediaVaultActionsLabel.text = getString(R.string.media_vault_actions_placeholder)
            return
        }

        val policy = MediaVaultPolicyGate.load(this)
        val summary = MediaVaultIndexStore.summary(this)
        val lastUpdated = summary.lastUpdatedIso.ifBlank { getString(R.string.media_vault_unknown_time) }
        binding.mediaVaultSummaryLabel.text = if (summary.activeCount == 0 && summary.deletedCount == 0) {
            getString(R.string.media_vault_summary_empty)
        } else {
            getString(
                R.string.media_vault_summary_template,
                summary.activeCount,
                summary.deletedCount,
                lastUpdated
            )
        }

        val access = PricingPolicy.resolveFeatureAccess(this)
        val capLine = if (access.paidAccess) {
            getString(R.string.media_vault_paid_limit_hint)
        } else {
            getString(R.string.media_vault_free_limit_hint_template, policy.maxItemsFree)
        }
        val retentionLine = getString(R.string.media_vault_retention_hint_template, policy.retentionDaysDeleted)
        binding.mediaVaultActionsLabel.text = "$capLine\n$retentionLine"
    }

    private fun startMediaVaultImport() {
        val activeCount = MediaVaultIndexStore.readActiveItems(this).size
        val accessCheck = MediaVaultPolicyGate.canImport(this, activeCount)
        if (!accessCheck.allowed) {
            applyMediaVaultAccessFailure(accessCheck)
            return
        }
        mediaVaultImportLauncher.launch("*/*")
    }

    private fun importMediaIntoVault(sourceUri: Uri) {
        val activeCount = MediaVaultIndexStore.readActiveItems(this).size
        val accessCheck = MediaVaultPolicyGate.canImport(this, activeCount)
        if (!accessCheck.allowed) {
            applyMediaVaultAccessFailure(accessCheck)
            return
        }

        setBusy(true, getString(R.string.media_vault_import_running))
        lifecycleScope.launch {
            val profileControl = PricingPolicy.resolveProfileControl(this@MainActivity)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    MediaVaultFileStore.importFromUri(
                        context = this@MainActivity,
                        sourceUri = sourceUri,
                        ownerRole = profileControl.roleCode
                    )
                }.getOrElse { null }
            }
            setBusy(false)
            binding.subStatusLabel.text = if (result != null) {
                getString(R.string.media_vault_imported_template, result.type)
            } else {
                getString(R.string.media_vault_import_failed)
            }
            refreshUiState()
        }
    }

    private fun openMediaVaultDialog() {
        val accessCheck = MediaVaultPolicyGate.canAccessVault(this)
        if (!accessCheck.allowed) {
            applyMediaVaultAccessFailure(accessCheck)
            return
        }

        lifecycleScope.launch {
            val policy = MediaVaultPolicyGate.load(this@MainActivity)
            val result = withContext(Dispatchers.IO) {
                val purged = MediaVaultFileStore.purgeExpiredDeleted(
                    context = this@MainActivity,
                    retentionDays = policy.retentionDaysDeleted
                )
                val items = MediaVaultIndexStore.readItems(this@MainActivity)
                Pair(purged, items)
            }
            val purged = result.first
            val items = result.second
            if (purged > 0) {
                binding.subStatusLabel.text = getString(R.string.media_vault_purge_result_template, purged)
            }
            refreshMediaVaultPanel()

            if (items.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.media_vault_dialog_title)
                    .setMessage(R.string.media_vault_dialog_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            val labels = items.map { MediaVaultFileStore.formatItemLine(it) }.toTypedArray()
            var selectedIndex = 0
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.media_vault_dialog_title)
                .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                    selectedIndex = which
                }
                .setPositiveButton(R.string.media_vault_action_manage) { _, _ ->
                    showMediaVaultItemActions(items[selectedIndex], policy.retentionDaysDeleted)
                }
                .setNeutralButton(R.string.media_vault_action_purge_expired) { _, _ ->
                    lifecycleScope.launch {
                        val purgedNow = withContext(Dispatchers.IO) {
                            MediaVaultFileStore.purgeExpiredDeleted(
                                context = this@MainActivity,
                                retentionDays = policy.retentionDaysDeleted
                            )
                        }
                        binding.subStatusLabel.text = getString(
                            R.string.media_vault_purge_result_template,
                            purgedNow
                        )
                        refreshUiState()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showMediaVaultItemActions(item: VaultItem, retentionDays: Int) {
        val state = VaultRetentionState.fromRaw(item.retentionState)
        if (state == VaultRetentionState.DELETED) {
            val options = arrayOf(
                getString(R.string.action_restore_vault_item),
                getString(R.string.action_delete_vault_item_now)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.media_vault_item_deleted_actions_title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> runVaultRestoreAction(item, retentionDays)
                        else -> confirmVaultDeleteAction(item, retentionDays, permanent = true)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val options = arrayOf(
            getString(R.string.action_secure_open),
            getString(R.string.action_export_vault_item),
            getString(R.string.action_delete_vault_item)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.media_vault_item_actions_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> runVaultOpenAction(item)
                    1 -> runVaultExportAction(item)
                    else -> confirmVaultDeleteAction(item, retentionDays, permanent = false)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runVaultOpenAction(item: VaultItem) {
        val accessCheck = MediaVaultPolicyGate.canAccessVault(this)
        if (!accessCheck.allowed) {
            applyMediaVaultAccessFailure(accessCheck)
            return
        }

        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    MediaVaultFileStore.openSecureView(this@MainActivity, item.id)
                }.isSuccess
            }
            binding.subStatusLabel.text = if (opened) {
                getString(R.string.media_vault_opened)
            } else {
                getString(R.string.media_vault_open_failed)
            }
            refreshUiState()
        }
    }

    private fun runVaultExportAction(item: VaultItem) {
        val accessCheck = MediaVaultPolicyGate.canAccessVault(this)
        if (!accessCheck.allowed) {
            applyMediaVaultAccessFailure(accessCheck)
            return
        }

        val launchPicker = { launchVaultExportPicker(item) }
        GuardianOverridePolicy.requestApproval(
            activity = this,
            action = GuardianProtectedAction.VAULT_EXPORT,
            actionLabel = getString(R.string.guardian_action_media_vault_export),
            reasonCode = "media_vault_export",
            onApproved = launchPicker
        )
    }

    private fun launchVaultExportPicker(item: VaultItem) {
        pendingVaultExportItemId = item.id
        val exportIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = item.mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_TITLE, MediaVaultFileStore.defaultExportFileName(item))
        }
        mediaVaultExportLauncher.launch(exportIntent)
    }

    private fun completePendingVaultExport(destinationUri: Uri?) {
        val itemId = pendingVaultExportItemId
        pendingVaultExportItemId = ""
        if (itemId.isBlank()) {
            return
        }
        if (destinationUri == null) {
            return
        }
        val accessCheck = MediaVaultPolicyGate.canAccessVault(this)
        if (!accessCheck.allowed) {
            applyMediaVaultAccessFailure(accessCheck)
            return
        }

        setBusy(true, getString(R.string.media_vault_export_running))
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    MediaVaultFileStore.exportToUri(
                        context = this@MainActivity,
                        itemId = itemId,
                        destinationUri = destinationUri
                    )
                }.isSuccess
            }
            setBusy(false)
            binding.subStatusLabel.text = if (success) {
                getString(R.string.media_vault_export_complete)
            } else {
                getString(R.string.media_vault_export_failed)
            }
            refreshUiState()
        }
    }

    private fun confirmVaultDeleteAction(item: VaultItem, retentionDays: Int, permanent: Boolean) {
        val titleRes = if (permanent) {
            R.string.media_vault_delete_now_confirm_title
        } else {
            R.string.media_vault_delete_confirm_title
        }
        val message = if (permanent) {
            getString(R.string.media_vault_delete_now_confirm_message)
        } else {
            getString(R.string.media_vault_delete_confirm_message_template, retentionDays)
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                runVaultDeleteAction(item, permanent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runVaultDeleteAction(item: VaultItem, permanent: Boolean) {
        val accessCheck = MediaVaultPolicyGate.canAccessVault(this)
        if (!accessCheck.allowed) {
            applyMediaVaultAccessFailure(accessCheck)
            return
        }

        val executeDelete: () -> Unit = {
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    if (permanent) {
                        MediaVaultFileStore.purgeImmediately(this@MainActivity, item.id)
                    } else {
                        MediaVaultFileStore.markDeleted(this@MainActivity, item.id) != null
                    }
                }
                binding.subStatusLabel.text = when {
                    !success -> getString(R.string.media_vault_action_unavailable)
                    permanent -> getString(R.string.media_vault_delete_now_status)
                    else -> getString(R.string.media_vault_deleted_status)
                }
                refreshUiState()
            }
        }

        GuardianOverridePolicy.requestApproval(
            activity = this,
            action = GuardianProtectedAction.VAULT_DELETE,
            actionLabel = getString(R.string.guardian_action_media_vault_delete),
            reasonCode = if (permanent) "media_vault_delete_permanent" else "media_vault_delete_soft",
            onApproved = executeDelete
        )
    }

    private fun runVaultRestoreAction(item: VaultItem, retentionDays: Int) {
        val accessCheck = MediaVaultPolicyGate.canAccessVault(this)
        if (!accessCheck.allowed) {
            applyMediaVaultAccessFailure(accessCheck)
            return
        }

        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) {
                MediaVaultFileStore.restore(
                    context = this@MainActivity,
                    itemId = item.id,
                    retentionDays = retentionDays
                )
            }
            binding.subStatusLabel.text = if (restored != null) {
                getString(R.string.media_vault_restore_status)
            } else {
                getString(R.string.media_vault_action_unavailable)
            }
            refreshUiState()
        }
    }

    private fun applyMediaVaultAccessFailure(check: MediaVaultAccessCheck) {
        binding.subStatusLabel.text = when (check.reasonCode) {
            "disabled" -> getString(R.string.media_vault_policy_disabled)
            "locked" -> getString(R.string.media_vault_locked)
            "free_limit" -> getString(
                R.string.media_vault_import_limit_template,
                check.maxItemsFree
            )
            else -> getString(R.string.media_vault_import_failed)
        }
    }

    private fun runScamTriage() {
        openPhishingTriageDialog()
    }

    private fun openPhishingTriageDialog() {
        val config = PhishingTriageEngine.config(this)
        if (!config.enabled) {
            binding.subStatusLabel.text = getString(R.string.phishing_triage_policy_disabled)
            return
        }

        val input = EditText(this).apply {
            hint = getString(R.string.phishing_triage_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }

        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle(R.string.phishing_triage_input_title)
            .setMessage(R.string.phishing_triage_input_message)
            .setView(input)
            .setPositiveButton(R.string.action_run_scam_triage, null)
            .setNegativeButton(android.R.string.cancel, null)

        if (config.allowManualClipboardIntake) {
            dialogBuilder.setNeutralButton(R.string.phishing_triage_input_clipboard, null)
        }

        val dialog = dialogBuilder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text?.toString().orEmpty().trim()
                if (text.isBlank()) {
                    input.error = getString(R.string.phishing_triage_input_required)
                    return@setOnClickListener
                }
                dialog.dismiss()
                executePhishingTriage(text, sourceRef = "manual_input")
            }

            if (config.allowManualClipboardIntake) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val text = clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.coerceToText(this)
                        ?.toString()
                        .orEmpty()
                        .trim()
                    if (text.isBlank()) {
                        binding.subStatusLabel.text = getString(R.string.phishing_triage_clipboard_unavailable)
                    } else {
                        input.setText(text)
                        input.setSelection(text.length)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun executePhishingTriage(input: String, sourceRef: String) {
        setBusy(true, getString(R.string.scam_shield_triage_running))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                PhishingTriageEngine.triage(
                    context = this@MainActivity,
                    input = input,
                    sourceRef = sourceRef
                )
            }
            binding.statusLabel.text = getString(
                R.string.phishing_triage_result_template,
                result.riskScore,
                result.severity.name.lowercase(Locale.US),
                result.sourceRef,
                result.triagedAtIso
            )
            binding.subStatusLabel.text = getString(R.string.phishing_triage_completed_status)
            binding.resultText.text = formatPhishingTriageResult(result)
            setBusy(false)
            refreshUiState()
            showPhishingTriageResultDialog(result)
        }
    }

    private fun formatPhishingTriageResult(result: PhishingTriageResult): String {
        return buildString {
            appendLine(
                getString(
                    R.string.phishing_triage_result_template,
                    result.riskScore,
                    result.severity.name.lowercase(Locale.US),
                    result.sourceRef,
                    result.triagedAtIso
                )
            )
            if (result.extractedUrls.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.phishing_triage_result_urls_title))
                result.extractedUrls.forEachIndexed { index, line ->
                    appendLine("${index + 1}. $line")
                }
            }
            appendLine()
            appendLine(getString(R.string.phishing_triage_result_reasons_title))
            result.reasons.forEachIndexed { index, line ->
                appendLine("${index + 1}. $line")
            }
            appendLine()
            appendLine(getString(R.string.phishing_triage_result_actions_title))
            result.suggestedActions.forEachIndexed { index, line ->
                appendLine("${index + 1}. $line")
            }
        }.trim()
    }

    private fun showPhishingTriageResultDialog(result: PhishingTriageResult) {
        val message = formatPhishingTriageResult(result)
        AlertDialog.Builder(this)
            .setTitle(R.string.phishing_triage_result_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_open_phishing_remediation) { _, _ ->
                val openSupport = { openSupportCenter() }
                if (result.severity == Severity.HIGH) {
                    GuardianOverridePolicy.requestApproval(
                        activity = this,
                        action = GuardianProtectedAction.HIGH_RISK_PHISHING_REMEDIATION,
                        actionLabel = getString(R.string.action_open_phishing_remediation),
                        reasonCode = "phishing_remediation",
                        onApproved = openSupport
                    )
                } else {
                    openSupport()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshScamPanel() {
        val latest = PhishingIntakeStore.latest(this)
        if (latest == null) {
            binding.scamSummaryLabel.text = getString(R.string.scam_shield_no_snapshot)
            binding.scamActionsLabel.text = getString(R.string.scam_shield_actions_placeholder)
            return
        }

        val (highCount, mediumCount, lowCount) = PhishingIntakeStore.summarizeRecent(this, lookback = 20)
        binding.scamSummaryLabel.text = getString(
            R.string.scam_shield_summary_template,
            latest.triagedAtIso.ifBlank { getString(R.string.scam_shield_unknown_time) },
            highCount,
            mediumCount,
            lowCount
        )

        val actions = latest.result.suggestedActions
        binding.scamActionsLabel.text = if (actions.isEmpty()) {
            getString(R.string.scam_shield_actions_none)
        } else {
            val formatted = actions.take(3).mapIndexed { index, action ->
                "${index + 1}. $action"
            }.joinToString("\n")
            "${getString(R.string.scam_shield_actions_title)}:\n$formatted"
        }
    }

    private fun runHygieneAudit(forceRefresh: Boolean = false) {
        refreshHygienePanel(forceRefresh = forceRefresh)
    }

    private fun refreshHygienePanel(forceRefresh: Boolean = false) {
        if (!forceRefresh) {
            latestHygieneAudit?.let { renderHygieneAudit(it) }
        } else {
            binding.hygieneSummaryLabel.text = getString(R.string.hygiene_loading)
            binding.hygieneActionsLabel.text = getString(R.string.hygiene_actions_placeholder)
        }
        if (hygieneAuditInFlight) {
            return
        }

        val shouldFetch = forceRefresh || latestHygieneAudit == null
        if (!shouldFetch) {
            return
        }

        hygieneAuditInFlight = true
        lifecycleScope.launch {
            val audit = withContext(Dispatchers.IO) {
                SafeHygieneToolkit.runAudit(this@MainActivity)
            }
            latestHygieneAudit = audit
            renderHygieneAudit(audit)
            hygieneAuditInFlight = false
            if (forceRefresh) {
                binding.subStatusLabel.text = getString(R.string.hygiene_refreshed_status)
            }
        }
    }

    private fun renderHygieneAudit(audit: HygieneAuditResult) {
        binding.hygieneSummaryLabel.text = audit.summaryLine()
        binding.hygieneActionsLabel.text = if (audit.topActions.isEmpty()) {
            getString(R.string.hygiene_actions_placeholder)
        } else {
            val rows = audit.topActions.mapIndexed { index, action ->
                "${index + 1}. $action"
            }.joinToString("\n")
            "${getString(R.string.hygiene_actions_title)}:\n$rows"
        }
    }

    private fun openHygieneCleanupDialog() {
        val audit = latestHygieneAudit
        if (audit == null) {
            runHygieneAudit(forceRefresh = true)
            return
        }
        val playbook = audit.playbook.take(6).joinToString("\n")
        val message = buildString {
            appendLine(getString(R.string.hygiene_cleanup_confirm_message_template))
            appendLine()
            appendLine(getString(R.string.hygiene_playbook_title))
            append(playbook)
        }.trim()

        AlertDialog.Builder(this)
            .setTitle(R.string.hygiene_cleanup_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                applySafeHygieneCleanup()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applySafeHygieneCleanup() {
        setBusy(true, "Applying safe hygiene cleanup...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SafeHygieneToolkit.runSafeCleanup(this@MainActivity)
            }
            setBusy(false)
            val changed = result.reclaimedCacheBytes > 0L ||
                result.reclaimedArtifactBytes > 0L ||
                result.removedArtifactCount > 0 ||
                result.removedCompletedQueueActions > 0
            binding.subStatusLabel.text = if (changed) {
                getString(
                    R.string.hygiene_cleanup_result_template,
                    SafeHygieneToolkit.formatBytes(result.reclaimedCacheBytes),
                    SafeHygieneToolkit.formatBytes(result.reclaimedArtifactBytes),
                    result.removedArtifactCount,
                    result.removedCompletedQueueActions
                )
            } else {
                getString(R.string.hygiene_cleanup_no_changes)
            }
            refreshHygienePanel(forceRefresh = true)
            refreshUiState()
        }
    }

    private fun refreshCopilotPanel(forceConnectedRefresh: Boolean = false) {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val profileControl = PricingPolicy.resolveProfileControl(this, access)
        val policy = ConnectedAiPolicyStore.load(this)
        val localBrief = AiCopilot.buildBrief(this)
        renderCopilotBrief(
            brief = localBrief,
            connected = false,
            sourceLabel = getString(R.string.copilot_source_local)
        )

        when {
            !access.features.aiHotlineEnabled -> {
                binding.copilotModeLabel.text = getString(R.string.copilot_mode_feature_locked)
                return
            }
            !policy.enabled || !policy.allowUserSubscriptionLink -> {
                binding.copilotModeLabel.text = getString(R.string.copilot_mode_policy_blocked)
                return
            }
        }

        val state = ConnectedAiLinkStore.read(this)
        if (!state.enabled) {
            binding.copilotModeLabel.text = getString(R.string.copilot_mode_local_default)
            return
        }
        if (!policy.isProviderAllowed(state.provider) || !policy.isModelAllowed(state.model)) {
            binding.copilotModeLabel.text = getString(R.string.copilot_mode_policy_blocked)
            return
        }
        if (!state.sessionKeyLoaded) {
            binding.copilotModeLabel.text = getString(R.string.copilot_mode_session_required)
            return
        }
        if (copilotRequestInFlight) {
            return
        }

        copilotRequestInFlight = true
        binding.copilotModeLabel.text = getString(
            R.string.copilot_mode_connecting_template,
            state.provider,
            state.model
        )

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ConnectedAiCopilotClient.requestBrief(
                    context = this@MainActivity,
                    localBrief = localBrief,
                    profileControl = profileControl,
                    policy = policy,
                    linkState = state
                )
            }
            copilotRequestInFlight = false

            if (result.connected) {
                renderCopilotBrief(
                    brief = result.brief,
                    connected = true,
                    sourceLabel = getString(R.string.copilot_source_connected)
                )
                binding.copilotModeLabel.text = getString(
                    R.string.copilot_mode_connected_template,
                    result.provider,
                    result.model
                )
                if (forceConnectedRefresh) {
                    binding.subStatusLabel.text = getString(R.string.copilot_connected_refreshed_status)
                }
                return@launch
            }

            renderCopilotBrief(
                brief = result.brief,
                connected = false,
                sourceLabel = getString(R.string.copilot_source_local)
            )
            binding.copilotModeLabel.text = when {
                result.warning.contains("session", ignoreCase = true) ->
                    getString(R.string.copilot_mode_session_required)
                result.warning.contains("policy", ignoreCase = true) ->
                    getString(R.string.copilot_mode_policy_blocked)
                else -> getString(R.string.copilot_mode_fallback)
            }
            if (result.warning.isNotBlank()) {
                binding.subStatusLabel.text = result.warning
            }
        }
    }

    private fun renderCopilotBrief(brief: CopilotBrief, connected: Boolean, sourceLabel: String) {
        latestCopilotBrief = brief
        latestCopilotFromConnectedAi = connected
        latestCopilotSourceLabel = sourceLabel
        binding.copilotSummaryLabel.text = brief.summary
        val actions = brief.actions.take(3)
        binding.copilotActionsLabel.text = if (actions.isEmpty()) {
            getString(R.string.copilot_actions_placeholder)
        } else {
            actions.mapIndexed { index, action ->
                "${index + 1}. ${action.title}\n${action.rationale}"
            }.joinToString("\n\n")
        }
    }

    private fun openCopilotPlaybookDialog() {
        val brief = latestCopilotBrief ?: AiCopilot.buildBrief(this).also { latestCopilotBrief = it }
        val sourceLine = getString(
            R.string.copilot_source_line_template,
            latestCopilotSourceLabel.ifBlank { getString(R.string.copilot_source_local) }
        )
        val body = buildString {
            appendLine(brief.summary)
            appendLine()
            appendLine(sourceLine)
            appendLine()
            appendLine(brief.rationale)
            appendLine()
            appendLine(getString(R.string.copilot_playbook_title))
            brief.actions.forEachIndexed { index, action ->
                appendLine("${index + 1}. ${action.title}")
                appendLine(action.rationale)
                appendLine()
            }
        }.trim()

        AlertDialog.Builder(this)
            .setTitle(R.string.copilot_dialog_title)
            .setMessage(body)
            .setPositiveButton(R.string.copilot_run_top_action) { _, _ ->
                brief.actions.firstOrNull()?.let {
                    executeCopilotRoute(
                        route = it.route,
                        connectedRecommendation = latestCopilotFromConnectedAi
                    )
                }
            }
            .setNeutralButton(R.string.action_copilot_refresh) { _, _ ->
                refreshCopilotPanel(forceConnectedRefresh = true)
                binding.subStatusLabel.text = getString(R.string.copilot_refreshed_status)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun executeCopilotRoute(route: CopilotRoute, connectedRecommendation: Boolean) {
        if (connectedRecommendation) {
            val policy = ConnectedAiPolicyStore.load(this)
            if (policy.requireExplicitActionConfirmation) {
                showConnectedActionConfirmation(route) {
                    runConnectedActionWithGuardianGuard(route)
                }
                return
            }
            runConnectedActionWithGuardianGuard(route)
            return
        }
        executeCopilotRouteInternal(route)
    }

    private fun showConnectedActionConfirmation(route: CopilotRoute, onConfirmed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.copilot_guarded_confirm_title)
            .setMessage(
                getString(
                    R.string.copilot_guarded_confirm_message_template,
                    copilotRouteLabel(route)
                )
            )
            .setPositiveButton(R.string.action_confirm) { _, _ -> onConfirmed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runConnectedActionWithGuardianGuard(route: CopilotRoute) {
        GuardianOverridePolicy.requestApproval(
            activity = this,
            action = GuardianProtectedAction.CONNECTED_AI_ACTION,
            actionLabel = copilotRouteLabel(route),
            reasonCode = "connected_ai_recommendation",
            onApproved = { executeCopilotRouteInternal(route) }
        )
    }

    private fun executeCopilotRouteInternal(route: CopilotRoute) {
        when (route) {
            CopilotRoute.RUN_ONE_TIME_SCAN -> runOneTimeScan()
            CopilotRoute.RUN_SCAM_TRIAGE -> runScamTriage()
            CopilotRoute.OPEN_CREDENTIAL_CENTER -> openCredentialCenter()
            CopilotRoute.OPEN_SUPPORT -> openSupportCenter()
        }
    }

    private fun copilotRouteLabel(route: CopilotRoute): String {
        return when (route) {
            CopilotRoute.RUN_ONE_TIME_SCAN -> getString(R.string.action_one_time_scan)
            CopilotRoute.RUN_SCAM_TRIAGE -> getString(R.string.action_run_scam_triage)
            CopilotRoute.OPEN_CREDENTIAL_CENTER -> getString(R.string.action_open_credential_center)
            CopilotRoute.OPEN_SUPPORT -> getString(R.string.action_support)
        }
    }

    private fun openConnectedAiDialog() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        if (!access.features.aiHotlineEnabled) {
            showFeatureLockedDialog(
                getString(R.string.feature_locked_ai_title),
                getString(R.string.feature_locked_ai_message)
            )
            return
        }

        val policy = ConnectedAiPolicyStore.load(this)
        if (!policy.enabled || !policy.allowUserSubscriptionLink) {
            AlertDialog.Builder(this)
                .setTitle(R.string.copilot_connect_dialog_title)
                .setMessage(R.string.copilot_mode_policy_blocked)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val state = ConnectedAiLinkStore.read(this)
        if (!state.enabled) {
            startConnectedAiLinkFlow(policy)
            return
        }

        val options = arrayOf(
            getString(R.string.copilot_manage_enter_session_key),
            getString(R.string.copilot_manage_refresh_now),
            getString(R.string.copilot_manage_disconnect)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.copilot_connect_dialog_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptConnectedAiApiKey(
                        title = getString(R.string.copilot_connect_api_key_title)
                    ) { apiKey ->
                        runConnectedAiGuardianCheck(
                            actionLabel = getString(R.string.copilot_manage_enter_session_key)
                        ) {
                            ConnectedAiLinkStore.updateSessionKey(this, apiKey)
                            binding.subStatusLabel.text = getString(
                                R.string.copilot_connect_saved_template,
                                state.provider,
                                state.model
                            )
                            refreshCopilotPanel(forceConnectedRefresh = true)
                        }
                    }

                    1 -> refreshCopilotPanel(forceConnectedRefresh = true)

                    else -> confirmConnectedAiDisconnect()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startConnectedAiLinkFlow(policy: ConnectedAiPolicy) {
        AlertDialog.Builder(this)
            .setTitle(R.string.copilot_connect_dialog_title)
            .setMessage(R.string.copilot_connect_dialog_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                chooseConnectedAiProvider(policy) { provider ->
                    chooseConnectedAiModel(policy) { model ->
                        promptConnectedAiApiKey(
                            title = getString(R.string.copilot_connect_api_key_title)
                        ) { apiKey ->
                            val linkAction = {
                                ConnectedAiLinkStore.link(
                                    context = this,
                                    provider = provider,
                                    model = model,
                                    apiKey = apiKey
                                )
                                binding.subStatusLabel.text = getString(
                                    R.string.copilot_connect_saved_template,
                                    provider,
                                    model
                                )
                                refreshCopilotPanel(forceConnectedRefresh = true)
                            }
                            runConnectedAiGuardianCheck(
                                actionLabel = getString(R.string.action_copilot_manage_connection),
                                onConfirmed = linkAction
                            )
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseConnectedAiProvider(policy: ConnectedAiPolicy, onSelected: (String) -> Unit) {
        val providers = policy.providerAllowlist.ifEmpty { listOf("openai") }
        if (providers.size == 1) {
            onSelected(providers.first())
            return
        }
        var selectedIndex = 0
        AlertDialog.Builder(this)
            .setTitle(R.string.copilot_connect_provider_title)
            .setSingleChoiceItems(providers.toTypedArray(), selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSelected(providers[selectedIndex])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseConnectedAiModel(policy: ConnectedAiPolicy, onSelected: (String) -> Unit) {
        val models = policy.modelAllowlist.ifEmpty { listOf("gpt-4.1-mini") }
        var selectedIndex = 0
        AlertDialog.Builder(this)
            .setTitle(R.string.copilot_connect_model_title)
            .setSingleChoiceItems(models.toTypedArray(), selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSelected(models[selectedIndex])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptConnectedAiApiKey(title: String, onSubmitted: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.copilot_connect_api_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val key = input.text?.toString().orEmpty().trim()
                if (key.isBlank()) {
                    binding.subStatusLabel.text = getString(R.string.copilot_connect_api_key_required)
                } else {
                    onSubmitted(key)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmConnectedAiDisconnect() {
        AlertDialog.Builder(this)
            .setTitle(R.string.copilot_disconnect_confirm_title)
            .setMessage(R.string.copilot_disconnect_confirm_message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                runConnectedAiGuardianCheck(
                    actionLabel = getString(R.string.copilot_manage_disconnect),
                    onConfirmed = {
                        ConnectedAiLinkStore.unlink(this)
                        binding.subStatusLabel.text = getString(R.string.copilot_disconnected_status)
                        refreshCopilotPanel(forceConnectedRefresh = false)
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runConnectedAiGuardianCheck(actionLabel: String, onConfirmed: () -> Unit) {
        GuardianOverridePolicy.requestApproval(
            activity = this,
            action = GuardianProtectedAction.CONNECTED_AI_ACTION,
            actionLabel = actionLabel,
            reasonCode = "connected_ai_management",
            onApproved = onConfirmed
        )
    }

    private fun openGuardianFeedDialog() {
        val profileControl = PricingPolicy.resolveProfileControl(this)
        if (!profileControl.canViewGuardianFeed) {
            AlertDialog.Builder(this)
                .setTitle(R.string.guardian_feed_restricted_title)
                .setMessage(R.string.guardian_feed_restricted_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val entries = GuardianAlertStore.readRecent(this, limit = 15)
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.guardian_feed_title)
                .setMessage(R.string.guardian_feed_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val body = entries.joinToString("\n\n") { entry ->
            val source = "${entry.sourceType.uppercase(Locale.US)} ${compactSingleLine(entry.sourceRef, 88)}".trim()
            getString(
                R.string.guardian_feed_entry_template,
                entry.recordedAtIso.ifBlank { getString(R.string.scam_shield_unknown_time) },
                entry.severity.name.lowercase(Locale.US),
                entry.score,
                compactSingleLine(entry.title, 74),
                source,
                compactSingleLine(entry.remediation, 100)
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.guardian_feed_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun compactSingleLine(value: String, maxLen: Int): String {
        val normalized = value.replace("\n", " ").replace("\r", " ").trim()
        if (normalized.length <= maxLen) {
            return normalized
        }
        return normalized.take(maxLen - 3) + "..."
    }

    private fun refreshTranslationPanel() {
        val profile = LocalizationSupport.resolveProfile(this)
        val packLabel = when (profile.packLevel) {
            TranslationPackLevel.FULL -> getString(R.string.translation_pack_full)
            TranslationPackLevel.CORE -> getString(R.string.translation_pack_core)
            TranslationPackLevel.FALLBACK -> getString(R.string.translation_pack_fallback)
        }

        binding.translationSummaryLabel.text = getString(
            R.string.translation_summary_template,
            profile.localeDisplayName,
            profile.localeTag
        )
        binding.translationMetricLabel.text = getString(
            R.string.translation_metric_template,
            profile.coveragePercent,
            packLabel
        )
        binding.translationGuidanceLabel.text = when (profile.packLevel) {
            TranslationPackLevel.FULL -> getString(R.string.translation_guidance_full)
            TranslationPackLevel.CORE -> getString(R.string.translation_guidance_core)
            TranslationPackLevel.FALLBACK -> getString(R.string.translation_guidance_fallback)
        }
    }

    private fun openLanguageSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        } else {
            Intent(Settings.ACTION_LOCALE_SETTINGS)
        }
        runCatching { startActivity(intent) }
            .onFailure { binding.subStatusLabel.text = getString(R.string.translation_settings_open_failed) }
    }

    private fun reportTranslationIssue() {
        val profile = LocalizationSupport.resolveProfile(this)
        val baseUrl = getString(R.string.support_center_url)
        val joined = if (baseUrl.contains("?")) "&" else "?"
        val supportUrl = "$baseUrl${joined}topic=translation&locale=${Uri.encode(profile.localeTag)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(supportUrl))
        runCatching {
            startActivity(intent)
            binding.subStatusLabel.text = getString(R.string.translation_issue_reported_status)
        }.onFailure {
            binding.subStatusLabel.text = getString(R.string.overlay_open_site_failed)
        }
    }

    private fun toggleAdvancedControls() {
        advancedControlsVisible = !advancedControlsVisible
        applyAdvancedControlsVisibility()
    }

    private fun applyAdvancedControlsVisibility() {
        val visibility = if (advancedControlsVisible) View.VISIBLE else View.GONE
        binding.readinessCard.visibility = visibility
        binding.incidentCard.visibility = visibility
        binding.checkUpdatesButton.visibility = visibility
        binding.refreshSupportRow.visibility = visibility
        binding.resultCard.visibility = visibility
        binding.advancedControlsToggleButton.text = if (advancedControlsVisible) {
            getString(R.string.action_hide_advanced_controls)
        } else {
            getString(R.string.action_show_advanced_controls)
        }
    }

    private fun refreshSecurityHero() {
        val state = buildSecurityHeroState()
        latestSecurityHeroState = state
        latestRiskCards = listOf(toRiskCardModel(state))
        binding.securityScoreLabel.text = getString(R.string.security_score_template, state.score)
        binding.securityTierLabel.text = state.tierLabel

        binding.securityUrgentActionsLabel.text = if (state.actions.isEmpty()) {
            getString(R.string.security_urgent_actions_none)
        } else {
            val lines = state.actions.take(3).mapIndexed { index, action ->
                "${index + 1}. ${action.label}"
            }.joinToString("\n")
            "${getString(R.string.security_urgent_actions_title)}:\n$lines"
        }

        val topAction = state.actions.firstOrNull()
            ?: SecurityHeroAction(
                label = getString(R.string.security_action_open_support),
                route = SecurityActionRoute.OPEN_SUPPORT
            )
        binding.securityTopActionButton.text = topAction.label
    }

    private fun toRiskCardModel(state: SecurityHeroState): RiskCardModel {
        val tier = when {
            state.score >= 85 -> RiskCardTier.STABLE
            state.score >= 65 -> RiskCardTier.GUARDED
            state.score >= 40 -> RiskCardTier.ELEVATED
            else -> RiskCardTier.HIGH
        }
        val actions = state.actions.map { action ->
            RiskCardActionModel(
                id = action.route.name.lowercase(Locale.US),
                label = action.label,
                route = action.route.name
            )
        }
        return RiskCardModel(
            cardId = "security_hero",
            title = getString(R.string.security_hero_title),
            score = state.score,
            tier = tier,
            summaryLines = state.details,
            actions = actions,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun buildSecurityHeroState(): SecurityHeroState {
        var score = 100
        val details = mutableListOf<String>()
        val actions = mutableListOf<SecurityHeroAction>()
        fun addAction(route: SecurityActionRoute, labelRes: Int) {
            if (actions.none { it.route == route }) {
                actions += SecurityHeroAction(getString(labelRes), route)
            }
        }

        val notificationRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notificationReady = !notificationRequired || isNotificationPermissionReady()
        if (notificationRequired && !notificationReady) {
            score -= 15
            details += getString(R.string.security_detail_notification_missing)
            addAction(SecurityActionRoute.FIX_NOTIFICATIONS, R.string.security_action_fix_notifications)
        }

        val overlayReady = Settings.canDrawOverlays(this)
        if (!overlayReady) {
            score -= 15
            details += getString(R.string.security_detail_overlay_missing)
            addAction(SecurityActionRoute.FIX_OVERLAY, R.string.security_action_fix_overlay)
        }

        val wifi = WifiScanSnapshotStore.latest(this)
        if (wifi == null) {
            score -= 10
            details += getString(R.string.security_detail_wifi_scan_missing)
            addAction(SecurityActionRoute.RUN_WIFI_POSTURE_SCAN, R.string.security_action_run_wifi_scan)
        } else {
            when (wifi.tier.lowercase(Locale.US)) {
                "high_risk" -> {
                    score -= 18
                    details += getString(R.string.security_detail_wifi_risk_high)
                    addAction(SecurityActionRoute.RUN_WIFI_POSTURE_SCAN, R.string.security_action_run_wifi_scan)
                }

                "elevated" -> {
                    score -= 10
                    details += getString(R.string.security_detail_wifi_risk_elevated)
                    addAction(SecurityActionRoute.RUN_WIFI_POSTURE_SCAN, R.string.security_action_run_wifi_scan)
                }
            }
        }

        val lastScan = SecurityScanner.readLastScanTimestamp(this)
        if (lastScan == "never") {
            score -= 20
            details += getString(R.string.security_detail_scan_missing)
            addAction(SecurityActionRoute.RUN_SCAN, R.string.security_action_run_scan)
        }

        val posture = SecurityScanner.currentRootPosture(this)
        when (posture.riskTier) {
            RootRiskTier.TRUSTED -> Unit
            RootRiskTier.ELEVATED -> {
                score -= 12
                details += getString(R.string.security_detail_root_elevated)
                addAction(SecurityActionRoute.OPEN_SUPPORT, R.string.security_action_open_support)
            }
            RootRiskTier.COMPROMISED -> {
                score -= 24
                details += getString(R.string.security_detail_root_compromised)
                addAction(SecurityActionRoute.OPEN_SUPPORT, R.string.security_action_open_support)
            }
        }

        val (phishingHigh, phishingMedium, _) = PhishingIntakeStore.summarizeRecent(this, lookback = 20)
        if (phishingHigh > 0) {
            score -= (phishingHigh * 4).coerceAtMost(20)
            details += getString(R.string.security_detail_scam_high_template, phishingHigh)
            addAction(SecurityActionRoute.RUN_PHISHING_TRIAGE, R.string.security_action_run_scam_triage)
        } else if (phishingMedium > 0) {
            score -= (phishingMedium * 2).coerceAtMost(10)
            details += getString(R.string.security_detail_scam_medium_template, phishingMedium)
            addAction(SecurityActionRoute.RUN_PHISHING_TRIAGE, R.string.security_action_run_scam_triage)
        }

        val incidentSummary = IncidentStore.summarize(this)
        val unresolvedIncidents = incidentSummary.openCount + incidentSummary.inProgressCount
        if (unresolvedIncidents > 0) {
            score -= (unresolvedIncidents * 3).coerceAtMost(18)
            details += getString(R.string.security_detail_incident_backlog_template, unresolvedIncidents)
            addAction(SecurityActionRoute.START_INCIDENT, R.string.security_action_start_incident)
        }

        if (!SecurityScanner.isContinuousModeEnabled(this)) {
            score -= 6
            details += getString(R.string.security_detail_continuous_off)
        }

        val access = PricingPolicy.resolveFeatureAccess(this)
        if (!access.paidAccess) {
            details += getString(R.string.security_detail_upgrade_prompt)
            addAction(SecurityActionRoute.OPEN_SUPPORT, R.string.security_action_open_support)
        }

        if (actions.isEmpty()) {
            addAction(
                SecurityActionRoute.OPEN_CREDENTIAL_CENTER,
                R.string.security_action_open_credentials
            )
        }
        if (details.isEmpty()) {
            details += getString(R.string.security_detail_stable)
        }

        score = score.coerceIn(0, 100)
        val tier = when {
            score >= 85 -> getString(R.string.security_tier_stable)
            score >= 65 -> getString(R.string.security_tier_guarded)
            else -> getString(R.string.security_tier_at_risk)
        }

        return SecurityHeroState(
            score = score,
            tierLabel = tier,
            actions = actions.take(3),
            details = details
        )
    }

    private fun runTopSecurityAction() {
        val route = latestSecurityHeroState?.actions?.firstOrNull()?.route
            ?: SecurityActionRoute.OPEN_SUPPORT
        executeSecurityAction(route)
    }

    private fun executeSecurityAction(route: SecurityActionRoute) {
        when (route) {
            SecurityActionRoute.RUN_SCAN -> runOneTimeScan()
            SecurityActionRoute.RUN_WIFI_POSTURE_SCAN -> ensureWifiScanPermissionsAndRun()
            SecurityActionRoute.FIX_NOTIFICATIONS -> openNotificationPermissionFix()
            SecurityActionRoute.FIX_OVERLAY -> openOverlayPermissionFix()
            SecurityActionRoute.RUN_PHISHING_TRIAGE -> runScamTriage()
            SecurityActionRoute.START_INCIDENT -> startNextIncident()
            SecurityActionRoute.OPEN_SUPPORT -> openSupportCenter()
            SecurityActionRoute.OPEN_CREDENTIAL_CENTER -> openCredentialCenter()
        }
    }

    private fun openSecurityDetailsDialog() {
        val state = latestSecurityHeroState ?: buildSecurityHeroState().also {
            latestSecurityHeroState = it
        }
        val riskCard = latestRiskCards.firstOrNull() ?: toRiskCardModel(state).also {
            latestRiskCards = listOf(it)
        }
        val actionsBlock = if (state.actions.isEmpty()) {
            getString(R.string.security_urgent_actions_none)
        } else {
            state.actions.mapIndexed { index, action ->
                "${index + 1}. ${action.label}"
            }.joinToString("\n")
        }
        val detailsBlock = state.details.mapIndexed { index, line ->
            "${index + 1}. $line"
        }.joinToString("\n")

        val message = buildString {
            appendLine(getString(R.string.security_score_template, riskCard.score))
            appendLine(state.tierLabel)
            appendLine()
            appendLine(getString(R.string.security_urgent_actions_title))
            appendLine(actionsBlock)
            appendLine()
            appendLine(detailsBlock)
        }.trim()

        AlertDialog.Builder(this)
            .setTitle(R.string.security_action_details_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refreshReadinessPanel() {
        val notificationRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notificationReady = !notificationRequired || isNotificationPermissionReady()
        val overlayReady = Settings.canDrawOverlays(this)
        val activeModeOn = SecurityScanner.isContinuousModeEnabled(this)
        val lastScan = SecurityScanner.readLastScanTimestamp(this)
        val posture = SecurityScanner.currentRootPosture(this)

        val notificationState = when {
            !notificationRequired -> getString(R.string.readiness_state_not_required)
            notificationReady -> getString(R.string.readiness_state_ok)
            else -> getString(R.string.readiness_state_action_needed)
        }

        val overlayState = if (overlayReady) {
            getString(R.string.readiness_state_ok)
        } else {
            getString(R.string.readiness_state_action_needed)
        }

        val activeModeState = if (activeModeOn) {
            getString(R.string.readiness_state_on)
        } else {
            getString(R.string.readiness_state_off)
        }
        val rootState = rootTierLabel(posture.riskTier)

        binding.readinessSummaryLabel.text = getString(
            R.string.readiness_summary_template,
            notificationState,
            overlayState,
            activeModeState,
            rootState,
            lastScan
        )
        binding.fixNotificationButton.isEnabled = notificationRequired && !notificationReady
        binding.fixOverlayButton.isEnabled = !overlayReady
    }

    private fun rootTierLabel(tier: RootRiskTier): String {
        return when (tier) {
            RootRiskTier.TRUSTED -> getString(R.string.root_tier_trusted)
            RootRiskTier.ELEVATED -> getString(R.string.root_tier_elevated)
            RootRiskTier.COMPROMISED -> getString(R.string.root_tier_compromised)
        }
    }

    private fun formatRootReasons(reasonCodes: Set<String>): String {
        if (reasonCodes.isEmpty()) {
            return getString(R.string.root_hardening_reason_none)
        }
        return reasonCodes.sorted().joinToString(", ")
    }

    private fun showRootHardeningBlockedDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.root_hardening_block_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showRootSensitiveConfirmation(
        posture: RootPosture,
        actionLabel: String,
        onConfirmed: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.root_hardening_sensitive_confirm_title)
            .setMessage(
                getString(
                    R.string.root_hardening_sensitive_confirm_message_template,
                    rootTierLabel(posture.riskTier),
                    formatRootReasons(posture.reasonCodes),
                    actionLabel
                )
            )
            .setPositiveButton(R.string.action_confirm) { _, _ -> onConfirmed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshIncidentPanel() {
        val summary = IncidentStore.summarize(this)
        binding.incidentSummaryLabel.text = getString(
            R.string.incident_summary_template,
            summary.openCount,
            summary.inProgressCount,
            summary.resolvedCount
        )
        binding.incidentDetailLabel.text = summary.preview
        binding.incidentStartButton.isEnabled = summary.openCount > 0
        binding.incidentResolveButton.isEnabled = summary.inProgressCount > 0 || summary.openCount > 0
        binding.incidentReopenButton.isEnabled = summary.resolvedCount > 0
    }

    private fun startNextIncident() {
        val updated = IncidentStore.markNextOpenInProgress(this)
        if (updated == null) {
            binding.subStatusLabel.text = getString(R.string.incident_no_open)
        } else {
            binding.subStatusLabel.text = getString(
                R.string.incident_started_template,
                updated.title.take(60)
            )
        }
        refreshIncidentPanel()
    }

    private fun resolveNextIncident() {
        val updated = IncidentStore.resolveNextActive(this)
        if (updated == null) {
            binding.subStatusLabel.text = getString(R.string.incident_no_active)
        } else {
            binding.subStatusLabel.text = getString(
                R.string.incident_resolved_template,
                updated.title.take(60)
            )
        }
        refreshIncidentPanel()
    }

    private fun reopenResolvedIncident() {
        val updated = IncidentStore.reopenLatestResolved(this)
        if (updated == null) {
            binding.subStatusLabel.text = getString(R.string.incident_no_resolved)
        } else {
            binding.subStatusLabel.text = getString(
                R.string.incident_reopened_template,
                updated.title.take(60)
            )
        }
        refreshIncidentPanel()
    }

    private fun isNotificationPermissionReady(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openNotificationPermissionFix() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        runCatching { startActivity(intent) }
            .onFailure { binding.updateStatusLabel.text = getString(R.string.overlay_open_site_failed) }
    }

    private fun openOverlayPermissionFix() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure { binding.updateStatusLabel.text = getString(R.string.overlay_open_site_failed) }
    }
}
