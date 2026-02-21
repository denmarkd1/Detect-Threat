package com.realyn.watchdog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.realyn.watchdog.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // No-op. App still functions if user denies notifications.
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
        binding.pricingManageButton.setOnClickListener { openPlanSelectionDialog() }
        binding.pricingFeedbackButton.setOnClickListener { openPricingFeedbackDialog() }

        maybeRequestNotificationPermission()
        refreshUiState()
        flushPendingFeedbackSync()
    }

    override fun onResume() {
        super.onResume()
        refreshUiState()
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
            val startIntent = Intent(this, ContinuousWatchdogService::class.java).apply {
                action = WatchdogConfig.ACTION_START_CONTINUOUS
            }
            ContextCompat.startForegroundService(this, startIntent)
            binding.subStatusLabel.text = getString(R.string.continuous_starting)
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

        refreshReadinessPanel()
        refreshIncidentPanel()
        refreshPricingPanel()
    }

    private fun setBusy(busy: Boolean, status: String? = null) {
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
        binding.pricingManageButton.isEnabled = !busy
        binding.pricingFeedbackButton.isEnabled = !busy

        if (status != null) {
            binding.statusLabel.text = status
        }
    }

    private fun openSupportCenter() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val supportTier = if (access.paidAccess) "paid" else "free"
        val aiEnabled = if (access.features.aiHotlineEnabled) "1" else "0"
        val baseUrl = getString(R.string.support_center_url)
        val joined = if (baseUrl.contains("?")) "&" else "?"
        val supportUrl = "$baseUrl${joined}tier=$supportTier&ai_hotline=$aiEnabled"
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
        val baseRegional = PricingPolicy.resolveForCurrentRegion(this, model)
        val trial = PricingPolicy.ensureTrial(this)
        val entitlement = PricingPolicy.entitlement(this)
        val feedback = PricingPolicy.feedbackStatus(this)
        val discountPercent = if (entitlement.isLifetimePro) {
            0.0
        } else {
            PricingPolicy.resolveReferralDiscountPercent(model, feedback)
        }
        val regional = PricingPolicy.applyReferralDiscount(baseRegional, discountPercent)
        val selectedPlan = PricingPolicy.selectedPlan(this)

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
        binding.pricingSummaryLabel.text = summary

        val competitorBitwarden = model.competitorReferences.find { it.name.equals("bitwarden", ignoreCase = true) }?.monthlyUsd
            ?: 1.65
        val competitorOnePassword = model.competitorReferences.find { it.name.equals("1password", ignoreCase = true) }?.monthlyUsd
            ?: 2.99
        val competitorDashlane = model.competitorReferences.find { it.name.equals("dashlane", ignoreCase = true) }?.monthlyUsd
            ?: 4.99

        binding.pricingMarketLabel.text = getString(
            R.string.pricing_market_template,
            PricingPolicy.formatMoney(model.baseCurrencyCode, model.competitorAverageMonthlyUsd),
            PricingPolicy.formatMoney(regional.currencyCode, regional.monthly),
            PricingPolicy.formatMoney(regional.currencyCode, regional.weekly),
            PricingPolicy.formatMoney(regional.currencyCode, regional.yearly)
        )

        val regionalContext = getString(
            R.string.pricing_region_template,
            regional.regionLabel,
            regional.regionCode,
            regional.currencyCode,
            String.format(Locale.US, "%.2fx", regional.multiplier)
        )

        val competitorContext = getString(
            R.string.pricing_competitor_short_template,
            PricingPolicy.formatMoney(model.baseCurrencyCode, competitorBitwarden),
            PricingPolicy.formatMoney(model.baseCurrencyCode, competitorOnePassword),
            PricingPolicy.formatMoney(model.baseCurrencyCode, competitorDashlane)
        )

        val discountContext = when {
            entitlement.isLifetimePro -> getString(R.string.pricing_referral_lifetime_note)
            discountPercent > 0.0 -> getString(
                R.string.pricing_referral_discount_active_template,
                formatDiscountPercent(discountPercent),
                PricingPolicy.formatMoney(baseRegional.currencyCode, baseRegional.monthly),
                PricingPolicy.formatMoney(regional.currencyCode, regional.monthly)
            )
            else -> getString(
                R.string.pricing_referral_discount_inactive_template,
                formatDiscountPercent(model.referralDiscountPercent)
            )
        }

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

        binding.pricingCompetitorLabel.text = "$regionalContext\n$discountContext\n$competitorContext"
        val recordsLimit = if (access.features.credentialRecordsLimit < 0) {
            getString(R.string.pricing_limit_unlimited)
        } else {
            access.features.credentialRecordsLimit.toString()
        }
        val queueLimit = if (access.features.queueActionsLimit < 0) {
            getString(R.string.pricing_limit_unlimited)
        } else {
            access.features.queueActionsLimit.toString()
        }
        val breachLimit = if (access.features.breachScansPerDay < 0) {
            getString(R.string.pricing_limit_unlimited)
        } else {
            access.features.breachScansPerDay.toString()
        }
        binding.pricingFeatureAccessLabel.text = getString(
            R.string.pricing_feature_access_template,
            featureTierLabel(access.tierCode),
            featureFlagLabel(access.features.continuousScanEnabled),
            featureFlagLabel(access.features.overlayAssistantEnabled),
            featureFlagLabel(access.features.aiHotlineEnabled),
            queueLimit,
            recordsLimit,
            breachLimit
        )
        binding.pricingFeedbackLabel.text = feedbackContext
    }

    private fun openPlanSelectionDialog() {
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
            )
        )
        val planIds = arrayOf("weekly", "monthly", "yearly")
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

    private fun featureTierLabel(tierCode: String): String {
        return when (tierCode.lowercase(Locale.US)) {
            "lifetime" -> getString(R.string.pricing_tier_lifetime)
            "trial" -> getString(R.string.pricing_tier_trial)
            "paid" -> getString(R.string.pricing_tier_paid)
            else -> getString(R.string.pricing_tier_free)
        }
    }

    private fun featureFlagLabel(enabled: Boolean): String {
        return if (enabled) {
            getString(R.string.pricing_feature_enabled)
        } else {
            getString(R.string.pricing_feature_locked)
        }
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
            val sync = withContext(Dispatchers.IO) {
                SupportFeedbackReporter.submitFeedback(
                    context = this@MainActivity,
                    rating = performanceRating,
                    recommendToFriends = recommendToFriends,
                    tier = accessTier,
                    selectedPlan = selectedPlan,
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

    private fun refreshReadinessPanel() {
        val notificationRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notificationReady = !notificationRequired || isNotificationPermissionReady()
        val overlayReady = Settings.canDrawOverlays(this)
        val activeModeOn = SecurityScanner.isContinuousModeEnabled(this)
        val lastScan = SecurityScanner.readLastScanTimestamp(this)

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

        binding.readinessSummaryLabel.text = getString(
            R.string.readiness_summary_template,
            notificationState,
            overlayState,
            activeModeState,
            lastScan
        )
        binding.fixNotificationButton.isEnabled = notificationRequired && !notificationReady
        binding.fixOverlayButton.isEnabled = !overlayReady
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
