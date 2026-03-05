package com.realyn.watchdog

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.realyn.watchdog.databinding.ActivityMainBinding
import com.realyn.watchdog.theme.LionThemeCatalog
import com.realyn.watchdog.theme.LionIdentityAccentStyle
import com.realyn.watchdog.theme.LionThemePalette
import com.realyn.watchdog.theme.LionThemeViewStyler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_GUARDIAN_SETTINGS_ACTION = "com.realyn.watchdog.extra.GUARDIAN_SETTINGS_ACTION"
        const val GUARDIAN_SETTINGS_ACTION_OPEN_PLAN_BILLING = "open_plan_billing"
        const val GUARDIAN_SETTINGS_ACTION_OPEN_AI_CONNECTION = "open_ai_connection"
        const val GUARDIAN_SETTINGS_ACTION_OPEN_LOCATOR_SETUP = "open_locator_setup"
        const val GUARDIAN_SETTINGS_ACTION_OPEN_HOME_RISK_SETUP = "open_home_risk_setup"
        const val GUARDIAN_SETTINGS_ACTION_OPEN_VPN_SETUP = "open_vpn_setup"
        const val GUARDIAN_SETTINGS_ACTION_OPEN_TUTORIAL = "open_tutorial"

        private const val UI_PREFS_FILE = "dt_ui_prefs"
        private const val KEY_HOME_INTRO_SHOWN = "home_intro_shown_v4"
        private const val KEY_HOME_INTRO_REPLAY_EVERY_LAUNCH = "home_intro_replay_every_launch_v1"
        private const val KEY_HOME_INTRO_WIDGET_MOTION = "home_intro_widget_motion_v1"
        private const val KEY_HOME_TUTORIAL_POPUP_SHOWN = "home_tutorial_popup_shown_v1"
        private const val KEY_HOME_TUTORIAL_COMPLETED = "home_tutorial_completed_v1"
        private const val HOME_SURFACE_CACHE_PREFS_FILE = "dt_home_surface_cache"
        private const val HOME_SURFACE_CACHE_PAYLOAD_KEY = "home_surface_snapshot_payload_v1"
        private const val HOME_SURFACE_CACHE_VERSION = 1
        private const val STARTUP_TRACE_TAG = "DTStartup"
        // Based on grade 4-5 oral reading fluency medians (~120-146 WPM).
        private const val INTRO_WELCOME_READING_WPM = 130f
        private const val INTRO_WIDGET_LAUNCH_DURATION_MS = 780L
        private const val INTRO_WIDGET_LAUNCH_STAGGER_MS = 120L
        private const val INTRO_WIDGET_ARC_HEIGHT_DP = 210f
        private const val INTRO_WIDGET_ARC_DENSITY_DP = 28f
        private const val HOME_WIDGET_PAGE_COUNT = 2
        private const val HOME_NAV_ICON_PAGE_COUNT = 2
        private const val SWIPE_TRIGGER_DP = 24f
        private const val SWIPE_DIRECTION_BIAS = 1.2f
        private const val LION_PROCESSING_INITIAL_PROGRESS = 0.06f
        private const val LION_PROCESSING_TARGET_PROGRESS = 0.94f
        private const val LION_PROCESSING_PROGRESS_MS_PER_POINT = 2000L
        private const val LION_PROCESSING_STEP_MIN_MS = 180L
        private const val LION_PROCESSING_STEP_MAX_MS = 920L
        private const val LION_PROCESSING_COMPLETE_ANIM_MS = 420L
        private const val LION_MIN_BUSY_VISUAL_MS_DEFAULT = 1800L
        private const val LION_MIN_BUSY_VISUAL_MS_QUICK_SCAN = 4200L
        private const val LION_MIN_BUSY_VISUAL_MS_DEEP_SCAN_BASE = 6800L
        private const val LION_MIN_BUSY_VISUAL_MS_DEEP_SCAN_PER_MODULE = 1400L
        private const val LION_MIN_BUSY_VISUAL_MS_DEEP_SCAN_MAX = 12000L
        private const val LION_SCAN_COMPLETE_HOLD_MS = 2800L
        private const val SCAN_TERMINAL_UPDATE_MIN_INTERVAL_MS = 120L

        fun isHomeIntroReplayEveryLaunch(context: Context): Boolean {
            return context.getSharedPreferences(UI_PREFS_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_HOME_INTRO_REPLAY_EVERY_LAUNCH, false)
        }

        fun setHomeIntroReplayEveryLaunch(context: Context, enabled: Boolean) {
            context.getSharedPreferences(UI_PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_HOME_INTRO_REPLAY_EVERY_LAUNCH, enabled)
                .apply()
        }

        fun isHomeIntroWidgetMotionEnabled(context: Context): Boolean {
            return context.getSharedPreferences(UI_PREFS_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_HOME_INTRO_WIDGET_MOTION, true)
        }

        fun setHomeIntroWidgetMotionEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(UI_PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_HOME_INTRO_WIDGET_MOTION, enabled)
                .apply()
        }
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

    private enum class HomeTutorialMode {
        GUIDED,
        LEARN_BY_DOING
    }

    private enum class NavSlotPosition {
        LEFT_OUTER,
        LEFT_INNER,
        RIGHT_INNER,
        RIGHT_OUTER
    }

    private enum class HomeNavDestination {
        SWEEP,
        THREATS,
        CREDENTIALS,
        SERVICES,
        HOME_RISK,
        VPN,
        DIGITAL_KEY,
        TIMELINE
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

    private data class DeepScanSelection(
        val includeStartupPersistenceSweep: Boolean,
        val includeStorageArtifactSweep: Boolean,
        val includeEmbeddedPathProbe: Boolean,
        val includeWifiPostureSweep: Boolean
    ) {
        fun hasAnyDeepModule(): Boolean {
            return includeStartupPersistenceSweep ||
                includeStorageArtifactSweep ||
                includeEmbeddedPathProbe
        }
    }

    private data class HomeRiskLookupResult(
        val posture: SmartHomePostureSnapshot? = null,
        val errorRes: Int = 0,
        val errorMessage: String = ""
    )

    private enum class HomeRiskSetupStatus {
        READY,
        ROLLOUT_DISABLED,
        CONNECTOR_UNAVAILABLE,
        CONSENT_FAILED
    }

    private data class HomeRiskSetupResult(
        val status: HomeRiskSetupStatus,
        val connectorLabel: String = "",
        val connectorId: String = "",
        val ownerRole: String = "",
        val healthStatus: String = "",
        val healthLastError: String = "",
        val readOnlyMode: Boolean = true,
        val scopeCount: Int = 0,
        val consentIssued: Boolean = false,
        val rolloutStage: String = "",
        val rolloutPercent: Int = 0,
        val errorMessage: String = ""
    )

    private data class VpnStatusLookupResult(
        val assertion: VpnAssertionState,
        val providerId: String,
        val providerLabel: String,
        val state: String,
        val details: String,
        val checkedAtIso: String,
        val reasonCode: String,
        val brokerNotice: String,
        val providerDataNotice: String,
        val paidTierNotice: String,
        val paidRequired: Boolean
    )

    private enum class VpnSetupStatus {
        READY,
        ROLLOUT_DISABLED,
        CONNECTOR_UNAVAILABLE,
        CONSENT_FAILED,
        PAID_TIER_REQUIRED,
        LAUNCH_FAILED
    }

    private data class VpnSetupResult(
        val status: VpnSetupStatus,
        val providerId: String = "",
        val providerLabel: String = "",
        val assertion: VpnAssertionState = VpnAssertionState.UNKNOWN,
        val state: String = "",
        val details: String = "",
        val checkedAtIso: String = "",
        val launchMode: String = "",
        val ownerRole: String = "",
        val rolloutStage: String = "",
        val rolloutPercent: Int = 0,
        val paidTierRequired: Boolean = false,
        val brokerNotice: String = "",
        val providerDataNotice: String = "",
        val paidTierNotice: String = "",
        val errorMessage: String = ""
    )

    private data class GuidedSuggestion(
        val destination: GuidedDestination,
        val destinationLabel: String,
        val bodyText: String
    )

    private data class HomeTutorialStep(
        val stepId: String,
        val titleRes: Int,
        val bodyRes: Int,
        val hintRes: Int,
        val targetViewProvider: () -> View,
        val widgetPageIndex: Int? = null,
        val navPageIndex: Int? = null
    )

    private data class HomeViewportProfile(
        val compactHeight: Boolean,
        val compactWidth: Boolean,
        val foldableLayout: Boolean,
        val tabletLayout: Boolean
    )

    private data class HomeSurfaceSnapshot(
        val capturedAtEpochMs: Long,
        val localeTag: String,
        val securityScoreLabel: String,
        val securityTierLabel: String,
        val securityUrgentActionsLabel: String,
        val widgetSweepValue: String,
        val widgetSweepHint: String,
        val widgetThreatsValue: String,
        val widgetThreatsHint: String,
        val widgetCredentialsValue: String,
        val widgetCredentialsHint: String,
        val widgetServicesValue: String,
        val widgetServicesHint: String,
        val widgetHomeRiskValue: String,
        val widgetHomeRiskHint: String,
        val widgetVpnValue: String,
        val widgetVpnHint: String,
        val widgetDigitalKeyValue: String,
        val widgetDigitalKeyHint: String,
        val widgetTimelineValue: String,
        val widgetTimelineHint: String,
        val navSignals: Map<HomeNavDestination, Boolean>,
        val heroState: SecurityHeroState? = null
    )

    private data class HomeSurfaceInputs(
        val notificationRequired: Boolean,
        val notificationReady: Boolean,
        val overlayReady: Boolean,
        val activeModeOn: Boolean,
        val lastScan: String,
        val wifi: WifiScanSnapshotRecord?,
        val latestThreat: PhishingIntakeEntry?,
        val phishingHigh12: Int,
        val phishingMedium12: Int,
        val phishingHigh20: Int,
        val phishingMedium20: Int,
        val pendingQueue: Int,
        val linkedEmail: Boolean,
        val access: ResolvedFeatureAccess,
        val incidentSummary: IncidentSummary,
        val meshState: IntegrationMeshController.IntegrationMeshControllerState,
        val homeRiskEnabled: Boolean,
        val homeRiskConsent: ConnectorConsentArtifact?,
        val vpnEnabled: Boolean,
        val vpnConsent: ConnectorConsentArtifact?,
        val vpnAssertion: VpnStatusAssertion,
        val digitalKeyEnabled: Boolean,
        val rootPosture: RootPosture,
        val guardianEntries: List<GuardianAlertEntry>,
        val connectorEvents: List<ConnectorAuditEvent>,
        val locatorState: LocatorCapabilityState
    )

    private data class ScanReviewPayload(
        val modeLabel: String,
        val summaryLine: String,
        val scopeSummary: String,
        val reportText: String,
        val completedAtEpochMs: Long,
        val highCount: Int,
        val mediumCount: Int,
        val lowCount: Int,
        val infoCount: Int,
        val maintenancePayloadJson: String = ""
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
    private var pendingDeepScanSelectionForMediaPermission: DeepScanSelection? = null
    private var pendingVaultExportItemId: String = ""
    private var pendingUnifiedEvidenceReport: String = ""
    private var lionFillMode: LionFillMode = LionFillMode.LEFT_TO_RIGHT
    private var lionBusyInProgress: Boolean = false
    private var lionProgressAnimator: ValueAnimator? = null
    private var lionCompletionDelayRunnable: Runnable? = null
    private var lionIdleResetRunnable: Runnable? = null
    private var lionBusyStartedAtMs: Long = 0L
    private var lionMinBusyVisualMs: Long = LION_MIN_BUSY_VISUAL_MS_DEFAULT
    private var lionAnimationSessionId: Int = 0
    private var scanTerminalEnabled: Boolean = false
    private var scanTerminalLastLine: String = ""
    private var scanTerminalLastRenderedAtMs: Long = 0L
    private var homeIntroHandledThisSession: Boolean = false
    private var homeIntroAnimating: Boolean = false
    private var introWordAnimator: ValueAnimator? = null
    private var introWipeAnimator: ValueAnimator? = null
    private var introSequenceRunnable: Runnable? = null
    private var widgetLaunchAnimatorSet: AnimatorSet? = null
    private var widgetHeartbeatAnimatorSet: AnimatorSet? = null
    private var navRippleAnimatorSet: AnimatorSet? = null
    private var latestSystemBarInsets: Insets = Insets.NONE
    private var activeHomePalette: LionThemePalette? = null
    private var pendingGuardianSettingsAction: String? = null
    private var homeWidgetPageIndex: Int = 0
    private var navIconPageIndex: Int = 0
    private var widgetPageAnimatorSet: AnimatorSet? = null
    private var navPageAnimatorSet: AnimatorSet? = null
    private val touchStartByViewId = mutableMapOf<Int, Pair<Float, Float>>()
    private val swipeConsumedViewIds = mutableSetOf<Int>()
    private var hasResumedOnce: Boolean = false
    private var latestHomeSurfaceSnapshot: HomeSurfaceSnapshot? = null
    private var latestHomeNavSignals: Map<HomeNavDestination, Boolean> = emptyMap()
    private var homeSurfaceHydrationJob: Job? = null
    private var homeSurfaceHydrationInFlight: Boolean = false
    private var homeSurfaceHydrationNonce: Long = 0L
    private var startupTraceStartMs: Long = 0L
    private val startupTraceMarks = linkedMapOf<String, Long>()
    private var startupFreshHydrationReady: Boolean = false
    private var startupReadyReported: Boolean = false
    private var homeTutorialActive: Boolean = false
    private var homeTutorialMode: HomeTutorialMode? = null
    private var homeTutorialSteps: List<HomeTutorialStep> = emptyList()
    private var homeTutorialStepIndex: Int = 0
    private var homeTutorialTargetTouched: Boolean = false
    private val tutorialOverlayLocation = IntArray(2)
    private val tutorialTargetLocation = IntArray(2)

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
                    refreshUiState()
                    return@registerForActivityResult
                }
                runWifiPostureScan()
            }
        }

    private val deepStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val pendingSelection = pendingDeepScanSelectionForMediaPermission ?: return@registerForActivityResult
            pendingDeepScanSelectionForMediaPermission = null
            if (requiredDeepStorageMediaPermissions().isNotEmpty()) {
                binding.subStatusLabel.text = getString(R.string.deep_scan_storage_permission_limited_status)
            }
            maybePromptAllFilesAccessForDeepScan(pendingSelection)
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

    private val unifiedReportExportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val destination = if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data
            } else {
                null
            }
            completePendingUnifiedReportExport(destination)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        beginStartupTrace()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingGuardianSettingsAction = intent?.getStringExtra(EXTRA_GUARDIAN_SETTINGS_ACTION)
        configureResponsiveHomeLayout()

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
        binding.deviceLocatorOpenButton.setOnClickListener { openDeviceLocatorProvider() }
        binding.deviceLocatorSetupButton.setOnClickListener { openDeviceLocatorSetupDialog() }
        binding.securityTopActionButton.setOnClickListener { runOneTimeScan() }
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
        binding.navScanButton.setOnClickListener { routeNavSlot(NavSlotPosition.LEFT_OUTER) }
        binding.navGuardButton.setOnClickListener { routeNavSlot(NavSlotPosition.LEFT_INNER) }
        binding.navLionButton.setOnClickListener { openLionNavDialog() }
        binding.navLionButton.setOnLongClickListener {
            openHomeRiskEntryPoint()
            true
        }
        binding.navVaultButton.setOnClickListener { routeNavSlot(NavSlotPosition.RIGHT_INNER) }
        binding.navSupportButton.setOnClickListener { routeNavSlot(NavSlotPosition.RIGHT_OUTER) }
        binding.widgetSweepCard.setOnClickListener { routeWidgetSlot(NavSlotPosition.LEFT_OUTER) }
        binding.widgetThreatsCard.setOnClickListener { routeWidgetSlot(NavSlotPosition.LEFT_INNER) }
        binding.widgetCredentialsCard.setOnClickListener { routeWidgetSlot(NavSlotPosition.RIGHT_INNER) }
        binding.widgetServicesCard.setOnClickListener { routeWidgetSlot(NavSlotPosition.RIGHT_OUTER) }
        binding.goProButton.setOnClickListener { openPlanSelectionDialog() }
        binding.lionModeToggleButton.setOnClickListener { openGuardianSettingsDialog() }
        binding.homeTutorialBackButton.setOnClickListener { moveHomeTutorialStep(-1) }
        binding.homeTutorialSkipButton.setOnClickListener { finishHomeTutorial(completed = false) }
        binding.homeTutorialActionButton.setOnClickListener { handleHomeTutorialActionPressed() }
        binding.homeTutorialCoachView.onHighlightTapped = {
            onHomeTutorialTargetTapped()
        }

        lionFillMode = LionThemePrefs.readFillMode(this)
        refreshLionHeroVisuals()
        binding.lionHeroView.setIdleState()
        setScanTerminalEnabled(false)
        binding.bottomNavCard.visibility = View.GONE
        applyMinimalHomeSurface()
        configureHomePagingState()
        applyCachedHomeSurfaceSnapshot()
        setupDepthMotionSystem()

        maybeRequestNotificationPermission()
        applyAdvancedControlsVisibility()
        markStartupTrace("access_gate_requested")
        enforceAccessGate()

        IntegrationMeshController.initialize(this)
    }

    override fun onResume() {
        super.onResume()
        if (!hasResumedOnce) {
            hasResumedOnce = true
            recoverBottomNavIfNeeded()
            return
        }
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
        homeSurfaceHydrationJob?.cancel()
        homeSurfaceHydrationJob = null
        binding.homeIntroOverlay.animate().cancel()
        binding.introLionHero.animate().cancel()
        binding.introTitleLabel.animate().cancel()
        binding.introWelcomeLabel.animate().cancel()
        binding.introCelebrationView.stopCelebration()
        binding.widgetTrailView.clear()
        widgetPageAnimatorSet?.cancel()
        widgetPageAnimatorSet = null
        navPageAnimatorSet?.cancel()
        navPageAnimatorSet = null
        allHomeWidgetCards().forEach { it.animate().cancel() }
        homeNavButtons().forEach { it.animate().cancel() }
        binding.navLionButton.animate().cancel()
        binding.bottomNavCard.animate().cancel()
        binding.homeTutorialCoachView.clearHighlight()
        binding.homeTutorialOverlay.visibility = View.GONE
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        AppAccessGate.onUserInteraction()
    }

    private fun setupDepthMotionSystem() {
        installDepthTouchFeedback()
    }

    private fun configureHomePagingState() {
        homeWidgetPageIndex = 0
        navIconPageIndex = 0
        applyWidgetPage(pageIndex = homeWidgetPageIndex, animate = false)
        applyNavIconPage(pageIndex = navIconPageIndex, animate = false)
    }

    private fun installDepthTouchFeedback() {
        depthInteractiveViews().forEach { target ->
            target.setOnTouchListener { view, event ->
                if (homeIntroAnimating) {
                    return@setOnTouchListener false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartByViewId[view.id] = event.rawX to event.rawY
                        swipeConsumedViewIds.remove(view.id)
                        applyDepthTouchDown(view, event.x, event.y)
                        false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        applyDepthTouchDown(view, event.x, event.y)
                        tryHandleHorizontalSwipe(view, event)
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        val consumedBySwipe = swipeConsumedViewIds.remove(view.id)
                        clearDepthTouchFeedback(view)
                        touchStartByViewId.remove(view.id)
                        consumedBySwipe
                    }

                    else -> false
                }
            }
        }
    }

    private fun tryHandleHorizontalSwipe(view: View, event: MotionEvent): Boolean {
        if (swipeConsumedViewIds.contains(view.id)) {
            return true
        }
        val start = touchStartByViewId[view.id] ?: return false
        val deltaX = event.rawX - start.first
        val deltaY = event.rawY - start.second
        val absX = abs(deltaX)
        val absY = abs(deltaY)
        val swipeThresholdPx = dpToPx(SWIPE_TRIGGER_DP).toFloat()
        if (absX < swipeThresholdPx || absX < (absY * SWIPE_DIRECTION_BIAS)) {
            return false
        }
        val forward = if (deltaX < 0f) 1 else -1
        val handled = when {
            isWidgetSwipeView(view.id) -> shiftWidgetPage(forward, animate = true)
            isNavSwipeView(view.id) -> shiftNavIconPage(forward, animate = true)
            else -> false
        }
        if (handled) {
            swipeConsumedViewIds += view.id
            clearDepthTouchFeedback(view)
        }
        return handled
    }

    private fun isWidgetSwipeView(viewId: Int): Boolean {
        return when (viewId) {
            R.id.widgetSweepCard,
            R.id.widgetThreatsCard,
            R.id.widgetCredentialsCard,
            R.id.widgetServicesCard -> true
            else -> false
        }
    }

    private fun isNavSwipeView(viewId: Int): Boolean {
        return when (viewId) {
            R.id.navScanButton,
            R.id.navGuardButton,
            R.id.navVaultButton,
            R.id.navSupportButton -> true
            else -> false
        }
    }

    private fun shiftWidgetPage(direction: Int, animate: Boolean): Boolean {
        val target = (homeWidgetPageIndex + direction).coerceIn(0, HOME_WIDGET_PAGE_COUNT - 1)
        if (target == homeWidgetPageIndex) {
            return false
        }
        applyWidgetPage(pageIndex = target, animate = animate)
        return true
    }

    private fun shiftNavIconPage(direction: Int, animate: Boolean): Boolean {
        val target = (navIconPageIndex + direction).coerceIn(0, HOME_NAV_ICON_PAGE_COUNT - 1)
        if (target == navIconPageIndex) {
            return false
        }
        applyNavIconPage(pageIndex = target, animate = animate)
        return true
    }

    private fun applyWidgetPage(pageIndex: Int, animate: Boolean) {
        val clamped = pageIndex.coerceIn(0, HOME_WIDGET_PAGE_COUNT - 1)
        val previous = homeWidgetPageIndex
        val forward = clamped > previous
        val cards = homeWidgetCards()
        binding.widgetSecondaryRowTop.visibility = View.GONE
        binding.widgetSecondaryRowBottom.visibility = View.GONE
        widgetPageAnimatorSet?.cancel()
        if (!animate || previous == clamped) {
            homeWidgetPageIndex = clamped
            applyWidgetLabelsForPage(clamped)
            binding.root.post { alignHomeWidgetsBetweenTitleAndScan() }
            return
        }
        val shiftPx = dpToPx(14f).toFloat() * if (forward) -1f else 1f
        val fadeOut = AnimatorSet().apply {
            playTogether(
                cards.map { card ->
                    ObjectAnimator.ofPropertyValuesHolder(
                        card,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, shiftPx)
                    )
                }
            )
            duration = 140L
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    homeWidgetPageIndex = clamped
                    applyWidgetLabelsForPage(clamped)
                    cards.forEach { card ->
                        card.alpha = 0f
                        card.translationX = -shiftPx
                    }
                }
            })
        }
        val fadeIn = AnimatorSet().apply {
            playTogether(
                cards.map { card ->
                    ObjectAnimator.ofPropertyValuesHolder(
                        card,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -shiftPx, 0f)
                    )
                }
            )
            duration = 180L
            interpolator = DecelerateInterpolator()
        }
        widgetPageAnimatorSet = AnimatorSet().apply {
            playSequentially(fadeOut, fadeIn)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    cards.forEach { card ->
                        card.alpha = 1f
                        card.translationX = 0f
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    widgetPageAnimatorSet = null
                    cards.forEach { card ->
                        card.alpha = 1f
                        card.translationX = 0f
                    }
                    binding.root.post { alignHomeWidgetsBetweenTitleAndScan() }
                }
            })
            start()
        }
    }

    private fun applyWidgetLabelsForPage(pageIndex: Int) {
        applyWidgetSlotVisual(
            slotPosition = NavSlotPosition.LEFT_OUTER,
            card = binding.widgetSweepCard,
            icon = binding.widgetSweepIcon,
            title = binding.widgetSweepTitle,
            value = binding.widgetSweepValue,
            hint = binding.widgetSweepHint,
            pageIndex = pageIndex
        )
        applyWidgetSlotVisual(
            slotPosition = NavSlotPosition.LEFT_INNER,
            card = binding.widgetThreatsCard,
            icon = binding.widgetThreatsIcon,
            title = binding.widgetThreatsTitle,
            value = binding.widgetThreatsValue,
            hint = binding.widgetThreatsHint,
            pageIndex = pageIndex
        )
        applyWidgetSlotVisual(
            slotPosition = NavSlotPosition.RIGHT_INNER,
            card = binding.widgetCredentialsCard,
            icon = binding.widgetCredentialsIcon,
            title = binding.widgetCredentialsTitle,
            value = binding.widgetCredentialsValue,
            hint = binding.widgetCredentialsHint,
            pageIndex = pageIndex
        )
        applyWidgetSlotVisual(
            slotPosition = NavSlotPosition.RIGHT_OUTER,
            card = binding.widgetServicesCard,
            icon = binding.widgetServicesIcon,
            title = binding.widgetServicesTitle,
            value = binding.widgetServicesValue,
            hint = binding.widgetServicesHint,
            pageIndex = pageIndex
        )
    }

    private fun applyWidgetSlotVisual(
        slotPosition: NavSlotPosition,
        card: View,
        icon: ImageView,
        title: TextView,
        value: TextView,
        hint: TextView,
        pageIndex: Int
    ) {
        val destination = destinationForSlot(pageIndex, slotPosition)
        val labelRes = labelResForDestination(destination)
        val snapshot = latestHomeSurfaceSnapshot
        icon.setImageResource(iconResForDestination(destination))
        icon.contentDescription = getString(labelRes)
        title.setText(labelRes)
        value.text = widgetValueForDestination(snapshot, destination)
        hint.text = widgetHintForDestination(snapshot, destination)
        configureWidgetHintTicker(hint, destination)
        card.contentDescription = getString(labelRes)
    }

    private fun configureWidgetHintTicker(hint: TextView, destination: HomeNavDestination) {
        val enableTicker = destination == HomeNavDestination.SWEEP
        if (enableTicker) {
            hint.isSingleLine = true
            hint.ellipsize = TextUtils.TruncateAt.MARQUEE
            hint.marqueeRepeatLimit = -1
            hint.isSelected = true
            hint.setHorizontallyScrolling(true)
            hint.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            hint.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            return
        }
        hint.isSingleLine = true
        hint.ellipsize = TextUtils.TruncateAt.END
        hint.marqueeRepeatLimit = 0
        hint.isSelected = false
        hint.setHorizontallyScrolling(false)
        hint.gravity = Gravity.CENTER
        hint.textAlignment = View.TEXT_ALIGNMENT_CENTER
    }

    private fun widgetValueForDestination(
        snapshot: HomeSurfaceSnapshot?,
        destination: HomeNavDestination
    ): String {
        if (snapshot == null) {
            return getString(R.string.home_widget_loading)
        }
        val value = when (destination) {
            HomeNavDestination.SWEEP -> snapshot.widgetSweepValue
            HomeNavDestination.THREATS -> snapshot.widgetThreatsValue
            HomeNavDestination.CREDENTIALS -> snapshot.widgetCredentialsValue
            HomeNavDestination.SERVICES -> snapshot.widgetServicesValue
            HomeNavDestination.HOME_RISK -> snapshot.widgetHomeRiskValue
            HomeNavDestination.VPN -> snapshot.widgetVpnValue
            HomeNavDestination.DIGITAL_KEY -> snapshot.widgetDigitalKeyValue
            HomeNavDestination.TIMELINE -> snapshot.widgetTimelineValue
        }
        return value.ifBlank { getString(R.string.home_widget_loading) }
    }

    private fun widgetHintForDestination(
        snapshot: HomeSurfaceSnapshot?,
        destination: HomeNavDestination
    ): String {
        if (snapshot == null) {
            return getString(R.string.home_widget_loading)
        }
        val value = when (destination) {
            HomeNavDestination.SWEEP -> snapshot.widgetSweepHint
            HomeNavDestination.THREATS -> snapshot.widgetThreatsHint
            HomeNavDestination.CREDENTIALS -> snapshot.widgetCredentialsHint
            HomeNavDestination.SERVICES -> snapshot.widgetServicesHint
            HomeNavDestination.HOME_RISK -> snapshot.widgetHomeRiskHint
            HomeNavDestination.VPN -> snapshot.widgetVpnHint
            HomeNavDestination.DIGITAL_KEY -> snapshot.widgetDigitalKeyHint
            HomeNavDestination.TIMELINE -> snapshot.widgetTimelineHint
        }
        return value.ifBlank { getString(R.string.home_widget_loading) }
    }

    private fun applyNavIconPage(pageIndex: Int, animate: Boolean) {
        val clamped = pageIndex.coerceIn(0, HOME_NAV_ICON_PAGE_COUNT - 1)
        val previous = navIconPageIndex
        val forward = clamped > previous
        val slots = navSlotViews()
        navPageAnimatorSet?.cancel()
        if (!animate || previous == clamped) {
            navIconPageIndex = clamped
            applyNavIconLabelsForPage(clamped)
            refreshBottomNavIndicators()
            return
        }
        val shiftPx = dpToPx(14f).toFloat() * if (forward) -1f else 1f
        val fadeOut = AnimatorSet().apply {
            playTogether(
                slots.map { slot ->
                    ObjectAnimator.ofPropertyValuesHolder(
                        slot,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, shiftPx)
                    )
                }
            )
            duration = 140L
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    navIconPageIndex = clamped
                    applyNavIconLabelsForPage(clamped)
                    slots.forEach { slot ->
                        slot.alpha = 0f
                        slot.translationX = -shiftPx
                    }
                }
            })
        }
        val fadeIn = AnimatorSet().apply {
            playTogether(
                slots.map { slot ->
                    ObjectAnimator.ofPropertyValuesHolder(
                        slot,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -shiftPx, 0f)
                    )
                }
            )
            duration = 180L
            interpolator = DecelerateInterpolator()
        }
        navPageAnimatorSet = AnimatorSet().apply {
            playSequentially(fadeOut, fadeIn)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    slots.forEach { slot ->
                        slot.alpha = 1f
                        slot.translationX = 0f
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    navPageAnimatorSet = null
                    slots.forEach { slot ->
                        slot.alpha = 1f
                        slot.translationX = 0f
                    }
                    refreshBottomNavIndicators()
                }
            })
            start()
        }
    }

    private fun navSlotViews(): List<View> {
        return listOf(
            binding.navScanButton,
            binding.navGuardButton,
            binding.navVaultButton,
            binding.navSupportButton
        )
    }

    private fun applyNavIconLabelsForPage(pageIndex: Int) {
        applyNavSlotVisual(
            slotPosition = NavSlotPosition.LEFT_OUTER,
            button = binding.navScanButton,
            icon = binding.navScanIcon,
            label = binding.navScanLabel,
            pageIndex = pageIndex
        )
        applyNavSlotVisual(
            slotPosition = NavSlotPosition.LEFT_INNER,
            button = binding.navGuardButton,
            icon = binding.navGuardIcon,
            label = binding.navGuardLabel,
            pageIndex = pageIndex
        )
        applyNavSlotVisual(
            slotPosition = NavSlotPosition.RIGHT_INNER,
            button = binding.navVaultButton,
            icon = binding.navVaultIcon,
            label = binding.navVaultLabel,
            pageIndex = pageIndex
        )
        applyNavSlotVisual(
            slotPosition = NavSlotPosition.RIGHT_OUTER,
            button = binding.navSupportButton,
            icon = binding.navSupportIcon,
            label = binding.navSupportLabel,
            pageIndex = pageIndex
        )
    }

    private fun applyNavSlotVisual(
        slotPosition: NavSlotPosition,
        button: LinearLayout,
        icon: ImageView,
        label: TextView,
        pageIndex: Int
    ) {
        val destination = destinationForSlot(pageIndex, slotPosition)
        val labelRes = labelResForDestination(destination)
        icon.setImageResource(iconResForDestination(destination))
        icon.contentDescription = getString(labelRes)
        label.setText(labelRes)
        button.contentDescription = getString(labelRes)
    }

    private fun routeNavSlot(slot: NavSlotPosition) {
        val destination = destinationForSlot(navIconPageIndex, slot)
        routeHomeDestination(destination)
    }

    private fun routeWidgetSlot(slot: NavSlotPosition) {
        val destination = destinationForSlot(homeWidgetPageIndex, slot)
        routeHomeDestination(destination)
    }

    private fun routeHomeDestination(destination: HomeNavDestination) {
        when (destination) {
            HomeNavDestination.SWEEP -> runOneTimeScan()
            HomeNavDestination.THREATS -> openPhishingTriageDialog()
            HomeNavDestination.CREDENTIALS -> openCredentialCenter()
            HomeNavDestination.SERVICES -> openSecurityDetailsDialog()
            HomeNavDestination.HOME_RISK -> openHomeRiskEntryPoint()
            HomeNavDestination.VPN -> openVpnEntryPoint()
            HomeNavDestination.DIGITAL_KEY -> openDigitalKeyGuardrailsDialog()
            HomeNavDestination.TIMELINE -> openTimelineReportDialog()
        }
    }

    private fun destinationForSlot(pageIndex: Int, slot: NavSlotPosition): HomeNavDestination {
        return if (pageIndex == 0) {
            when (slot) {
                NavSlotPosition.LEFT_OUTER -> HomeNavDestination.SWEEP
                NavSlotPosition.LEFT_INNER -> HomeNavDestination.THREATS
                NavSlotPosition.RIGHT_INNER -> HomeNavDestination.CREDENTIALS
                NavSlotPosition.RIGHT_OUTER -> HomeNavDestination.SERVICES
            }
        } else {
            when (slot) {
                NavSlotPosition.LEFT_OUTER -> HomeNavDestination.HOME_RISK
                NavSlotPosition.LEFT_INNER -> HomeNavDestination.VPN
                NavSlotPosition.RIGHT_INNER -> HomeNavDestination.DIGITAL_KEY
                NavSlotPosition.RIGHT_OUTER -> HomeNavDestination.TIMELINE
            }
        }
    }

    private fun labelResForDestination(destination: HomeNavDestination): Int {
        return when (destination) {
            HomeNavDestination.SWEEP -> R.string.nav_scan
            HomeNavDestination.THREATS -> R.string.nav_guard
            HomeNavDestination.CREDENTIALS -> R.string.nav_vault
            HomeNavDestination.SERVICES -> R.string.nav_support
            HomeNavDestination.HOME_RISK -> R.string.home_widget_home_risk_title
            HomeNavDestination.VPN -> R.string.home_widget_vpn_title
            HomeNavDestination.DIGITAL_KEY -> R.string.home_widget_digital_key_title
            HomeNavDestination.TIMELINE -> R.string.home_widget_timeline_title
        }
    }

    private fun iconResForDestination(destination: HomeNavDestination): Int {
        return when (destination) {
            HomeNavDestination.SWEEP -> R.drawable.ic_nav_scan
            HomeNavDestination.THREATS -> R.drawable.ic_nav_guard
            HomeNavDestination.CREDENTIALS -> R.drawable.ic_nav_credentials
            HomeNavDestination.SERVICES -> R.drawable.ic_nav_services
            HomeNavDestination.HOME_RISK -> R.drawable.ic_shield
            HomeNavDestination.VPN -> R.drawable.ic_nav_services
            HomeNavDestination.DIGITAL_KEY -> R.drawable.ic_nav_guard
            HomeNavDestination.TIMELINE -> R.drawable.ic_nav_scan
        }
    }

    private fun buildNavSignalMap(): Map<HomeNavDestination, Boolean> {
        if (latestHomeNavSignals.isNotEmpty()) {
            return latestHomeNavSignals
        }
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
        val meshState = IntegrationMeshController.snapshot(this)
        val homeRiskEnabled = IntegrationMeshController.isModuleEnabled(this, IntegrationMeshModule.SMART_HOME_CONNECTOR)
        val homeRiskConsent = IntegrationMeshAuditStore.latestActiveConsent(
            context = this,
            connectorId = meshState.smartHomeConnectorId,
            ownerId = meshState.ownerId
        )
        val vpnEnabled = IntegrationMeshController.isModuleEnabled(this, IntegrationMeshModule.VPN_PROVIDER_CONNECTOR)
        val vpnConsent = IntegrationMeshAuditStore.latestActiveConsent(
            context = this,
            connectorId = meshState.vpnConnectorId,
            ownerId = meshState.ownerId
        )
        val vpnAssertion = VpnStatusAssertions.resolveCached(this)
        val digitalKeyEnabled = IntegrationMeshController.isModuleEnabled(
            this,
            IntegrationMeshModule.DIGITAL_KEY_RISK_ADAPTER
        )
        val rootRisk = SecurityScanner.currentRootPosture(this).riskTier
        val guardianEntries = GuardianAlertStore.readRecent(this, limit = 20)
        val connectorEvents = IntegrationMeshAuditStore.readRecentEvents(this, limit = 20)
        val timelineNeedsAction = guardianEntries.any {
            it.severity == Severity.HIGH || it.severity == Severity.MEDIUM
        } || connectorEvents.any { event ->
            event.riskLevel.equals("high", ignoreCase = true) ||
                event.riskLevel.equals("medium", ignoreCase = true)
        }

        return mapOf(
            HomeNavDestination.SWEEP to scanNeedsAction,
            HomeNavDestination.THREATS to threatNeedsAction,
            HomeNavDestination.CREDENTIALS to queueHasPending,
            HomeNavDestination.SERVICES to serviceNeedsAction,
            HomeNavDestination.HOME_RISK to (!homeRiskEnabled || homeRiskConsent == null),
            HomeNavDestination.VPN to (
                !vpnEnabled ||
                    vpnConsent == null ||
                    vpnAssertion.assertion != VpnAssertionState.CONNECTED
                ),
            HomeNavDestination.DIGITAL_KEY to (
                !digitalKeyEnabled ||
                    rootRisk == RootRiskTier.COMPROMISED ||
                    rootRisk == RootRiskTier.ELEVATED
                ),
            HomeNavDestination.TIMELINE to timelineNeedsAction
        )
    }

    private fun depthInteractiveViews(): List<View> {
        return listOf(
            binding.goProButton,
            binding.lionModeToggleButton,
            binding.widgetSweepCard,
            binding.widgetThreatsCard,
            binding.widgetCredentialsCard,
            binding.widgetServicesCard,
            binding.widgetHomeRiskCard,
            binding.widgetVpnCard,
            binding.widgetDigitalKeyCard,
            binding.widgetTimelineCard,
            binding.securityTopActionButton,
            binding.navScanButton,
            binding.navGuardButton,
            binding.navLionButton,
            binding.navVaultButton,
            binding.navSupportButton
        )
    }

    private fun applyDepthTouchDown(view: View, touchX: Float, touchY: Float) {
        if (swipeConsumedViewIds.contains(view.id)) {
            return
        }
        if (view.width <= 0 || view.height <= 0) {
            return
        }
        val normalizedX = ((touchX / view.width) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
        val normalizedY = ((touchY / view.height) - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
        val shiftPx = dpToPx(if (view === binding.navLionButton) 2.2f else 1.5f).toFloat()
        view.animate().cancel()
        view.scaleX = 0.987f
        view.scaleY = 0.987f
        view.translationX = normalizedX * shiftPx
        view.translationY = normalizedY * shiftPx
        view.translationZ = dpToPx(if (view === binding.navLionButton) 6f else 3f).toFloat()
    }

    private fun clearDepthTouchFeedback(view: View) {
        if (swipeConsumedViewIds.contains(view.id)) {
            view.animate().cancel()
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.translationZ = 0f
            return
        }
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.translationZ = 0f
            }
            .start()
    }

    private fun enforceAccessGate() {
        AppAccessGate.ensureUnlocked(
            activity = this,
            onUnlocked = {
                if (!appUnlockBootstrapDone) {
                    appUnlockBootstrapDone = true
                    flushPendingFeedbackSync()
                }
                markStartupTrace("access_gate_unlocked")
                if (!homeIntroHandledThisSession) {
                    markStartupTrace("intro_requested")
                    maybeRunHomeIntroOnce()
                }
                binding.root.post { refreshUiState() }
                consumePendingGuardianSettingsAction()
                refreshBillingEntitlementIfNeeded(force = false, userVisibleStatus = false)
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

        val navHeightPx = dpToPx(if (profile.compactHeight) 68f else 74f)
        val navBottomMargin = latestSystemBarInsets.bottom + dpToPx(if (profile.compactHeight) 5f else 10f)
        val navHorizontalMargin = dpToPx(if (profile.tabletLayout) 24f else 10f)
        binding.bottomNavCard.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            height = navHeightPx
            leftMargin = navHorizontalMargin + latestSystemBarInsets.left
            rightMargin = navHorizontalMargin + latestSystemBarInsets.right
            bottomMargin = navBottomMargin
        }

        val scanButtonHeightPx = dpToPx(if (profile.compactHeight) 42f else 48f)
        val scanButtonBottomMargin = navBottomMargin + navHeightPx + dpToPx(if (profile.compactHeight) 18f else 18f)
        binding.securityTopActionButton.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            height = scanButtonHeightPx
            leftMargin = navHorizontalMargin + latestSystemBarInsets.left
            rightMargin = navHorizontalMargin + latestSystemBarInsets.right
            bottomMargin = scanButtonBottomMargin
        }
        val heroContainerHeight = (
            resources.displayMetrics.heightPixels -
                (latestSystemBarInsets.top + dpToPx(8f)) -
                scanButtonBottomMargin -
                scanButtonHeightPx -
                dpToPx(if (profile.compactHeight) 20f else 24f)
            )
            .coerceAtLeast(dpToPx(if (profile.compactHeight) 490f else 560f))
            .coerceAtMost(dpToPx(if (profile.tabletLayout) 880f else 700f))
        binding.homeHeroCard.updateLayoutParams<LinearLayout.LayoutParams> {
            height = heroContainerHeight
        }
        binding.homeHeroContent.updateLayoutParams<FrameLayout.LayoutParams> {
            height = FrameLayout.LayoutParams.MATCH_PARENT
        }

        binding.mainScrollView.updatePadding(
            top = latestSystemBarInsets.top + dpToPx(8f),
            bottom = scanButtonBottomMargin + dpToPx(if (profile.compactHeight) 52f else 64f)
        )

        applyHeroViewportProfile(profile)
        binding.homeQuickWidgetsGrid.translationY = 0f
        binding.root.post { alignHomeWidgetsBetweenTitleAndScan() }
    }

    private fun applyHeroViewportProfile(profile: HomeViewportProfile) {
        val heroHeightPx = when {
            profile.tabletLayout -> dpToPx(284f)
            profile.compactHeight -> dpToPx(172f)
            profile.foldableLayout -> dpToPx(238f)
            else -> resources.getDimensionPixelSize(R.dimen.home_lion_hero_height)
        }
        binding.lionHeroView.updateLayoutParams<LinearLayout.LayoutParams> {
            height = heroHeightPx
        }

        val widgetCardHeightPx = when {
            profile.tabletLayout -> dpToPx(100f)
            profile.compactHeight -> dpToPx(84f)
            else -> dpToPx(90f)
        }
        listOf(
            binding.widgetSweepCard,
            binding.widgetThreatsCard,
            binding.widgetCredentialsCard,
            binding.widgetServicesCard,
            binding.widgetHomeRiskCard,
            binding.widgetVpnCard,
            binding.widgetDigitalKeyCard,
            binding.widgetTimelineCard
        ).forEach { card ->
            card.updateLayoutParams<LinearLayout.LayoutParams> {
                height = widgetCardHeightPx
            }
        }

        val introHeroSizePx = when {
            profile.tabletLayout -> dpToPx(390f)
            profile.compactHeight -> dpToPx(252f)
            profile.foldableLayout -> dpToPx(340f)
            else -> resources.getDimensionPixelSize(R.dimen.intro_lion_hero_size)
        }
        binding.introLionHero.updateLayoutParams<FrameLayout.LayoutParams> {
            width = introHeroSizePx
            height = introHeroSizePx
        }

        val welcomeBottomMargin = when {
            profile.tabletLayout -> dpToPx(220f)
            profile.compactHeight -> dpToPx(124f)
            else -> resources.getDimensionPixelSize(R.dimen.home_intro_welcome_bottom_margin)
        }
        val introMessageBottomMargin = (welcomeBottomMargin - dpToPx(48f)).coerceAtLeast(dpToPx(72f))
        binding.introTitleLabel.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = welcomeBottomMargin
        }
        binding.introWelcomeLabel.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = introMessageBottomMargin
        }
        binding.introTitleLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.tabletLayout) 26f else if (profile.compactWidth) 21f else 24f
        )
        binding.introWelcomeLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.tabletLayout) 20f else if (profile.compactWidth) 16f else 18f
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
            if (profile.compactHeight) 18f else 22f
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
        binding.scanTerminalLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactHeight) 10f else 11f
        )
        binding.securityUrgentActionsLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactHeight) 10f else 11f
        )

        val widgetValueSize = if (profile.compactHeight) 12f else 14f
        val widgetHintSize = if (profile.compactHeight) 9.8f else 11f
        listOf(
            binding.widgetSweepValue,
            binding.widgetThreatsValue,
            binding.widgetCredentialsValue,
            binding.widgetServicesValue,
            binding.widgetHomeRiskValue,
            binding.widgetVpnValue,
            binding.widgetDigitalKeyValue,
            binding.widgetTimelineValue
        ).forEach { label ->
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, widgetValueSize)
        }
        listOf(
            binding.widgetSweepHint,
            binding.widgetThreatsHint,
            binding.widgetCredentialsHint,
            binding.widgetServicesHint,
            binding.widgetHomeRiskHint,
            binding.widgetVpnHint,
            binding.widgetDigitalKeyHint,
            binding.widgetTimelineHint
        ).forEach { label ->
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, widgetHintSize)
        }

        binding.securityTopActionButton.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (profile.compactHeight) 14f else 15f
        )
        val navLionSizePx = dpToPx(if (profile.compactHeight) 48f else 52f)
        binding.navLionButton.updateLayoutParams<FrameLayout.LayoutParams> {
            width = navLionSizePx
            height = navLionSizePx
        }
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

    private fun beginStartupTrace() {
        startupTraceStartMs = SystemClock.elapsedRealtime()
        startupTraceMarks.clear()
        startupFreshHydrationReady = false
        startupReadyReported = false
        markStartupTrace("activity_created")
    }

    private fun markStartupTrace(stage: String) {
        if (startupTraceStartMs <= 0L || startupTraceMarks.containsKey(stage)) {
            return
        }
        val elapsed = (SystemClock.elapsedRealtime() - startupTraceStartMs).coerceAtLeast(0L)
        startupTraceMarks[stage] = elapsed
        Log.i(STARTUP_TRACE_TAG, "$stage=${elapsed}ms")
    }

    private fun maybeReportStartupReady() {
        if (startupReadyReported || !startupFreshHydrationReady || homeIntroAnimating) {
            return
        }
        startupReadyReported = true
        markStartupTrace("startup_ready")
        val summary = startupTraceMarks.entries.joinToString(separator = ", ") { entry ->
            "${entry.key}=${entry.value}ms"
        }
        Log.i(STARTUP_TRACE_TAG, "startup_summary {$summary}")
        runCatching { reportFullyDrawn() }
    }

    private fun runOneTimeScan() {
        if (homeIntroAnimating || lionBusyInProgress) {
            return
        }
        showScanModeDialog()
    }

    private fun showScanModeDialog() {
        val dialog = LionAlertDialogBuilder(this)
            .setTitle(R.string.scan_mode_dialog_title)
            .setMessage(R.string.scan_mode_dialog_message)
            .setPositiveButton(R.string.scan_mode_option_quick) { _, _ ->
                runHomeFullScan()
            }
            .setNeutralButton(R.string.scan_mode_option_deep_custom) { _, _ ->
                showDeepScanOptionsDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        LionDialogStyler.applyForActivity(this, dialog)
    }

    private fun showDeepScanOptionsDialog() {
        val labels = arrayOf(
            getString(R.string.deep_scan_option_startup_persistence),
            getString(R.string.deep_scan_option_storage_artifacts),
            getString(R.string.deep_scan_option_embedded_probe),
            getString(R.string.deep_scan_option_wifi_posture)
        )
        val checks = booleanArrayOf(
            true,
            true,
            true,
            WifiPostureScanner.config(this).enabled
        )
        val dialog = LionAlertDialogBuilder(this)
            .setTitle(R.string.deep_scan_options_title)
            .setMessage(R.string.deep_scan_options_message)
            .setMultiChoiceItems(labels, checks) { _, which, isChecked ->
                checks[which] = isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.deep_scan_action_start) { _, _ ->
                val selection = DeepScanSelection(
                    includeStartupPersistenceSweep = checks[0],
                    includeStorageArtifactSweep = checks[1],
                    includeEmbeddedPathProbe = checks[2],
                    includeWifiPostureSweep = checks[3]
                )
                if (!selection.hasAnyDeepModule() && !selection.includeWifiPostureSweep) {
                    Toast.makeText(
                        this,
                        R.string.deep_scan_selection_required,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                maybeRunDeepScanWithStorageOnboarding(selection)
            }
            .create()
        dialog.show()
        LionDialogStyler.applyForActivity(this, dialog)
    }

    private fun maybeRunDeepScanWithStorageOnboarding(selection: DeepScanSelection) {
        if (!selection.includeStorageArtifactSweep) {
            runHomeDeepScan(selection)
            return
        }
        val missingMediaPermissions = requiredDeepStorageMediaPermissions()
        if (missingMediaPermissions.isNotEmpty()) {
            val dialog = LionAlertDialogBuilder(this)
                .setTitle(R.string.deep_scan_storage_media_permission_title)
                .setMessage(R.string.deep_scan_storage_media_permission_message)
                .setPositiveButton(R.string.deep_scan_storage_permission_grant) { _, _ ->
                    pendingDeepScanSelectionForMediaPermission = selection
                    deepStoragePermissionLauncher.launch(missingMediaPermissions)
                }
                .setNeutralButton(R.string.deep_scan_storage_permission_continue_limited) { _, _ ->
                    maybePromptAllFilesAccessForDeepScan(selection)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.show()
            LionDialogStyler.applyForActivity(this, dialog)
            return
        }
        maybePromptAllFilesAccessForDeepScan(selection)
    }

    private fun maybePromptAllFilesAccessForDeepScan(selection: DeepScanSelection) {
        if (!selection.includeStorageArtifactSweep) {
            runHomeDeepScan(selection)
            return
        }
        val canOfferAllFiles = DeepThreatScanner.canOfferAllFilesAccessOnboarding(this)
        val hasAllFiles = DeepThreatScanner.hasAllFilesAccess(this)
        if (!canOfferAllFiles || hasAllFiles) {
            runHomeDeepScan(selection)
            return
        }
        val dialog = LionAlertDialogBuilder(this)
            .setTitle(R.string.deep_scan_all_files_onboarding_title)
            .setMessage(R.string.deep_scan_all_files_onboarding_message)
            .setPositiveButton(R.string.deep_scan_storage_permission_continue_limited) { _, _ ->
                runHomeDeepScan(selection)
            }
            .setNeutralButton(R.string.deep_scan_all_files_open_settings) { _, _ ->
                val opened = openAllFilesAccessSettings()
                binding.subStatusLabel.text = if (opened) {
                    getString(R.string.deep_scan_all_files_settings_opened_status)
                } else {
                    getString(R.string.deep_scan_all_files_settings_open_failed)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        LionDialogStyler.applyForActivity(this, dialog)
    }

    private fun requiredDeepStorageMediaPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ).filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                emptyArray()
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun openAllFilesAccessSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        val packageUri = Uri.parse("package:$packageName")
        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        if (runCatching { startActivity(appIntent) }.isSuccess) {
            return true
        }
        val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return runCatching { startActivity(fallbackIntent) }.isSuccess
    }

    private fun runHomeFullScan() {
        if (homeIntroAnimating) {
            return
        }
        setScanTerminalEnabled(true)
        setScanTerminalLine(
            buildScanTerminalLine(
                progress = 0f,
                stageStatus = getString(R.string.scan_stage_preparing),
                detail = "core: preparing snapshot collectors"
            ),
            force = true
        )
        val coreStageLabel = getString(R.string.scan_stage_core_running)
        setBusy(
            busy = true,
            status = getString(R.string.home_full_scan_running),
            lionMinDurationMs = 0L
        )
        updateLionProcessingCheckpoint(0.08f, getString(R.string.scan_stage_preparing))
        val startedAtMs = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                SecurityScanner.runScan(
                    context = this@MainActivity,
                    createBaselineIfMissing = true,
                    progressCallback = ScanProgressCallback { coreProgress, coreDetail ->
                        postScanProgress(
                            progress = 0.10f + (coreProgress * 0.58f),
                            stageStatus = coreStageLabel,
                            terminalDetail = coreDetail
                        )
                    }
                )
            }
            updateLionProcessingCheckpoint(0.70f, getString(R.string.scan_stage_core_complete))
            val wifiEnabled = WifiPostureScanner.config(this@MainActivity).enabled
            if (wifiEnabled) {
                updateLionProcessingCheckpoint(0.74f, getString(R.string.scan_stage_wifi_running))
                val wifiSnapshot = withContext(Dispatchers.Default) {
                    WifiPostureScanner.runPostureScan(this@MainActivity)
                }
                withContext(Dispatchers.IO) {
                    IncidentStore.syncFromWifiPosture(this@MainActivity, wifiSnapshot)
                }
                updateLionProcessingCheckpoint(0.88f, getString(R.string.scan_stage_wifi_complete))
            } else {
                updateLionProcessingCheckpoint(0.84f)
            }
            latestWifiSnapshot = WifiScanSnapshotStore.latest(this@MainActivity)
            binding.statusLabel.text = SecurityScanner.summaryLine(result)
            updateLionProcessingCheckpoint(0.91f, getString(R.string.scan_stage_finalizing))
            val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
            val scopeSummary = if (wifiEnabled) {
                getString(R.string.home_full_scan_scope_with_wifi)
            } else {
                getString(R.string.home_full_scan_scope_core)
            }
            binding.subStatusLabel.text = getString(
                R.string.home_full_scan_completed_timing_scope,
                formatScanDuration(elapsedMs),
                scopeSummary
            )
            val reportText = SecurityScanner.formatReport(result)
            binding.resultText.text = reportText
            val reviewPayload = buildFullScanReviewPayload(
                result = result,
                scopeSummary = scopeSummary,
                reportText = reportText
            )
            updateLionProcessingCheckpoint(LION_PROCESSING_TARGET_PROGRESS)
            setBusy(false)
            setScanTerminalEnabled(false)
            refreshUiState()
            showScanCompletionPrompt(reviewPayload)
        }
    }

    private fun runHomeDeepScan(selection: DeepScanSelection) {
        if (homeIntroAnimating) {
            return
        }
        setScanTerminalEnabled(true)
        setScanTerminalLine(
            buildScanTerminalLine(
                progress = 0f,
                stageStatus = getString(R.string.scan_stage_preparing),
                detail = "deep: preparing module pipeline"
            ),
            force = true
        )
        val coreStageLabel = getString(R.string.scan_stage_core_running)
        val deepStageLabel = getString(R.string.scan_stage_deep_running)
        setBusy(
            busy = true,
            status = getString(R.string.home_deep_scan_running),
            lionMinDurationMs = 0L
        )
        updateLionProcessingCheckpoint(0.08f, getString(R.string.scan_stage_preparing))
        val startedAtMs = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            var reviewPayload: ScanReviewPayload? = null
            runCatching {
                val coreResult = withContext(Dispatchers.Default) {
                    SecurityScanner.runScan(
                        context = this@MainActivity,
                        createBaselineIfMissing = true,
                        progressCallback = ScanProgressCallback { coreProgress, coreDetail ->
                            postScanProgress(
                                progress = 0.10f + (coreProgress * 0.34f),
                                stageStatus = coreStageLabel,
                                terminalDetail = coreDetail
                            )
                        }
                    )
                }
                updateLionProcessingCheckpoint(0.46f, getString(R.string.scan_stage_core_complete))
                val deepResult = withContext(Dispatchers.IO) {
                    DeepThreatScanner.run(
                        context = this@MainActivity,
                        options = DeepScanOptions(
                            includeStartupPersistenceSweep = selection.includeStartupPersistenceSweep,
                            includeStorageArtifactSweep = selection.includeStorageArtifactSweep,
                            includeEmbeddedPathProbe = selection.includeEmbeddedPathProbe
                        ),
                        progressCallback = DeepScanProgressCallback { deepProgress, deepDetail ->
                            postScanProgress(
                                progress = 0.48f + (deepProgress * 0.34f),
                                stageStatus = deepStageLabel,
                                terminalDetail = deepDetail
                            )
                        }
                    )
                }
                withContext(Dispatchers.IO) {
                    IncidentStore.syncFromDeepScan(this@MainActivity, deepResult)
                }
                updateLionProcessingCheckpoint(0.82f, getString(R.string.scan_stage_deep_complete))
                if (selection.includeWifiPostureSweep) {
                    updateLionProcessingCheckpoint(0.85f, getString(R.string.scan_stage_wifi_running))
                    val wifiSnapshot = withContext(Dispatchers.Default) {
                        WifiPostureScanner.runPostureScan(this@MainActivity)
                    }
                    withContext(Dispatchers.IO) {
                        IncidentStore.syncFromWifiPosture(this@MainActivity, wifiSnapshot)
                    }
                    updateLionProcessingCheckpoint(0.89f, getString(R.string.scan_stage_wifi_complete))
                }
                latestWifiSnapshot = WifiScanSnapshotStore.latest(this@MainActivity)
                updateLionProcessingCheckpoint(0.90f, getString(R.string.scan_stage_maintenance_running))
                val maintenanceAudit = withContext(Dispatchers.IO) {
                    SafeHygieneToolkit.runAudit(this@MainActivity)
                }
                latestHygieneAudit = maintenanceAudit
                SafeHygieneToolkit.logMaintenanceAction(
                    context = this@MainActivity,
                    action = "deep_scan_maintenance_snapshot",
                    detail = JSONObject()
                        .put("inactiveAppCandidateCount", maintenanceAudit.healthReport.inactiveAppCandidateCount)
                        .put("duplicateMediaGroupCount", maintenanceAudit.healthReport.duplicateMediaGroupCount)
                        .put("installerRemnantCount", maintenanceAudit.healthReport.installerRemnantCount)
                        .put("safeCleanupBytes", maintenanceAudit.healthReport.safeCleanupBytes)
                )
                updateLionProcessingCheckpoint(0.93f, getString(R.string.scan_stage_maintenance_complete))

                val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
                val scopeSummary = "${buildDeepScopeSummary(selection)}, ${getString(R.string.scan_results_scope_maintenance)}"
                binding.statusLabel.text = "${SecurityScanner.summaryLine(coreResult)} ${deepResult.summaryLine()}"
                updateLionProcessingCheckpoint(0.95f, getString(R.string.scan_stage_finalizing))
                binding.subStatusLabel.text = getString(
                    R.string.home_deep_scan_completed_timing_scope,
                    formatScanDuration(elapsedMs),
                    scopeSummary
                )
                val reportText = buildString {
                    appendLine(SecurityScanner.formatReport(coreResult))
                    appendLine()
                    appendLine(deepResult.formatReport())
                    appendLine()
                    appendLine(buildMaintenanceReportSection(maintenanceAudit))
                }.trim()
                binding.resultText.text = reportText
                reviewPayload = buildDeepScanReviewPayload(
                    coreResult = coreResult,
                    deepResult = deepResult,
                    scopeSummary = scopeSummary,
                    reportText = reportText,
                    maintenancePayloadJson = buildMaintenancePayloadJson(maintenanceAudit)
                )
            }.onFailure {
                binding.subStatusLabel.text = getString(R.string.home_deep_scan_failed)
                setScanTerminalLine(getString(R.string.scan_terminal_failed), force = true)
            }
            updateLionProcessingCheckpoint(LION_PROCESSING_TARGET_PROGRESS)
            setBusy(false)
            setScanTerminalEnabled(false)
            refreshUiState()
            reviewPayload?.let { showScanCompletionPrompt(it) }
        }
    }

    private fun resolveDeepScanMinimumVisualMs(selection: DeepScanSelection): Long {
        var estimate = LION_MIN_BUSY_VISUAL_MS_DEEP_SCAN_BASE
        if (selection.includeStartupPersistenceSweep) {
            estimate += LION_MIN_BUSY_VISUAL_MS_DEEP_SCAN_PER_MODULE
        }
        if (selection.includeStorageArtifactSweep) {
            estimate += LION_MIN_BUSY_VISUAL_MS_DEEP_SCAN_PER_MODULE
        }
        if (selection.includeEmbeddedPathProbe) {
            estimate += LION_MIN_BUSY_VISUAL_MS_DEEP_SCAN_PER_MODULE
        }
        if (selection.includeWifiPostureSweep) {
            estimate += LION_MIN_BUSY_VISUAL_MS_DEEP_SCAN_PER_MODULE
        }
        return estimate.coerceAtMost(LION_MIN_BUSY_VISUAL_MS_DEEP_SCAN_MAX)
    }

    private fun buildDeepScopeSummary(selection: DeepScanSelection): String {
        val parts = mutableListOf<String>()
        parts += getString(R.string.home_deep_scope_core)
        if (selection.includeStartupPersistenceSweep) {
            parts += getString(R.string.home_deep_scope_startup)
        }
        if (selection.includeStorageArtifactSweep) {
            parts += getString(R.string.home_deep_scope_storage)
        }
        if (selection.includeEmbeddedPathProbe) {
            parts += getString(R.string.home_deep_scope_embedded)
        }
        if (selection.includeWifiPostureSweep) {
            parts += getString(R.string.home_deep_scope_wifi)
        }
        return parts.joinToString(", ")
    }

    private fun buildMaintenancePayloadJson(audit: HygieneAuditResult): String {
        val health = audit.healthReport
        return JSONObject()
            .put("generatedAtEpochMs", audit.generatedAtEpochMs)
            .put("appCacheBytes", audit.appCacheBytes)
            .put("staleArtifactCount", audit.staleArtifactCount)
            .put("staleArtifactBytes", audit.staleArtifactBytes)
            .put("staleCompletedQueueCount", audit.staleCompletedQueueCount)
            .put("safeCleanupBytes", health.safeCleanupBytes)
            .put("usageAccessGranted", health.usageAccessGranted)
            .put("inactiveAppCandidateCount", health.inactiveAppCandidateCount)
            .put("inactiveAppExamples", JSONArray(health.inactiveAppExamples))
            .put("mediaReadAccessGranted", health.mediaReadAccessGranted)
            .put("duplicateMediaGroupCount", health.duplicateMediaGroupCount)
            .put("duplicateMediaFileCount", health.duplicateMediaFileCount)
            .put("duplicateMediaReclaimableBytes", health.duplicateMediaReclaimableBytes)
            .put("duplicateMediaExamples", JSONArray(health.duplicateMediaExamples))
            .put("installerRemnantCount", health.installerRemnantCount)
            .put("installerRemnantBytes", health.installerRemnantBytes)
            .put("installerRemnantExamples", JSONArray(health.installerRemnantExamples))
            .toString()
    }

    private fun buildMaintenanceReportSection(audit: HygieneAuditResult): String {
        val health = audit.healthReport
        return buildString {
            appendLine("Safe Hygiene Report")
            appendLine("Generated: ${toIsoUtc(audit.generatedAtEpochMs)}")
            appendLine(
                "Cleanup-safe reclaimable: ${
                    SafeHygieneToolkit.formatBytes(health.safeCleanupBytes)
                } (cache + local stale artifacts)"
            )
            appendLine(
                "Inactive apps: ${health.inactiveAppCandidateCount} | usage access: ${
                    if (health.usageAccessGranted) "granted" else "missing"
                }"
            )
            appendLine(
                "Duplicate media: ${health.duplicateMediaGroupCount} group(s), ${health.duplicateMediaFileCount} file(s), potential ${
                    SafeHygieneToolkit.formatBytes(health.duplicateMediaReclaimableBytes)
                } reclaim"
            )
            appendLine(
                "Installer remnants: ${health.installerRemnantCount} file(s), ${
                    SafeHygieneToolkit.formatBytes(health.installerRemnantBytes)
                }"
            )
            appendLine(
                "Media read access: ${
                    if (health.mediaReadAccessGranted) "granted" else "missing"
                }"
            )
        }.trim()
    }

    private fun formatScanDuration(elapsedMs: Long): String {
        val normalized = elapsedMs.coerceAtLeast(0L)
        val seconds = normalized / 1000.0
        return if (seconds < 10.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            "${normalized / 1000L}s"
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
                LionAlertDialogBuilder(this@MainActivity)
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

        refreshHomeSurface(force = true)
        if (homeIntroAnimating) {
            applyAdvancedControlsVisibility()
            recoverBottomNavIfNeeded()
            return
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
        applyAdvancedControlsVisibility()
        recoverBottomNavIfNeeded()
    }

    private fun setBusy(
        busy: Boolean,
        status: String? = null,
        lionMinDurationMs: Long = LION_MIN_BUSY_VISUAL_MS_DEFAULT
    ) {
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
        binding.widgetHomeRiskCard.isEnabled = navEnabled
        binding.widgetVpnCard.isEnabled = navEnabled
        binding.widgetDigitalKeyCard.isEnabled = navEnabled
        binding.widgetTimelineCard.isEnabled = navEnabled

        if (busy) {
            beginLionProcessingAnimation(lionMinDurationMs)
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
        if (!shouldRunHomeIntroThisLaunch()) {
            showBottomNavImmediate()
            markStartupTrace("intro_skipped")
            maybeShowHomeTutorialPopup()
            maybeReportStartupReady()
            return
        }
        playHomeIntroSequence()
    }

    private fun playHomeIntroSequence() {
        markStartupTrace("intro_started")
        homeIntroAnimating = true
        clearHomeIntroTimeline()
        resetWidgetCardTransforms()
        resetBottomNavTransforms()
        hideScanTopActionForIntro()
        binding.widgetTrailView.clear()
        binding.bottomNavCard.visibility = View.GONE
        binding.homeIntroOverlay.visibility = View.VISIBLE
        binding.homeIntroOverlay.alpha = 1f
        binding.introTitleLabel.alpha = 0f
        binding.introTitleLabel.translationX = 0f
        binding.introTitleLabel.translationY = 0f
        binding.introTitleLabel.scaleX = 1f
        binding.introTitleLabel.scaleY = 1f
        binding.introTitleLabel.text = getString(R.string.screen_title)
        binding.introWelcomeLabel.alpha = 0f
        binding.introWelcomeLabel.text = getString(R.string.home_intro_welcome)
        binding.introWelcomeLabel.translationX = 0f
        binding.introWelcomeLabel.translationY = 0f
        binding.introWelcomeLabel.clipBounds = null
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
                .setDuration(2850L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    binding.lionHeroView.alpha = 1f
                    animateIntroTitleRiseIntoHomePosition {
                        animateIntroWelcomeMessage {
                            playIntroCelebrationAndLockIn()
                        }
                    }
                }
                .start()
        }
    }

    private fun animateIntroTitleRiseIntoHomePosition(onComplete: () -> Unit) {
        if (isFinishing || isDestroyed) {
            return
        }
        val titleLabel = binding.introTitleLabel
        if (
            titleLabel.width <= 0 || titleLabel.height <= 0 ||
            binding.homeFrameTitleLabel.width <= 0 || binding.homeFrameTitleLabel.height <= 0
        ) {
            titleLabel.post { animateIntroTitleRiseIntoHomePosition(onComplete) }
            return
        }
        val titleLoc = IntArray(2)
        val targetLoc = IntArray(2)
        titleLabel.getLocationOnScreen(titleLoc)
        binding.homeFrameTitleLabel.getLocationOnScreen(targetLoc)

        val startCenterX = titleLoc[0] + (titleLabel.width / 2f)
        val startCenterY = titleLoc[1] + (titleLabel.height / 2f)
        val targetCenterX = targetLoc[0] + (binding.homeFrameTitleLabel.width / 2f)
        val targetCenterY = targetLoc[1] + (binding.homeFrameTitleLabel.height / 2f)
        val deltaX = targetCenterX - startCenterX
        val deltaY = targetCenterY - startCenterY

        titleLabel.animate().cancel()
        titleLabel.alpha = 0f
        titleLabel.translationX = 0f
        titleLabel.translationY = 0f
        titleLabel.animate()
            .alpha(1f)
            .translationX(deltaX)
            .translationY(deltaY)
            .setDuration(2100L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (isFinishing || isDestroyed) {
                    return@withEndAction
                }
                introSequenceRunnable = Runnable { onComplete() }
                binding.homeIntroOverlay.postDelayed(introSequenceRunnable, 380L)
            }
            .start()
    }

    private fun positionIntroWelcomeBelowTitle() {
        val label = binding.introWelcomeLabel
        val title = binding.introTitleLabel
        if (
            label.width <= 0 || label.height <= 0 ||
            title.width <= 0 || title.height <= 0 ||
            binding.homeIntroOverlay.width <= 0 || binding.homeIntroOverlay.height <= 0
        ) {
            return
        }
        val labelLoc = IntArray(2)
        val titleLoc = IntArray(2)
        val overlayLoc = IntArray(2)
        label.getLocationOnScreen(labelLoc)
        title.getLocationOnScreen(titleLoc)
        binding.homeIntroOverlay.getLocationOnScreen(overlayLoc)

        val desiredTopRaw = titleLoc[1] + title.height + dpToPx(10f)
        val minTop = overlayLoc[1] + dpToPx(56f)
        val maxTop = overlayLoc[1] + binding.homeIntroOverlay.height - label.height - dpToPx(96f)
        val desiredTop = desiredTopRaw.coerceIn(minTop, maxTop)
        label.translationY += (desiredTop - labelLoc[1])
        label.translationX = 0f
    }

    private fun animateIntroWelcomeMessage(onComplete: () -> Unit) {
        if (isFinishing || isDestroyed) {
            return
        }
        val fullText = getString(R.string.home_intro_welcome)
        if (fullText.isBlank()) {
            onComplete()
            return
        }
        introWordAnimator?.cancel()
        binding.introWelcomeLabel.animate().cancel()
        positionIntroWelcomeBelowTitle()
        binding.introWelcomeLabel.alpha = 0f
        binding.introWelcomeLabel.text = ""
        binding.introWelcomeLabel.clipBounds = null
        binding.introWelcomeLabel.animate()
            .alpha(1f)
            .setDuration(520L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        var cancelled = false
        var lastCount = -1
        introWordAnimator = ValueAnimator.ofFloat(0f, fullText.length.toFloat()).apply {
            duration = computeIntroWelcomeReadDurationMs(fullText)
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val count = (animator.animatedValue as Float).toInt().coerceIn(0, fullText.length)
                if (count != lastCount) {
                    binding.introWelcomeLabel.text = fullText.substring(0, count)
                    lastCount = count
                }
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
                    binding.homeIntroOverlay.postDelayed(introSequenceRunnable, 1400L)
                }
            })
            start()
        }
    }

    private fun computeIntroWelcomeReadDurationMs(text: String): Long {
        val words = Regex("\\S+").findAll(text).count().coerceAtLeast(1)
        val duration = ((words.toFloat() / INTRO_WELCOME_READING_WPM) * 60_000f).toLong()
        return duration.coerceIn(5600L, 12500L)
    }

    private fun playIntroCelebrationAndLockIn() {
        if (isFinishing || isDestroyed) {
            return
        }
        val celebrationDurationMs = 3800L
        val accentColor = activeHomePalette?.accent ?: LionThemePrefs.resolveAccentColor(this)
        binding.introCelebrationView.animate().cancel()
        binding.introCelebrationView.alpha = 0f
        binding.introCelebrationView.animate()
            .alpha(1f)
            .setDuration(420L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.introCelebrationView.startCelebration(accentColor, celebrationDurationMs)
            }
            .start()

        binding.introLionHero.animate()
            .scaleXBy(0.05f)
            .scaleYBy(0.05f)
            .setDuration(420L)
            .withEndAction {
                binding.introLionHero.animate()
                    .scaleXBy(-0.05f)
                    .scaleYBy(-0.05f)
                    .setDuration(420L)
                    .start()
            }
            .start()

        introSequenceRunnable = Runnable {
            animateIntroWelcomeWipeTopToBottom {
                finalizeHomeIntroTransition()
            }
        }
        binding.homeIntroOverlay.postDelayed(introSequenceRunnable, celebrationDurationMs + 260L)
    }

    private fun animateIntroWelcomeWipeTopToBottom(onComplete: () -> Unit) {
        if (isFinishing || isDestroyed) {
            return
        }
        val label = binding.introWelcomeLabel
        if (label.width <= 0 || label.height <= 0) {
            label.post { animateIntroWelcomeWipeTopToBottom(onComplete) }
            return
        }
        introWipeAnimator?.cancel()
        label.alpha = 1f
        label.clipBounds = Rect(0, 0, label.width, label.height)
        var cancelled = false
        introWipeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1320L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedFraction
                val top = (label.height * progress).toInt().coerceIn(0, label.height)
                label.clipBounds = Rect(0, top, label.width, label.height)
                label.alpha = (1f - (progress * 0.18f)).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    introWipeAnimator = null
                    label.clipBounds = null
                    label.alpha = 0f
                    if (!cancelled && !isFinishing && !isDestroyed) {
                        onComplete()
                    }
                }
            })
            start()
        }
    }

    private fun finalizeHomeIntroTransition() {
        if (isFinishing || isDestroyed) {
            return
        }
        val runWidgetMotion = isHomeIntroWidgetMotionEnabled(this)
        if (runWidgetMotion) {
            hideWidgetCardsForIntroLaunch()
        }
        binding.homeIntroOverlay.animate()
            .alpha(0f)
            .setDuration(620L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding.homeIntroOverlay.visibility = View.GONE
                binding.homeIntroOverlay.alpha = 1f
                binding.introTitleLabel.alpha = 0f
                binding.introTitleLabel.translationX = 0f
                binding.introTitleLabel.translationY = 0f
                binding.introTitleLabel.text = getString(R.string.screen_title)
                binding.introWelcomeLabel.alpha = 0f
                binding.introWelcomeLabel.text = getString(R.string.home_intro_welcome)
                binding.introWelcomeLabel.translationX = 0f
                binding.introWelcomeLabel.translationY = 0f
                binding.introWelcomeLabel.clipBounds = null
                binding.introLionHero.translationX = 0f
                binding.introLionHero.translationY = 0f
                binding.introLionHero.scaleX = 1f
                binding.introLionHero.scaleY = 1f
                binding.introCelebrationView.alpha = 0f
                binding.introCelebrationView.stopCelebration()
                if (runWidgetMotion) {
                    animateWidgetCardsArcInThenNavRipple {
                        completeHomeIntroSequence()
                    }
                } else {
                    resetWidgetCardTransforms()
                    applyImmediateBottomNavState()
                    completeHomeIntroSequence()
                }
            }
            .start()
    }

    private fun clearHomeIntroTimeline() {
        introWordAnimator?.cancel()
        introWordAnimator = null
        introWipeAnimator?.cancel()
        introWipeAnimator = null
        introSequenceRunnable?.let { pending ->
            binding.homeIntroOverlay.removeCallbacks(pending)
        }
        introSequenceRunnable = null
        binding.introCelebrationView.animate().cancel()
        binding.introCelebrationView.stopCelebration()
        widgetLaunchAnimatorSet?.cancel()
        widgetLaunchAnimatorSet = null
        widgetHeartbeatAnimatorSet?.cancel()
        widgetHeartbeatAnimatorSet = null
        navRippleAnimatorSet?.cancel()
        navRippleAnimatorSet = null
        widgetPageAnimatorSet?.cancel()
        widgetPageAnimatorSet = null
        navPageAnimatorSet?.cancel()
        navPageAnimatorSet = null
        binding.introTitleLabel.animate().cancel()
        binding.introWelcomeLabel.clipBounds = null
        binding.introWelcomeLabel.animate().cancel()
        binding.widgetTrailView.clear()
        allHomeWidgetCards().forEach { it.animate().cancel() }
        homeNavButtons().forEach { it.animate().cancel() }
        binding.securityTopActionButton.animate().cancel()
        binding.navLionButton.animate().cancel()
    }

    private fun animateWidgetCardsArcInThenNavRipple(onComplete: () -> Unit) {
        val baseCards = homeWidgetCards()
        if (baseCards.any { it.width <= 0 || it.height <= 0 }) {
            binding.root.post { animateWidgetCardsArcInThenNavRipple(onComplete) }
            return
        }
        val cards = orderedWidgetCardsForSweep(baseCards)
        val launchSettleDelay = (500L - ((cards.size - 4).coerceAtLeast(0) * 40L)).coerceAtLeast(260L)
        val gridLocation = IntArray(2)
        binding.homeQuickWidgetsGrid.getLocationOnScreen(gridLocation)
        val originX = gridLocation[0] + (binding.homeQuickWidgetsGrid.width / 2f)
        val originY = gridLocation[1] + (binding.homeQuickWidgetsGrid.height * 0.55f)
        val launchStaggerMs = (INTRO_WIDGET_LAUNCH_STAGGER_MS - ((cards.size - 4).coerceAtLeast(0) * 8L))
            .coerceAtLeast(70L)
        val startScale = 0.08f
        val baseArcHeight = dpToPx(INTRO_WIDGET_ARC_HEIGHT_DP).toFloat()
        val densityBias = dpToPx(INTRO_WIDGET_ARC_DENSITY_DP).toFloat()
        val launchAnimators = cards.mapIndexed { index, card ->
            val targetLocation = IntArray(2)
            card.getLocationOnScreen(targetLocation)
            val targetCenterX = targetLocation[0] + (card.width / 2f)
            val targetCenterY = targetLocation[1] + (card.height / 2f)
            val startDx = originX - targetCenterX
            val startDy = originY - targetCenterY
            val sideBias = if (targetCenterX <= originX) -1f else 1f
            val rowBias = if (targetCenterY >= originY) 1f else -1f
            val controlDx = (startDx * 0.40f) + (sideBias * densityBias)
            val controlDy = (startDy * 0.70f) - baseArcHeight + (rowBias * dpToPx(12f))
            card.translationX = startDx
            card.translationY = startDy
            card.scaleX = startScale
            card.scaleY = startScale
            card.alpha = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = INTRO_WIDGET_LAUNCH_DURATION_MS
                startDelay = index * launchStaggerMs
                interpolator = DecelerateInterpolator(1.0f)
                addUpdateListener { animator ->
                    val t = animator.animatedFraction.coerceIn(0f, 1f)
                    val inv = 1f - t
                    card.translationX = (inv * inv * startDx) + (2f * inv * t * controlDx)
                    card.translationY = (inv * inv * startDy) + (2f * inv * t * controlDy)
                    val scale = lerp(startScale, 1f, t)
                    card.scaleX = scale
                    card.scaleY = scale
                    card.alpha = (t * 1.25f).coerceIn(0f, 1f)
                }
            }
        }

        widgetLaunchAnimatorSet?.cancel()
        widgetLaunchAnimatorSet = AnimatorSet().apply {
            playTogether(launchAnimators)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    widgetLaunchAnimatorSet = null
                    resetWidgetCardTransforms()
                    if (cancelled || isFinishing || isDestroyed) {
                        return
                    }
                    binding.root.postDelayed(
                        {
                            animateWidgetHeartbeat(cards) {
                                animateBottomNavIn {
                                    onComplete()
                                }
                            }
                        },
                        launchSettleDelay
                    )
                }
            })
            start()
        }
    }

    private fun animateWidgetHeartbeat(cards: List<View>, onComplete: () -> Unit) {
        val heartbeatStaggerMs = (90L - ((cards.size - 4).coerceAtLeast(0) * 6L)).coerceAtLeast(56L)
        val heartbeatAnimators = cards.mapIndexed { index, card ->
            ObjectAnimator.ofPropertyValuesHolder(
                card,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.14f, 0.95f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.14f, 0.95f, 1f)
            ).apply {
                duration = 360L
                startDelay = index * heartbeatStaggerMs
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        widgetHeartbeatAnimatorSet?.cancel()
        widgetHeartbeatAnimatorSet = AnimatorSet().apply {
            playTogether(heartbeatAnimators)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    widgetHeartbeatAnimatorSet = null
                    resetWidgetCardTransforms()
                    if (!cancelled && !isFinishing && !isDestroyed) {
                        onComplete()
                    }
                }
            })
            start()
        }
    }

    private fun animateBottomNavIn(onComplete: (() -> Unit)? = null) {
        navRippleAnimatorSet?.cancel()
        binding.widgetTrailView.endWithFade(1080L)
        binding.bottomNavCard.visibility = View.VISIBLE
        binding.bottomNavCard.alpha = 0f
        binding.bottomNavCard.translationY = dpToPx(20f).toFloat()
        binding.securityTopActionButton.visibility = View.VISIBLE
        binding.securityTopActionButton.alpha = 0f
        binding.securityTopActionButton.translationY = dpToPx(16f).toFloat()
        binding.securityTopActionButton.scaleX = 0.97f
        binding.securityTopActionButton.scaleY = 0.97f
        val navRippleDistance = dpToPx(24f).toFloat()
        binding.navLionButton.apply {
            alpha = 0f
            scaleX = 0.62f
            scaleY = 0.62f
            translationX = 0f
            translationY = 0f
        }
        binding.navGuardButton.apply {
            alpha = 0f
            scaleX = 0.86f
            scaleY = 0.86f
            translationX = navRippleDistance
            translationY = 0f
        }
        binding.navVaultButton.apply {
            alpha = 0f
            scaleX = 0.86f
            scaleY = 0.86f
            translationX = -navRippleDistance
            translationY = 0f
        }
        binding.navScanButton.apply {
            alpha = 0f
            scaleX = 0.82f
            scaleY = 0.82f
            translationX = navRippleDistance * 1.45f
            translationY = 0f
        }
        binding.navSupportButton.apply {
            alpha = 0f
            scaleX = 0.82f
            scaleY = 0.82f
            translationX = -navRippleDistance * 1.45f
            translationY = 0f
        }

        val shellAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.bottomNavCard, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(
                    binding.bottomNavCard,
                    View.TRANSLATION_Y,
                    binding.bottomNavCard.translationY,
                    0f
                )
            )
            duration = 430L
            interpolator = DecelerateInterpolator()
        }
        val lionPulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.navLionButton,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.62f, 1.16f, 0.96f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.62f, 1.16f, 0.96f, 1f)
        ).apply {
            startDelay = 40L
            duration = 440L
            interpolator = DecelerateInterpolator()
        }
        val innerLeft = createNavPulseAnimator(
            view = binding.navGuardButton,
            startTranslationX = navRippleDistance,
            startDelay = 190L
        )
        val innerRight = createNavPulseAnimator(
            view = binding.navVaultButton,
            startTranslationX = -navRippleDistance,
            startDelay = 190L
        )
        val outerLeft = createNavPulseAnimator(
            view = binding.navScanButton,
            startTranslationX = navRippleDistance * 1.45f,
            startDelay = 300L
        )
        val outerRight = createNavPulseAnimator(
            view = binding.navSupportButton,
            startTranslationX = -navRippleDistance * 1.45f,
            startDelay = 300L
        )
        val scanActionAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.securityTopActionButton,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, binding.securityTopActionButton.translationY, 0f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.97f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.97f, 1f)
        ).apply {
            startDelay = 280L
            duration = 420L
            interpolator = DecelerateInterpolator()
        }
        navRippleAnimatorSet = AnimatorSet().apply {
            playTogether(
                shellAnimator,
                lionPulse,
                innerLeft,
                innerRight,
                outerLeft,
                outerRight,
                scanActionAnimator
            )
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    navRippleAnimatorSet = null
                    binding.bottomNavCard.translationY = 0f
                    binding.securityTopActionButton.alpha = 1f
                    binding.securityTopActionButton.translationY = 0f
                    binding.securityTopActionButton.scaleX = 1f
                    binding.securityTopActionButton.scaleY = 1f
                    resetBottomNavTransforms()
                    refreshBottomNavIndicators()
                    if (!cancelled && !isFinishing && !isDestroyed) {
                        onComplete?.invoke()
                    }
                }
            })
            start()
        }
    }

    private fun createNavPulseAnimator(
        view: View,
        startTranslationX: Float,
        startDelay: Long
    ): ObjectAnimator {
        return ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, view.scaleX, 1.09f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, view.scaleY, 1.09f, 1f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, startTranslationX, 0f)
        ).apply {
            this.startDelay = startDelay
            duration = 360L
            interpolator = DecelerateInterpolator()
        }
    }

    private fun completeHomeIntroSequence() {
        homeIntroAnimating = false
        setBusy(false)
        markHomeIntroShown()
        markStartupTrace("intro_completed")
        maybeReportStartupReady()
        maybeShowHomeTutorialPopup()
    }

    private fun homeWidgetCards(): List<View> {
        return primaryHomeWidgetCards()
    }

    private fun primaryHomeWidgetCards(): List<View> {
        return listOf(
            binding.widgetSweepCard,
            binding.widgetThreatsCard,
            binding.widgetCredentialsCard,
            binding.widgetServicesCard
        )
    }

    private fun allHomeWidgetCards(): List<View> {
        return listOf(
            binding.widgetSweepCard,
            binding.widgetThreatsCard,
            binding.widgetCredentialsCard,
            binding.widgetServicesCard,
            binding.widgetHomeRiskCard,
            binding.widgetVpnCard,
            binding.widgetDigitalKeyCard,
            binding.widgetTimelineCard
        )
    }

    private fun orderedWidgetCardsForSweep(cards: List<View>): List<View> {
        if (cards.size <= 2) {
            return cards
        }

        data class WidgetPosition(val view: View, val centerX: Float, val centerY: Float)
        val positioned = cards.map { view ->
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            WidgetPosition(
                view = view,
                centerX = loc[0] + (view.width / 2f),
                centerY = loc[1] + (view.height / 2f)
            )
        }

        val sortedByY = positioned.sortedBy { it.centerY }
        val rowTolerance = dpToPx(22f).toFloat()
        val rows = mutableListOf<MutableList<WidgetPosition>>()
        sortedByY.forEach { item ->
            val targetRow = rows.firstOrNull { row ->
                kotlin.math.abs(row.first().centerY - item.centerY) <= rowTolerance
            }
            if (targetRow == null) {
                rows += mutableListOf(item)
            } else {
                targetRow += item
            }
        }

        if (rows.size <= 1) {
            return positioned.sortedBy { it.centerX }.map { it.view }
        }

        val ordered = mutableListOf<View>()
        rows.forEachIndexed { rowIndex, row ->
            val lateralOrder = row.sortedBy { it.centerX }.map { it.view }
            if (rowIndex % 2 == 0) {
                ordered += lateralOrder
            } else {
                ordered += lateralOrder.asReversed()
            }
        }
        return ordered
    }

    private fun homeNavButtons(): List<View> {
        return listOf(
            binding.navScanButton,
            binding.navGuardButton,
            binding.navLionButton,
            binding.navVaultButton,
            binding.navSupportButton
        )
    }

    private fun resetWidgetCardTransforms() {
        homeWidgetCards().forEach { card ->
            card.alpha = 1f
            card.scaleX = 1f
            card.scaleY = 1f
            card.translationX = 0f
            card.translationY = 0f
        }
    }

    private fun hideWidgetCardsForIntroLaunch() {
        homeWidgetCards().forEach { card ->
            card.alpha = 0f
            card.scaleX = 1f
            card.scaleY = 1f
            card.translationX = 0f
            card.translationY = 0f
        }
    }

    private fun resetBottomNavTransforms() {
        homeNavButtons().forEach { view ->
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
        }
    }

    private fun applyImmediateBottomNavState() {
        binding.widgetTrailView.clear()
        binding.bottomNavCard.visibility = View.VISIBLE
        binding.bottomNavCard.alpha = 1f
        binding.bottomNavCard.translationY = 0f
        showScanTopActionImmediate()
        resetBottomNavTransforms()
        applyNavIconPage(pageIndex = navIconPageIndex, animate = false)
        refreshBottomNavIndicators()
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float {
        return start + ((end - start) * amount)
    }

    private fun showBottomNavImmediate() {
        clearHomeIntroTimeline()
        homeIntroAnimating = false
        binding.homeIntroOverlay.visibility = View.GONE
        binding.lionHeroView.alpha = 1f
        binding.introTitleLabel.alpha = 0f
        binding.introTitleLabel.translationX = 0f
        binding.introTitleLabel.translationY = 0f
        binding.introTitleLabel.text = getString(R.string.screen_title)
        binding.introWelcomeLabel.alpha = 0f
        binding.introWelcomeLabel.text = getString(R.string.home_intro_welcome)
        binding.introWelcomeLabel.clipBounds = null
        resetWidgetCardTransforms()
        applyImmediateBottomNavState()
        maybeReportStartupReady()
    }

    private fun recoverBottomNavIfNeeded() {
        if (
            homeIntroAnimating &&
            binding.homeIntroOverlay.visibility != View.VISIBLE &&
            binding.bottomNavCard.visibility == View.VISIBLE &&
            binding.bottomNavCard.alpha >= 0.99f
        ) {
            homeIntroAnimating = false
        }
        val introReadyForNav = isHomeIntroAlreadyShown() || homeIntroHandledThisSession
        if (!homeIntroAnimating && introReadyForNav) {
            showScanTopActionImmediate()
        }
        if (!homeIntroAnimating && introReadyForNav && binding.bottomNavCard.visibility != View.VISIBLE) {
            showBottomNavImmediate()
            maybeShowHomeTutorialPopup()
        }
    }

    private fun hideScanTopActionForIntro() {
        binding.securityTopActionButton.visibility = View.INVISIBLE
        binding.securityTopActionButton.alpha = 0f
        binding.securityTopActionButton.translationY = dpToPx(16f).toFloat()
        binding.securityTopActionButton.scaleX = 0.97f
        binding.securityTopActionButton.scaleY = 0.97f
    }

    private fun showScanTopActionImmediate() {
        binding.securityTopActionButton.visibility = View.VISIBLE
        binding.securityTopActionButton.alpha = 1f
        binding.securityTopActionButton.translationY = 0f
        binding.securityTopActionButton.scaleX = 1f
        binding.securityTopActionButton.scaleY = 1f
    }

    private fun maybeShowHomeTutorialPopup() {
        if (isFinishing || isDestroyed) {
            return
        }
        val prefs = getSharedPreferences(UI_PREFS_FILE, MODE_PRIVATE)
        if (
            prefs.getBoolean(KEY_HOME_TUTORIAL_POPUP_SHOWN, false) ||
            prefs.getBoolean(KEY_HOME_TUTORIAL_COMPLETED, false)
        ) {
            return
        }
        prefs.edit()
            .putBoolean(KEY_HOME_TUTORIAL_POPUP_SHOWN, true)
            .apply()
        openHomeTutorialChoiceDialog()
    }

    private fun openHomeTutorialChoiceDialog() {
        if (isFinishing || isDestroyed || homeTutorialActive) {
            return
        }
        val state = latestSecurityHeroState ?: buildSecurityHeroState()
        val suggestion = resolveGuidedSuggestion(state)
        LionAlertDialogBuilder(this)
            .setTitle(R.string.home_tutorial_popup_title)
            .setMessage(
                getString(
                    R.string.home_tutorial_popup_message_template,
                    suggestion.destinationLabel,
                    suggestion.bodyText
                )
            )
            .setPositiveButton(R.string.home_tutorial_popup_action) { _, _ ->
                startHomeTutorial(mode = HomeTutorialMode.GUIDED)
            }
            .setNeutralButton(R.string.home_tutorial_popup_learn_action) { _, _ ->
                startHomeTutorial(mode = HomeTutorialMode.LEARN_BY_DOING)
            }
            .setNegativeButton(R.string.home_tutorial_popup_later, null)
            .show()
    }

    private fun openSecurityScoreDialog() {
        openHomeRiskEntryPoint()
    }

    private fun openLionNavDialog() {
        if (homeIntroAnimating) {
            return
        }
        val actions = arrayOf(
            getString(R.string.lion_nav_action_home_risk),
            getString(R.string.lion_nav_action_vpn_status),
            getString(R.string.lion_nav_action_timeline_report),
            getString(R.string.lion_nav_action_security_details),
            getString(R.string.lion_nav_action_guardian_settings),
            getString(R.string.lion_nav_action_tutorial_overlay)
        )
        LionAlertDialogBuilder(this)
            .setTitle(R.string.lion_nav_dialog_title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openHomeRiskEntryPoint()
                    1 -> openVpnEntryPoint()
                    2 -> openTimelineReportDialog()
                    3 -> openSecurityDetailsDialog()
                    4 -> openGuardianSettingsDialog()
                    5 -> openHomeTutorialModeDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openHomeTutorialModeDialog() {
        if (homeTutorialActive) {
            return
        }
        LionAlertDialogBuilder(this)
            .setTitle(R.string.home_tutorial_mode_dialog_title)
            .setMessage(R.string.home_tutorial_mode_dialog_message)
            .setPositiveButton(R.string.home_tutorial_mode_guided) { _, _ ->
                startHomeTutorial(mode = HomeTutorialMode.GUIDED)
            }
            .setNeutralButton(R.string.home_tutorial_mode_learn) { _, _ ->
                startHomeTutorial(mode = HomeTutorialMode.LEARN_BY_DOING)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startHomeTutorial(mode: HomeTutorialMode) {
        if (isFinishing || isDestroyed || homeTutorialActive) {
            return
        }
        val steps = buildHomeTutorialSteps()
        if (steps.isEmpty()) {
            return
        }
        homeTutorialActive = true
        homeTutorialMode = mode
        homeTutorialSteps = steps
        homeTutorialStepIndex = 0
        homeTutorialTargetTouched = false
        binding.homeTutorialOverlay.visibility = View.VISIBLE
        binding.homeTutorialCard.alpha = 0f
        showHomeTutorialStep(index = 0, announce = false)
    }

    private fun finishHomeTutorial(completed: Boolean) {
        if (!homeTutorialActive) {
            binding.homeTutorialOverlay.visibility = View.GONE
            binding.homeTutorialCoachView.clearHighlight()
            return
        }
        if (completed) {
            getSharedPreferences(UI_PREFS_FILE, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_HOME_TUTORIAL_COMPLETED, true)
                .apply()
            binding.subStatusLabel.text = getString(R.string.home_tutorial_complete_status)
        }
        binding.homeTutorialOverlay.animate()
            .alpha(0f)
            .setDuration(160L)
            .withEndAction {
                binding.homeTutorialOverlay.visibility = View.GONE
                binding.homeTutorialOverlay.alpha = 1f
                binding.homeTutorialCoachView.clearHighlight()
                binding.homeTutorialCard.alpha = 1f
            }
            .start()
        homeTutorialActive = false
        homeTutorialMode = null
        homeTutorialSteps = emptyList()
        homeTutorialStepIndex = 0
        homeTutorialTargetTouched = false
    }

    private fun buildHomeTutorialSteps(): List<HomeTutorialStep> {
        return listOf(
            HomeTutorialStep(
                stepId = "sweep_primary",
                titleRes = R.string.home_tutorial_step_sweep_title,
                bodyRes = R.string.home_tutorial_step_sweep_body,
                hintRes = R.string.home_tutorial_step_sweep_hint,
                targetViewProvider = { binding.securityTopActionButton },
                widgetPageIndex = 0,
                navPageIndex = 0
            ),
            HomeTutorialStep(
                stepId = "threats_widget",
                titleRes = R.string.home_tutorial_step_threats_title,
                bodyRes = R.string.home_tutorial_step_threats_body,
                hintRes = R.string.home_tutorial_step_threats_hint,
                targetViewProvider = { binding.widgetThreatsCard },
                widgetPageIndex = 0,
                navPageIndex = 0
            ),
            HomeTutorialStep(
                stepId = "credentials_widget",
                titleRes = R.string.home_tutorial_step_credentials_title,
                bodyRes = R.string.home_tutorial_step_credentials_body,
                hintRes = R.string.home_tutorial_step_credentials_hint,
                targetViewProvider = { binding.widgetCredentialsCard },
                widgetPageIndex = 0,
                navPageIndex = 0
            ),
            HomeTutorialStep(
                stepId = "services_widget",
                titleRes = R.string.home_tutorial_step_services_title,
                bodyRes = R.string.home_tutorial_step_services_body,
                hintRes = R.string.home_tutorial_step_services_hint,
                targetViewProvider = { binding.widgetServicesCard },
                widgetPageIndex = 0,
                navPageIndex = 0
            ),
            HomeTutorialStep(
                stepId = "plan_button",
                titleRes = R.string.home_tutorial_step_plan_title,
                bodyRes = R.string.home_tutorial_step_plan_body,
                hintRes = R.string.home_tutorial_step_plan_hint,
                targetViewProvider = { binding.goProButton },
                widgetPageIndex = 0,
                navPageIndex = 0
            ),
            HomeTutorialStep(
                stepId = "settings_button",
                titleRes = R.string.home_tutorial_step_settings_title,
                bodyRes = R.string.home_tutorial_step_settings_body,
                hintRes = R.string.home_tutorial_step_settings_hint,
                targetViewProvider = { binding.lionModeToggleButton },
                widgetPageIndex = 0,
                navPageIndex = 0
            ),
            HomeTutorialStep(
                stepId = "home_risk_widget",
                titleRes = R.string.home_tutorial_step_home_risk_title,
                bodyRes = R.string.home_tutorial_step_home_risk_body,
                hintRes = R.string.home_tutorial_step_home_risk_hint,
                targetViewProvider = { binding.widgetSweepCard },
                widgetPageIndex = 1,
                navPageIndex = 1
            ),
            HomeTutorialStep(
                stepId = "vpn_widget",
                titleRes = R.string.home_tutorial_step_vpn_title,
                bodyRes = R.string.home_tutorial_step_vpn_body,
                hintRes = R.string.home_tutorial_step_vpn_hint,
                targetViewProvider = { binding.widgetThreatsCard },
                widgetPageIndex = 1,
                navPageIndex = 1
            ),
            HomeTutorialStep(
                stepId = "digital_key_widget",
                titleRes = R.string.home_tutorial_step_digital_key_title,
                bodyRes = R.string.home_tutorial_step_digital_key_body,
                hintRes = R.string.home_tutorial_step_digital_key_hint,
                targetViewProvider = { binding.widgetCredentialsCard },
                widgetPageIndex = 1,
                navPageIndex = 1
            ),
            HomeTutorialStep(
                stepId = "timeline_widget",
                titleRes = R.string.home_tutorial_step_timeline_title,
                bodyRes = R.string.home_tutorial_step_timeline_body,
                hintRes = R.string.home_tutorial_step_timeline_hint,
                targetViewProvider = { binding.widgetServicesCard },
                widgetPageIndex = 1,
                navPageIndex = 1
            ),
            HomeTutorialStep(
                stepId = "lion_nav",
                titleRes = R.string.home_tutorial_step_lion_nav_title,
                bodyRes = R.string.home_tutorial_step_lion_nav_body,
                hintRes = R.string.home_tutorial_step_lion_nav_hint,
                targetViewProvider = { binding.navLionButton },
                widgetPageIndex = 0,
                navPageIndex = 0
            ),
            HomeTutorialStep(
                stepId = "nav_scan",
                titleRes = R.string.home_tutorial_step_nav_scan_title,
                bodyRes = R.string.home_tutorial_step_nav_scan_body,
                hintRes = R.string.home_tutorial_step_nav_scan_hint,
                targetViewProvider = { binding.navScanButton },
                widgetPageIndex = 0,
                navPageIndex = 0
            ),
            HomeTutorialStep(
                stepId = "nav_guard",
                titleRes = R.string.home_tutorial_step_nav_guard_title,
                bodyRes = R.string.home_tutorial_step_nav_guard_body,
                hintRes = R.string.home_tutorial_step_nav_guard_hint,
                targetViewProvider = { binding.navGuardButton },
                widgetPageIndex = 0,
                navPageIndex = 0
            ),
            HomeTutorialStep(
                stepId = "nav_vault",
                titleRes = R.string.home_tutorial_step_nav_vault_title,
                bodyRes = R.string.home_tutorial_step_nav_vault_body,
                hintRes = R.string.home_tutorial_step_nav_vault_hint,
                targetViewProvider = { binding.navVaultButton },
                widgetPageIndex = 0,
                navPageIndex = 0
            ),
            HomeTutorialStep(
                stepId = "nav_support",
                titleRes = R.string.home_tutorial_step_nav_support_title,
                bodyRes = R.string.home_tutorial_step_nav_support_body,
                hintRes = R.string.home_tutorial_step_nav_support_hint,
                targetViewProvider = { binding.navSupportButton },
                widgetPageIndex = 0,
                navPageIndex = 0
            )
        )
    }

    private fun moveHomeTutorialStep(direction: Int) {
        if (!homeTutorialActive) {
            return
        }
        val next = (homeTutorialStepIndex + direction).coerceIn(0, homeTutorialSteps.lastIndex)
        if (next == homeTutorialStepIndex) {
            return
        }
        showHomeTutorialStep(index = next, announce = true)
    }

    private fun showHomeTutorialStep(index: Int, announce: Boolean) {
        if (!homeTutorialActive || index !in homeTutorialSteps.indices) {
            return
        }
        val step = homeTutorialSteps[index]
        homeTutorialStepIndex = index
        homeTutorialTargetTouched = false

        step.widgetPageIndex?.let { applyWidgetPage(pageIndex = it, animate = true) }
        step.navPageIndex?.let { applyNavIconPage(pageIndex = it, animate = true) }

        binding.homeTutorialModeLabel.text = when (homeTutorialMode) {
            HomeTutorialMode.GUIDED -> getString(R.string.home_tutorial_mode_guided)
            HomeTutorialMode.LEARN_BY_DOING -> getString(R.string.home_tutorial_mode_learn)
            null -> ""
        }
        binding.homeTutorialTitleLabel.setText(step.titleRes)
        binding.homeTutorialBodyLabel.setText(step.bodyRes)
        binding.homeTutorialHintLabel.setText(step.hintRes)
        binding.homeTutorialProgressLabel.text = getString(
            R.string.home_tutorial_progress_template,
            index + 1,
            homeTutorialSteps.size
        )
        binding.homeTutorialBackButton.visibility = if (index == 0) View.GONE else View.VISIBLE
        binding.homeTutorialCard.animate()
            .alpha(1f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        binding.homeTutorialOverlay.post {
            val target = step.targetViewProvider()
            val highlightRect = resolveTutorialHighlightRect(target)
            positionTutorialCard(highlightRect)
            binding.homeTutorialCoachView.setHighlight(highlightRect, dpToPx(14f).toFloat())
            updateHomeTutorialActionUi()
            if (announce) {
                binding.homeTutorialTitleLabel.announceForAccessibility(binding.homeTutorialTitleLabel.text)
            }
        }
    }

    private fun resolveTutorialHighlightRect(target: View): RectF {
        binding.homeTutorialCoachView.getLocationOnScreen(tutorialOverlayLocation)
        target.getLocationOnScreen(tutorialTargetLocation)
        val left = (tutorialTargetLocation[0] - tutorialOverlayLocation[0]).toFloat()
        val top = (tutorialTargetLocation[1] - tutorialOverlayLocation[1]).toFloat()
        val right = left + target.width.toFloat()
        val bottom = top + target.height.toFloat()
        val inset = dpToPx(6f).toFloat()
        return RectF(
            left - inset,
            top - inset,
            right + inset,
            bottom + inset
        )
    }

    private fun positionTutorialCard(highlightRect: RectF) {
        val params = binding.homeTutorialCard.layoutParams as FrameLayout.LayoutParams
        binding.homeTutorialCoachView.getLocationOnScreen(tutorialOverlayLocation)
        binding.homeQuickWidgetsGrid.getLocationOnScreen(tutorialTargetLocation)

        val overlayHeight = binding.homeTutorialOverlay.height
        val cardHeight = binding.homeTutorialCard.height
            .takeIf { it > 0 }
            ?: binding.homeTutorialCard.measuredHeight
        val safeTop = latestSystemBarInsets.top + dpToPx(14f)
        val safeBottom = latestSystemBarInsets.bottom + dpToPx(14f)
        val gapAboveWidgets = dpToPx(10f)
        val widgetsTopInOverlay = tutorialTargetLocation[1] - tutorialOverlayLocation[1]
        val preferredTop = widgetsTopInOverlay - cardHeight - gapAboveWidgets
        val maxTop = (overlayHeight - cardHeight - safeBottom).coerceAtLeast(safeTop)
        val fallbackTop = ((highlightRect.top - cardHeight) - gapAboveWidgets.toFloat()).toInt()
        val resolvedTop = when {
            preferredTop >= safeTop -> preferredTop
            fallbackTop in safeTop..maxTop -> fallbackTop
            else -> safeTop
        }.coerceAtMost(maxTop)

        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.topMargin = resolvedTop
        params.bottomMargin = 0
        binding.homeTutorialCard.layoutParams = params
    }

    private fun onHomeTutorialTargetTapped() {
        if (!homeTutorialActive || homeTutorialMode != HomeTutorialMode.LEARN_BY_DOING) {
            return
        }
        if (!homeTutorialTargetTouched) {
            homeTutorialTargetTouched = true
            binding.homeTutorialHintLabel.text = getString(R.string.home_tutorial_status_target_done)
            updateHomeTutorialActionUi()
        }
    }

    private fun handleHomeTutorialActionPressed() {
        if (!homeTutorialActive) {
            return
        }
        val mode = homeTutorialMode ?: return
        if (mode == HomeTutorialMode.LEARN_BY_DOING && !homeTutorialTargetTouched) {
            return
        }
        if (homeTutorialStepIndex >= homeTutorialSteps.lastIndex) {
            finishHomeTutorial(completed = true)
            return
        }
        showHomeTutorialStep(index = homeTutorialStepIndex + 1, announce = true)
    }

    private fun updateHomeTutorialActionUi() {
        val mode = homeTutorialMode ?: return
        val last = homeTutorialStepIndex >= homeTutorialSteps.lastIndex
        if (mode == HomeTutorialMode.GUIDED) {
            binding.homeTutorialActionButton.isEnabled = true
            binding.homeTutorialActionButton.text = if (last) {
                getString(R.string.home_tutorial_action_finish)
            } else {
                getString(R.string.home_tutorial_action_next)
            }
            return
        }

        if (homeTutorialTargetTouched) {
            binding.homeTutorialActionButton.isEnabled = true
            binding.homeTutorialActionButton.text = if (last) {
                getString(R.string.home_tutorial_action_finish)
            } else {
                getString(R.string.home_tutorial_action_next)
            }
            return
        }

        binding.homeTutorialActionButton.isEnabled = false
        binding.homeTutorialActionButton.text = getString(R.string.home_tutorial_action_wait_for_tap)
        binding.homeTutorialHintLabel.text = getString(R.string.home_tutorial_status_tap_target)
    }

    private fun openHomeRiskEntryPoint() {
        val state = IntegrationMeshController.snapshot(this)
        val homeRiskEnabled = IntegrationMeshController.isModuleEnabled(
            this,
            IntegrationMeshModule.SMART_HOME_CONNECTOR
        )
        val consent = IntegrationMeshAuditStore.latestActiveConsent(
            context = this,
            connectorId = state.smartHomeConnectorId,
            ownerId = state.ownerId
        )
        if (!homeRiskEnabled || consent == null) {
            openHomeRiskSetupFlow()
            return
        }
        openHomeRiskDialog()
    }

    private fun openHomeRiskDialog() {
        if (homeIntroAnimating) {
            return
        }

        setBusy(true, getString(R.string.home_risk_loading))
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { resolveHomeRiskLookup() }
                if (isFinishing || isDestroyed) {
                    return@launch
                }
                val dialogBuilder = LionAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.home_risk_dialog_title)
                    .setMessage(buildHomeRiskDialogMessage(result))
                    .setPositiveButton(android.R.string.ok, null)
                if (result.posture == null) {
                    if (result.errorRes == R.string.home_risk_not_configured ||
                        result.errorRes == R.string.home_risk_consent_missing ||
                        result.errorRes == R.string.home_risk_connector_missing
                    ) {
                        dialogBuilder.setNeutralButton(R.string.action_home_risk_setup) { _, _ ->
                            openHomeRiskSetupFlow()
                        }
                    } else {
                        dialogBuilder.setNeutralButton(R.string.action_view_details) { _, _ ->
                            openSecurityDetailsDialog()
                        }
                    }
                }
                dialogBuilder.show()
            } catch (error: Exception) {
                if (!isFinishing && !isDestroyed) {
                    LionAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.home_risk_dialog_title)
                        .setMessage(
                            buildString {
                                append(getString(R.string.home_risk_collection_failed))
                                val errorMessage = error.message.orEmpty().trim()
                                if (errorMessage.isNotBlank()) {
                                    appendLine()
                                    appendLine()
                                    append(errorMessage)
                                }
                            }
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    setBusy(false)
                }
            }
        }
    }

    private fun openHomeRiskSetupFlow() {
        if (homeIntroAnimating) {
            return
        }
        setBusy(true, getString(R.string.home_risk_setup_loading))
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { resolveHomeRiskSetup() }
                if (isFinishing || isDestroyed) {
                    return@launch
                }
                val dialogBuilder = LionAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.home_risk_setup_dialog_title)
                    .setMessage(buildHomeRiskSetupDialogMessage(result))
                    .setPositiveButton(android.R.string.ok, null)
                when (result.status) {
                    HomeRiskSetupStatus.READY -> {
                        if (shouldOfferSmartThingsInstallCta(result)) {
                            dialogBuilder.setNeutralButton(R.string.action_install_or_open_smartthings) { _, _ ->
                                openSmartThingsInstallOrApp()
                            }
                        } else {
                            dialogBuilder.setNeutralButton(R.string.security_action_open_home_risk) { _, _ ->
                                openHomeRiskDialog()
                            }
                        }
                    }

                    HomeRiskSetupStatus.ROLLOUT_DISABLED,
                    HomeRiskSetupStatus.CONNECTOR_UNAVAILABLE,
                    HomeRiskSetupStatus.CONSENT_FAILED -> {
                        dialogBuilder.setNeutralButton(R.string.action_guardian_settings) { _, _ ->
                            openGuardianSettingsDialog()
                        }
                    }
                }
                dialogBuilder.show()
                if (result.status == HomeRiskSetupStatus.READY) {
                    refreshUiState()
                }
            } catch (error: Exception) {
                if (!isFinishing && !isDestroyed) {
                    LionAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.home_risk_setup_dialog_title)
                        .setMessage(
                            buildString {
                                append(getString(R.string.home_risk_setup_consent_failed))
                                val errorMessage = error.message.orEmpty().trim()
                                if (errorMessage.isNotBlank()) {
                                    appendLine()
                                    appendLine()
                                    append(errorMessage)
                                }
                            }
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.action_guardian_settings) { _, _ ->
                            openGuardianSettingsDialog()
                        }
                        .show()
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    setBusy(false)
                }
            }
        }
    }

    private suspend fun resolveHomeRiskSetup(): HomeRiskSetupResult {
        IntegrationMeshController.refresh(this)
        val state = IntegrationMeshController.snapshot(this)
        val config = IntegrationMeshController.readConfig(this)
        val smartHomeFlag = config.featureFlags.smartHomeConnector
        val activeStage = config.rollout.activeStageForOwner(state.ownerRole)
        val rolloutStage = activeStage?.name ?: config.rollout.currentStage
        val rolloutPercent = minOf(
            smartHomeFlag.maxRolloutPercent,
            activeStage?.maxPercent ?: smartHomeFlag.maxRolloutPercent
        ).coerceAtLeast(0)
        val homeRiskEnabled = IntegrationMeshController.isModuleEnabled(
            this,
            IntegrationMeshModule.SMART_HOME_CONNECTOR
        )
        if (!homeRiskEnabled) {
            return HomeRiskSetupResult(
                status = HomeRiskSetupStatus.ROLLOUT_DISABLED,
                ownerRole = state.ownerRole,
                connectorId = state.smartHomeConnectorId,
                rolloutStage = rolloutStage,
                rolloutPercent = rolloutPercent
            )
        }
        val connector = IntegrationMeshController.getActiveSmartHomeConnector(this)
            ?: return HomeRiskSetupResult(
                status = HomeRiskSetupStatus.CONNECTOR_UNAVAILABLE,
                ownerRole = state.ownerRole,
                connectorId = state.smartHomeConnectorId
            )

        val activeConsent = IntegrationMeshAuditStore.latestActiveConsent(
            context = this,
            connectorId = state.smartHomeConnectorId,
            ownerId = state.ownerId
        )
        val consent = activeConsent ?: connector.ensureConsent(this, state.ownerRole)
            ?: return HomeRiskSetupResult(
                status = HomeRiskSetupStatus.CONSENT_FAILED,
                ownerRole = state.ownerRole,
                connectorId = connector.connectorId,
                connectorLabel = connector.connectorLabel,
                errorMessage = "consent_artifact_unavailable"
            )

        val health = runCatching {
            connector.getHealth(this, consent)
        }.getOrElse { error ->
            return HomeRiskSetupResult(
                status = HomeRiskSetupStatus.CONSENT_FAILED,
                ownerRole = consent.ownerRole,
                connectorId = connector.connectorId,
                connectorLabel = connector.connectorLabel,
                scopeCount = consent.consentScopes.size,
                errorMessage = error.message.orEmpty().trim()
            )
        }

        return HomeRiskSetupResult(
            status = HomeRiskSetupStatus.READY,
            connectorLabel = connector.connectorLabel,
            connectorId = connector.connectorId,
            ownerRole = consent.ownerRole,
            healthStatus = health.status,
            healthLastError = health.lastError,
            readOnlyMode = connector.isReadOnlyModeEnabled(),
            scopeCount = consent.consentScopes.size,
            consentIssued = activeConsent == null
        )
    }

    private fun buildHomeRiskSetupDialogMessage(result: HomeRiskSetupResult): String {
        return when (result.status) {
            HomeRiskSetupStatus.READY -> {
                val setupStateLabel = getString(
                    if (result.consentIssued) {
                        R.string.home_risk_setup_state_consent_issued
                    } else {
                        R.string.home_risk_setup_state_consent_active
                    }
                )
                val readOnlyLabel = getString(
                    if (result.readOnlyMode) {
                        R.string.home_risk_setup_mode_read_only
                    } else {
                        R.string.home_risk_setup_mode_live
                    }
                )
                getString(
                    R.string.home_risk_setup_ready_template,
                    setupStateLabel,
                    result.connectorLabel.ifBlank { result.connectorId.ifBlank { "smart_home" } },
                    result.ownerRole.ifBlank { "owner" },
                    result.healthStatus.ifBlank { getString(R.string.home_risk_setup_health_unknown) },
                    readOnlyLabel,
                    result.scopeCount
                ).let { base ->
                    if (shouldOfferSmartThingsInstallCta(result)) {
                        "$base\n\n${getString(R.string.home_risk_setup_smartthings_missing_hint)}"
                    } else {
                        base
                    }
                }
            }

            HomeRiskSetupStatus.ROLLOUT_DISABLED -> getString(
                R.string.home_risk_setup_rollout_disabled_template,
                result.ownerRole.ifBlank { "owner" },
                result.rolloutStage.ifBlank { getString(R.string.home_risk_setup_stage_unknown) },
                result.rolloutPercent
            )

            HomeRiskSetupStatus.CONNECTOR_UNAVAILABLE -> getString(
                R.string.home_risk_setup_connector_missing_detail_template,
                result.connectorId.ifBlank { "smart_home" }
            )

            HomeRiskSetupStatus.CONSENT_FAILED -> buildString {
                append(getString(R.string.home_risk_setup_consent_failed))
                val detail = result.errorMessage.trim()
                if (detail.isNotBlank()) {
                    appendLine()
                    appendLine()
                    append(detail)
                }
            }
        }
    }

    private fun shouldOfferSmartThingsInstallCta(result: HomeRiskSetupResult): Boolean {
        if (result.status != HomeRiskSetupStatus.READY) {
            return false
        }
        val missingClient = result.healthLastError.equals("smart_home_app_not_installed", ignoreCase = true)
        val smartThingsConnector = result.connectorId.contains("smartthings", ignoreCase = true) ||
            result.connectorLabel.contains("smartthings", ignoreCase = true)
        return missingClient && smartThingsConnector
    }

    private fun openSmartThingsInstallOrApp() {
        val packageNames = listOf(
            "com.samsung.android.oneconnect",
            "com.smartthings.android"
        )
        packageNames.firstNotNullOfOrNull { packageName ->
            packageManager.getLaunchIntentForPackage(packageName)
        }?.let { launchIntent ->
            runCatching { startActivity(launchIntent) }
                .onFailure { openSmartThingsInstallFallback() }
            return
        }
        openSmartThingsInstallFallback()
    }

    private fun openSmartThingsInstallFallback() {
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.samsung.android.oneconnect")),
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=com.samsung.android.oneconnect")
            )
        )
        for (intent in intents) {
            val opened = runCatching {
                startActivity(intent)
                true
            }.getOrElse { false }
            if (opened) {
                return
            }
        }
        binding.subStatusLabel.text = getString(R.string.home_risk_setup_smartthings_open_failed)
    }

    private suspend fun resolveHomeRiskLookup(): HomeRiskLookupResult {
        val smartHomeEnabled = IntegrationMeshController.isModuleEnabled(
            context = this,
            module = IntegrationMeshModule.SMART_HOME_CONNECTOR
        )
        if (!smartHomeEnabled) {
            return HomeRiskLookupResult(errorRes = R.string.home_risk_not_configured)
        }

        val state = IntegrationMeshController.snapshot(this)
        val connector = IntegrationMeshController.getActiveSmartHomeConnector(this)
            ?: return HomeRiskLookupResult(errorRes = R.string.home_risk_connector_missing)

        val consent = IntegrationMeshAuditStore.latestActiveConsent(
            context = this,
            connectorId = state.smartHomeConnectorId,
            ownerId = state.ownerId
        ) ?: return HomeRiskLookupResult(errorRes = R.string.home_risk_consent_missing)

        return runCatching {
            HomeRiskLookupResult(posture = connector.collectPosture(this, consent))
        }.getOrElse { ex ->
            HomeRiskLookupResult(
                errorRes = R.string.home_risk_collection_failed,
                errorMessage = ex.message.orEmpty().trim()
            )
        }
    }

    private fun buildHomeRiskDialogMessage(result: HomeRiskLookupResult): String {
        if (result.posture != null) {
            val posture = result.posture
            return buildString {
                appendLine(
                    getString(
                        R.string.home_risk_summary_template,
                        posture.connectorId,
                        posture.ownerRole,
                        posture.deviceCount,
                        posture.riskScore
                    )
                )
                appendLine()
                if (posture.findings.isEmpty()) {
                    appendLine(getString(R.string.home_risk_findings_empty))
                } else {
                    appendLine(getString(R.string.home_risk_findings_title))
                    posture.findings.forEachIndexed { index, finding ->
                        appendLine("${index + 1}. $finding")
                    }
                }
            }.trim()
        }

        return buildString {
            if (result.errorRes != 0) {
                appendLine(getString(result.errorRes))
            } else {
                appendLine(getString(R.string.home_risk_not_configured))
            }
            if (result.errorMessage.isNotBlank()) {
                appendLine()
                appendLine(result.errorMessage)
            }
            appendLine()
            appendLine()
            append(buildHomeRiskFallbackSummary())
        }.trim()
    }

    private fun buildHomeRiskFallbackSummary(): String {
        val wifiState = latestWifiSnapshot?.tier?.ifBlank { "unknown" } ?: "unknown"
        val (high, medium, _) = PhishingIntakeStore.summarizeRecent(this, lookback = 12)
        val pendingQueue = CredentialActionStore.loadQueue(this)
            .count { !it.status.equals("completed", ignoreCase = true) }
        return getString(
            R.string.home_risk_fallback_summary_template,
            wifiState,
            high,
            medium,
            pendingQueue
        )
    }

    private fun openVpnEntryPoint() {
        val assertion = VpnStatusAssertions.resolveCached(this)
        if (assertion.reasonCode == "rollout_disabled" ||
            assertion.reasonCode == "connector_missing" ||
            assertion.reasonCode == "consent_missing"
        ) {
            openVpnSetupFlow()
            return
        }
        openVpnStatusDialog()
    }

    private fun openVpnSetupFlow() {
        if (homeIntroAnimating) {
            return
        }
        setBusy(true, getString(R.string.vpn_setup_loading))
        lifecycleScope.launch {
            try {
                val result = resolveVpnSetup()
                if (isFinishing || isDestroyed) {
                    return@launch
                }
                val dialogBuilder = LionAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.vpn_setup_dialog_title)
                    .setMessage(buildVpnSetupDialogMessage(result))
                    .setPositiveButton(android.R.string.ok, null)

                when (result.status) {
                    VpnSetupStatus.READY -> {
                        dialogBuilder.setNeutralButton(R.string.lion_nav_action_vpn_status) { _, _ ->
                            openVpnStatusDialog()
                        }
                    }
                    VpnSetupStatus.PAID_TIER_REQUIRED -> {
                        dialogBuilder.setNeutralButton(R.string.action_open_billing) { _, _ ->
                            openPlanSelectionDialog()
                        }
                    }
                    VpnSetupStatus.ROLLOUT_DISABLED,
                    VpnSetupStatus.CONNECTOR_UNAVAILABLE,
                    VpnSetupStatus.CONSENT_FAILED,
                    VpnSetupStatus.LAUNCH_FAILED -> {
                        dialogBuilder.setNeutralButton(R.string.action_guardian_settings) { _, _ ->
                            openGuardianSettingsDialog()
                        }
                    }
                }

                dialogBuilder.show()
                if (result.status == VpnSetupStatus.READY) {
                    refreshUiState()
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    setBusy(false)
                }
            }
        }
    }

    private suspend fun resolveVpnSetup(): VpnSetupResult {
        IntegrationMeshController.refresh(this)
        val state = IntegrationMeshController.snapshot(this)
        val config = IntegrationMeshController.readConfig(this)
        val vpnConfig = config.connectors.vpnBrokers
        val provider = VpnProviderRegistry.resolveProvider(vpnConfig, state.vpnConnectorId)
        val activeStage = config.rollout.activeStageForOwner(state.ownerRole)
        val rolloutStage = activeStage?.name ?: config.rollout.currentStage
        val rolloutPercent = minOf(
            config.featureFlags.vpnProviderConnector.maxRolloutPercent,
            activeStage?.maxPercent ?: config.featureFlags.vpnProviderConnector.maxRolloutPercent
        ).coerceAtLeast(0)

        val enabled = IntegrationMeshController.isModuleEnabled(
            context = this,
            module = IntegrationMeshModule.VPN_PROVIDER_CONNECTOR
        )
        if (!enabled) {
            return VpnSetupResult(
                status = VpnSetupStatus.ROLLOUT_DISABLED,
                providerId = provider.id,
                providerLabel = provider.label,
                ownerRole = state.ownerRole,
                rolloutStage = rolloutStage,
                rolloutPercent = rolloutPercent,
                brokerNotice = vpnConfig.disclosurePolicy.brokerNotice,
                providerDataNotice = vpnConfig.disclosurePolicy.providerDataNotice,
                paidTierNotice = vpnConfig.disclosurePolicy.paidTierNotice
            )
        }

        val connector = IntegrationMeshController.getActiveVpnConnector(this)
            ?: return VpnSetupResult(
                status = VpnSetupStatus.CONNECTOR_UNAVAILABLE,
                providerId = provider.id,
                providerLabel = provider.label,
                ownerRole = state.ownerRole,
                brokerNotice = vpnConfig.disclosurePolicy.brokerNotice,
                providerDataNotice = vpnConfig.disclosurePolicy.providerDataNotice,
                paidTierNotice = vpnConfig.disclosurePolicy.paidTierNotice
            )

        val access = PricingPolicy.resolveFeatureAccess(this)
        val paidTierRequired = VpnProviderRegistry.isPaidTierRequired(vpnConfig, provider)
        if (paidTierRequired && !access.paidAccess) {
            return VpnSetupResult(
                status = VpnSetupStatus.PAID_TIER_REQUIRED,
                providerId = provider.id,
                providerLabel = provider.label,
                ownerRole = state.ownerRole,
                paidTierRequired = true,
                brokerNotice = vpnConfig.disclosurePolicy.brokerNotice,
                providerDataNotice = vpnConfig.disclosurePolicy.providerDataNotice,
                paidTierNotice = vpnConfig.disclosurePolicy.paidTierNotice
            )
        }

        val consent = IntegrationMeshAuditStore.latestActiveConsent(
            context = this,
            connectorId = state.vpnConnectorId,
            ownerId = state.ownerId
        ) ?: connector.ensureConsent(this, state.ownerRole)
            ?: return VpnSetupResult(
                status = VpnSetupStatus.CONSENT_FAILED,
                providerId = provider.id,
                providerLabel = provider.label,
                ownerRole = state.ownerRole,
                brokerNotice = vpnConfig.disclosurePolicy.brokerNotice,
                providerDataNotice = vpnConfig.disclosurePolicy.providerDataNotice,
                paidTierNotice = vpnConfig.disclosurePolicy.paidTierNotice,
                errorMessage = "consent_artifact_unavailable"
            )

        val launchResult = connector.launchProvider(this, consent)
        val assertion = VpnStatusAssertions.resolve(this)
        val status = if (launchResult.opened) VpnSetupStatus.READY else VpnSetupStatus.LAUNCH_FAILED
        val checkedAtIso = if (assertion.checkedAtEpochMs > 0L) {
            toIsoUtc(assertion.checkedAtEpochMs)
        } else {
            ""
        }

        return VpnSetupResult(
            status = status,
            providerId = assertion.providerId.ifBlank { provider.id },
            providerLabel = assertion.providerLabel.ifBlank { provider.label },
            assertion = assertion.assertion,
            state = assertion.rawState,
            details = assertion.details,
            checkedAtIso = checkedAtIso,
            launchMode = launchResult.launchMode,
            ownerRole = state.ownerRole,
            paidTierRequired = paidTierRequired,
            brokerNotice = vpnConfig.disclosurePolicy.brokerNotice,
            providerDataNotice = vpnConfig.disclosurePolicy.providerDataNotice,
            paidTierNotice = vpnConfig.disclosurePolicy.paidTierNotice,
            errorMessage = if (launchResult.opened) "" else launchResult.launchMode
        )
    }

    private fun buildVpnSetupDialogMessage(result: VpnSetupResult): String {
        return when (result.status) {
            VpnSetupStatus.READY -> buildString {
                append(
                    getString(
                        R.string.vpn_setup_ready_template,
                        result.providerLabel.ifBlank { result.providerId },
                        vpnAssertionLabel(result.assertion),
                        result.state.ifBlank { getString(R.string.vpn_status_state_unknown) },
                        result.checkedAtIso.ifBlank { getString(R.string.scam_shield_unknown_time) },
                        result.details.ifBlank { getString(R.string.vpn_status_details_none) },
                        result.launchMode.ifBlank { getString(R.string.vpn_status_details_none) }
                    )
                )
                if (result.brokerNotice.isNotBlank()) {
                    appendLine()
                    appendLine()
                    append(getString(R.string.vpn_status_disclosure_broker_template, result.brokerNotice))
                }
                if (result.providerDataNotice.isNotBlank()) {
                    appendLine()
                    append(getString(R.string.vpn_status_disclosure_provider_template, result.providerDataNotice))
                }
            }.trim()

            VpnSetupStatus.ROLLOUT_DISABLED -> getString(
                R.string.vpn_setup_rollout_disabled_template,
                result.ownerRole.ifBlank { "owner" },
                result.rolloutStage.ifBlank { "unassigned" },
                result.rolloutPercent
            )

            VpnSetupStatus.CONNECTOR_UNAVAILABLE -> getString(
                R.string.vpn_setup_connector_missing_template,
                result.providerId.ifBlank { "vpn_provider" }
            )

            VpnSetupStatus.CONSENT_FAILED -> buildString {
                append(getString(R.string.vpn_setup_consent_failed))
                if (result.errorMessage.isNotBlank()) {
                    appendLine()
                    appendLine()
                    append(result.errorMessage)
                }
            }

            VpnSetupStatus.PAID_TIER_REQUIRED -> buildString {
                append(
                    getString(
                        R.string.vpn_setup_paid_required_template,
                        result.providerLabel.ifBlank { result.providerId },
                        result.paidTierNotice.ifBlank { getString(R.string.vpn_status_paid_required_default) }
                    )
                )
                if (result.brokerNotice.isNotBlank()) {
                    appendLine()
                    appendLine()
                    append(getString(R.string.vpn_status_disclosure_broker_template, result.brokerNotice))
                }
            }

            VpnSetupStatus.LAUNCH_FAILED -> buildString {
                append(getString(R.string.vpn_setup_launch_failed))
                if (result.errorMessage.isNotBlank()) {
                    appendLine()
                    appendLine()
                    append(result.errorMessage)
                }
            }
        }
    }

    private fun openVpnStatusDialog() {
        if (homeIntroAnimating) {
            return
        }
        setBusy(true, getString(R.string.vpn_status_loading))
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { resolveVpnStatusLookup() }
                if (!isFinishing && !isDestroyed) {
                    val dialogBuilder = LionAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.vpn_status_dialog_title)
                        .setMessage(buildVpnStatusDialogMessage(result))
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.action_vpn_setup) { _, _ ->
                            openVpnSetupFlow()
                        }

                    val access = PricingPolicy.resolveFeatureAccess(this@MainActivity)
                    if (result.paidRequired && !access.paidAccess) {
                        dialogBuilder.setNegativeButton(R.string.action_open_billing) { _, _ ->
                            openPlanSelectionDialog()
                        }
                    }

                    dialogBuilder.show()
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    setBusy(false)
                }
            }
        }
    }

    private suspend fun resolveVpnStatusLookup(): VpnStatusLookupResult {
        val assertion = VpnStatusAssertions.resolve(this)
        val config = IntegrationMeshController.readConfig(this).connectors.vpnBrokers
        val provider = VpnProviderRegistry.resolveProvider(config, assertion.providerId.ifBlank { config.defaultProviderId })
        val paidRequired = VpnProviderRegistry.isPaidTierRequired(config, provider)
        val details = when (assertion.reasonCode) {
            "rollout_disabled" -> getString(R.string.vpn_status_not_configured)
            "connector_missing" -> getString(R.string.vpn_status_connector_missing)
            "consent_missing" -> getString(R.string.vpn_status_consent_missing)
            else -> assertion.details.ifBlank { getString(R.string.vpn_status_details_none) }
        }
        return VpnStatusLookupResult(
            assertion = assertion.assertion,
            providerId = assertion.providerId.ifBlank { provider.id },
            providerLabel = assertion.providerLabel.ifBlank { provider.label },
            state = assertion.rawState.ifBlank { getString(R.string.vpn_status_state_unknown) },
            details = details,
            checkedAtIso = if (assertion.checkedAtEpochMs > 0L) toIsoUtc(assertion.checkedAtEpochMs) else "",
            reasonCode = assertion.reasonCode,
            brokerNotice = config.disclosurePolicy.brokerNotice,
            providerDataNotice = config.disclosurePolicy.providerDataNotice,
            paidTierNotice = config.disclosurePolicy.paidTierNotice,
            paidRequired = paidRequired
        )
    }

    private fun buildVpnStatusDialogMessage(result: VpnStatusLookupResult): String {
        return buildString {
            append(
                getString(
                    R.string.vpn_status_summary_template,
                    result.providerLabel.ifBlank { result.providerId },
                    vpnAssertionLabel(result.assertion),
                    result.state,
                    result.checkedAtIso.ifBlank { getString(R.string.scam_shield_unknown_time) },
                    result.details
                )
            )
            if (result.brokerNotice.isNotBlank()) {
                appendLine()
                appendLine()
                append(getString(R.string.vpn_status_disclosure_broker_template, result.brokerNotice))
            }
            if (result.providerDataNotice.isNotBlank()) {
                appendLine()
                append(getString(R.string.vpn_status_disclosure_provider_template, result.providerDataNotice))
            }
            if (result.paidRequired && result.paidTierNotice.isNotBlank()) {
                appendLine()
                append(getString(R.string.vpn_status_disclosure_paid_template, result.paidTierNotice))
            }
        }.trim()
    }

    private fun vpnAssertionLabel(assertion: VpnAssertionState): String {
        return when (assertion) {
            VpnAssertionState.CONFIGURED -> getString(R.string.vpn_assertion_configured)
            VpnAssertionState.CONNECTED -> getString(R.string.vpn_assertion_connected)
            VpnAssertionState.STALE -> getString(R.string.vpn_assertion_stale)
            VpnAssertionState.UNKNOWN -> getString(R.string.vpn_assertion_unknown)
        }
    }

    private fun openDigitalKeyGuardrailsDialog() {
        if (homeIntroAnimating) {
            return
        }
        setBusy(true, getString(R.string.digital_key_status_loading))
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.Default) { resolveDigitalKeyGuardrailReport() }
                if (isFinishing || isDestroyed) {
                    return@launch
                }
                LionAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.digital_key_status_dialog_title)
                    .setMessage(buildDigitalKeyGuardrailsDialogMessage(report))
                    .setPositiveButton(R.string.digital_key_action_open_setup_guidance) { _, _ ->
                        openDigitalKeySetupGuidanceDialog(report)
                    }
                    .setNeutralButton(R.string.digital_key_action_key_share_prompt) { _, _ ->
                        runDigitalKeyHighRiskAction(DigitalKeyHighRiskAction.KEY_SHARE, report)
                    }
                    .setNegativeButton(R.string.digital_key_action_remote_command_prompt) { _, _ ->
                        runDigitalKeyHighRiskAction(DigitalKeyHighRiskAction.REMOTE_COMMAND, report)
                    }
                    .show()
            } catch (error: Exception) {
                if (!isFinishing && !isDestroyed) {
                    LionAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.digital_key_status_dialog_title)
                        .setMessage(
                            buildString {
                                append(getString(R.string.digital_key_status_load_failed))
                                val errorMessage = error.message.orEmpty().trim()
                                if (errorMessage.isNotBlank()) {
                                    appendLine()
                                    appendLine()
                                    append(errorMessage)
                                }
                            }
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    setBusy(false)
                }
            }
        }
    }

    private suspend fun resolveDigitalKeyGuardrailReport(): DigitalKeyGuardrailReport {
        IntegrationMeshController.refresh(this)
        val state = IntegrationMeshController.snapshot(this)
        val config = IntegrationMeshController.readConfig(this).connectors.digitalKeys
        val enabled = IntegrationMeshController.isModuleEnabled(
            context = this,
            module = IntegrationMeshModule.DIGITAL_KEY_RISK_ADAPTER
        )
        val adapter = IntegrationMeshController.getActiveDigitalKeyRiskAdapter(this)
        val adapterId = IntegrationMeshController.digitalKeyAdapterId(this)

        val consentArtifacts = mutableListOf<ConnectorConsentArtifact>()
        val smartHomeConsent = IntegrationMeshAuditStore.latestActiveConsent(
            context = this,
            connectorId = state.smartHomeConnectorId,
            ownerId = state.ownerId
        )
        if (smartHomeConsent != null) {
            consentArtifacts += smartHomeConsent
        }
        val vpnConsent = IntegrationMeshAuditStore.latestActiveConsent(
            context = this,
            connectorId = state.vpnConnectorId,
            ownerId = state.ownerId
        )
        if (vpnConsent != null) {
            consentArtifacts += vpnConsent
        }

        val postureSnapshots = mutableListOf<SmartHomePostureSnapshot>()
        if (smartHomeConsent != null) {
            val connector = IntegrationMeshController.getActiveSmartHomeConnector(this)
            if (connector != null) {
                runCatching { connector.collectPosture(this, smartHomeConsent) }
                    .getOrNull()
                    ?.let { snapshot -> postureSnapshots += snapshot }
            }
        }

        return DigitalKeyGuardrailEngine.resolveReport(
            context = this,
            ownerRole = state.ownerRole,
            enabled = enabled,
            adapterId = adapterId,
            adapter = adapter,
            config = config,
            consentArtifacts = consentArtifacts,
            postureSnapshots = postureSnapshots
        )
    }

    private fun buildDigitalKeyGuardrailsDialogMessage(report: DigitalKeyGuardrailReport): String {
        val stateLabel = if (report.enabled) {
            getString(R.string.digital_key_status_enabled)
        } else {
            getString(R.string.digital_key_status_not_configured)
        }
        val prerequisites = report.prerequisites
        val walletPreview = report.walletCapabilities.take(2)
        val manufacturerPreview = report.manufacturerCapabilities.take(2)
        val findingsPreview = report.assessment.findings.take(4)

        return buildString {
            append(
                getString(
                    R.string.digital_key_status_summary_template,
                    stateLabel,
                    digitalKeyBooleanLabel(prerequisites.biometricReady),
                    rootTierLabel(prerequisites.rootPosture.riskTier)
                )
            )
            appendLine()
            appendLine()
            append(
                getString(
                    R.string.digital_key_status_risk_template,
                    digitalKeyRiskLevelLabel(report.assessment.overallRiskLevel),
                    report.assessment.totalRiskScore
                )
            )
            appendLine()
            append(getString(R.string.digital_key_prereq_lock_template, digitalKeyBooleanLabel(prerequisites.lockScreenSecure)))
            appendLine()
            append(getString(R.string.digital_key_prereq_biometric_template, digitalKeyBooleanLabel(prerequisites.biometricReady)))
            appendLine()
            append(
                getString(
                    R.string.digital_key_prereq_integrity_template,
                    digitalKeyBooleanLabel(prerequisites.playDeviceIntegrityReady && prerequisites.playStrongIntegrityReady)
                )
            )
            appendLine()
            append(getString(R.string.digital_key_prereq_app_lock_template, digitalKeyBooleanLabel(prerequisites.appLockEnabled)))

            if (prerequisites.blockedReasonCodes.isNotEmpty()) {
                appendLine()
                appendLine()
                append(getString(R.string.digital_key_prereq_blocked_title))
                prerequisites.blockedReasonCodes.forEach { reasonCode ->
                    appendLine()
                    append("- ")
                    append(digitalKeyPrerequisiteLabel(reasonCode))
                }
            }
            if (prerequisites.warningReasonCodes.isNotEmpty()) {
                appendLine()
                appendLine()
                append(getString(R.string.digital_key_prereq_warning_title))
                prerequisites.warningReasonCodes.forEach { reasonCode ->
                    appendLine()
                    append("- ")
                    append(digitalKeyPrerequisiteLabel(reasonCode))
                }
            }

            appendLine()
            appendLine()
            append(getString(R.string.digital_key_setup_wallet_title))
            if (walletPreview.isEmpty()) {
                appendLine()
                append("- ")
                append(getString(R.string.digital_key_setup_none))
            } else {
                walletPreview.forEach { capability ->
                    appendLine()
                    append(
                        getString(
                            R.string.digital_key_setup_bullet_template,
                            capability.provider.label,
                            digitalKeySetupCapabilityLabel(capability)
                        )
                    )
                }
            }

            appendLine()
            appendLine()
            append(getString(R.string.digital_key_setup_manufacturer_title))
            if (manufacturerPreview.isEmpty()) {
                appendLine()
                append("- ")
                append(getString(R.string.digital_key_setup_none))
            } else {
                manufacturerPreview.forEach { capability ->
                    appendLine()
                    append(
                        getString(
                            R.string.digital_key_setup_bullet_template,
                            capability.provider.label,
                            digitalKeySetupCapabilityLabel(capability)
                        )
                    )
                }
            }

            appendLine()
            appendLine()
            append(getString(R.string.digital_key_risk_checklist_title))
            if (findingsPreview.isEmpty()) {
                appendLine()
                append("- ")
                append(getString(R.string.digital_key_risk_checklist_none))
            } else {
                findingsPreview.forEach { finding ->
                    appendLine()
                    append(
                        getString(
                            R.string.digital_key_risk_checklist_item_template,
                            digitalKeyRiskLevelLabel(finding.severity),
                            finding.message
                        )
                    )
                }
            }
        }.trim()
    }

    private fun digitalKeyRiskLevelLabel(level: String): String {
        return when (level.trim().lowercase(Locale.US)) {
            "high" -> getString(R.string.risk_level_high)
            "medium" -> getString(R.string.risk_level_medium)
            "low" -> getString(R.string.risk_level_low)
            else -> getString(R.string.readiness_state_unknown)
        }
    }

    private fun digitalKeyBooleanLabel(value: Boolean): String {
        return if (value) {
            getString(R.string.readiness_state_ok)
        } else {
            getString(R.string.readiness_state_action_needed)
        }
    }

    private fun digitalKeySetupCapabilityLabel(capability: DigitalKeySetupCapability): String {
        return when {
            capability.appLaunchReady -> getString(R.string.digital_key_setup_state_app_ready)
            capability.appInstalled -> getString(R.string.digital_key_setup_state_installed)
            capability.provider.setupUri.isNotBlank() -> getString(R.string.digital_key_setup_state_setup_link)
            capability.provider.fallbackUri.isNotBlank() -> getString(R.string.digital_key_setup_state_fallback_link)
            else -> getString(R.string.digital_key_setup_state_unavailable)
        }
    }

    private fun digitalKeyPrerequisiteLabel(reasonCode: String): String {
        return when (reasonCode.trim().lowercase(Locale.US)) {
            "lock_screen_not_secure" -> getString(R.string.digital_key_prereq_reason_lock_screen_not_secure)
            "biometric_not_ready" -> getString(R.string.digital_key_prereq_reason_biometric_not_ready)
            "integrity_compromised" -> getString(R.string.digital_key_prereq_reason_integrity_compromised)
            "integrity_elevated" -> getString(R.string.digital_key_prereq_reason_integrity_elevated)
            "play_device_integrity_missing" -> getString(R.string.digital_key_prereq_reason_play_device_missing)
            "play_strong_integrity_missing" -> getString(R.string.digital_key_prereq_reason_play_strong_missing)
            "play_integrity_not_ingested" -> getString(R.string.digital_key_prereq_reason_play_not_ingested)
            "app_lock_disabled" -> getString(R.string.digital_key_prereq_reason_app_lock_disabled)
            else -> reasonCode
        }
    }

    private fun openDigitalKeySetupGuidanceDialog(report: DigitalKeyGuardrailReport) {
        val capabilities = report.walletCapabilities + report.manufacturerCapabilities
        if (capabilities.isEmpty()) {
            LionAlertDialogBuilder(this)
                .setTitle(R.string.digital_key_setup_dialog_title)
                .setMessage(R.string.digital_key_setup_none)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val options = capabilities.map { capability ->
            getString(
                R.string.digital_key_setup_option_template,
                capability.provider.label,
                capability.categoryLabel,
                digitalKeySetupCapabilityLabel(capability)
            )
        }.toTypedArray()

        LionAlertDialogBuilder(this)
            .setTitle(R.string.digital_key_setup_dialog_title)
            .setItems(options) { _, which ->
                val selected = capabilities[which]
                val launchMode = launchDigitalKeySetupCapability(selected)
                val opened = !launchMode.equals("failed", ignoreCase = true)
                val statusMessage = when (launchMode) {
                    "app" -> getString(
                        R.string.digital_key_setup_opened_app_template,
                        selected.provider.label
                    )
                    "uri" -> getString(
                        R.string.digital_key_setup_opened_link_template,
                        selected.provider.label
                    )
                    else -> getString(R.string.digital_key_setup_open_failed)
                }
                binding.subStatusLabel.text = statusMessage
                appendDigitalKeyAuditEvent(
                    eventType = "digital_key.setup.opened",
                    outcome = if (opened) "success" else "failed",
                    riskLevel = if (opened) "low" else "medium",
                    details = "provider=${selected.provider.id};category=${selected.categoryLabel};mode=$launchMode"
                )
            }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchDigitalKeySetupCapability(capability: DigitalKeySetupCapability): String {
        val launchIntent = capability.provider.packageNames.firstNotNullOfOrNull { packageName ->
            packageManager.getLaunchIntentForPackage(packageName)
        }
        if (launchIntent != null) {
            return runCatching {
                startActivity(launchIntent)
                "app"
            }.getOrDefault("failed")
        }

        val uri = capability.provider.setupUri.ifBlank { capability.provider.fallbackUri }
        if (uri.isBlank()) {
            return "failed"
        }

        return runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            "uri"
        }.getOrDefault("failed")
    }

    private fun runDigitalKeyHighRiskAction(
        action: DigitalKeyHighRiskAction,
        report: DigitalKeyGuardrailReport
    ) {
        if (!report.enabled) {
            LionAlertDialogBuilder(this)
                .setTitle(R.string.digital_key_high_risk_blocked_title)
                .setMessage(R.string.digital_key_high_risk_blocked_not_configured)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.action_guardian_settings) { _, _ ->
                    openGuardianSettingsDialog()
                }
                .show()
            appendDigitalKeyAuditEvent(
                eventType = "digital_key.high_risk.blocked",
                outcome = "blocked_not_configured",
                riskLevel = "medium",
                details = "action=${action.name.lowercase(Locale.US)}"
            )
            return
        }

        val prerequisites = report.prerequisites
        if (!prerequisites.highRiskAllowed) {
            val blockedReasons = prerequisites.blockedReasonCodes
                .map { reasonCode -> digitalKeyPrerequisiteLabel(reasonCode) }
                .joinToString("\n") { label -> "- $label" }
            LionAlertDialogBuilder(this)
                .setTitle(R.string.digital_key_high_risk_blocked_title)
                .setMessage(
                    getString(
                        R.string.digital_key_high_risk_blocked_template,
                        digitalKeyActionLabel(action),
                        blockedReasons
                    )
                )
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.digital_key_action_open_setup_guidance) { _, _ ->
                    openDigitalKeySetupGuidanceDialog(report)
                }
                .show()
            appendDigitalKeyAuditEvent(
                eventType = "digital_key.high_risk.blocked",
                outcome = "blocked_prerequisite",
                riskLevel = "high",
                details = "action=${action.name.lowercase(Locale.US)};blocked=${prerequisites.blockedReasonCodes.joinToString(",")}"
            )
            if (PricingPolicy.resolveProfileControl(this).requiresGuardianApprovalForSensitiveActions) {
                GuardianAlertStore.appendManualEntry(
                    context = this,
                    severity = Severity.HIGH,
                    score = 88,
                    title = getString(R.string.digital_key_guardian_blocked_title),
                    sourceType = "digital_key_guardrails",
                    sourceRef = digitalKeyActionLabel(action),
                    remediation = getString(R.string.digital_key_guardian_blocked_remediation)
                )
            }
            return
        }

        val digitalKeyConfig = IntegrationMeshController.readConfig(this).connectors.digitalKeys
        val requiresGuardian = shouldRequireGuardianForDigitalKeyAction(action, digitalKeyConfig)
        val proceed = {
            openDigitalKeySocialDefensePrompt(action, report)
        }

        if (!requiresGuardian) {
            proceed()
            return
        }

        val guardAction = when (action) {
            DigitalKeyHighRiskAction.KEY_SHARE -> GuardianProtectedAction.DIGITAL_KEY_SHARE
            DigitalKeyHighRiskAction.REMOTE_COMMAND -> GuardianProtectedAction.DIGITAL_KEY_REMOTE_COMMAND
        }
        val actionLabel = when (action) {
            DigitalKeyHighRiskAction.KEY_SHARE -> getString(R.string.guardian_action_digital_key_share)
            DigitalKeyHighRiskAction.REMOTE_COMMAND -> getString(R.string.guardian_action_digital_key_remote_command)
        }

        GuardianOverridePolicy.requestApproval(
            activity = this,
            action = guardAction,
            actionLabel = actionLabel,
            onApproved = proceed,
            onDenied = {
                appendDigitalKeyAuditEvent(
                    eventType = "digital_key.high_risk.guardian",
                    outcome = "denied",
                    riskLevel = "medium",
                    details = "action=${action.name.lowercase(Locale.US)}"
                )
            }
        )
    }

    private fun shouldRequireGuardianForDigitalKeyAction(
        action: DigitalKeyHighRiskAction,
        config: IntegrationMeshDigitalKeyConfig
    ): Boolean {
        val profileControl = PricingPolicy.resolveProfileControl(this)
        if (!profileControl.requiresGuardianApprovalForSensitiveActions) {
            return false
        }
        return when (action) {
            DigitalKeyHighRiskAction.KEY_SHARE -> config.requireParentApprovalForShare
            DigitalKeyHighRiskAction.REMOTE_COMMAND -> config.requireParentApprovalForRemoteCommands
        }
    }

    private fun openDigitalKeySocialDefensePrompt(
        action: DigitalKeyHighRiskAction,
        report: DigitalKeyGuardrailReport
    ) {
        val warningBlock = if (report.prerequisites.warningReasonCodes.isEmpty()) {
            ""
        } else {
            val warnings = report.prerequisites.warningReasonCodes
                .map { reasonCode -> digitalKeyPrerequisiteLabel(reasonCode) }
                .joinToString("\n") { label -> "- $label" }
            getString(R.string.digital_key_social_warning_template, warnings)
        }
        val socialPrompt = when (action) {
            DigitalKeyHighRiskAction.KEY_SHARE -> getString(R.string.digital_key_social_prompt_share)
            DigitalKeyHighRiskAction.REMOTE_COMMAND -> getString(R.string.digital_key_social_prompt_remote)
        }
        val actionLabel = digitalKeyActionLabel(action)
        val message = buildString {
            append(
                getString(
                    R.string.digital_key_social_prompt_template,
                    actionLabel,
                    socialPrompt
                )
            )
            if (warningBlock.isNotBlank()) {
                appendLine()
                appendLine()
                append(warningBlock)
            }
        }

        LionAlertDialogBuilder(this)
            .setTitle(R.string.digital_key_social_prompt_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                appendDigitalKeyAuditEvent(
                    eventType = "digital_key.high_risk.confirmed",
                    outcome = "confirmed",
                    riskLevel = if (action == DigitalKeyHighRiskAction.REMOTE_COMMAND) "high" else "medium",
                    details = "action=${action.name.lowercase(Locale.US)}"
                )
                if (PricingPolicy.resolveProfileControl(this).requiresGuardianApprovalForSensitiveActions) {
                    GuardianAlertStore.appendManualEntry(
                        context = this,
                        severity = Severity.MEDIUM,
                        score = 70,
                        title = getString(R.string.digital_key_guardian_confirmed_title),
                        sourceType = "digital_key_guardrails",
                        sourceRef = actionLabel,
                        remediation = getString(R.string.digital_key_guardian_confirmed_remediation)
                    )
                }
                binding.subStatusLabel.text = getString(
                    R.string.digital_key_high_risk_confirmed_template,
                    actionLabel
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                appendDigitalKeyAuditEvent(
                    eventType = "digital_key.high_risk.confirmed",
                    outcome = "cancelled",
                    riskLevel = "low",
                    details = "action=${action.name.lowercase(Locale.US)}"
                )
            }
            .show()
    }

    private fun digitalKeyActionLabel(action: DigitalKeyHighRiskAction): String {
        return when (action) {
            DigitalKeyHighRiskAction.KEY_SHARE -> getString(R.string.digital_key_action_share_label)
            DigitalKeyHighRiskAction.REMOTE_COMMAND -> getString(R.string.digital_key_action_remote_label)
        }
    }

    private fun appendDigitalKeyAuditEvent(
        eventType: String,
        outcome: String,
        riskLevel: String,
        details: String,
        consentArtifactId: String = ""
    ) {
        val state = IntegrationMeshController.snapshot(this)
        val now = System.currentTimeMillis()
        val adapterId = IntegrationMeshController.digitalKeyAdapterId(this)
        val event = ConnectorAuditEvent(
            schemaVersion = INTEGRATION_MESH_SCHEMA_VERSION,
            eventId = java.util.UUID.randomUUID().toString(),
            eventType = eventType,
            recordType = CONNECTOR_AUDIT_EVENT_TYPE,
            connectorId = adapterId,
            connectorType = "digital_key",
            ownerRole = state.ownerRole,
            ownerId = state.ownerId,
            actorRole = state.ownerRole,
            actorId = state.ownerId,
            recordedAtEpochMs = now,
            recordedAtIso = toIsoUtc(now),
            outcome = outcome,
            consentArtifactId = consentArtifactId,
            sourceModule = "digital_key_guardrails",
            details = details,
            detailsHash = createHash("$adapterId|$eventType|$outcome|$details|$now"),
            riskLevel = riskLevel.ifBlank { "medium" }
        )
        IntegrationMeshAuditStore.appendConnectorEvent(this, event)
    }

    private fun openTimelineReportDialog() {
        if (homeIntroAnimating) {
            return
        }
        val queue = CredentialActionStore.loadQueue(this)
        val guardianEntries = GuardianAlertStore.readRecent(this, limit = 20)
        val connectorEvents = IntegrationMeshAuditStore.readRecentEvents(this, limit = 80)
        val appRiskBoard = Phase5ParityEngine.buildAppRiskBoard(this, queue)
        val anomalies = Phase5ParityEngine.buildConnectedHomeAnomalies(connectorEvents)
        val accountability = Phase5ParityEngine.buildOwnerAccountability(anomalies, queue)
        val kpi = Phase5ParityEngine.computeKpis(queue, connectorEvents)
        KpiTelemetryStore.appendSnapshot(this, kpi)
        val high = guardianEntries.count { it.severity == Severity.HIGH }
        val medium = guardianEntries.count { it.severity == Severity.MEDIUM }
        val appBoardRows = appRiskBoard.take(4).mapIndexed { index, row ->
            val queueLink = if (row.linkedQueueActionId.isBlank()) {
                "queue_link=none"
            } else {
                "queue_link=${row.linkedQueueActionId} (${row.linkedQueueOwner}/${row.linkedQueueCategory})"
            }
            "${index + 1}. ${row.appRef} [${row.severity.name}/${row.score}] $queueLink"
        }
        val anomalyRows = anomalies.take(4).mapIndexed { index, row ->
            val owner = CredentialPolicy.canonicalOwnerId(row.ownerRole)
            "${index + 1}. owner=$owner connector=${row.connectorId} event=${row.eventType} outcome=${row.outcome} risk=${row.severity.name}"
        }
        val accountabilityRows = accountability.map { row ->
            "owner=${row.ownerRole} high=${row.anomalyHighCount} medium=${row.anomalyMediumCount} pending_queue=${row.pendingQueueCount}"
        }
        val message = buildString {
            if (guardianEntries.isEmpty() && connectorEvents.isEmpty() && appRiskBoard.isEmpty()) {
                appendLine(getString(R.string.timeline_report_empty))
                appendLine()
            } else {
                appendLine(
                    getString(
                        R.string.timeline_report_summary_template,
                        guardianEntries.size,
                        high,
                        medium,
                        connectorEvents.size
                    )
                )
                appendLine()
            }
            appendLine(getString(R.string.phase5_app_risk_board_title))
            appendLine("Items: ${appRiskBoard.size}")
            if (appBoardRows.isEmpty()) {
                appendLine(getString(R.string.phase5_none))
            } else {
                appBoardRows.forEach { appendLine(it) }
            }
            appendLine()
            appendLine(getString(R.string.phase5_connected_home_timeline_title))
            appendLine("Anomalies: ${anomalies.size}")
            if (anomalyRows.isEmpty()) {
                appendLine(getString(R.string.phase5_none))
            } else {
                anomalyRows.forEach { appendLine(it) }
            }
            appendLine()
            appendLine(getString(R.string.phase5_owner_accountability_title))
            accountabilityRows.forEach { appendLine(it) }
            appendLine()
            appendLine(getString(R.string.phase5_kpi_title))
            appendLine(
                getString(
                    R.string.phase5_kpi_mttr_template,
                    String.format(Locale.US, "%.1f", kpi.meanTimeToRemediateHours),
                    kpi.sampleCompletedRemediations
                )
            )
            appendLine(
                getString(
                    R.string.phase5_kpi_high_risk_success_template,
                    Phase5ParityEngine.formatPercent(kpi.highRiskActionSuccessRate),
                    kpi.sampleHighRiskActions
                )
            )
            appendLine(
                getString(
                    R.string.phase5_kpi_connector_reliability_template,
                    Phase5ParityEngine.formatPercent(kpi.connectorReliabilityRate),
                    kpi.sampleConnectorEvents
                )
            )
        }.trim()
        val reportText = Phase5ParityEngine.buildUnifiedEvidenceReport(
            capturedAtEpochMs = System.currentTimeMillis(),
            appRiskBoard = appRiskBoard,
            anomalies = anomalies,
            accountability = accountability,
            kpi = kpi,
            guardianEntries = guardianEntries,
            connectorEvents = connectorEvents,
            queue = queue
        )
        LionAlertDialogBuilder(this)
            .setTitle(R.string.timeline_report_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.phase5_action_open_remediation_queue) { _, _ ->
                openCredentialCenter()
            }
            .setNeutralButton(R.string.phase5_action_export_unified_report) { _, _ ->
                launchUnifiedReportExport(reportText)
            }
            .setNegativeButton(R.string.guardian_feed_title) { _, _ ->
                openGuardianFeedDialog()
            }
            .show()
    }

    private fun shouldRunHomeIntroThisLaunch(): Boolean {
        return isHomeIntroReplayEveryLaunch(this) || !isHomeIntroAlreadyShown()
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
            GUARDIAN_SETTINGS_ACTION_OPEN_HOME_RISK_SETUP -> openHomeRiskSetupFlow()
            GUARDIAN_SETTINGS_ACTION_OPEN_VPN_SETUP -> openVpnSetupFlow()
            GUARDIAN_SETTINGS_ACTION_OPEN_TUTORIAL -> openHomeTutorialChoiceDialog()
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
        val useDarkLionPresentation = LionThemePrefs.shouldUseDarkLionPresentation(themeState.isDark)
        val accentColor = themeState.palette.accent
        activeHomePalette = themeState.palette
        applyHomeTheme(
            palette = themeState.palette,
            isDarkTone = themeState.isDark,
            accentStyle = themeState.accentStyle
        )
        binding.lionHeroView.setFillMode(lionFillMode)
        binding.lionHeroView.setImageOffsetY(0f)
        binding.lionHeroView.setSurfaceTone(useDarkLionPresentation)
        binding.lionHeroView.setLionBitmap(selectedBitmap)
        binding.lionHeroView.setAccentColor(accentColor)
        binding.introLionHero.setFillMode(lionFillMode)
        binding.introLionHero.setImageOffsetY(0f)
        binding.introLionHero.setSurfaceTone(useDarkLionPresentation)
        binding.introLionHero.setLionBitmap(selectedBitmap)
        binding.introLionHero.setAccentColor(accentColor)
        applyNavLionAsset(selectedBitmap)
        binding.lionModeToggleButton.text = getString(R.string.action_guardian_settings)
    }

    private fun applyNavLionAsset(selectedBitmap: Bitmap?) {
        if (selectedBitmap != null) {
            binding.navLionButton.setImageBitmap(selectedBitmap)
            return
        }
        binding.navLionButton.setImageResource(R.drawable.lion_icon_non_binary)
    }

    private fun applyHomeTheme(
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

        binding.root.background = gradientBackground(
            startColor = palette.backgroundStart,
            centerColor = palette.backgroundCenter,
            endColor = palette.backgroundEnd,
            angle = 90
        )
        binding.homeIntroOverlay.setBackgroundColor(palette.backgroundEnd)
        binding.homeHeroCard.strokeColor = blendColors(
            palette.stroke,
            palette.accent,
            accentStyle.buttonStrokeAccentBlend * 0.45f
        )
        binding.homeFrameTitleLabel.setTextColor(palette.textPrimary)
        binding.introTitleLabel.setTextColor(palette.textPrimary)
        binding.introWelcomeLabel.setTextColor(palette.textPrimary)
        binding.lionModeToggleButton.setTextColor(palette.accent)
        binding.scanTerminalCard.background = null
        binding.scanTerminalCard.setCardBackgroundColor(Color.TRANSPARENT)
        binding.scanTerminalCard.strokeWidth = 0
        binding.scanTerminalCard.cardElevation = 0f
        binding.scanTerminalCard.translationZ = 0f
        binding.scanTerminalCard.preventCornerOverlap = false
        binding.scanTerminalLabel.setTextColor(
            if (isDarkTone) {
                blendColors(palette.accent, palette.textPrimary, 0.52f)
            } else {
                blendColors(palette.accent, palette.textSecondary, 0.62f)
            }
        )

        applyWidgetCardPalette(binding.widgetSweepCard, palette, accentStyle)
        applyWidgetCardPalette(binding.widgetThreatsCard, palette, accentStyle)
        applyWidgetCardPalette(binding.widgetCredentialsCard, palette, accentStyle)
        applyWidgetCardPalette(binding.widgetServicesCard, palette, accentStyle)
        applyWidgetCardPalette(binding.widgetHomeRiskCard, palette, accentStyle)
        applyWidgetCardPalette(binding.widgetVpnCard, palette, accentStyle)
        applyWidgetCardPalette(binding.widgetDigitalKeyCard, palette, accentStyle)
        applyWidgetCardPalette(binding.widgetTimelineCard, palette, accentStyle)
        LionThemeViewStyler.applyMaterialButtonPalette(
            root = binding.root,
            palette = palette,
            accentStyle = accentStyle
        )
        applyActionButtonPalette(binding.goProButton, palette, accentStyle)
        applyActionButtonPalette(binding.securityTopActionButton, palette, accentStyle)
        applySettingsButtonPalette(binding.lionModeToggleButton, palette, accentStyle)

        binding.widgetSweepValue.setTextColor(palette.textPrimary)
        binding.widgetThreatsValue.setTextColor(palette.textPrimary)
        binding.widgetCredentialsValue.setTextColor(palette.textPrimary)
        binding.widgetServicesValue.setTextColor(palette.textPrimary)
        binding.widgetHomeRiskValue.setTextColor(palette.textPrimary)
        binding.widgetVpnValue.setTextColor(palette.textPrimary)
        binding.widgetDigitalKeyValue.setTextColor(palette.textPrimary)
        binding.widgetTimelineValue.setTextColor(palette.textPrimary)
        val widgetHintColor = if (isDarkTone) palette.textMuted else palette.textSecondary
        binding.widgetSweepHint.setTextColor(widgetHintColor)
        binding.widgetThreatsHint.setTextColor(widgetHintColor)
        binding.widgetCredentialsHint.setTextColor(widgetHintColor)
        binding.widgetServicesHint.setTextColor(widgetHintColor)
        binding.widgetHomeRiskHint.setTextColor(widgetHintColor)
        binding.widgetVpnHint.setTextColor(widgetHintColor)
        binding.widgetDigitalKeyHint.setTextColor(widgetHintColor)
        binding.widgetTimelineHint.setTextColor(widgetHintColor)

        binding.bottomNavCard.strokeColor = blendColors(
            palette.stroke,
            palette.accent,
            accentStyle.navButtonStrokeAccentBlend * 0.50f
        )
        binding.bottomNavCard.strokeWidth = dpToPx(1f)
        binding.bottomNavRow.background = createDepthSurfaceDrawable(
            topColor = ColorUtils.setAlphaComponent(
                blendColors(palette.navShellStart, Color.WHITE, 0.10f),
                228
            ),
            bottomColor = ColorUtils.setAlphaComponent(
                blendColors(palette.navShellEnd, palette.backgroundEnd, 0.24f),
                214
            ),
            strokeColor = ColorUtils.setAlphaComponent(
                blendColors(palette.stroke, palette.accent, accentStyle.navButtonStrokeAccentBlend),
                accentStyle.navButtonStrokeAlpha.coerceIn(156, 224)
            ),
            cornerRadiusDp = 22f * accentStyle.navShellCornerScale,
            glossAlpha = 62,
            shadowAlpha = 94,
            innerStrokeAlpha = if (accentStyle.cornerScale < 1f) 30 else 40
        )
        binding.navLionButton.imageTintList = null
        binding.navLionButton.background = null
        binding.navLionButton.elevation = 0f
        binding.navLionButton.translationZ = 0f
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
        palette: LionThemePalette,
        accentStyle: LionIdentityAccentStyle
    ) {
        val baseCornerRadiusDp = if (button === binding.securityTopActionButton) 14f else 12f
        val cornerRadiusDp = baseCornerRadiusDp * accentStyle.cornerScale
        val topColor = ColorUtils.setAlphaComponent(
            blendColors(palette.panelAlt, Color.WHITE, 0.13f),
            236
        )
        val bottomColor = ColorUtils.setAlphaComponent(
            blendColors(palette.panelAlt, palette.backgroundEnd, 0.28f),
            222
        )
        button.backgroundTintList = null
        button.background = createDepthSurfaceDrawable(
            topColor = topColor,
            bottomColor = bottomColor,
            strokeColor = ColorUtils.setAlphaComponent(
                blendColors(palette.stroke, palette.accent, accentStyle.buttonStrokeAccentBlend),
                216
            ),
            cornerRadiusDp = cornerRadiusDp,
            glossAlpha = 58,
            shadowAlpha = 82,
            innerStrokeAlpha = 38
        )
        val selectableBackground = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }
        button.foreground = if (selectableBackground.resourceId != 0) {
            ContextCompat.getDrawable(this, selectableBackground.resourceId)
        } else {
            null
        }
        button.setTextColor(palette.accent)
        button.iconTint = ColorStateList.valueOf(palette.accent)
        button.elevation = dpToPx(if (button === binding.securityTopActionButton) 6f else 5f).toFloat()
        button.translationZ = dpToPx(1f).toFloat()
    }

    private fun applyWidgetCardPalette(
        card: com.google.android.material.card.MaterialCardView,
        palette: LionThemePalette,
        accentStyle: LionIdentityAccentStyle
    ) {
        val topColor = ColorUtils.setAlphaComponent(
            blendColors(palette.panelAlt, Color.WHITE, 0.13f),
            236
        )
        val bottomColor = ColorUtils.setAlphaComponent(
            blendColors(palette.panelAlt, palette.backgroundEnd, 0.28f),
            222
        )
        card.background = createDepthSurfaceDrawable(
            topColor = topColor,
            bottomColor = bottomColor,
            strokeColor = ColorUtils.setAlphaComponent(
                blendColors(palette.stroke, palette.accent, accentStyle.buttonStrokeAccentBlend * 0.78f),
                216
            ),
            cornerRadiusDp = 12f * accentStyle.cornerScale,
            glossAlpha = 58,
            shadowAlpha = 82,
            innerStrokeAlpha = 38
        )
        card.setCardBackgroundColor(Color.TRANSPARENT)
        card.strokeWidth = 0
        card.cardElevation = dpToPx(3f).toFloat()
        card.translationZ = dpToPx(1f).toFloat()
        card.preventCornerOverlap = false
        val content = card.getChildAt(0) as? LinearLayout ?: return
        val headerRow = content.getChildAt(0) as? LinearLayout ?: return
        (headerRow.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(palette.accent)
        (headerRow.getChildAt(1) as? TextView)?.setTextColor(palette.textSecondary)
    }

    private fun applySettingsButtonPalette(
        button: com.google.android.material.button.MaterialButton,
        palette: LionThemePalette,
        accentStyle: LionIdentityAccentStyle
    ) {
        val topColor = ColorUtils.setAlphaComponent(
            blendColors(palette.panelAlt, Color.WHITE, 0.11f),
            232
        )
        val bottomColor = ColorUtils.setAlphaComponent(
            blendColors(palette.panelAlt, palette.backgroundEnd, 0.30f),
            214
        )
        button.backgroundTintList = null
        button.background = createDepthSurfaceDrawable(
            topColor = topColor,
            bottomColor = bottomColor,
            strokeColor = ColorUtils.setAlphaComponent(
                blendColors(palette.stroke, palette.accent, accentStyle.buttonStrokeAccentBlend * 0.88f),
                214
            ),
            cornerRadiusDp = 18f * accentStyle.cornerScale,
            glossAlpha = 56,
            shadowAlpha = 80,
            innerStrokeAlpha = 34
        )
        val selectableBackground = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }
        button.foreground = if (selectableBackground.resourceId != 0) {
            ContextCompat.getDrawable(this, selectableBackground.resourceId)
        } else {
            null
        }
        button.setTextColor(palette.accent)
        button.iconTint = ColorStateList.valueOf(palette.accent)
        button.elevation = dpToPx(4.5f).toFloat()
        button.translationZ = dpToPx(1f).toFloat()
    }

    private fun applyBottomNavButtonPalette(
        button: LinearLayout,
        palette: LionThemePalette
    ) {
        button.background = null
        button.elevation = 0f
        button.translationZ = 0f
        button.foreground = null
        for (index in 0 until button.childCount) {
            when (val child = button.getChildAt(index)) {
                is ImageView -> child.imageTintList = ColorStateList.valueOf(
                    blendColors(palette.textSecondary, palette.textPrimary, 0.12f)
                )
                is TextView -> child.setTextColor(
                    blendColors(palette.textSecondary, palette.textPrimary, 0.08f)
                )
            }
        }
    }

    private fun createDepthSurfaceDrawable(
        @androidx.annotation.ColorInt topColor: Int,
        @androidx.annotation.ColorInt bottomColor: Int,
        @androidx.annotation.ColorInt strokeColor: Int,
        cornerRadiusDp: Float,
        glossAlpha: Int = 52,
        shadowAlpha: Int = 76,
        innerStrokeAlpha: Int = 32,
        oval: Boolean = false
    ): LayerDrawable {
        val shapeType = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        val cornerRadiusPx = dpToPx(cornerRadiusDp).toFloat()
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, bottomColor)
        ).apply {
            shape = shapeType
            if (!oval) {
                cornerRadius = cornerRadiusPx
            }
        }
        val shadow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(Color.BLACK, shadowAlpha.coerceIn(0, 255))
            )
        ).apply {
            shape = shapeType
            if (!oval) {
                cornerRadius = cornerRadiusPx
            }
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, glossAlpha.coerceIn(0, 255)),
                Color.TRANSPARENT
            )
        ).apply {
            shape = shapeType
            if (!oval) {
                cornerRadius = cornerRadiusPx
            }
        }
        val outerRim = GradientDrawable().apply {
            shape = shapeType
            if (!oval) {
                cornerRadius = cornerRadiusPx
            }
            setColor(Color.TRANSPARENT)
            setStroke(dpToPx(1f), strokeColor)
        }
        val innerRim = GradientDrawable().apply {
            shape = shapeType
            if (!oval) {
                cornerRadius = (cornerRadiusPx - dpToPx(1f).toFloat()).coerceAtLeast(0f)
            }
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
        return ColorUtils.blendARGB(
            from,
            to,
            fraction.coerceIn(0f, 1f)
        )
    }

    private fun postScanProgress(
        progress: Float,
        stageStatus: String? = null,
        terminalDetail: String? = null
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateLionProcessingCheckpoint(progress, stageStatus, terminalDetail)
            return
        }
        runOnUiThread {
            updateLionProcessingCheckpoint(progress, stageStatus, terminalDetail)
        }
    }

    private fun updateLionProcessingCheckpoint(
        progress: Float,
        stageStatus: String? = null,
        terminalDetail: String? = null
    ) {
        if (lionBusyInProgress) {
            if (!stageStatus.isNullOrBlank()) {
                binding.subStatusLabel.text = stageStatus
            }
            val current = binding.lionHeroView.readScanProgress()
            val target = progress.coerceIn(
                LION_PROCESSING_INITIAL_PROGRESS,
                LION_PROCESSING_TARGET_PROGRESS
            )
            if (target <= current + 0.001f) {
                return
            }
            binding.lionHeroView.setScanProgress(target)
            val terminalText = buildScanTerminalLine(
                progress = target,
                stageStatus = stageStatus,
                detail = terminalDetail
            )
            setScanTerminalLine(terminalText)
        }
    }

    private fun buildScanTerminalLine(
        progress: Float,
        stageStatus: String? = null,
        detail: String? = null
    ): String {
        val detailText = (detail ?: stageStatus)
            .orEmpty()
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { getString(R.string.scan_terminal_working) }
            .let { if (it.length > 96) "${it.take(93)}..." else it }
        val percent = (progress.coerceIn(0f, 1f) * 100f).toInt()
        return getString(R.string.scan_terminal_progress_template, percent, detailText)
    }

    private fun setScanTerminalEnabled(enabled: Boolean) {
        scanTerminalEnabled = enabled
        binding.scanTerminalCard.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) {
            scanTerminalLastLine = ""
            scanTerminalLastRenderedAtMs = 0L
            binding.scanTerminalLabel.text = ""
        }
        binding.root.post { alignHomeWidgetsBetweenTitleAndScan() }
    }

    private fun setScanTerminalLine(message: String, force: Boolean = false) {
        if (!scanTerminalEnabled) {
            return
        }
        if (binding.scanTerminalCard.visibility != View.VISIBLE) {
            binding.scanTerminalCard.visibility = View.VISIBLE
        }
        val normalized = message.trim().ifBlank { getString(R.string.scan_terminal_idle) }
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (normalized == scanTerminalLastLine) {
                return
            }
            if ((now - scanTerminalLastRenderedAtMs) < SCAN_TERMINAL_UPDATE_MIN_INTERVAL_MS) {
                return
            }
        }
        scanTerminalLastLine = normalized
        scanTerminalLastRenderedAtMs = now
        binding.scanTerminalLabel.text = normalized
    }

    private fun buildFullScanReviewPayload(
        result: ScanResult,
        scopeSummary: String,
        reportText: String
    ): ScanReviewPayload {
        val highCount = result.alerts.count { it.severity == Severity.HIGH }
        val mediumCount = result.alerts.count { it.severity == Severity.MEDIUM }
        val lowCount = result.alerts.count { it.severity == Severity.LOW }
        val infoCount = result.alerts.count { it.severity == Severity.INFO }
        return ScanReviewPayload(
            modeLabel = getString(R.string.scan_mode_option_quick),
            summaryLine = SecurityScanner.summaryLine(result),
            scopeSummary = scopeSummary,
            reportText = reportText,
            completedAtEpochMs = result.snapshot.scannedAtEpochMs,
            highCount = highCount,
            mediumCount = mediumCount,
            lowCount = lowCount,
            infoCount = infoCount,
            maintenancePayloadJson = ""
        )
    }

    private fun buildDeepScanReviewPayload(
        coreResult: ScanResult,
        deepResult: DeepScanResult,
        scopeSummary: String,
        reportText: String,
        maintenancePayloadJson: String = ""
    ): ScanReviewPayload {
        val coreHigh = coreResult.alerts.count { it.severity == Severity.HIGH }
        val coreMedium = coreResult.alerts.count { it.severity == Severity.MEDIUM }
        val coreLow = coreResult.alerts.count { it.severity == Severity.LOW }
        val coreInfo = coreResult.alerts.count { it.severity == Severity.INFO }
        return ScanReviewPayload(
            modeLabel = getString(R.string.scan_mode_option_deep_custom),
            summaryLine = "${SecurityScanner.summaryLine(coreResult)} ${deepResult.summaryLine()}",
            scopeSummary = scopeSummary,
            reportText = reportText,
            completedAtEpochMs = deepResult.scannedAtEpochMs,
            highCount = coreHigh + deepResult.highCount(),
            mediumCount = coreMedium + deepResult.mediumCount(),
            lowCount = coreLow + deepResult.lowCount(),
            infoCount = coreInfo + deepResult.infoCount(),
            maintenancePayloadJson = maintenancePayloadJson
        )
    }

    private fun showScanCompletionPrompt(payload: ScanReviewPayload) {
        if (isFinishing || isDestroyed) {
            return
        }
        val message = getString(
            R.string.scan_results_prompt_message_template,
            payload.summaryLine
        )
        val dialog = LionAlertDialogBuilder(this)
            .setTitle(R.string.scan_results_prompt_title)
            .setMessage(message)
            .setPositiveButton(R.string.scan_results_prompt_review) { _, _ ->
                openScanResultsScreen(payload)
            }
            .setNegativeButton(R.string.scan_results_prompt_later, null)
            .create()
        dialog.show()
        LionDialogStyler.applyForActivity(this, dialog)
    }

    private fun openScanResultsScreen(payload: ScanReviewPayload) {
        val intent = Intent(this, ScanResultsActivity::class.java).apply {
            putExtra(ScanResultsActivity.EXTRA_MODE_LABEL, payload.modeLabel)
            putExtra(ScanResultsActivity.EXTRA_SUMMARY_LINE, payload.summaryLine)
            putExtra(ScanResultsActivity.EXTRA_SCOPE_SUMMARY, payload.scopeSummary)
            putExtra(ScanResultsActivity.EXTRA_REPORT_TEXT, payload.reportText)
            putExtra(ScanResultsActivity.EXTRA_COMPLETED_AT_EPOCH_MS, payload.completedAtEpochMs)
            putExtra(ScanResultsActivity.EXTRA_HIGH_COUNT, payload.highCount)
            putExtra(ScanResultsActivity.EXTRA_MEDIUM_COUNT, payload.mediumCount)
            putExtra(ScanResultsActivity.EXTRA_LOW_COUNT, payload.lowCount)
            putExtra(ScanResultsActivity.EXTRA_INFO_COUNT, payload.infoCount)
            putExtra(ScanResultsActivity.EXTRA_MAINTENANCE_PAYLOAD_JSON, payload.maintenancePayloadJson)
        }
        startActivity(intent)
    }

    private fun animateLionProgressTo(targetProgress: Float, explicitDurationMs: Long? = null) {
        val current = binding.lionHeroView.readScanProgress()
        val target = targetProgress.coerceIn(current, LION_PROCESSING_TARGET_PROGRESS)
        if (target <= current + 0.001f) {
            return
        }
        lionProgressAnimator?.cancel()
        val duration = explicitDurationMs ?: ((target - current) * LION_PROCESSING_PROGRESS_MS_PER_POINT)
            .toLong()
            .coerceIn(LION_PROCESSING_STEP_MIN_MS, LION_PROCESSING_STEP_MAX_MS)
        lionProgressAnimator = ValueAnimator.ofFloat(current, target).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                binding.lionHeroView.setScanProgress(animator.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    if (lionProgressAnimator === animation) {
                        lionProgressAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun beginLionProcessingAnimation(minVisualDurationMs: Long = LION_MIN_BUSY_VISUAL_MS_DEFAULT) {
        if (lionBusyInProgress) {
            return
        }
        lionBusyInProgress = true
        lionMinBusyVisualMs = minVisualDurationMs.coerceIn(0L, LION_MIN_BUSY_VISUAL_MS_DEEP_SCAN_MAX)
        lionBusyStartedAtMs = SystemClock.elapsedRealtime()
        lionAnimationSessionId += 1
        lionCompletionDelayRunnable?.let { pending ->
            binding.lionHeroView.removeCallbacks(pending)
        }
        lionCompletionDelayRunnable = null
        lionIdleResetRunnable?.let { pending ->
            binding.lionHeroView.removeCallbacks(pending)
        }
        lionIdleResetRunnable = null
        binding.lionHeroView.setScanProgress(LION_PROCESSING_INITIAL_PROGRESS)
        animateLionProgressTo(
            targetProgress = (LION_PROCESSING_INITIAL_PROGRESS + 0.04f)
                .coerceAtMost(LION_PROCESSING_TARGET_PROGRESS),
            explicitDurationMs = 320L
        )
    }

    private fun completeLionProcessingAnimation() {
        if (!lionBusyInProgress) {
            return
        }
        lionBusyInProgress = false
        val sessionIdAtComplete = lionAnimationSessionId
        val elapsed = (SystemClock.elapsedRealtime() - lionBusyStartedAtMs).coerceAtLeast(0L)
        val remaining = (lionMinBusyVisualMs - elapsed).coerceAtLeast(0L)
        lionCompletionDelayRunnable?.let { pending ->
            binding.lionHeroView.removeCallbacks(pending)
        }
        lionCompletionDelayRunnable = null
        if (remaining > 0L) {
            val completionRunnable = Runnable {
                finalizeLionProcessingAnimation(sessionIdAtComplete)
            }
            lionCompletionDelayRunnable = completionRunnable
            binding.lionHeroView.postDelayed(completionRunnable, remaining)
            return
        }
        finalizeLionProcessingAnimation(sessionIdAtComplete)
    }

    private fun finalizeLionProcessingAnimation(sessionId: Int) {
        if (lionBusyInProgress || lionAnimationSessionId != sessionId) {
            return
        }
        lionCompletionDelayRunnable = null
        lionProgressAnimator?.cancel()
        val completionStart = binding.lionHeroView.readScanProgress()
            .coerceIn(LION_PROCESSING_INITIAL_PROGRESS, 1f)
        lionProgressAnimator = ValueAnimator.ofFloat(completionStart, 1f).apply {
            duration = LION_PROCESSING_COMPLETE_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                binding.lionHeroView.setScanProgress(animator.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (lionBusyInProgress || lionAnimationSessionId != sessionId) {
                        return
                    }
                    lionProgressAnimator = null
                    binding.lionHeroView.setScanComplete()
                    lionIdleResetRunnable?.let { pending ->
                        binding.lionHeroView.removeCallbacks(pending)
                    }
                    val resetRunnable = Runnable {
                        if (!lionBusyInProgress && lionAnimationSessionId == sessionId) {
                            binding.lionHeroView.setIdleState()
                        }
                    }
                    lionIdleResetRunnable = resetRunnable
                    binding.lionHeroView.postDelayed(resetRunnable, LION_SCAN_COMPLETE_HOLD_MS)
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (lionProgressAnimator === animation) {
                        lionProgressAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun cancelLionProcessingAnimations(resetToIdle: Boolean) {
        lionAnimationSessionId += 1
        lionBusyInProgress = false
        lionBusyStartedAtMs = 0L
        lionMinBusyVisualMs = LION_MIN_BUSY_VISUAL_MS_DEFAULT
        setScanTerminalEnabled(false)
        lionProgressAnimator?.cancel()
        lionProgressAnimator = null
        lionCompletionDelayRunnable?.let { pending ->
            binding.lionHeroView.removeCallbacks(pending)
        }
        lionCompletionDelayRunnable = null
        lionIdleResetRunnable?.let { pending ->
            binding.lionHeroView.removeCallbacks(pending)
        }
        lionIdleResetRunnable = null
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
            LionAlertDialogBuilder(this)
                .setTitle(R.string.pricing_manage_restricted_title)
                .setMessage(R.string.pricing_manage_restricted_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val entitlement = PricingPolicy.entitlement(this)
        if (entitlement.isLifetimePro) {
            LionAlertDialogBuilder(this)
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

        LionAlertDialogBuilder(this)
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

        LionAlertDialogBuilder(this)
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

        LionAlertDialogBuilder(this)
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
        LionAlertDialogBuilder(this)
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

        LionAlertDialogBuilder(this)
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
            try {
                val snapshot = withContext(Dispatchers.Default) {
                    WifiPostureScanner.runPostureScan(this@MainActivity)
                }
                withContext(Dispatchers.IO) {
                    IncidentStore.syncFromWifiPosture(this@MainActivity, snapshot)
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
            } catch (_: Exception) {
                binding.subStatusLabel.text = getString(R.string.wifi_posture_scan_failed)
            } finally {
                setBusy(false)
                refreshUiState()
            }
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
            LionAlertDialogBuilder(this)
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

        LionAlertDialogBuilder(this)
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
                LionAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.media_vault_dialog_title)
                    .setMessage(R.string.media_vault_dialog_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            val labels = items.map { MediaVaultFileStore.formatItemLine(it) }.toTypedArray()
            var selectedIndex = 0
            LionAlertDialogBuilder(this@MainActivity)
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
            LionAlertDialogBuilder(this)
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
        LionAlertDialogBuilder(this)
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

    private fun launchUnifiedReportExport(reportText: String) {
        val normalized = reportText.trim()
        if (normalized.isBlank()) {
            binding.subStatusLabel.text = getString(R.string.timeline_report_export_unavailable)
            return
        }
        pendingUnifiedEvidenceReport = normalized
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "dt_unified_evidence_$timestamp.txt")
        }
        unifiedReportExportLauncher.launch(exportIntent)
    }

    private fun completePendingUnifiedReportExport(destinationUri: Uri?) {
        val payload = pendingUnifiedEvidenceReport
        pendingUnifiedEvidenceReport = ""
        if (payload.isBlank() || destinationUri == null) {
            return
        }
        val saved = runCatching {
            contentResolver.openOutputStream(destinationUri)?.use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.flush()
            } != null
        }.getOrDefault(false)
        binding.subStatusLabel.text = if (saved) {
            getString(R.string.timeline_report_export_complete)
        } else {
            getString(R.string.timeline_report_export_failed)
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
        LionAlertDialogBuilder(this)
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

        LionAlertDialogBuilder(this)
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

        val dialogBuilder = LionAlertDialogBuilder(this)
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
        LionDialogStyler.applyForActivity(this, dialog)
    }

    private fun executePhishingTriage(input: String, sourceRef: String) {
        if (!PhishingTriageEngine.config(this).enabled) {
            binding.subStatusLabel.text = getString(R.string.phishing_triage_policy_disabled)
            refreshUiState()
            return
        }
        setBusy(true, getString(R.string.scam_shield_triage_running))

        lifecycleScope.launch {
            try {
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
                showPhishingTriageResultDialog(result)
            } catch (_: Exception) {
                binding.subStatusLabel.text = getString(R.string.phishing_triage_scan_error)
            } finally {
                setBusy(false)
                refreshUiState()
            }
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
        LionAlertDialogBuilder(this)
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
        val cleanupOptions = arrayOf(
            getString(R.string.hygiene_cleanup_option_cache),
            getString(R.string.hygiene_cleanup_option_artifacts),
            getString(R.string.hygiene_cleanup_option_queue)
        )
        val optionChecks = booleanArrayOf(
            audit.appCacheBytes > 0L,
            audit.staleArtifactCount > 0,
            audit.staleCompletedQueueCount > 0
        )
        if (!optionChecks.any { it }) {
            optionChecks[0] = true
        }
        val healthSummary = buildHygieneHealthSummary(audit)
        val message = buildString {
            appendLine(getString(R.string.hygiene_health_report_title))
            appendLine(healthSummary)
            appendLine()
            appendLine(getString(R.string.hygiene_cleanup_confirm_message_template))
            appendLine()
            appendLine(getString(R.string.hygiene_playbook_title))
            append(playbook)
        }.trim()

        val dialog = LionAlertDialogBuilder(this)
            .setTitle(R.string.hygiene_cleanup_confirm_title)
            .setMessage(message)
            .setMultiChoiceItems(cleanupOptions, optionChecks) { _, which, isChecked ->
                optionChecks[which] = isChecked
            }
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val selection = HygieneCleanupSelection(
                    clearCache = optionChecks[0],
                    removeStaleArtifacts = optionChecks[1],
                    trimCompletedQueue = optionChecks[2]
                )
                if (!selection.hasAnySelection()) {
                    Toast.makeText(
                        this,
                        R.string.hygiene_cleanup_selection_required,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                applySafeHygieneCleanup(selection)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        LionDialogStyler.applyForActivity(this, dialog)
    }

    private fun buildHygieneHealthSummary(audit: HygieneAuditResult): String {
        val inactiveStatus = if (audit.healthReport.usageAccessGranted) {
            getString(
                R.string.hygiene_health_inactive_template,
                audit.healthReport.inactiveAppCandidateCount
            )
        } else {
            getString(R.string.hygiene_health_inactive_permission_missing)
        }
        val duplicateStatus = if (audit.healthReport.mediaReadAccessGranted) {
            getString(
                R.string.hygiene_health_duplicate_template,
                audit.healthReport.duplicateMediaGroupCount,
                audit.healthReport.duplicateMediaFileCount,
                SafeHygieneToolkit.formatBytes(audit.healthReport.duplicateMediaReclaimableBytes)
            )
        } else {
            getString(R.string.hygiene_health_duplicate_permission_missing)
        }
        val installerStatus = if (audit.healthReport.mediaReadAccessGranted) {
            getString(
                R.string.hygiene_health_installer_template,
                audit.healthReport.installerRemnantCount,
                SafeHygieneToolkit.formatBytes(audit.healthReport.installerRemnantBytes)
            )
        } else {
            getString(R.string.hygiene_health_installer_permission_missing)
        }
        return buildString {
            appendLine(
                getString(
                    R.string.hygiene_health_safe_reclaim_template,
                    SafeHygieneToolkit.formatBytes(audit.healthReport.safeCleanupBytes)
                )
            )
            appendLine(inactiveStatus)
            appendLine(duplicateStatus)
            append(installerStatus)
        }.trim()
    }

    private fun applySafeHygieneCleanup(selection: HygieneCleanupSelection) {
        setBusy(true, "Applying safe hygiene cleanup...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SafeHygieneToolkit.runSafeCleanup(
                    context = this@MainActivity,
                    selection = selection
                )
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

        LionAlertDialogBuilder(this)
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
        LionAlertDialogBuilder(this)
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
            LionAlertDialogBuilder(this)
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
        LionAlertDialogBuilder(this)
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
        LionAlertDialogBuilder(this)
            .setTitle(R.string.copilot_connect_dialog_title)
            .setMessage(R.string.copilot_connect_dialog_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                chooseConnectedAiProvider(policy) { provider ->
                    chooseConnectedAiModel(policy) { model ->
                        promptConnectedAiApiKey(
                            title = getString(R.string.copilot_connect_api_key_title)
                        ) { apiKey ->
                            val linkAction = {
                                ConnectedAiLinkStore.link(this, provider, model, apiKey)
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
        LionAlertDialogBuilder(this)
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
        LionAlertDialogBuilder(this)
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
        LionAlertDialogBuilder(this)
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
        LionAlertDialogBuilder(this)
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
            LionAlertDialogBuilder(this)
                .setTitle(R.string.guardian_feed_restricted_title)
                .setMessage(R.string.guardian_feed_restricted_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val entries = GuardianAlertStore.readRecent(this, limit = 15)
        if (entries.isEmpty()) {
            LionAlertDialogBuilder(this)
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

        LionAlertDialogBuilder(this)
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

    private fun refreshHomeSurface(force: Boolean = false) {
        if (!force) {
            latestHomeSurfaceSnapshot?.let { snapshot ->
                applyHomeSurfaceSnapshot(snapshot, persist = false, fromCache = false)
                return
            }
            readHomeSurfaceSnapshotCache()?.let { cached ->
                applyHomeSurfaceSnapshot(cached, persist = false, fromCache = true)
            }
        } else if (latestHomeSurfaceSnapshot == null) {
            readHomeSurfaceSnapshotCache()?.let { cached ->
                applyHomeSurfaceSnapshot(cached, persist = false, fromCache = true)
            }
        }

        if (homeSurfaceHydrationInFlight && !force) {
            return
        }
        if (force && homeSurfaceHydrationInFlight) {
            homeSurfaceHydrationJob?.cancel()
        }

        val hydrationNonce = homeSurfaceHydrationNonce + 1L
        homeSurfaceHydrationNonce = hydrationNonce
        homeSurfaceHydrationInFlight = true
        markStartupTrace("home_surface_hydration_started")
        homeSurfaceHydrationJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val inputs = collectHomeSurfaceInputs()
                    inputs to buildHomeSurfaceSnapshot(inputs)
                }
                if (hydrationNonce != homeSurfaceHydrationNonce || isFinishing || isDestroyed) {
                    return@launch
                }
                latestLocatorState = result.first.locatorState
                applyHomeSurfaceSnapshot(result.second, persist = true, fromCache = false)
                startupFreshHydrationReady = true
                markStartupTrace("home_surface_hydration_ready")
                maybeReportStartupReady()
            } catch (_: Exception) {
                // Keep previously rendered snapshot when hydration fails.
            } finally {
                if (hydrationNonce == homeSurfaceHydrationNonce) {
                    homeSurfaceHydrationInFlight = false
                }
            }
        }
    }

    private fun collectHomeSurfaceInputs(): HomeSurfaceInputs {
        val notificationRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notificationReady = !notificationRequired || isNotificationPermissionReady()
        val overlayReady = Settings.canDrawOverlays(this)
        val activeModeOn = SecurityScanner.isContinuousModeEnabled(this)
        val lastScan = SecurityScanner.readLastScanTimestamp(this)
        val wifi = WifiScanSnapshotStore.latest(this)

        val phishingRecent = PhishingIntakeStore.readRecent(this, limit = 20)
        val latestThreat = phishingRecent.firstOrNull()
        val phishing12 = phishingRecent.take(12)
        val phishingHigh12 = phishing12.count {
            it.result.severity == Severity.HIGH
        }
        val phishingMedium12 = phishing12.count {
            it.result.severity == Severity.MEDIUM
        }
        val phishingHigh20 = phishingRecent.count {
            it.result.severity == Severity.HIGH
        }
        val phishingMedium20 = phishingRecent.count {
            it.result.severity == Severity.MEDIUM
        }

        val pendingQueue = CredentialActionStore.loadQueue(this)
            .count { !it.status.equals("completed", ignoreCase = true) }
        val profile = PrimaryIdentityStore.readProfile(this)
        val linkedEmail = profile.primaryEmail.isNotBlank() && profile.emailLinkedAtEpochMs > 0L
        val access = PricingPolicy.resolveFeatureAccess(this)
        val incidentSummary = IncidentStore.summarize(this)
        val meshState = IntegrationMeshController.snapshot(this)

        val homeRiskEnabled = IntegrationMeshController.isModuleEnabled(
            this,
            IntegrationMeshModule.SMART_HOME_CONNECTOR
        )
        val homeRiskConsent = IntegrationMeshAuditStore.latestActiveConsent(
            context = this,
            connectorId = meshState.smartHomeConnectorId,
            ownerId = meshState.ownerId
        )

        val vpnEnabled = IntegrationMeshController.isModuleEnabled(
            this,
            IntegrationMeshModule.VPN_PROVIDER_CONNECTOR
        )
        val vpnConsent = IntegrationMeshAuditStore.latestActiveConsent(
            context = this,
            connectorId = meshState.vpnConnectorId,
            ownerId = meshState.ownerId
        )
        val vpnAssertion = VpnStatusAssertions.resolveCached(this)

        val digitalKeyEnabled = IntegrationMeshController.isModuleEnabled(
            this,
            IntegrationMeshModule.DIGITAL_KEY_RISK_ADAPTER
        )
        val rootPosture = SecurityScanner.currentRootPosture(this)
        val guardianEntries = GuardianAlertStore.readRecent(this, limit = 20)
        val connectorEvents = IntegrationMeshAuditStore.readRecentEvents(this, limit = 20)
        val locatorState = LocatorCapabilityViewModel.resolve(this)

        return HomeSurfaceInputs(
            notificationRequired = notificationRequired,
            notificationReady = notificationReady,
            overlayReady = overlayReady,
            activeModeOn = activeModeOn,
            lastScan = lastScan,
            wifi = wifi,
            latestThreat = latestThreat,
            phishingHigh12 = phishingHigh12,
            phishingMedium12 = phishingMedium12,
            phishingHigh20 = phishingHigh20,
            phishingMedium20 = phishingMedium20,
            pendingQueue = pendingQueue,
            linkedEmail = linkedEmail,
            access = access,
            incidentSummary = incidentSummary,
            meshState = meshState,
            homeRiskEnabled = homeRiskEnabled,
            homeRiskConsent = homeRiskConsent,
            vpnEnabled = vpnEnabled,
            vpnConsent = vpnConsent,
            vpnAssertion = vpnAssertion,
            digitalKeyEnabled = digitalKeyEnabled,
            rootPosture = rootPosture,
            guardianEntries = guardianEntries,
            connectorEvents = connectorEvents,
            locatorState = locatorState
        )
    }

    private fun buildHomeSurfaceSnapshot(inputs: HomeSurfaceInputs): HomeSurfaceSnapshot {
        val heroState = buildSecurityHeroStateFromInputs(inputs)
        val hasScan = inputs.lastScan != "never"

        val threatCount = inputs.phishingHigh12 + inputs.phishingMedium12
        val openIncidents = inputs.incidentSummary.openCount + inputs.incidentSummary.inProgressCount
        val timelineCount = inputs.guardianEntries.size + inputs.connectorEvents.size
        val timelineHighCount = inputs.guardianEntries.count { it.severity == Severity.HIGH }
        val timelineMediumCount = inputs.guardianEntries.count { it.severity == Severity.MEDIUM }

        val navSignals = buildNavSignalMapFromInputs(inputs, heroState)

        return HomeSurfaceSnapshot(
            capturedAtEpochMs = System.currentTimeMillis(),
            localeTag = currentLocaleTag(),
            securityScoreLabel = getString(R.string.security_score_template, heroState.score),
            securityTierLabel = heroState.tierLabel,
            securityUrgentActionsLabel = buildHomeStatusBlockFromInputs(inputs),
            widgetSweepValue = if (hasScan) {
                getString(R.string.home_widget_sweep_ready)
            } else {
                getString(R.string.home_widget_sweep_pending)
            },
            widgetSweepHint = if (hasScan) {
                getString(R.string.home_widget_last_scan_template, compactScanTimestamp(inputs.lastScan))
            } else {
                getString(R.string.home_widget_last_scan_never)
            },
            widgetThreatsValue = if (threatCount <= 0) {
                getString(R.string.home_widget_threats_clear)
            } else {
                getString(R.string.home_widget_threats_count_template, threatCount)
            },
            widgetThreatsHint = getString(
                R.string.home_widget_threats_hint_template,
                inputs.phishingHigh12,
                inputs.phishingMedium12
            ),
            widgetCredentialsValue = if (inputs.pendingQueue <= 0) {
                getString(R.string.home_widget_credentials_clear)
            } else {
                getString(R.string.home_widget_credentials_pending_template, inputs.pendingQueue)
            },
            widgetCredentialsHint = if (inputs.linkedEmail) {
                getString(R.string.home_widget_credentials_hint_linked)
            } else {
                getString(R.string.home_widget_credentials_hint_not_linked)
            },
            widgetServicesValue = if (inputs.access.paidAccess) {
                getString(R.string.home_widget_services_paid)
            } else {
                getString(R.string.home_widget_services_free)
            },
            widgetServicesHint = getString(
                R.string.home_widget_services_hint_template,
                openIncidents
            ),
            widgetHomeRiskValue = when {
                !inputs.homeRiskEnabled -> getString(R.string.home_widget_home_risk_setup_needed)
                inputs.homeRiskConsent == null -> getString(R.string.home_widget_home_risk_authorize)
                else -> getString(R.string.home_widget_home_risk_ready)
            },
            widgetHomeRiskHint = getString(
                R.string.home_widget_home_risk_hint_template,
                inputs.meshState.smartHomeConnectorId
            ),
            widgetVpnValue = when {
                !inputs.vpnEnabled -> getString(R.string.home_widget_vpn_setup_needed)
                inputs.vpnConsent == null -> getString(R.string.home_widget_vpn_authorize)
                inputs.vpnAssertion.assertion == VpnAssertionState.CONNECTED -> getString(R.string.home_widget_vpn_connected)
                inputs.vpnAssertion.assertion == VpnAssertionState.STALE -> getString(R.string.home_widget_vpn_stale)
                inputs.vpnAssertion.assertion == VpnAssertionState.CONFIGURED -> getString(R.string.home_widget_vpn_configured)
                else -> getString(R.string.home_widget_vpn_unknown)
            },
            widgetVpnHint = getString(
                R.string.home_widget_vpn_hint_template,
                inputs.vpnAssertion.providerLabel.ifBlank { inputs.meshState.vpnConnectorId }
            ),
            widgetDigitalKeyValue = if (inputs.digitalKeyEnabled) {
                getString(R.string.home_widget_digital_key_enabled)
            } else {
                getString(R.string.home_widget_digital_key_pending)
            },
            widgetDigitalKeyHint = getString(
                R.string.home_widget_digital_key_hint_template,
                rootTierLabel(inputs.rootPosture.riskTier)
            ),
            widgetTimelineValue = if (timelineCount <= 0) {
                getString(R.string.home_widget_timeline_clear)
            } else {
                getString(R.string.home_widget_timeline_events_template, timelineCount)
            },
            widgetTimelineHint = getString(
                R.string.home_widget_timeline_hint_template,
                timelineHighCount,
                timelineMediumCount
            ),
            navSignals = navSignals,
            heroState = heroState
        )
    }

    private fun buildHomeStatusBlockFromInputs(inputs: HomeSurfaceInputs): String {
        val scanReady = inputs.lastScan != "never"
        val wifiState = when {
            inputs.wifi == null -> getString(R.string.home_state_pending)
            inputs.wifi.tier.equals("high_risk", ignoreCase = true) ||
                inputs.wifi.tier.equals("elevated", ignoreCase = true) ->
                getString(R.string.home_state_attention)
            else -> getString(R.string.home_state_ready)
        }
        val threatState = when {
            inputs.latestThreat == null -> getString(R.string.home_state_pending)
            inputs.latestThreat.result.severity == Severity.HIGH ||
                inputs.latestThreat.result.severity == Severity.MEDIUM ->
                getString(R.string.home_state_attention)
            else -> getString(R.string.home_state_ready)
        }
        val credentialState = if (inputs.linkedEmail) {
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

    private fun buildNavSignalMapFromInputs(
        inputs: HomeSurfaceInputs,
        heroState: SecurityHeroState
    ): Map<HomeNavDestination, Boolean> {
        val scanNeedsAction = heroState.score < 85 || inputs.lastScan == "never"
        val threatNeedsAction = inputs.phishingHigh12 > 0 || inputs.phishingMedium12 > 0 ||
            inputs.wifi?.tier.equals("high_risk", ignoreCase = true) ||
            inputs.wifi?.tier.equals("elevated", ignoreCase = true)
        val queueHasPending = inputs.pendingQueue > 0
        val serviceNeedsAction = !inputs.access.paidAccess ||
            (inputs.incidentSummary.openCount + inputs.incidentSummary.inProgressCount > 0)
        val rootRisk = inputs.rootPosture.riskTier
        val timelineNeedsAction = inputs.guardianEntries.any {
            it.severity == Severity.HIGH || it.severity == Severity.MEDIUM
        } || inputs.connectorEvents.any { event ->
            event.riskLevel.equals("high", ignoreCase = true) ||
                event.riskLevel.equals("medium", ignoreCase = true)
        }

        return mapOf(
            HomeNavDestination.SWEEP to scanNeedsAction,
            HomeNavDestination.THREATS to threatNeedsAction,
            HomeNavDestination.CREDENTIALS to queueHasPending,
            HomeNavDestination.SERVICES to serviceNeedsAction,
            HomeNavDestination.HOME_RISK to (!inputs.homeRiskEnabled || inputs.homeRiskConsent == null),
            HomeNavDestination.VPN to (
                !inputs.vpnEnabled ||
                    inputs.vpnConsent == null ||
                    inputs.vpnAssertion.assertion != VpnAssertionState.CONNECTED
                ),
            HomeNavDestination.DIGITAL_KEY to (
                !inputs.digitalKeyEnabled ||
                    rootRisk == RootRiskTier.COMPROMISED ||
                    rootRisk == RootRiskTier.ELEVATED
                ),
            HomeNavDestination.TIMELINE to timelineNeedsAction
        )
    }

    private fun buildSecurityHeroStateFromInputs(inputs: HomeSurfaceInputs): SecurityHeroState {
        var score = 100
        val details = mutableListOf<String>()
        val actions = mutableListOf<SecurityHeroAction>()

        fun addAction(route: SecurityActionRoute, labelRes: Int) {
            if (actions.none { it.route == route }) {
                actions += SecurityHeroAction(getString(labelRes), route)
            }
        }

        if (inputs.notificationRequired && !inputs.notificationReady) {
            score -= 15
            details += getString(R.string.security_detail_notification_missing)
            addAction(SecurityActionRoute.FIX_NOTIFICATIONS, R.string.security_action_fix_notifications)
        }

        if (!inputs.overlayReady) {
            score -= 15
            details += getString(R.string.security_detail_overlay_missing)
            addAction(SecurityActionRoute.FIX_OVERLAY, R.string.security_action_fix_overlay)
        }

        if (inputs.wifi == null) {
            score -= 10
            details += getString(R.string.security_detail_wifi_scan_missing)
            addAction(SecurityActionRoute.RUN_WIFI_POSTURE_SCAN, R.string.security_action_run_wifi_scan)
        } else {
            when (inputs.wifi.tier.lowercase(Locale.US)) {
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

        if (inputs.locatorState.enabled) {
            when {
                !inputs.locatorState.hasProvidersConfigured -> {
                    score -= 10
                    details += getString(R.string.security_detail_locator_not_configured)
                    addAction(
                        SecurityActionRoute.OPEN_DEVICE_LOCATOR_SETUP,
                        R.string.security_action_setup_locator
                    )
                }

                !inputs.locatorState.hasLaunchableProvider -> {
                    score -= 10
                    details += getString(R.string.security_detail_locator_unavailable)
                    addAction(
                        SecurityActionRoute.OPEN_DEVICE_LOCATOR_SETUP,
                        R.string.security_action_setup_locator
                    )
                }

                !inputs.locatorState.primaryEmailLinked -> {
                    score -= 6
                    details += getString(R.string.security_detail_locator_not_linked)
                    addAction(
                        SecurityActionRoute.OPEN_DEVICE_LOCATOR_SETUP,
                        R.string.security_action_setup_locator
                    )
                }

                inputs.locatorState.providers.none { it.packageLaunchReady || it.deepLinkReady } -> {
                    score -= 3
                    details += getString(R.string.security_detail_locator_web_only)
                    addAction(
                        SecurityActionRoute.OPEN_DEVICE_LOCATOR_PROVIDER,
                        R.string.security_action_open_locator
                    )
                }
            }
        }

        if (inputs.lastScan == "never") {
            score -= 20
            details += getString(R.string.security_detail_scan_missing)
            addAction(SecurityActionRoute.RUN_SCAN, R.string.security_action_run_scan)
        }

        when (inputs.rootPosture.riskTier) {
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

        if (inputs.phishingHigh20 > 0) {
            score -= (inputs.phishingHigh20 * 4).coerceAtMost(20)
            details += getString(R.string.security_detail_scam_high_template, inputs.phishingHigh20)
            addAction(SecurityActionRoute.RUN_PHISHING_TRIAGE, R.string.security_action_run_scam_triage)
        } else if (inputs.phishingMedium20 > 0) {
            score -= (inputs.phishingMedium20 * 2).coerceAtMost(10)
            details += getString(R.string.security_detail_scam_medium_template, inputs.phishingMedium20)
            addAction(SecurityActionRoute.RUN_PHISHING_TRIAGE, R.string.security_action_run_scam_triage)
        }

        val unresolvedIncidents = inputs.incidentSummary.openCount + inputs.incidentSummary.inProgressCount
        if (unresolvedIncidents > 0) {
            score -= (unresolvedIncidents * 3).coerceAtMost(18)
            details += getString(R.string.security_detail_incident_backlog_template, unresolvedIncidents)
            addAction(SecurityActionRoute.START_INCIDENT, R.string.security_action_start_incident)
        }

        if (!inputs.activeModeOn) {
            score -= 6
            details += getString(R.string.security_detail_continuous_off)
        }

        if (!inputs.access.paidAccess) {
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
            actions = actions.take(4),
            details = details.take(6)
        )
    }

    private fun applyHomeSurfaceSnapshot(
        snapshot: HomeSurfaceSnapshot,
        persist: Boolean,
        fromCache: Boolean
    ) {
        latestHomeSurfaceSnapshot = snapshot
        latestHomeNavSignals = snapshot.navSignals
        snapshot.heroState?.let { state ->
            latestSecurityHeroState = state
            latestRiskCards = listOf(toRiskCardModel(state))
        }
        binding.securityScoreLabel.text = snapshot.securityScoreLabel
        binding.securityTierLabel.text = snapshot.securityTierLabel
        binding.securityUrgentActionsLabel.text = snapshot.securityUrgentActionsLabel
        binding.securityTopActionButton.text = getString(R.string.action_scan_now_all)

        binding.widgetSweepValue.text = snapshot.widgetSweepValue
        binding.widgetSweepHint.text = snapshot.widgetSweepHint
        binding.widgetThreatsValue.text = snapshot.widgetThreatsValue
        binding.widgetThreatsHint.text = snapshot.widgetThreatsHint
        binding.widgetCredentialsValue.text = snapshot.widgetCredentialsValue
        binding.widgetCredentialsHint.text = snapshot.widgetCredentialsHint
        binding.widgetServicesValue.text = snapshot.widgetServicesValue
        binding.widgetServicesHint.text = snapshot.widgetServicesHint
        binding.widgetHomeRiskValue.text = snapshot.widgetHomeRiskValue
        binding.widgetHomeRiskHint.text = snapshot.widgetHomeRiskHint
        binding.widgetVpnValue.text = snapshot.widgetVpnValue
        binding.widgetVpnHint.text = snapshot.widgetVpnHint
        binding.widgetDigitalKeyValue.text = snapshot.widgetDigitalKeyValue
        binding.widgetDigitalKeyHint.text = snapshot.widgetDigitalKeyHint
        binding.widgetTimelineValue.text = snapshot.widgetTimelineValue
        binding.widgetTimelineHint.text = snapshot.widgetTimelineHint

        applyWidgetPage(pageIndex = homeWidgetPageIndex, animate = false)
        refreshBottomNavIndicators()

        if (persist && !fromCache) {
            writeHomeSurfaceSnapshotCache(snapshot)
        }
        if (fromCache) {
            markStartupTrace("home_surface_cache_applied")
        }
    }

    private fun currentLocaleTag(): String {
        val locales = resources.configuration.locales
        return if (locales.isEmpty) {
            Locale.getDefault().toLanguageTag()
        } else {
            locales[0].toLanguageTag()
        }
    }

    private fun applyCachedHomeSurfaceSnapshot() {
        readHomeSurfaceSnapshotCache()?.let { cached ->
            applyHomeSurfaceSnapshot(cached, persist = false, fromCache = true)
        }
    }

    private fun readHomeSurfaceSnapshotCache(): HomeSurfaceSnapshot? {
        val prefs = getSharedPreferences(HOME_SURFACE_CACHE_PREFS_FILE, MODE_PRIVATE)
        val raw = prefs.getString(HOME_SURFACE_CACHE_PAYLOAD_KEY, "")?.trim().orEmpty()
        if (raw.isBlank()) {
            return null
        }
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (payload.optInt("version", 0) != HOME_SURFACE_CACHE_VERSION) {
            return null
        }
        val localeTag = payload.optString("locale_tag", "")
        if (!localeTag.equals(currentLocaleTag(), ignoreCase = true)) {
            return null
        }
        val navPayload = payload.optJSONObject("nav_signals") ?: JSONObject()
        val navSignals = HomeNavDestination.entries.associateWith { destination ->
            navPayload.optBoolean(destination.name, false)
        }

        return HomeSurfaceSnapshot(
            capturedAtEpochMs = payload.optLong("captured_at_epoch_ms", 0L),
            localeTag = localeTag,
            securityScoreLabel = payload.optString("security_score_label", ""),
            securityTierLabel = payload.optString("security_tier_label", ""),
            securityUrgentActionsLabel = payload.optString("security_urgent_actions_label", ""),
            widgetSweepValue = payload.optString("widget_sweep_value", ""),
            widgetSweepHint = payload.optString("widget_sweep_hint", ""),
            widgetThreatsValue = payload.optString("widget_threats_value", ""),
            widgetThreatsHint = payload.optString("widget_threats_hint", ""),
            widgetCredentialsValue = payload.optString("widget_credentials_value", ""),
            widgetCredentialsHint = payload.optString("widget_credentials_hint", ""),
            widgetServicesValue = payload.optString("widget_services_value", ""),
            widgetServicesHint = payload.optString("widget_services_hint", ""),
            widgetHomeRiskValue = payload.optString("widget_home_risk_value", ""),
            widgetHomeRiskHint = payload.optString("widget_home_risk_hint", ""),
            widgetVpnValue = payload.optString("widget_vpn_value", ""),
            widgetVpnHint = payload.optString("widget_vpn_hint", ""),
            widgetDigitalKeyValue = payload.optString("widget_digital_key_value", ""),
            widgetDigitalKeyHint = payload.optString("widget_digital_key_hint", ""),
            widgetTimelineValue = payload.optString("widget_timeline_value", ""),
            widgetTimelineHint = payload.optString("widget_timeline_hint", ""),
            navSignals = navSignals,
            heroState = null
        )
    }

    private fun writeHomeSurfaceSnapshotCache(snapshot: HomeSurfaceSnapshot) {
        val navSignals = JSONObject()
        HomeNavDestination.entries.forEach { destination ->
            navSignals.put(destination.name, snapshot.navSignals[destination] == true)
        }
        val payload = JSONObject()
            .put("version", HOME_SURFACE_CACHE_VERSION)
            .put("captured_at_epoch_ms", snapshot.capturedAtEpochMs)
            .put("locale_tag", snapshot.localeTag)
            .put("security_score_label", snapshot.securityScoreLabel)
            .put("security_tier_label", snapshot.securityTierLabel)
            .put("security_urgent_actions_label", snapshot.securityUrgentActionsLabel)
            .put("widget_sweep_value", snapshot.widgetSweepValue)
            .put("widget_sweep_hint", snapshot.widgetSweepHint)
            .put("widget_threats_value", snapshot.widgetThreatsValue)
            .put("widget_threats_hint", snapshot.widgetThreatsHint)
            .put("widget_credentials_value", snapshot.widgetCredentialsValue)
            .put("widget_credentials_hint", snapshot.widgetCredentialsHint)
            .put("widget_services_value", snapshot.widgetServicesValue)
            .put("widget_services_hint", snapshot.widgetServicesHint)
            .put("widget_home_risk_value", snapshot.widgetHomeRiskValue)
            .put("widget_home_risk_hint", snapshot.widgetHomeRiskHint)
            .put("widget_vpn_value", snapshot.widgetVpnValue)
            .put("widget_vpn_hint", snapshot.widgetVpnHint)
            .put("widget_digital_key_value", snapshot.widgetDigitalKeyValue)
            .put("widget_digital_key_hint", snapshot.widgetDigitalKeyHint)
            .put("widget_timeline_value", snapshot.widgetTimelineValue)
            .put("widget_timeline_hint", snapshot.widgetTimelineHint)
            .put("nav_signals", navSignals)

        getSharedPreferences(HOME_SURFACE_CACHE_PREFS_FILE, MODE_PRIVATE)
            .edit()
            .putString(HOME_SURFACE_CACHE_PAYLOAD_KEY, payload.toString())
            .apply()
    }

    private fun refreshSecurityHero() {
        latestHomeSurfaceSnapshot?.let { snapshot ->
            applyHomeSurfaceSnapshot(snapshot, persist = false, fromCache = false)
            return
        }
        refreshHomeSurface(force = true)
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
            GuidedDestination.SWEEP -> runOneTimeScan()
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
        val signals = if (latestHomeNavSignals.isNotEmpty()) {
            latestHomeNavSignals
        } else {
            buildNavSignalMap()
        }
        applyNavSignal(
            binding.navScanButton,
            binding.navScanDot,
            signals[destinationForSlot(navIconPageIndex, NavSlotPosition.LEFT_OUTER)] == true
        )
        applyNavSignal(
            binding.navGuardButton,
            binding.navGuardDot,
            signals[destinationForSlot(navIconPageIndex, NavSlotPosition.LEFT_INNER)] == true
        )
        applyNavSignal(
            binding.navVaultButton,
            binding.navVaultDot,
            signals[destinationForSlot(navIconPageIndex, NavSlotPosition.RIGHT_INNER)] == true
        )
        applyNavSignal(
            binding.navSupportButton,
            binding.navSupportDot,
            signals[destinationForSlot(navIconPageIndex, NavSlotPosition.RIGHT_OUTER)] == true
        )
    }

    private fun refreshHomeQuickWidgets() {
        latestHomeSurfaceSnapshot?.let { snapshot ->
            applyHomeSurfaceSnapshot(snapshot, persist = false, fromCache = false)
            return
        }
        refreshHomeSurface(force = true)
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
        val terminalVisible = binding.scanTerminalCard.visibility == View.VISIBLE &&
            binding.scanTerminalCard.height > 0
        val terminalBottom = if (terminalVisible) {
            val terminalLoc = IntArray(2)
            binding.scanTerminalCard.getLocationOnScreen(terminalLoc)
            terminalLoc[1].toFloat() + binding.scanTerminalCard.height
        } else {
            titleLoc[1].toFloat() + binding.homeFrameTitleLabel.height
        }

        val titleBottom = titleLoc[1].toFloat() + binding.homeFrameTitleLabel.height
        val scanTop = scanLoc[1].toFloat()
        val heroTop = heroLoc[1].toFloat() + binding.homeHeroContent.paddingTop
        val heroBottom = heroLoc[1].toFloat() + binding.homeHeroContent.height - binding.homeHeroContent.paddingBottom
        val safeTop = if (terminalVisible) {
            maxOf(titleBottom + dpToPx(10f), terminalBottom + dpToPx(8f), heroTop)
        } else {
            maxOf(titleBottom + dpToPx(10f), heroTop)
        }
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
        navView.alpha = if (active) 1f else 0.72f
        navView.isActivated = active
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

        LionAlertDialogBuilder(this)
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
        LionAlertDialogBuilder(this)
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
        LionAlertDialogBuilder(this)
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
