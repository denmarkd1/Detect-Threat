package com.realyn.watchdog

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.realyn.watchdog.databinding.ActivityGuardianSettingsBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.realyn.watchdog.theme.LionIdentityAccentStyle
import com.realyn.watchdog.theme.LionThemeCatalog
import com.realyn.watchdog.theme.LionThemePalette
import com.realyn.watchdog.theme.LionThemeToneMode
import com.realyn.watchdog.theme.LionThemeViewStyler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GuardianSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuardianSettingsBinding
    private var accessGateBootstrapped: Boolean = false
    private var syncingIntroSwitches: Boolean = false
    private var syncingChildOverrideSwitch: Boolean = false
    private var isAppearanceSubtitleExpanded: Boolean = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideStatusRunnable = Runnable { hideSettingsStatusWithFade() }
    private val deviceUmbrellaScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents?.trim().orEmpty()
        if (content.isBlank()) {
            showSettingsStatus(getString(R.string.device_umbrella_status_scan_cancelled))
            return@registerForActivityResult
        }
        runDeviceUmbrellaJoin(content)
    }
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchDeviceUmbrellaQrScanner()
            } else {
                showSettingsStatus(getString(R.string.device_umbrella_status_camera_denied))
            }
        }

    private companion object {
        const val APPEARANCE_SUBTITLE_COLLAPSED_LINES = 2
        const val SETTINGS_STATUS_FADE_DELAY_MS = 2800L
        const val SETTINGS_STATUS_FADE_DURATION_MS = 180L
        const val STATE_KEY_APPEARANCE_SUBTITLE_EXPANDED = "appearance_subtitle_expanded"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isAppearanceSubtitleExpanded = savedInstanceState?.getBoolean(
            STATE_KEY_APPEARANCE_SUBTITLE_EXPANDED,
            false
        ) ?: false
        setupAppearanceSubtitleToggle()
        binding.settingsToolbar.setNavigationOnClickListener { finish() }
        binding.cycleThemeShadeButton.setOnClickListener { cycleThemeShade() }
        binding.cycleFillModeButton.setOnClickListener { cycleFillMode() }
        binding.chooseMaleProfileButton.setOnClickListener {
            cycleIdentityProfile(LionThemePrefs.LionIdentityAudience.MALE)
        }
        binding.chooseFemaleProfileButton.setOnClickListener {
            cycleIdentityProfile(LionThemePrefs.LionIdentityAudience.FEMALE)
        }
        binding.chooseNonBinaryProfileButton.setOnClickListener {
            cycleIdentityProfile(LionThemePrefs.LionIdentityAudience.NON_BINARY)
        }
        binding.settingsChildMainOverrideSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingChildOverrideSwitch) {
                return@setOnCheckedChangeListener
            }
            onChildMainOverrideToggled(isChecked)
        }
        binding.setUsStateOverrideButton.setOnClickListener { openUsStateOverrideDialog() }
        binding.previewAccentSwatch.setOnClickListener { cycleThemeShade() }
        binding.previewToneSwatch.setOnClickListener { toggleLightDarkTone() }
        binding.settingsRunTutorialButton.setOnClickListener {
            openMainForSettingsAction(MainActivity.GUARDIAN_SETTINGS_ACTION_OPEN_TUTORIAL)
        }
        binding.openPlanBillingButton.setOnClickListener {
            openMainForSettingsAction(MainActivity.GUARDIAN_SETTINGS_ACTION_OPEN_PLAN_BILLING)
        }
        binding.openAiConnectionButton.setOnClickListener {
            openMainForSettingsAction(MainActivity.GUARDIAN_SETTINGS_ACTION_OPEN_AI_CONNECTION)
        }
        binding.openLanguageButton.setOnClickListener { openLanguageSettings() }
        binding.openLocatorSetupButton.setOnClickListener {
            openMainForSettingsAction(MainActivity.GUARDIAN_SETTINGS_ACTION_OPEN_LOCATOR_SETUP)
        }
        binding.openHomeRiskSetupButton.setOnClickListener {
            openMainForSettingsAction(MainActivity.GUARDIAN_SETTINGS_ACTION_OPEN_HOME_RISK_SETUP)
        }
        binding.openVpnSetupButton.setOnClickListener {
            openMainForSettingsAction(MainActivity.GUARDIAN_SETTINGS_ACTION_OPEN_VPN_SETUP)
        }
        binding.openCredentialCenterButton.setOnClickListener {
            startActivity(Intent(this, CredentialDefenseActivity::class.java))
        }
        binding.deviceUmbrellaCreateButton.setOnClickListener { createDeviceUmbrellaLinkCode() }
        binding.deviceUmbrellaJoinButton.setOnClickListener { openDeviceUmbrellaJoinDialog() }
        binding.deviceUmbrellaScanQrButton.setOnClickListener { openDeviceUmbrellaQrScanner() }
        binding.deviceUmbrellaReviewJoinRequestsButton.setOnClickListener { reviewDeviceUmbrellaJoinRequests() }
        binding.deviceUmbrellaSyncButton.setOnClickListener { runDeviceUmbrellaSyncNow() }
        binding.deviceUmbrellaCopyPayloadButton.setOnClickListener { copyDeviceUmbrellaPayload() }
        binding.settingsIntroReplaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingIntroSwitches) {
                return@setOnCheckedChangeListener
            }
            MainActivity.setHomeIntroReplayEveryLaunch(this, isChecked)
            showSettingsStatus(
                getString(
                    if (isChecked) {
                        R.string.guardian_settings_intro_mode_every_launch
                    } else {
                        R.string.guardian_settings_intro_mode_first_launch
                    }
                )
            )
        }
        binding.settingsIntroWidgetMotionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingIntroSwitches) {
                return@setOnCheckedChangeListener
            }
            MainActivity.setHomeIntroWidgetMotionEnabled(this, isChecked)
            showSettingsStatus(
                getString(
                    if (isChecked) {
                        R.string.guardian_settings_intro_motion_enabled
                    } else {
                        R.string.guardian_settings_intro_motion_disabled
                    }
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        enforceAccessGateAndRefresh()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            AppAccessGate.onAppBackgrounded(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_KEY_APPEARANCE_SUBTITLE_EXPANDED, isAppearanceSubtitleExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        binding.settingsStatusLabel.animate().cancel()
        uiHandler.removeCallbacks(hideStatusRunnable)
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        AppAccessGate.onUserInteraction()
    }

    private fun enforceAccessGateAndRefresh() {
        AppAccessGate.ensureUnlocked(
            activity = this,
            onUnlocked = {
                if (!accessGateBootstrapped) {
                    accessGateBootstrapped = true
                    binding.settingsStatusLabel.animate().cancel()
                    binding.settingsStatusLabel.alpha = 1f
                    binding.settingsStatusLabel.visibility = View.INVISIBLE
                    uiHandler.removeCallbacks(hideStatusRunnable)
                }
                refreshSettingsUi()
            },
            onDenied = { finish() }
        )
    }

    private fun refreshSettingsUi() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val profileControl = PricingPolicy.resolveProfileControl(this, access)
        val selectedBitmap = LionThemePrefs.resolveSelectedLionBitmap(this)
        val fillMode = LionThemePrefs.readFillMode(this)
        val toneMode = LionThemePrefs.readToneMode(this)
        val childMainOverride = LionThemePrefs.readAllowChildProfilesAsMain(this)
        val canToggleChildOverride = profileControl.canManagePlan
        val themeState = LionThemeCatalog.resolveState(
            context = this,
            paidAccess = access.paidAccess,
            selectedLionBitmap = selectedBitmap
        )

        applySettingsTheme(
            palette = themeState.palette,
            isDarkTone = themeState.isDark,
            accentStyle = themeState.accentStyle
        )
        binding.settingThemeShadeValue.text = getString(themeState.variant.labelRes)
        binding.settingThemeToneValue.text = getString(toneMode.labelRes)
        binding.settingFillModeValue.text = getString(fillMode.labelRes)
        binding.settingLionAssetValue.text = themeState.identityProfile.label
        binding.settingChildMainOverrideValue.text = getString(
            if (childMainOverride) {
                R.string.pricing_feature_enabled
            } else {
                R.string.pricing_feature_locked
            }
        )
        val usStateOverride = LionThemePrefs.readCannabisUsStateOverride(this)
        binding.settingUsStateOverrideValue.text = usStateOverride
            ?: getString(R.string.guardian_settings_us_state_override_auto)

        binding.settingsLionPreview.setFillMode(fillMode)
        binding.settingsLionPreview.setImageOffsetY(0f)
        binding.settingsLionPreview.setSurfaceTone(
            LionThemePrefs.shouldUseDarkLionPresentation(themeState.isDark)
        )
        binding.settingsLionPreview.setLionBitmap(selectedBitmap)
        binding.settingsLionPreview.setAccentColor(themeState.palette.accent)
        binding.settingsLionPreview.setIdleState()
        binding.previewToneSwatch.setBackgroundColor(
            if (themeState.isDark) themeState.palette.backgroundEnd else Color.WHITE
        )
        binding.previewToneSwatch.contentDescription = getString(
            if (themeState.isDark) {
                R.string.guardian_settings_preview_tone_toggle_dark
            } else {
                R.string.guardian_settings_preview_tone_toggle_light
            }
        )

        val maleProfiles = LionThemePrefs.listIdentityProfilesForAudience(
            context = this,
            audience = LionThemePrefs.LionIdentityAudience.MALE,
            paidAccess = access.paidAccess
        )
        val femaleProfiles = LionThemePrefs.listIdentityProfilesForAudience(
            context = this,
            audience = LionThemePrefs.LionIdentityAudience.FEMALE,
            paidAccess = access.paidAccess
        )
        val nonBinaryProfiles = LionThemePrefs.listIdentityProfilesForAudience(
            context = this,
            audience = LionThemePrefs.LionIdentityAudience.NON_BINARY,
            paidAccess = access.paidAccess
        )
        binding.chooseMaleProfileButton.isEnabled = maleProfiles.isNotEmpty()
        binding.chooseMaleProfileButton.alpha = if (maleProfiles.isNotEmpty()) 1f else 0.60f
        binding.chooseFemaleProfileButton.isEnabled = femaleProfiles.isNotEmpty()
        binding.chooseFemaleProfileButton.alpha = if (femaleProfiles.isNotEmpty()) 1f else 0.60f
        binding.chooseNonBinaryProfileButton.isEnabled = nonBinaryProfiles.isNotEmpty()
        binding.chooseNonBinaryProfileButton.alpha = if (nonBinaryProfiles.isNotEmpty()) 1f else 0.60f
        syncingChildOverrideSwitch = true
        binding.settingsChildMainOverrideSwitch.isChecked = childMainOverride
        binding.settingsChildMainOverrideSwitch.isEnabled = canToggleChildOverride
        binding.settingsChildMainOverrideSwitch.alpha = if (canToggleChildOverride) 1f else 0.60f
        syncingChildOverrideSwitch = false
        syncingIntroSwitches = true
        binding.settingsIntroReplaySwitch.isChecked = MainActivity.isHomeIntroReplayEveryLaunch(this)
        binding.settingsIntroWidgetMotionSwitch.isChecked = MainActivity.isHomeIntroWidgetMotionEnabled(this)
        syncingIntroSwitches = false
        refreshDeviceUmbrellaSummary()
    }

    private fun cycleThemeShade() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val next = LionThemePrefs.cycleThemeVariant(this, access.paidAccess)
        refreshSettingsUi()
        showSettingsStatus(getString(
            R.string.lion_theme_variant_status_template,
            getString(next.labelRes)
        ))
    }

    private fun toggleLightDarkTone() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val selectedBitmap = LionThemePrefs.resolveSelectedLionBitmap(this)
        val currentState = LionThemeCatalog.resolveState(
            context = this,
            paidAccess = access.paidAccess,
            selectedLionBitmap = selectedBitmap
        )
        val nextTone = if (currentState.isDark) {
            LionThemeToneMode.LIGHT
        } else {
            LionThemeToneMode.DARK
        }
        LionThemePrefs.writeToneMode(this, nextTone)
        refreshSettingsUi()
        showSettingsStatus(getString(
            R.string.lion_theme_tone_status_template,
            getString(nextTone.labelRes)
        ))
    }

    private fun cycleFillMode() {
        val next = LionThemePrefs.readFillMode(this).next()
        LionThemePrefs.writeFillMode(this, next)
        refreshSettingsUi()
        showSettingsStatus(getString(
            R.string.lion_mode_status_template,
            getString(next.labelRes)
        ))
    }

    private fun cycleIdentityProfile(audience: LionThemePrefs.LionIdentityAudience) {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val next = LionThemePrefs.cycleIdentityProfileForAudience(
            context = this,
            audience = audience,
            paidAccess = access.paidAccess
        ) ?: run {
            showSettingsStatus(
                getString(
                    R.string.guardian_settings_no_profiles_for_category_template,
                    getString(profileCategoryLabelRes(audience))
                )
            )
            return
        }
        refreshSettingsUi()
        showSettingsStatus(
            getString(
                R.string.lion_identity_profile_status_template,
                next.label
            )
        )
    }

    private fun onChildMainOverrideToggled(enabled: Boolean) {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val profileControl = PricingPolicy.resolveProfileControl(this, access)
        if (!profileControl.canManagePlan) {
            syncingChildOverrideSwitch = true
            binding.settingsChildMainOverrideSwitch.isChecked =
                LionThemePrefs.readAllowChildProfilesAsMain(this)
            syncingChildOverrideSwitch = false
            showSettingsStatus(getString(R.string.guardian_settings_child_main_override_guardian_only))
            return
        }
        if (enabled && !access.paidAccess) {
            syncingChildOverrideSwitch = true
            binding.settingsChildMainOverrideSwitch.isChecked = false
            syncingChildOverrideSwitch = false
            LionThemePrefs.writeAllowChildProfilesAsMain(this, false)
            refreshSettingsUi()
            showSettingsStatus(getString(R.string.guardian_settings_child_main_override_pro_only))
            return
        }
        LionThemePrefs.writeAllowChildProfilesAsMain(this, enabled)
        refreshSettingsUi()
        showSettingsStatus(
            getString(
                if (enabled) {
                    R.string.guardian_settings_child_main_override_enabled
                } else {
                    R.string.guardian_settings_child_main_override_disabled
                }
            )
        )
    }

    private fun profileCategoryLabelRes(audience: LionThemePrefs.LionIdentityAudience): Int {
        return when (audience) {
            LionThemePrefs.LionIdentityAudience.MALE -> R.string.lion_identity_profile_male
            LionThemePrefs.LionIdentityAudience.FEMALE -> R.string.lion_identity_profile_female
            LionThemePrefs.LionIdentityAudience.NON_BINARY -> R.string.lion_identity_profile_non_binary
        }
    }

    private fun openLanguageSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_LOCALE_SETTINGS)
        }
        runCatching { startActivity(intent) }
            .onFailure { showSettingsStatus(getString(R.string.translation_settings_open_failed)) }
    }

    private fun openUsStateOverrideDialog() {
        val current = LionThemePrefs.readCannabisUsStateOverride(this).orEmpty()
        val input = EditText(this).apply {
            setText(current)
            hint = "CA"
            filters = arrayOf(InputFilter.LengthFilter(2))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            isSingleLine = true
            setSelection(text?.length ?: 0)
        }
        LionAlertDialogBuilder(this)
            .setTitle(R.string.guardian_settings_us_state_override_dialog_title)
            .setMessage(R.string.guardian_settings_us_state_override_dialog_message)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val raw = input.text?.toString().orEmpty().trim()
                applyUsStateOverride(raw)
            }
            .setNeutralButton(R.string.guardian_settings_action_clear_us_state_override) { _, _ ->
                LionThemePrefs.writeCannabisUsStateOverride(this, null)
                refreshSettingsUi()
                showSettingsStatus(
                    getString(
                        R.string.guardian_settings_us_state_override_status_template,
                        getString(R.string.guardian_settings_us_state_override_auto)
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyUsStateOverride(raw: String) {
        if (raw.isBlank()) {
            LionThemePrefs.writeCannabisUsStateOverride(this, null)
            refreshSettingsUi()
            showSettingsStatus(
                getString(
                    R.string.guardian_settings_us_state_override_status_template,
                    getString(R.string.guardian_settings_us_state_override_auto)
                )
            )
            return
        }
        val normalized = raw.uppercase(Locale.US)
        if (!normalized.matches(Regex("^[A-Z]{2}$"))) {
            showSettingsStatus(getString(R.string.guardian_settings_us_state_override_invalid))
            return
        }
        LionThemePrefs.writeCannabisUsStateOverride(this, normalized)
        refreshSettingsUi()
        showSettingsStatus(
            getString(
                R.string.guardian_settings_us_state_override_status_template,
                normalized
            )
        )
    }

    private fun showSettingsStatus(message: CharSequence) {
        if (isFinishing || isDestroyed) {
            return
        }
        binding.settingsStatusLabel.text = message
        binding.settingsStatusLabel.animate().cancel()
        binding.settingsStatusLabel.alpha = 1f
        binding.settingsStatusLabel.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideStatusRunnable)
        uiHandler.postDelayed(hideStatusRunnable, SETTINGS_STATUS_FADE_DELAY_MS)
    }

    private fun hideSettingsStatusWithFade() {
        if (isFinishing || isDestroyed) {
            return
        }
        if (binding.settingsStatusLabel.visibility != View.VISIBLE) {
            return
        }
        val statusLabel = binding.settingsStatusLabel
        statusLabel.animate().cancel()
        statusLabel.animate()
            .alpha(0f)
            .setDuration(SETTINGS_STATUS_FADE_DURATION_MS)
            .withEndAction {
                if (isFinishing || isDestroyed) {
                    return@withEndAction
                }
                statusLabel.visibility = View.INVISIBLE
                statusLabel.alpha = 1f
            }
            .start()
    }

    private fun setupAppearanceSubtitleToggle() {
        binding.settingsAppearanceSubtitleToggle.setOnClickListener {
            isAppearanceSubtitleExpanded = !isAppearanceSubtitleExpanded
            applyAppearanceSubtitleExpansion(announceChange = true)
        }
        applyAppearanceSubtitleExpansion()
    }

    private fun applyAppearanceSubtitleExpansion(announceChange: Boolean = false) {
        binding.settingsAppearanceSubtitle.maxLines = if (isAppearanceSubtitleExpanded) {
            Int.MAX_VALUE
        } else {
            APPEARANCE_SUBTITLE_COLLAPSED_LINES
        }
        val toggleTextRes = if (isAppearanceSubtitleExpanded) {
            R.string.guardian_settings_action_show_less
        } else {
            R.string.guardian_settings_action_show_more
        }
        binding.settingsAppearanceSubtitleToggle.text = getString(toggleTextRes)
        val toggleContentDescriptionRes = if (isAppearanceSubtitleExpanded) {
            R.string.guardian_settings_action_show_less_content_description
        } else {
            R.string.guardian_settings_action_show_more_content_description
        }
        binding.settingsAppearanceSubtitleToggle.contentDescription =
            getString(toggleContentDescriptionRes)
        if (announceChange) {
            binding.settingsAppearanceSubtitleToggle.announceForAccessibility(
                getString(
                    if (isAppearanceSubtitleExpanded) {
                        R.string.guardian_settings_section_appearance_expanded_announcement
                    } else {
                        R.string.guardian_settings_section_appearance_collapsed_announcement
                    }
                )
            )
        }
    }

    private fun openMainForSettingsAction(action: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_GUARDIAN_SETTINGS_ACTION, action)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun refreshDeviceUmbrellaSummary() {
        val state = DeviceUmbrellaSyncStore.readState(this)
        if (state == null) {
            binding.deviceUmbrellaStatusLabel.text = getString(R.string.device_umbrella_status_not_linked)
            binding.deviceUmbrellaCopyPayloadButton.isEnabled = false
            binding.deviceUmbrellaSyncButton.isEnabled = false
            binding.deviceUmbrellaReviewJoinRequestsButton.isEnabled = false
            return
        }

        val roleLabel = if (state.memberRole.equals("host", ignoreCase = true)) {
            getString(R.string.device_umbrella_role_host)
        } else {
            getString(R.string.device_umbrella_role_member)
        }
        val lastSyncText = if (state.lastSyncAtEpochMs > 0L) {
            formatUtcTime(state.lastSyncAtEpochMs)
        } else {
            getString(R.string.device_umbrella_last_sync_never)
        }
        binding.deviceUmbrellaStatusLabel.text = getString(
            R.string.device_umbrella_status_linked_template,
            state.umbrellaId.ifBlank { "umbrella" },
            roleLabel,
            lastSyncText,
            state.lastRemoteEnvelopeCount,
            state.lastRemoteHighCount,
            state.lastRemoteMediumCount
        )
        binding.deviceUmbrellaCopyPayloadButton.isEnabled = state.qrPayloadUri.isNotBlank()
        binding.deviceUmbrellaSyncButton.isEnabled = true
        binding.deviceUmbrellaReviewJoinRequestsButton.isEnabled =
            state.memberRole.equals("host", ignoreCase = true)
    }

    private fun createDeviceUmbrellaLinkCode() {
        setDeviceUmbrellaBusy(true)
        showSettingsStatus(getString(R.string.device_umbrella_status_creating))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DeviceUmbrellaSyncClient.createSession(this@GuardianSettingsActivity) }
            }
            setDeviceUmbrellaBusy(false)
            result.onSuccess { created ->
                refreshSettingsUi()
                showSettingsStatus(getString(R.string.device_umbrella_status_created))
                showDeviceUmbrellaPairReadyDialog(created.state)
            }.onFailure { error ->
                showSettingsStatus(
                    getString(
                        R.string.device_umbrella_status_error_template,
                        cleanErrorMessage(error)
                    )
                )
            }
        }
    }

    private fun openDeviceUmbrellaJoinDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.device_umbrella_join_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }

        LionAlertDialogBuilder(this)
            .setTitle(R.string.device_umbrella_join_dialog_title)
            .setMessage(R.string.device_umbrella_join_dialog_message)
            .setView(input)
            .setPositiveButton(R.string.device_umbrella_action_join_code) { _, _ ->
                runDeviceUmbrellaJoin(input.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDeviceUmbrellaQrScanner() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchDeviceUmbrellaQrScanner()
            return
        }
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchDeviceUmbrellaQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.device_umbrella_scan_prompt))
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            setOrientationLocked(true)
        }
        deviceUmbrellaScanLauncher.launch(options)
    }

    private fun reviewDeviceUmbrellaJoinRequests() {
        val state = DeviceUmbrellaSyncStore.readState(this)
        if (state == null) {
            showSettingsStatus(getString(R.string.device_umbrella_status_not_linked))
            return
        }
        if (!state.memberRole.equals("host", ignoreCase = true)) {
            showSettingsStatus(getString(R.string.device_umbrella_status_host_only_approval))
            return
        }
        setDeviceUmbrellaBusy(true)
        showSettingsStatus(getString(R.string.device_umbrella_status_reviewing_requests))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DeviceUmbrellaSyncClient.listPendingJoinRequests(this@GuardianSettingsActivity) }
            }
            setDeviceUmbrellaBusy(false)
            result.onSuccess { requests ->
                if (requests.length() == 0) {
                    showSettingsStatus(getString(R.string.device_umbrella_status_no_pending_requests))
                    return@onSuccess
                }
                showJoinRequestDecisionDialog(requests)
            }.onFailure { error ->
                showSettingsStatus(
                    getString(
                        R.string.device_umbrella_status_error_template,
                        cleanErrorMessage(error)
                    )
                )
            }
        }
    }

    private fun showJoinRequestDecisionDialog(requests: JSONArray, index: Int = 0) {
        if (index >= requests.length()) {
            showSettingsStatus(getString(R.string.device_umbrella_status_no_pending_requests))
            return
        }
        val request = requests.optJSONObject(index)
        if (request == null) {
            showJoinRequestDecisionDialog(requests, index + 1)
            return
        }
        val requestId = request.optString("request_id").trim()
        if (requestId.isBlank()) {
            showJoinRequestDecisionDialog(requests, index + 1)
            return
        }
        val alias = request.optString("device_alias").trim().ifBlank { "Android device" }
        val model = request.optString("device_model").trim().ifBlank { "unknown model" }
        val requestedAt = request.optString("requested_at").trim().ifBlank { "unknown time" }
        val pendingCount = (requests.length() - index).coerceAtLeast(1)
        LionAlertDialogBuilder(this)
            .setTitle(R.string.device_umbrella_join_request_dialog_title)
            .setMessage(
                getString(
                    R.string.device_umbrella_join_request_dialog_message_template,
                    alias,
                    model,
                    requestedAt,
                    pendingCount
                )
            )
            .setPositiveButton(R.string.device_umbrella_action_approve_request) { _, _ ->
                decideDeviceUmbrellaJoinRequest(requestId = requestId, alias = alias, approve = true)
            }
            .setNeutralButton(R.string.device_umbrella_action_reject_request) { _, _ ->
                decideDeviceUmbrellaJoinRequest(requestId = requestId, alias = alias, approve = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun decideDeviceUmbrellaJoinRequest(requestId: String, alias: String, approve: Boolean) {
        setDeviceUmbrellaBusy(true)
        showSettingsStatus(getString(R.string.device_umbrella_status_reviewing_requests))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    DeviceUmbrellaSyncClient.decideJoinRequest(
                        context = this@GuardianSettingsActivity,
                        requestId = requestId,
                        approve = approve
                    )
                }
            }
            setDeviceUmbrellaBusy(false)
            result.onSuccess {
                refreshSettingsUi()
                showSettingsStatus(
                    getString(
                        if (approve) {
                            R.string.device_umbrella_status_request_approved_template
                        } else {
                            R.string.device_umbrella_status_request_rejected_template
                        },
                        alias
                    )
                )
            }.onFailure { error ->
                showSettingsStatus(
                    getString(
                        R.string.device_umbrella_status_error_template,
                        cleanErrorMessage(error)
                    )
                )
            }
        }
    }

    private fun runDeviceUmbrellaJoin(rawInput: String) {
        val value = rawInput.trim()
        if (value.isBlank()) {
            showSettingsStatus(getString(R.string.device_umbrella_status_join_empty))
            return
        }
        setDeviceUmbrellaBusy(true)
        showSettingsStatus(getString(R.string.device_umbrella_status_joining))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DeviceUmbrellaSyncClient.joinSession(this@GuardianSettingsActivity, value) }
            }
            setDeviceUmbrellaBusy(false)
            result.onSuccess {
                refreshSettingsUi()
                showSettingsStatus(getString(R.string.device_umbrella_status_joined))
            }.onFailure { error ->
                val cleaned = cleanErrorMessage(error)
                if (cleaned == getString(R.string.device_umbrella_status_pending_host_approval)) {
                    showSettingsStatus(getString(R.string.device_umbrella_status_pending_host_approval))
                    return@onFailure
                }
                showSettingsStatus(
                    getString(
                        R.string.device_umbrella_status_error_template,
                        cleaned
                    )
                )
            }
        }
    }

    private fun runDeviceUmbrellaSyncNow() {
        val state = DeviceUmbrellaSyncStore.readState(this)
        if (state == null) {
            showSettingsStatus(getString(R.string.device_umbrella_status_not_linked))
            return
        }

        setDeviceUmbrellaBusy(true)
        showSettingsStatus(getString(R.string.device_umbrella_status_syncing))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DeviceUmbrellaSyncClient.syncNow(this@GuardianSettingsActivity) }
            }
            setDeviceUmbrellaBusy(false)
            result.onSuccess { synced ->
                refreshSettingsUi()
                showSettingsStatus(
                    getString(
                        R.string.device_umbrella_status_synced_template,
                        synced.remoteEnvelopeCount,
                        synced.remoteHighCount,
                        synced.remoteMediumCount
                    )
                )
            }.onFailure { error ->
                showSettingsStatus(
                    getString(
                        R.string.device_umbrella_status_error_template,
                        cleanErrorMessage(error)
                    )
                )
            }
        }
    }

    private fun copyDeviceUmbrellaPayload() {
        val state = DeviceUmbrellaSyncStore.readState(this)
        val payload = state?.qrPayloadUri.orEmpty()
        if (payload.isBlank()) {
            showSettingsStatus(getString(R.string.device_umbrella_status_not_linked))
            return
        }
        copyToClipboard(
            label = getString(R.string.device_umbrella_clipboard_payload_label),
            value = payload
        )
        showSettingsStatus(getString(R.string.device_umbrella_status_payload_copied))
    }

    private fun showDeviceUmbrellaPairReadyDialog(state: DeviceUmbrellaState) {
        val expires = if (state.expiresAtEpochMs > 0L) {
            formatUtcTime(state.expiresAtEpochMs)
        } else {
            getString(R.string.device_umbrella_expiry_unknown)
        }
        val details = getString(
            R.string.device_umbrella_pair_ready_message_template,
            state.linkCode,
            state.qrPayloadUri,
            expires
        )
        val qrBitmap = buildUmbrellaQrBitmap(state.qrPayloadUri)
        LionAlertDialogBuilder(this)
            .setTitle(R.string.device_umbrella_pair_ready_title)
            .apply {
                if (qrBitmap == null) {
                    setMessage(details)
                } else {
                    setView(buildUmbrellaPairReadyView(details, qrBitmap))
                }
            }
            .setPositiveButton(R.string.device_umbrella_action_copy_code) { _, _ ->
                copyToClipboard(
                    label = getString(R.string.device_umbrella_clipboard_code_label),
                    value = state.linkCode
                )
                showSettingsStatus(getString(R.string.device_umbrella_status_code_copied))
            }
            .setNeutralButton(R.string.device_umbrella_action_copy_qr_payload) { _, _ ->
                copyToClipboard(
                    label = getString(R.string.device_umbrella_clipboard_payload_label),
                    value = state.qrPayloadUri
                )
                showSettingsStatus(getString(R.string.device_umbrella_status_payload_copied))
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun buildUmbrellaPairReadyView(details: String, qrBitmap: Bitmap): View {
        val padding = dp(10)
        val qrSize = dp(220).coerceAtLeast(dp(180))
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val qrImage = ImageView(this).apply {
            contentDescription = getString(R.string.device_umbrella_qr_content_description)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setImageBitmap(qrBitmap)
        }
        val detailsLabel = TextView(this).apply {
            text = details
            textSize = 13f
            setLineSpacing(0f, 1.08f)
            setPadding(0, dp(12), 0, 0)
        }
        container.addView(qrImage)
        container.addView(detailsLabel)
        return container
    }

    private fun buildUmbrellaQrBitmap(payload: String): Bitmap? {
        if (payload.isBlank()) {
            return null
        }
        return runCatching {
            val size = dp(220).coerceAtLeast(dp(180))
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        }.getOrNull()
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun setDeviceUmbrellaBusy(busy: Boolean) {
        val state = DeviceUmbrellaSyncStore.readState(this)
        val isHost = state?.memberRole?.equals("host", ignoreCase = true) == true
        binding.settingsRunTutorialButton.isEnabled = !busy
        binding.deviceUmbrellaCreateButton.isEnabled = !busy
        binding.deviceUmbrellaJoinButton.isEnabled = !busy
        binding.deviceUmbrellaScanQrButton.isEnabled = !busy
        binding.deviceUmbrellaReviewJoinRequestsButton.isEnabled = !busy && isHost
        binding.deviceUmbrellaSyncButton.isEnabled = !busy && state != null
        binding.deviceUmbrellaCopyPayloadButton.isEnabled =
            !busy && state?.qrPayloadUri?.isNotBlank() == true
    }

    private fun cleanErrorMessage(error: Throwable): String {
        return error.message.orEmpty()
            .replace("\n", " ")
            .trim()
            .ifBlank { getString(R.string.device_umbrella_error_generic) }
            .take(180)
    }

    private fun formatUtcTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMs))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun applySettingsTheme(
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
        binding.settingsToolbar.setBackgroundColor(palette.backgroundEnd)
        binding.settingsToolbar.setTitleTextColor(palette.textPrimary)
        binding.settingsToolbar.navigationIcon?.mutate()?.setTint(palette.accent)
        binding.settingsStatusLabel.setTextColor(palette.textSecondary)
        binding.settingsAppearanceSubtitle.setTextColor(palette.textSecondary)
        binding.settingsAppearanceSubtitleToggle.setTextColor(palette.accent)
        binding.settingThemeShadeValue.setTextColor(palette.textPrimary)
        binding.settingThemeToneValue.setTextColor(palette.textPrimary)
        binding.settingFillModeValue.setTextColor(palette.textPrimary)
        binding.settingLionAssetValue.setTextColor(palette.textPrimary)
        binding.settingChildMainOverrideValue.setTextColor(palette.textPrimary)
        binding.settingUsStateOverrideValue.setTextColor(palette.textPrimary)
        binding.settingsChildMainOverrideSwitch.setTextColor(palette.textPrimary)
        binding.deviceUmbrellaStatusLabel.setTextColor(palette.textSecondary)
        applyPaletteToMaterialCards(binding.root, palette, accentStyle)
        LionThemeViewStyler.applyMaterialButtonPalette(
            root = binding.root,
            palette = palette,
            accentStyle = accentStyle
        )
        LionThemeViewStyler.installMaterialButtonTouchFeedback(
            root = binding.root,
            accentStyle = accentStyle
        )
        binding.settingsRunTutorialButton.setTextColor(palette.accent)
        binding.deviceUmbrellaCopyPayloadButton.setTextColor(palette.accent)

        binding.previewAccentSwatch.setBackgroundColor(palette.accent)
    }

    private fun applyPaletteToMaterialCards(
        view: View,
        palette: LionThemePalette,
        accentStyle: LionIdentityAccentStyle
    ) {
        if (view is com.google.android.material.card.MaterialCardView) {
            val current = view.cardBackgroundColor.defaultColor
            if (Color.alpha(current) > 0) {
                view.setCardBackgroundColor(palette.panelAlt)
            }
            view.strokeColor = androidx.core.graphics.ColorUtils.blendARGB(
                palette.stroke,
                palette.accent,
                accentStyle.buttonStrokeAccentBlend * 0.72f
            )
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyPaletteToMaterialCards(view.getChildAt(index), palette, accentStyle)
            }
        }
    }

}
