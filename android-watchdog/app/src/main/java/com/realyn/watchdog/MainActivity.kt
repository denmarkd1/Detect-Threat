package com.realyn.watchdog

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.realyn.watchdog.databinding.ActivityMainBinding
import com.realyn.watchdog.theme.LionThemeCatalog
import com.realyn.watchdog.theme.LionThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_GUARDIAN_SETTINGS_ACTION = "com.realyn.watchdog.extra.GUARDIAN_SETTINGS_ACTION"
        const val GUARDIAN_SETTINGS_ACTION_OPEN_PLAN_BILLING = "open_plan_billing"
        const val GUARDIAN_SETTINGS_ACTION_OPEN_AI_CONNECTION = "open_ai_connection"
        const val GUARDIAN_SETTINGS_ACTION_OPEN_LOCATOR_SETUP = "open_locator_setup"

        private const val UI_PREFS_FILE = "dt_ui_prefs"
        private const val KEY_HOME_INTRO_SHOWN = "home_intro_shown_v3"
        private const val KEY_HOME_TUTORIAL_POPUP_SHOWN = "home_tutorial_popup_shown_v1"
    }

    private enum class SecurityActionRoute {
        RUN_SCAN,
        RUN_WIFI_POSTURE_SCAN,
        FIX_NOTIFICATIONS,
        FIX_OVERLAY,
        RUN_PHISHING_TRIAGE,
        START_INCIDENT,
        OPEN_DEVICE_LOCATOR_PROVIDER,
        OPEN_DEVICE_LOCATOR_SETUP,
        OPEN_SUPPORT,
        OPEN_CREDENTIAL_CENTER
    }

    private enum class GuidedDestination {
        SWEEP,
        THREATS,
        CREDENTIALS,
        SERVICES
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

    private data class GuidedSuggestion(
        val destination: GuidedDestination,
        val destinationLabel: String,
        val bodyText: String
    )

    private data class HomeViewportProfile(
        val compactHeight: Boolean,
        val compactWidth: Boolean,
        val foldableLayout: Boolean,
        val tabletLayout: Boolean
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
    private var billingRefreshInFlight: Boolean = false
    private var latestWifiSnapshot: WifiScanSnapshotRecord? = null
    private var latestLocatorState: LocatorCapabilityState? = null
    private var pendingWifiPermissionScan: Boolean = false
    private var pendingVaultExportItemId: String = ""
    private var lionFillMode: LionFillMode = LionFillMode.LEFT_TO_RIGHT
    private var lionBusyInProgress: Boolean = false
    private var lionProgressAnimator: ValueAnimator? = null
    private var lionIdleResetRunnable: Runnable? = null
    private var homeIntroHandledThisSession: Boolean = false
    private var homeIntroAnimating: Boolean = false
    private var introWordAnimator: ValueAnimator? = null
    private var introSequenceRunnable: Runnable? = null
    private var latestSystemBarInsets: Insets = Insets.NONE
    private var activeHomePalette: LionThemePalette? = null
    private var pendingGuardianSettingsAction: String? = null

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
        pendingGuardianSettingsAction = intent?.getStringExtra(EXTRA_GUARDIAN_SETTINGS_ACTION)
        configureResponsiveHomeLayout()

        binding.oneTimeScanButton.setOnClickListener { runHomeFullScan() }
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
        binding.deviceLocatorOpenButton.setOnClickListener { openDeviceLocatorProvider() }
        binding.deviceLocatorSetupButton.setOnClickListener { openDeviceLocatorSetupDialog() }
        binding.securityTopActionButton.setOnClickListener { runHomeFullScan() }
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
        binding.navScanButton.setOnClickListener { runHomeFullScan() }
        binding.navGuardButton.setOnClickListener { openPhishingTriageDialog() }
        binding.navLionButton.setOnClickListener { openSecurityScoreDialog() }
        binding.navVaultButton.setOnClickListener { openCredentialCenter() }
        binding.navSupportButton.setOnClickListener { openSecurityDetailsDialog() }
        binding.widgetSweepCard.setOnClickListener { runHomeFullScan() }
        binding.widgetThreatsCard.setOnClickListener { openPhishingTriageDialog() }
        binding.widgetCredentialsCard.setOnClickListener { openCredentialCenter() }
        binding.widgetServicesCard.setOnClickListener { openSecurityDetailsDialog() }
        binding.goProButton.setOnClickListener { openPlanSelectionDialog() }
        binding.lionModeToggleButton.setOnClickListener { openGuardianSettingsDialog() }

        lionFillMode = LionThemePrefs.readFillMode(this)
        refreshLionHeroVisuals()
        binding.lionHeroView.setIdleState()
        binding.bottomNavCard.visibility = View.GONE
        applyMinimalHomeSurface()

        maybeRequestNotificationPermission()
        applyAdvancedControlsVisibility()
        enforceAccessGate()
    }

    override fun onResume() {
        super.onResume()
        enforceAccessGate()
        recoverBottomNavIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingGuardianSettingsAction = intent.getStringExtra(EXTRA_GUARDIAN_SETTINGS_ACTION)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            AppAccessGate.onAppBackgrounded(this)
        }
    }

    override fun onDestroy() {
        cancelLionProcessingAnimations(resetToIdle = false)
        clearHomeIntroTimeline()
        binding.homeIntroOverlay.animate().cancel()
        binding.introLionHero.animate().cancel()
        binding.introWelcomeLabel.animate().cancel()
        binding.introCelebrationView.stopCelebration()
        binding.bottomNavCard.animate().cancel()
        super.onDestroy()
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
                consumePendingGuardianSettingsAction()
                refreshBillingEntitlementIfNeeded(force = false, userVisibleStatus = false)
                if (!homeIntroHandledThisSession) {
                    binding.root.post { maybeRunHomeIntroOnce() }
                }
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

    private fun configureResponsiveHomeLayout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            latestSystemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            applyResponsiveHomeViewport(resources.configuration)
            insets
        }
        binding.root.post { applyResponsiveHomeViewport(resources.configuration) }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun applyResponsiveHomeViewport(configuration: Configuration) {
        val profile = resolveHomeViewportProfile(configuration)
        val sideInset = maxOf(latestSystemBarInsets.left, latestSystemBarInsets.right)
        val screenWidthPx = resources.displayMetrics.widthPixels - (sideInset * 2)
        val maxContentWidthDp = when {
            profile.tabletLayout -> 780f
            profile.foldableLayout -> 640f
            else -> 560f
        }
        val contentWidthPx = screenWidthPx
            .coerceAtMost(dpToPx(maxContentWidthDp))
            .coerceAtLeast(dpToPx(280f))
        binding.homeContentColumn.updateLayoutParams<FrameLayout.LayoutParams> {
            width = contentWidthPx
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        val navHeightPx = dpToPx(if (profile.compactHeight) 70f else 74f)
        val navBottomMargin = latestSystemBarInsets.bottom + dpToPx(if (profile.compactHeight) 6f else 10f)
        val navHorizontalMargin = dpToPx(if (profile.tabletLayout) 24f else 12f)
        binding.bottomNavCard.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            height = navHeightPx
            leftMargin = navHorizontalMargin + latestSystemBarInsets.left
            rightMargin = navHorizontalMargin + latestSystemBarInsets.right
            bottomMargin = navBottomMargin
        }

        val scanButtonBottomMargin = navBottomMargin + navHeightPx + dpToPx(if (profile.compactHeight) 8f else 12f)
        binding.securityTopActionButton.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            leftMargin = navHorizontalMargin + latestSystemBarInsets.left
            rightMargin = navHorizontalMargin + latestSystemBarInsets.right
            bottomMargin = scanButtonBottomMargin
        }

        val scanButtonHeightPx = dpToPx(if (profile.compactHeight) 50f else 54f)
        val heroContainerHeight = (
            resources.displayMetrics.heightPixels -
                (latestSystemBarInsets.top + dpToPx(8f)) -
                scanButtonBottomMargin -
                scanButtonHeightPx -
                dpToPx(if (profile.compactHeight) 20f else 24f)
            )
            .coerceAtLeast(dpToPx(if (profile.compactHeight) 500f else 560f))
            .coerceAtMost(dpToPx(if (profile.tabletLayout) 880f else 700f))
        binding.homeHeroCard.updateLayoutParams<LinearLayout.LayoutParams> {
            height = heroContainerHeight
        }
        binding.homeHeroContent.updateLayoutParams<FrameLayout.LayoutParams> {
            height = FrameLayout.LayoutParams.MATCH_PARENT
        }

        binding.mainScrollView.updatePadding(
            top = latestSystemBarInsets.top + dpToPx(8f),
            bottom = scanButtonBottomMargin + dpToPx(if (profile.compactHeight) 58f else 64f)
        )

        applyHeroViewportProfile(profile)
        binding.homeQuickWidgetsGrid.translationY = 0f
        binding.root.post { alignHomeWidgetsBetweenTitleAndScan() }
    }

    private fun applyHeroViewportProfile(profile: HomeViewportProfile) {
        val heroHeightPx = when {
            profile.tabletLayout -> dpToPx(284f)
            profile.compactHeight -> dpToPx(176f)
            profile.foldableLayout -> dpToPx(238f)
            else -> resources.getDimensionPixelSize(R.dimen.home_lion_hero_height)
        }
        binding.lionHeroView.updateLayoutParams<LinearLayout.LayoutParams> {
            height = heroHeightPx
        }

        val widgetCardHeightPx = when {
            profile.tabletLayout -> dpToPx(118f)
            profile.compactHeight -> dpToPx(96f)
            else -> dpToPx(108f)
        }
        listOf(
            binding.widgetSweepCard,
            binding.widgetThreatsCard,
            binding.widgetCredentialsCard,
            binding.widgetServicesCard
        ).forEach { card ->
            card.updateLayoutParams<LinearLayout.LayoutParams> {
                height = widgetCardHeightPx
            }
        }

        val introHeroSizePx = when {
            profile.tabletLayout -> dpToPx(390f)
            profile.compactHeight -> dpToPx(268f)
            profile.foldableLayout -> dpToPx(340f)
            else -> resources.getDimensionPixelSize(R.dimen.intro_lion_hero_size)
        }
        binding.introLionHero.updateLayoutParams<FrameLayout.LayoutParams> {
            width = introHeroSizePx
            height = introHeroSizePx
        }

        val welcomeBottomMargin = when {
            profile.tabletLayout -> dpToPx(220f)
            profile.compactHeight -> dpToPx(136f)
            else -> resources.getDimensionPixelSize(R.dimen.home_intro_welcome_bottom_margin)
        }
        binding.introWelcomeLabel.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = welcomeBottomMargin
        }
        binding.introWelcomeLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.tabletLayout) 22f else if (profile.compactWidth) 17f else 19f
        )

        val compactControls = profile.compactWidth && profile.compactHeight
        binding.heroTopControlsRow.orientation = if (compactControls) {
            LinearLayout.VERTICAL
        } else {
            LinearLayout.HORIZONTAL
        }
        binding.heroTopSpacer.visibility = if (compactControls) View.GONE else View.VISIBLE

        val goProLayout = binding.goProButton.layoutParams as LinearLayout.LayoutParams
        val modeLayout = binding.lionModeToggleButton.layoutParams as LinearLayout.LayoutParams
        if (compactControls) {
            goProLayout.width = LinearLayout.LayoutParams.WRAP_CONTENT
            goProLayout.weight = 0f
            goProLayout.bottomMargin = dpToPx(4f)
            modeLayout.width = LinearLayout.LayoutParams.MATCH_PARENT
            modeLayout.weight = 0f
            modeLayout.topMargin = 0
            binding.lionModeToggleButton.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            binding.lionModeToggleButton.maxLines = 2
        } else {
            goProLayout.bottomMargin = 0
            modeLayout.width = LinearLayout.LayoutParams.WRAP_CONTENT
            modeLayout.weight = 0f
            modeLayout.topMargin = 0
            binding.lionModeToggleButton.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            binding.lionModeToggleButton.maxLines = 1
        }
        binding.goProButton.layoutParams = goProLayout
        binding.lionModeToggleButton.layoutParams = modeLayout

        binding.goProButton.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactWidth) 13f else 14f
        )
        binding.lionModeToggleButton.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactWidth) 11f else 12f
        )
        binding.homeFrameTitleLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactHeight) 19f else 22f
        )
        binding.securityScoreLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactHeight) 22f else if (profile.tabletLayout) 30f else 26f
        )
        binding.securityHeroTitleLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactHeight) 10f else 11f
        )
        binding.securityTierLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactHeight) 11f else 12f
        )
        binding.statusLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactHeight) 14f else 15f
        )
        binding.subStatusLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactHeight) 11f else 12f
        )
        binding.securityUrgentActionsLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactHeight) 10f else 11f
        )
    }

    private fun resolveHomeViewportProfile(configuration: Configuration): HomeViewportProfile {
        val widthDp = configuration.screenWidthDp
        val heightDp = configuration.screenHeightDp
        val smallestDp = configuration.smallestScreenWidthDp
        val tabletLayout = smallestDp >= 600
        val foldableLayout = !tabletLayout && widthDp >= 540
        val compactHeight = heightDp in 1..760
        val compactWidth = widthDp in 1..359
        return HomeViewportProfile(
            compactHeight = compactHeight,
            compactWidth = compactWidth,
            foldableLayout = foldableLayout,
            tabletLayout = tabletLayout
        )
    }

    private fun applyMinimalHomeSurface() {
        binding.mainScrollView.scrollEnabled = false
        binding.mainScrollView.overScrollMode = View.OVER_SCROLL_NEVER
        binding.securityActionDetailsButton.visibility = View.GONE
        binding.updateStatusLabel.visibility = View.GONE
        binding.wifiPostureCard.visibility = View.GONE
        binding.mediaVaultCard.visibility = View.GONE
        binding.deviceLocatorCard.visibility = View.GONE
        binding.translationCard.visibility = View.GONE
        binding.pricingCard.visibility = View.GONE
        binding.scamShieldCard.visibility = View.GONE
        binding.readinessCard.visibility = View.GONE
        binding.incidentCard.visibility = View.GONE
        binding.readinessActionsCard.visibility = View.GONE
        binding.incidentLifecycleCard.visibility = View.GONE
        binding.oneTimeScanButton.visibility = View.GONE
        binding.continuousScanButton.visibility = View.GONE
        binding.credentialCenterButton.visibility = View.GONE
        binding.checkUpdatesButton.visibility = View.GONE
        binding.refreshSupportRow.visibility = View.GONE
        binding.resultCard.visibility = View.GONE
        binding.advancedControlsToggleButton.visibility = View.GONE
        binding.attributionText.visibility = View.GONE
    }

    private fun runOneTimeScan() {
        runHomeFullScan()
    }

    private fun runHomeFullScan() {
        if (homeIntroAnimating) {
            return
        }
        setBusy(true, getString(R.string.home_full_scan_running))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                SecurityScanner.runScan(this@MainActivity, createBaselineIfMissing = true)
            }
            val wifiEnabled = WifiPostureScanner.config(this@MainActivity).enabled
            if (wifiEnabled) {
                withContext(Dispatchers.Default) {
                    WifiPostureScanner.runPostureScan(this@MainActivity)
                }
            }
            latestWifiSnapshot = WifiScanSnapshotStore.latest(this@MainActivity)
            binding.statusLabel.text = SecurityScanner.summaryLine(result)
            binding.subStatusLabel.text = getString(R.string.home_full_scan_completed)
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
        refreshLionHeroVisuals()
        binding.goProButton.text = if (access.paidAccess) {
            getString(R.string.action_pro_active)
        } else {
            getString(R.string.action_go_pro)
        }
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
        refreshDeviceLocatorPanel()
        refreshReadinessPanel()
        refreshScamPanel()
        refreshCopilotPanel()
        refreshHygienePanel()
        refreshIncidentPanel()
        refreshPricingPanel()
        refreshTranslationPanel()
        refreshSecurityHero()
        refreshHomeQuickWidgets()
        refreshBottomNavIndicators()
        applyAdvancedControlsVisibility()
        recoverBottomNavIfNeeded()
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
        binding.deviceLocatorOpenButton.isEnabled = !busy
        binding.deviceLocatorSetupButton.isEnabled = !busy
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
        binding.lionModeToggleButton.isEnabled = !busy
        binding.goProButton.isEnabled = !busy
        val navEnabled = !busy && !homeIntroAnimating
        binding.navScanButton.isEnabled = navEnabled
        binding.navGuardButton.isEnabled = navEnabled
        binding.navLionButton.isEnabled = navEnabled
        binding.navVaultButton.isEnabled = navEnabled
        binding.navSupportButton.isEnabled = navEnabled
        binding.widgetSweepCard.isEnabled = navEnabled
        binding.widgetThreatsCard.isEnabled = navEnabled
        binding.widgetCredentialsCard.isEnabled = navEnabled
        binding.widgetServicesCard.isEnabled = navEnabled

        if (busy) {
            beginLionProcessingAnimation()
        } else {
            completeLionProcessingAnimation()
        }
        if (status != null) {
            binding.statusLabel.text = status
        }
    }

    private fun maybeRunHomeIntroOnce() {
        if (homeIntroHandledThisSession || isFinishing || isDestroyed) {
            return
        }
        homeIntroHandledThisSession = true
        if (isHomeIntroAlreadyShown()) {
            showBottomNavImmediate()
            maybeShowHomeTutorialPopup()
            return
        }
        playHomeIntroSequence()
    }

    private fun playHomeIntroSequence() {
        homeIntroAnimating = true
        clearHomeIntroTimeline()
        binding.bottomNavCard.visibility = View.GONE
        binding.homeIntroOverlay.visibility = View.VISIBLE
        binding.homeIntroOverlay.alpha = 1f
        binding.introWelcomeLabel.alpha = 0f
        binding.introWelcomeLabel.text = ""
        binding.lionHeroView.alpha = 0f
        binding.introCelebrationView.alpha = 0f
        refreshLionHeroVisuals()
        binding.introLionHero.setScanProgress(1f)
        setBusy(false)

        binding.homeIntroOverlay.post {
            if (isFinishing || isDestroyed) {
                return@post
            }
            val introLoc = IntArray(2)
            val targetLoc = IntArray(2)
            binding.introLionHero.getLocationOnScreen(introLoc)
            binding.lionHeroView.getLocationOnScreen(targetLoc)

            val introCenterX = introLoc[0] + (binding.introLionHero.width / 2f)
            val introCenterY = introLoc[1] + (binding.introLionHero.height / 2f)
            val targetCenterX = targetLoc[0] + (binding.lionHeroView.width / 2f)
            val targetCenterY = targetLoc[1] + (binding.lionHeroView.height / 2f)

            val deltaX = targetCenterX - introCenterX
            val deltaY = targetCenterY - introCenterY
            val targetScale = ((binding.lionHeroView.height.toFloat() / binding.introLionHero.height.toFloat()) * 0.88f)
                .coerceIn(0.42f, 0.72f)

            binding.introLionHero.apply {
                scaleX = 1.30f
                scaleY = 1.30f
                translationX = 0f
                translationY = 0f
            }

            binding.introLionHero.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .translationX(deltaX)
                .translationY(deltaY)
                .setDuration(1850L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    binding.lionHeroView.alpha = 1f
                    animateIntroWelcomeMessage {
                        playIntroCelebrationAndLockIn()
                    }
                }
                .start()
        }
    }

    private fun animateIntroWelcomeMessage(onComplete: () -> Unit) {
        if (isFinishing || isDestroyed) {
            return
        }
        val fullText = getString(R.string.home_intro_welcome)
        val chunks = Regex("\\S+\\s*").findAll(fullText).map { it.value }.toList()
        if (chunks.isEmpty()) {
            onComplete()
            return
        }

        introWordAnimator?.cancel()
        binding.introWelcomeLabel.text = ""
        binding.introWelcomeLabel.alpha = 0f
        binding.introWelcomeLabel.animate()
            .alpha(1f)
            .setDuration(420L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        var cancelled = false
        introWordAnimator = ValueAnimator.ofInt(0, chunks.size).apply {
            duration = chunks.size * 210L
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val count = (animator.animatedValue as Int).coerceIn(0, chunks.size)
                binding.introWelcomeLabel.text = chunks.take(count).joinToString(separator = "")
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || isFinishing || isDestroyed) {
                        return
                    }
                    introSequenceRunnable = Runnable { onComplete() }
                    binding.homeIntroOverlay.postDelayed(introSequenceRunnable, 1100L)
                }
            })
            start()
        }
    }

    private fun playIntroCelebrationAndLockIn() {
        if (isFinishing || isDestroyed) {
            return
        }
        val accentColor = activeHomePalette?.accent ?: LionThemePrefs.resolveAccentColor(this)
        binding.introCelebrationView.animate().cancel()
        binding.introCelebrationView.alpha = 0f
        binding.introCelebrationView.animate()
            .alpha(1f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.introCelebrationView.startCelebration(accentColor, 2500L)
            }
            .start()

        binding.introLionHero.animate()
            .scaleXBy(0.05f)
            .scaleYBy(0.05f)
            .setDuration(300L)
            .withEndAction {
                binding.introLionHero.animate()
                    .scaleXBy(-0.05f)
                    .scaleYBy(-0.05f)
                    .setDuration(300L)
                    .start()
            }
            .start()

        introSequenceRunnable = Runnable { finalizeHomeIntroTransition() }
        binding.homeIntroOverlay.postDelayed(introSequenceRunnable, 2600L)
    }

    private fun finalizeHomeIntroTransition() {
        if (isFinishing || isDestroyed) {
            return
        }
        binding.homeIntroOverlay.animate()
            .alpha(0f)
            .setDuration(540L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.homeIntroOverlay.visibility = View.GONE
                binding.homeIntroOverlay.alpha = 1f
                binding.introWelcomeLabel.alpha = 0f
                binding.introWelcomeLabel.text = getString(R.string.home_intro_welcome)
                binding.introLionHero.translationX = 0f
                binding.introLionHero.translationY = 0f
                binding.introLionHero.scaleX = 1f
                binding.introLionHero.scaleY = 1f
                binding.introCelebrationView.alpha = 0f
                binding.introCelebrationView.stopCelebration()
                homeIntroAnimating = false
                animateBottomNavIn()
                setBusy(false)
                markHomeIntroShown()
                maybeShowHomeTutorialPopup()
            }
            .start()
    }

    private fun clearHomeIntroTimeline() {
        introWordAnimator?.cancel()
        introWordAnimator = null
        introSequenceRunnable?.let { pending ->
            binding.homeIntroOverlay.removeCallbacks(pending)
        }
        introSequenceRunnable = null
        binding.introCelebrationView.animate().cancel()
        binding.introCelebrationView.stopCelebration()
    }

    private fun animateBottomNavIn() {
        binding.bottomNavCard.visibility = View.VISIBLE
        binding.bottomNavCard.alpha = 0f
        binding.bottomNavCard.translationY = 56f
        val navItems = listOf(
            binding.navScanButton,
            binding.navGuardButton,
            binding.navLionButton,
            binding.navVaultButton,
            binding.navSupportButton
        )
        navItems.forEach {
            it.alpha = 0f
            it.translationY = 12f
        }
        binding.bottomNavCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(360L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        navItems.forEachIndexed { index, view ->
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 70L) + 110L)
                .setDuration(250L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        binding.bottomNavCard.postDelayed(
            { refreshBottomNavIndicators() },
            640L
        )
    }

    private fun showBottomNavImmediate() {
        clearHomeIntroTimeline()
        homeIntroAnimating = false
        binding.homeIntroOverlay.visibility = View.GONE
        binding.lionHeroView.alpha = 1f
        binding.bottomNavCard.visibility = View.VISIBLE
        binding.bottomNavCard.alpha = 1f
        binding.bottomNavCard.translationY = 0f
        val navItems = listOf(
            binding.navScanButton,
            binding.navGuardButton,
            binding.navLionButton,
            binding.navVaultButton,
            binding.navSupportButton
        )
        navItems.forEach {
            it.alpha = 1f
            it.translationY = 0f
        }
        refreshBottomNavIndicators()
    }

    private fun recoverBottomNavIfNeeded() {
        if (homeIntroAnimating && binding.homeIntroOverlay.visibility != View.VISIBLE) {
            homeIntroAnimating = false
        }
        val introReadyForNav = isHomeIntroAlreadyShown() || homeIntroHandledThisSession
        if (!homeIntroAnimating && introReadyForNav && binding.bottomNavCard.visibility != View.VISIBLE) {
            showBottomNavImmediate()
            maybeShowHomeTutorialPopup()
        }
    }

    private fun maybeShowHomeTutorialPopup() {
        if (isFinishing || isDestroyed) {
            return
        }
        val prefs = getSharedPreferences(UI_PREFS_FILE, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HOME_TUTORIAL_POPUP_SHOWN, false)) {
            return
        }
        val state = latestSecurityHeroState ?: buildSecurityHeroState()
        val suggestion = resolveGuidedSuggestion(state)
        prefs.edit()
            .putBoolean(KEY_HOME_TUTORIAL_POPUP_SHOWN, true)
            .apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.home_tutorial_popup_title)
            .setMessage(
                getString(
                    R.string.home_tutorial_popup_message_template,
                    suggestion.destinationLabel,
                    suggestion.bodyText
                )
            )
            .setPositiveButton(R.string.home_tutorial_popup_action) { _, _ ->
                routeToGuidedDestination(suggestion.destination)
            }
            .setNegativeButton(R.string.home_tutorial_popup_later, null)
            .show()
    }

    private fun openSecurityScoreDialog() {
        val state = latestSecurityHeroState ?: buildSecurityHeroState().also {
            latestSecurityHeroState = it
        }
        val headline = state.details.firstOrNull()
        val message = buildString {
            appendLine(getString(R.string.security_score_template, state.score))
            appendLine(state.tierLabel)
            if (!headline.isNullOrBlank()) {
                appendLine()
                append(headline)
            }
        }.trim()
        AlertDialog.Builder(this)
            .setTitle(R.string.security_hero_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_view_details) { _, _ ->
                openSecurityDetailsDialog()
            }
            .show()
    }

    private fun isHomeIntroAlreadyShown(): Boolean {
        return getSharedPreferences(UI_PREFS_FILE, MODE_PRIVATE)
            .getBoolean(KEY_HOME_INTRO_SHOWN, false)
    }

    private fun markHomeIntroShown() {
        getSharedPreferences(UI_PREFS_FILE, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HOME_INTRO_SHOWN, true)
            .apply()
    }

    private fun openGuardianSettingsDialog() {
        startActivity(Intent(this, GuardianSettingsActivity::class.java))
    }

    private fun consumePendingGuardianSettingsAction() {
        val action = pendingGuardianSettingsAction ?: return
        pendingGuardianSettingsAction = null
        when (action) {
            GUARDIAN_SETTINGS_ACTION_OPEN_PLAN_BILLING -> openPlanSelectionDialog()
            GUARDIAN_SETTINGS_ACTION_OPEN_AI_CONNECTION -> openConnectedAiDialog()
            GUARDIAN_SETTINGS_ACTION_OPEN_LOCATOR_SETUP -> openDeviceLocatorSetupDialog()
        }
    }

    private fun cycleLionFillMode() {
        lionFillMode = lionFillMode.next()
        LionThemePrefs.writeFillMode(this, lionFillMode)
        refreshLionHeroVisuals()
        binding.subStatusLabel.text = getString(
            R.string.lion_mode_status_template,
            getString(lionFillMode.labelRes)
        )
    }

    private fun refreshLionHeroVisuals() {
        lionFillMode = LionThemePrefs.readFillMode(this)
        val access = PricingPolicy.resolveFeatureAccess(this)
        val selectedBitmap = LionThemePrefs.resolveSelectedLionBitmap(this)
        val themeState = LionThemeCatalog.resolveState(
            context = this,
            paidAccess = access.paidAccess,
            selectedLionBitmap = selectedBitmap
        )
        val accentColor = themeState.palette.accent
        activeHomePalette = themeState.palette
        applyHomeTheme(themeState.palette, themeState.isDark)
        binding.lionHeroView.setFillMode(lionFillMode)
        binding.lionHeroView.setSurfaceTone(themeState.isDark)
        binding.lionHeroView.setLionBitmap(selectedBitmap)
        binding.lionHeroView.setAccentColor(accentColor)
        binding.introLionHero.setFillMode(lionFillMode)
        binding.introLionHero.setSurfaceTone(themeState.isDark)
        binding.introLionHero.setLionBitmap(selectedBitmap)
        binding.introLionHero.setAccentColor(accentColor)
        binding.lionModeToggleButton.text = getString(R.string.action_guardian_settings)
    }

    private fun applyHomeTheme(
        palette: LionThemePalette,
        isDarkTone: Boolean
    ) {
        window.statusBarColor = palette.backgroundEnd
        window.navigationBarColor = palette.backgroundEnd
        val systemBarController = WindowCompat.getInsetsController(window, binding.root)
        systemBarController.isAppearanceLightStatusBars = !isDarkTone
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemBarController.isAppearanceLightNavigationBars = !isDarkTone
        }

        binding.root.background = gradientBackground(
            startColor = palette.backgroundStart,
            centerColor = palette.backgroundCenter,
            endColor = palette.backgroundEnd,
            angle = 90
        )
        binding.homeIntroOverlay.setBackgroundColor(palette.backgroundEnd)
        binding.homeHeroCard.strokeColor = palette.stroke
        binding.homeFrameTitleLabel.setTextColor(palette.textPrimary)
        binding.lionModeToggleButton.setTextColor(palette.accent)

        applyActionButtonPalette(binding.goProButton, palette)
        applyActionButtonPalette(binding.securityTopActionButton, palette)

        applyWidgetCardPalette(binding.widgetSweepCard, palette)
        applyWidgetCardPalette(binding.widgetThreatsCard, palette)
        applyWidgetCardPalette(binding.widgetCredentialsCard, palette)
        applyWidgetCardPalette(binding.widgetServicesCard, palette)

        binding.widgetSweepValue.setTextColor(palette.textPrimary)
        binding.widgetThreatsValue.setTextColor(palette.textPrimary)
        binding.widgetCredentialsValue.setTextColor(palette.textPrimary)
        binding.widgetServicesValue.setTextColor(palette.textPrimary)
        binding.widgetSweepHint.setTextColor(palette.textMuted)
        binding.widgetThreatsHint.setTextColor(palette.textMuted)
        binding.widgetCredentialsHint.setTextColor(palette.textMuted)
        binding.widgetServicesHint.setTextColor(palette.textMuted)

        binding.bottomNavCard.strokeColor = palette.stroke
        binding.bottomNavRow.background = gradientBackground(
            startColor = palette.navShellStart,
            centerColor = blendColors(palette.navShellStart, palette.navShellEnd, 0.45f),
            endColor = palette.navShellEnd,
            angle = 90,
            cornerRadiusDp = 22f
        )
        binding.navLionButton.background = if (isDarkTone) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(blendColors(palette.panel, Color.WHITE, 0.14f))
            }
        }
        applyBottomNavButtonPalette(binding.navScanButton, palette)
        applyBottomNavButtonPalette(binding.navGuardButton, palette)
        applyBottomNavButtonPalette(binding.navVaultButton, palette)
        applyBottomNavButtonPalette(binding.navSupportButton, palette)
        tintAlertDot(binding.navScanDot, palette.alert)
        tintAlertDot(binding.navGuardDot, palette.alert)
        tintAlertDot(binding.navVaultDot, palette.alert)
        tintAlertDot(binding.navSupportDot, palette.alert)
    }

    private fun applyActionButtonPalette(
        button: com.google.android.material.button.MaterialButton,
        palette: LionThemePalette
    ) {
        button.backgroundTintList = ColorStateList.valueOf(palette.panelAlt)
        button.strokeColor = ColorStateList.valueOf(palette.stroke)
        button.setTextColor(palette.accent)
        button.iconTint = ColorStateList.valueOf(palette.accent)
    }

    private fun applyWidgetCardPalette(
        card: com.google.android.material.card.MaterialCardView,
        palette: LionThemePalette
    ) {
        card.setCardBackgroundColor(palette.panelAlt)
        card.strokeColor = palette.stroke
        val content = card.getChildAt(0) as? LinearLayout ?: return
        val headerRow = content.getChildAt(0) as? LinearLayout ?: return
        (headerRow.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(palette.accent)
        (headerRow.getChildAt(1) as? TextView)?.setTextColor(palette.textSecondary)
    }

    private fun applyBottomNavButtonPalette(button: LinearLayout, palette: LionThemePalette) {
        for (index in 0 until button.childCount) {
            when (val child = button.getChildAt(index)) {
                is ImageView -> child.imageTintList = ColorStateList.valueOf(palette.textSecondary)
                is TextView -> child.setTextColor(palette.textSecondary)
            }
        }
    }

    private fun tintAlertDot(dot: View, @androidx.annotation.ColorInt color: Int) {
        dot.background?.mutate()?.setTint(color)
    }

    private fun gradientBackground(
        @androidx.annotation.ColorInt startColor: Int,
        @androidx.annotation.ColorInt centerColor: Int,
        @androidx.annotation.ColorInt endColor: Int,
        angle: Int,
        cornerRadiusDp: Float = 0f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            orientation = if (angle == 90) {
                GradientDrawable.Orientation.TOP_BOTTOM
            } else {
                GradientDrawable.Orientation.LEFT_RIGHT
            }
            colors = intArrayOf(startColor, centerColor, endColor)
            if (cornerRadiusDp > 0f) {
                cornerRadius = dpToPx(cornerRadiusDp).toFloat()
            }
        }
    }

    private fun blendColors(
        @androidx.annotation.ColorInt from: Int,
        @androidx.annotation.ColorInt to: Int,
        fraction: Float
    ): Int {
        return androidx.core.graphics.ColorUtils.blendARGB(
            from,
            to,
            fraction.coerceIn(0f, 1f)
        )
    }

    private fun beginLionProcessingAnimation() {
        if (lionBusyInProgress) {
            return
        }
        lionBusyInProgress = true
        lionIdleResetRunnable?.let { pending ->
            binding.lionHeroView.removeCallbacks(pending)
        }
        lionIdleResetRunnable = null
        lionProgressAnimator?.cancel()
        lionProgressAnimator = ValueAnimator.ofFloat(0f, 0.92f).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                binding.lionHeroView.setScanProgress(animator.animatedValue as Float)
            }
            start()
        }
    }

    private fun completeLionProcessingAnimation() {
        if (!lionBusyInProgress) {
            return
        }
        lionBusyInProgress = false
        lionProgressAnimator?.cancel()
        lionProgressAnimator = null
        binding.lionHeroView.setScanComplete()
        val resetRunnable = Runnable {
            if (!lionBusyInProgress) {
                binding.lionHeroView.setIdleState()
            }
        }
        lionIdleResetRunnable = resetRunnable
        binding.lionHeroView.postDelayed(resetRunnable, 1400L)
    }

    private fun cancelLionProcessingAnimations(resetToIdle: Boolean) {
        lionProgressAnimator?.cancel()
        lionProgressAnimator = null
        lionIdleResetRunnable?.let { pending ->
            binding.lionHeroView.removeCallbacks(pending)
        }
        lionIdleResetRunnable = null
        lionBusyInProgress = false
        if (resetToIdle) {
            binding.lionHeroView.setIdleState()
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

    private fun refreshBillingEntitlementIfNeeded(force: Boolean, userVisibleStatus: Boolean) {
        if (billingRefreshInFlight) {
            return
        }
        if (!force && !PlayBillingEntitlementRefresher.shouldRefresh(this)) {
            return
        }
        billingRefreshInFlight = true
        if (userVisibleStatus) {
            binding.subStatusLabel.text = getString(R.string.billing_refresh_started)
        }
        PlayBillingEntitlementRefresher.refresh(
            activity = this,
            force = force
        ) { result ->
            billingRefreshInFlight = false
            refreshPricingPanel()
            if (userVisibleStatus) {
                binding.subStatusLabel.text = when {
                    !result.success -> getString(R.string.billing_refresh_failed)
                    result.verifiedPlanId == "lifetime" -> getString(R.string.billing_refresh_lifetime)
                    result.verifiedPlanId != "none" -> getString(
                        R.string.billing_refresh_verified_template,
                        shortPlanLabel(result.verifiedPlanId)
                    )
                    else -> getString(R.string.billing_refresh_no_active_purchase)
                }
            }
        }
    }

    private fun refreshPricingPanel() {
        val model = PricingPolicy.load(this)
        val access = PricingPolicy.resolveFeatureAccess(this, model)
        val profileControl = PricingPolicy.resolveProfileControl(this, access)
        val regional = PricingPolicy.resolveForCurrentRegion(this, model)
        val trial = PricingPolicy.ensureTrial(this)
        val entitlement = PricingPolicy.entitlement(this)
        val verifiedPlan = PricingPolicy.verifiedPaidPlan(this)
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
        } else if (verifiedPlan != null) {
            getString(
                R.string.pricing_paid_verified_plan_template,
                planLabel(verifiedPlan.planId, regional)
            )
        } else {
            if (selectedPlan == "none") {
                getString(R.string.pricing_trial_ended_no_plan)
            } else {
                getString(
                    R.string.pricing_trial_ended_plan_selected_template,
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
        val familyPriceLine = if (selectedPlan == "family" || verifiedPlan?.planId == "family") {
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
                .setNeutralButton(R.string.action_refresh_purchase_status) { _, _ ->
                    refreshBillingEntitlementIfNeeded(force = true, userVisibleStatus = true)
                }
                .setNegativeButton(R.string.action_open_billing) { _, _ ->
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
                    R.string.plan_saved_pending_verification_template,
                    planLabel(selectedPlan, regional)
                )
                refreshBillingEntitlementIfNeeded(force = true, userVisibleStatus = false)
            }
            .setNeutralButton(R.string.action_refresh_purchase_status) { _, _ ->
                refreshBillingEntitlementIfNeeded(force = true, userVisibleStatus = true)
            }
            .setNegativeButton(R.string.action_open_billing) { _, _ ->
                openSupportCenter()
            }
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
            "play_billing" -> getString(R.string.pricing_lifetime_source_play_billing)
            "device_allowlist" -> getString(R.string.pricing_lifetime_source_allowlist)
            "debug_auto" -> getString(R.string.pricing_lifetime_source_debug)
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

    private fun refreshDeviceLocatorPanel() {
        val state = LocatorCapabilityViewModel.resolve(this)
        latestLocatorState = state

        if (!state.enabled) {
            binding.deviceLocatorSummaryLabel.text = getString(R.string.device_locator_disabled)
            binding.deviceLocatorActionsLabel.text = getString(R.string.device_locator_actions_placeholder)
            return
        }
        if (!state.hasProvidersConfigured) {
            binding.deviceLocatorSummaryLabel.text = getString(R.string.device_locator_no_providers)
            binding.deviceLocatorActionsLabel.text = getString(R.string.device_locator_actions_placeholder)
            return
        }

        val available = state.providers.count { it.launchReady }
        val recommended = preferredLocatorProvider(state)?.provider?.label
            ?: getString(R.string.device_locator_recommended_none)
        val linked = if (state.primaryEmailLinked) {
            getString(R.string.device_locator_linked_yes)
        } else {
            getString(R.string.device_locator_linked_no)
        }
        binding.deviceLocatorSummaryLabel.text = getString(
            R.string.device_locator_summary_template,
            linked,
            available,
            state.providers.size,
            recommended
        )

        val lines = mutableListOf<String>()
        if (!state.primaryEmailLinked) {
            lines += getString(R.string.device_locator_guidance_link_account)
        }
        preferredLocatorProvider(state)?.let { preferred ->
            lines += getString(
                R.string.device_locator_guidance_open_provider_template,
                preferred.provider.label
            )
        }
        state.providers.take(3).forEachIndexed { index, capability ->
            lines += "${index + 1}. ${capability.provider.label}: ${locatorCapabilityLabel(capability)}"
        }

        binding.deviceLocatorActionsLabel.text = if (lines.isEmpty()) {
            getString(R.string.device_locator_actions_placeholder)
        } else {
            "${getString(R.string.device_locator_actions_title)}:\n${lines.joinToString("\n")}"
        }
    }

    private fun openDeviceLocatorProvider() {
        val state = latestLocatorState ?: LocatorCapabilityViewModel.resolve(this).also {
            latestLocatorState = it
        }
        if (!state.enabled) {
            binding.subStatusLabel.text = getString(R.string.device_locator_disabled)
            return
        }

        val providers = state.providers.filter { it.launchReady }
        if (providers.isEmpty()) {
            binding.subStatusLabel.text = getString(R.string.device_locator_launch_failed)
            return
        }

        if (providers.size == 1) {
            launchLocatorProvider(providers.first())
            return
        }
        openLocatorProviderPicker(
            titleRes = R.string.device_locator_provider_picker_title,
            providers = providers,
            onSelected = { launchLocatorProvider(it) }
        )
    }

    private fun openDeviceLocatorSetupDialog() {
        val state = latestLocatorState ?: LocatorCapabilityViewModel.resolve(this).also {
            latestLocatorState = it
        }
        if (!state.enabled) {
            binding.subStatusLabel.text = getString(R.string.device_locator_disabled)
            return
        }
        if (!state.hasProvidersConfigured) {
            binding.subStatusLabel.text = getString(R.string.device_locator_no_providers)
            return
        }
        val setupProviders = state.providers.filter { it.setupReady }
        if (setupProviders.isEmpty()) {
            binding.subStatusLabel.text = getString(R.string.device_locator_setup_failed)
            return
        }

        openLocatorProviderPicker(
            titleRes = R.string.device_locator_setup_dialog_title,
            providers = setupProviders,
            onSelected = { launchLocatorSetup(it) }
        )
    }

    private fun openLocatorProviderPicker(
        titleRes: Int,
        providers: List<LocatorProviderCapability>,
        onSelected: (LocatorProviderCapability) -> Unit
    ) {
        if (providers.isEmpty()) {
            return
        }
        val options = providers.map { capability ->
            getString(
                R.string.device_locator_provider_option_template,
                capability.provider.label,
                locatorCapabilityLabel(capability)
            )
        }.toTypedArray()
        var selectedIndex = 0

        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSelected(providers[selectedIndex])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchLocatorProvider(capability: LocatorProviderCapability) {
        val result = DeviceLocatorLinkLauncher.openProvider(this, capability.provider)
        binding.subStatusLabel.text = when {
            result.opened && result.usedFallback -> getString(
                R.string.device_locator_launch_fallback_template,
                capability.provider.label
            )
            result.opened -> getString(
                R.string.device_locator_launch_success_template,
                capability.provider.label
            )
            else -> getString(R.string.device_locator_launch_failed)
        }
    }

    private fun launchLocatorSetup(capability: LocatorProviderCapability) {
        val result = DeviceLocatorLinkLauncher.openSetup(this, capability.provider)
        binding.subStatusLabel.text = if (result.opened) {
            getString(R.string.device_locator_setup_opened)
        } else {
            getString(R.string.device_locator_setup_failed)
        }
    }

    private fun preferredLocatorProvider(state: LocatorCapabilityState): LocatorProviderCapability? {
        return state.providers.firstOrNull { it.packageLaunchReady } ?:
            state.providers.firstOrNull { it.deepLinkReady } ?:
            state.providers.firstOrNull { it.fallbackReady }
    }

    private fun locatorCapabilityLabel(capability: LocatorProviderCapability): String {
        return when {
            capability.packageLaunchReady -> getString(R.string.device_locator_capability_app_ready)
            capability.deepLinkReady -> getString(R.string.device_locator_capability_link_ready)
            capability.fallbackReady -> getString(R.string.device_locator_capability_web_ready)
            else -> getString(R.string.device_locator_capability_unavailable)
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
        binding.securityUrgentActionsLabel.text = buildHomeStatusBlock()
        binding.securityTopActionButton.text = getString(R.string.action_scan_now_all)
    }

    private fun resolveGuidedSuggestion(state: SecurityHeroState): GuidedSuggestion {
        val route = state.actions.firstOrNull()?.route
        return when (route) {
            SecurityActionRoute.RUN_PHISHING_TRIAGE,
            SecurityActionRoute.START_INCIDENT -> GuidedSuggestion(
                destination = GuidedDestination.THREATS,
                destinationLabel = getString(R.string.guided_target_threats),
                bodyText = getString(R.string.guided_body_threats)
            )

            SecurityActionRoute.OPEN_CREDENTIAL_CENTER -> GuidedSuggestion(
                destination = GuidedDestination.CREDENTIALS,
                destinationLabel = getString(R.string.guided_target_credentials),
                bodyText = getString(R.string.guided_body_credentials)
            )

            SecurityActionRoute.OPEN_SUPPORT,
            SecurityActionRoute.OPEN_DEVICE_LOCATOR_PROVIDER,
            SecurityActionRoute.OPEN_DEVICE_LOCATOR_SETUP -> GuidedSuggestion(
                destination = GuidedDestination.SERVICES,
                destinationLabel = getString(R.string.guided_target_services),
                bodyText = getString(R.string.guided_body_services)
            )

            else -> GuidedSuggestion(
                destination = GuidedDestination.SWEEP,
                destinationLabel = getString(R.string.guided_target_sweep),
                bodyText = getString(R.string.guided_body_sweep)
            )
        }
    }

    private fun routeToGuidedDestination(destination: GuidedDestination) {
        when (destination) {
            GuidedDestination.SWEEP -> runHomeFullScan()
            GuidedDestination.THREATS -> openPhishingTriageDialog()
            GuidedDestination.CREDENTIALS -> openCredentialCenter()
            GuidedDestination.SERVICES -> openSecurityDetailsDialog()
        }
    }

    private fun buildHomeStatusBlock(): String {
        val scanReady = SecurityScanner.readLastScanTimestamp(this) != "never"
        val wifi = WifiScanSnapshotStore.latest(this)
        val wifiState = when {
            wifi == null -> getString(R.string.home_state_pending)
            wifi.tier.equals("high_risk", ignoreCase = true) ||
                wifi.tier.equals("elevated", ignoreCase = true) ->
                getString(R.string.home_state_attention)
            else -> getString(R.string.home_state_ready)
        }
        val latestThreat = PhishingIntakeStore.latest(this)
        val threatState = when {
            latestThreat == null -> getString(R.string.home_state_pending)
            latestThreat.result.severity == Severity.HIGH || latestThreat.result.severity == Severity.MEDIUM ->
                getString(R.string.home_state_attention)
            else -> getString(R.string.home_state_ready)
        }
        val profile = PrimaryIdentityStore.readProfile(this)
        val credentialState = if (profile.primaryEmail.isNotBlank() && profile.emailLinkedAtEpochMs > 0L) {
            getString(R.string.home_state_ready)
        } else {
            getString(R.string.home_state_pending)
        }
        return getString(
            R.string.home_status_block_template,
            if (scanReady) getString(R.string.home_state_ready) else getString(R.string.home_state_pending),
            wifiState,
            threatState,
            credentialState
        )
    }

    private fun refreshBottomNavIndicators() {
        val state = latestSecurityHeroState ?: buildSecurityHeroState()
        val scanNeedsAction = state.score < 85 || SecurityScanner.readLastScanTimestamp(this) == "never"
        val wifi = WifiScanSnapshotStore.latest(this)
        val (phishingHigh, phishingMedium, _) = PhishingIntakeStore.summarizeRecent(this, lookback = 12)
        val threatNeedsAction = phishingHigh > 0 || phishingMedium > 0 ||
            wifi?.tier.equals("high_risk", ignoreCase = true) ||
            wifi?.tier.equals("elevated", ignoreCase = true)
        val queueHasPending = CredentialActionStore.loadQueue(this)
            .any { !it.status.equals("completed", ignoreCase = true) }
        val incidentSummary = IncidentStore.summarize(this)
        val serviceNeedsAction = !PricingPolicy.resolveFeatureAccess(this).paidAccess ||
            (incidentSummary.openCount + incidentSummary.inProgressCount > 0)

        applyNavSignal(binding.navScanButton, binding.navScanDot, scanNeedsAction)
        applyNavSignal(binding.navGuardButton, binding.navGuardDot, threatNeedsAction)
        applyNavSignal(binding.navVaultButton, binding.navVaultDot, queueHasPending)
        applyNavSignal(binding.navSupportButton, binding.navSupportDot, serviceNeedsAction)
    }

    private fun refreshHomeQuickWidgets() {
        val lastScan = SecurityScanner.readLastScanTimestamp(this)
        val hasScan = lastScan != "never"
        binding.widgetSweepValue.text = if (hasScan) {
            getString(R.string.home_widget_sweep_ready)
        } else {
            getString(R.string.home_widget_sweep_pending)
        }
        binding.widgetSweepHint.text = if (hasScan) {
            getString(R.string.home_widget_last_scan_template, compactScanTimestamp(lastScan))
        } else {
            getString(R.string.home_widget_last_scan_never)
        }

        val (phishingHigh, phishingMedium, _) = PhishingIntakeStore.summarizeRecent(this, lookback = 12)
        val threatCount = phishingHigh + phishingMedium
        binding.widgetThreatsValue.text = if (threatCount <= 0) {
            getString(R.string.home_widget_threats_clear)
        } else {
            getString(R.string.home_widget_threats_count_template, threatCount)
        }
        binding.widgetThreatsHint.text = getString(
            R.string.home_widget_threats_hint_template,
            phishingHigh,
            phishingMedium
        )

        val pendingQueue = CredentialActionStore.loadQueue(this)
            .count { !it.status.equals("completed", ignoreCase = true) }
        val profile = PrimaryIdentityStore.readProfile(this)
        val linkedEmail = profile.primaryEmail.isNotBlank() && profile.emailLinkedAtEpochMs > 0L
        binding.widgetCredentialsValue.text = if (pendingQueue <= 0) {
            getString(R.string.home_widget_credentials_clear)
        } else {
            getString(R.string.home_widget_credentials_pending_template, pendingQueue)
        }
        binding.widgetCredentialsHint.text = if (linkedEmail) {
            getString(R.string.home_widget_credentials_hint_linked)
        } else {
            getString(R.string.home_widget_credentials_hint_not_linked)
        }

        val access = PricingPolicy.resolveFeatureAccess(this)
        val incidentSummary = IncidentStore.summarize(this)
        val openIncidents = incidentSummary.openCount + incidentSummary.inProgressCount
        binding.widgetServicesValue.text = if (access.paidAccess) {
            getString(R.string.home_widget_services_paid)
        } else {
            getString(R.string.home_widget_services_free)
        }
        binding.widgetServicesHint.text = getString(
            R.string.home_widget_services_hint_template,
            openIncidents
        )
    }

    private fun compactScanTimestamp(raw: String): String {
        val normalized = raw.trim()
            .replace('T', ' ')
            .removeSuffix("Z")
        if (normalized.equals("never", ignoreCase = true)) {
            return normalized
        }
        return if (normalized.length > 16) {
            normalized.substring(0, 16)
        } else {
            normalized
        }
    }

    private fun alignHomeWidgetsBetweenTitleAndScan() {
        if (
            binding.homeFrameTitleLabel.height <= 0 ||
            binding.homeQuickWidgetsGrid.height <= 0 ||
            binding.securityTopActionButton.height <= 0 ||
            binding.homeHeroContent.height <= 0
        ) {
            return
        }
        binding.homeQuickWidgetsGrid.translationY = 0f
        val titleLoc = IntArray(2)
        val widgetLoc = IntArray(2)
        val scanLoc = IntArray(2)
        val heroLoc = IntArray(2)
        binding.homeFrameTitleLabel.getLocationOnScreen(titleLoc)
        binding.homeQuickWidgetsGrid.getLocationOnScreen(widgetLoc)
        binding.securityTopActionButton.getLocationOnScreen(scanLoc)
        binding.homeHeroContent.getLocationOnScreen(heroLoc)

        val titleBottom = titleLoc[1].toFloat() + binding.homeFrameTitleLabel.height
        val scanTop = scanLoc[1].toFloat()
        val heroTop = heroLoc[1].toFloat() + binding.homeHeroContent.paddingTop
        val heroBottom = heroLoc[1].toFloat() + binding.homeHeroContent.height - binding.homeHeroContent.paddingBottom
        val safeTop = maxOf(titleBottom + dpToPx(10f), heroTop)
        val safeBottom = minOf(scanTop - dpToPx(10f), heroBottom)
        if (safeBottom <= safeTop) {
            return
        }

        val currentTop = widgetLoc[1].toFloat()
        val currentBottom = currentTop + binding.homeQuickWidgetsGrid.height
        val currentCenterY = currentTop + (binding.homeQuickWidgetsGrid.height * 0.5f)
        val targetCenterY = safeTop + ((safeBottom - safeTop) * 0.5f)
        val minDelta = safeTop - currentTop
        val maxDelta = safeBottom - currentBottom
        val delta = if (minDelta > maxDelta) {
            // Safe range is smaller than widget block height; prefer keeping lower row visible.
            maxDelta
        } else {
            (targetCenterY - currentCenterY).coerceIn(minDelta, maxDelta)
        }
        binding.homeQuickWidgetsGrid.translationY = delta
    }

    private fun applyNavSignal(navView: View, dotView: View, active: Boolean) {
        dotView.visibility = if (active) View.VISIBLE else View.GONE
        navView.alpha = if (active) 1f else 0.50f
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

        val locatorState = LocatorCapabilityViewModel.resolve(this)
        latestLocatorState = locatorState
        if (locatorState.enabled) {
            when {
                !locatorState.hasProvidersConfigured -> {
                    score -= 10
                    details += getString(R.string.security_detail_locator_not_configured)
                    addAction(
                        SecurityActionRoute.OPEN_DEVICE_LOCATOR_SETUP,
                        R.string.security_action_setup_locator
                    )
                }
                !locatorState.hasLaunchableProvider -> {
                    score -= 10
                    details += getString(R.string.security_detail_locator_unavailable)
                    addAction(
                        SecurityActionRoute.OPEN_DEVICE_LOCATOR_SETUP,
                        R.string.security_action_setup_locator
                    )
                }
                !locatorState.primaryEmailLinked -> {
                    score -= 6
                    details += getString(R.string.security_detail_locator_not_linked)
                    addAction(
                        SecurityActionRoute.OPEN_DEVICE_LOCATOR_SETUP,
                        R.string.security_action_setup_locator
                    )
                }
                locatorState.providers.none { it.packageLaunchReady || it.deepLinkReady } -> {
                    score -= 3
                    details += getString(R.string.security_detail_locator_web_only)
                    addAction(
                        SecurityActionRoute.OPEN_DEVICE_LOCATOR_PROVIDER,
                        R.string.security_action_open_locator
                    )
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
            SecurityActionRoute.OPEN_DEVICE_LOCATOR_PROVIDER -> openDeviceLocatorProvider()
            SecurityActionRoute.OPEN_DEVICE_LOCATOR_SETUP -> openDeviceLocatorSetupDialog()
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

    private fun dpToPx(valueDp: Float): Int {
        return (valueDp * resources.displayMetrics.density).toInt()
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
