package com.realyn.watchdog

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.realyn.watchdog.databinding.ActivityGuardianSettingsBinding
import com.realyn.watchdog.theme.LionIdentityAccentStyle
import com.realyn.watchdog.theme.LionThemeCatalog
import com.realyn.watchdog.theme.LionThemePalette
import com.realyn.watchdog.theme.LionThemeToneMode
import com.realyn.watchdog.theme.LionThemeViewStyler

class GuardianSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuardianSettingsBinding
    private var accessGateBootstrapped: Boolean = false
    private var syncingIntroSwitches: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsToolbar.setNavigationOnClickListener { finish() }
        binding.cycleThemeShadeButton.setOnClickListener { cycleThemeShade() }
        binding.cycleFillModeButton.setOnClickListener { cycleFillMode() }
        binding.cycleIdentityProfileButton.setOnClickListener { cycleIdentityProfile() }
        binding.previewAccentSwatch.setOnClickListener { cycleThemeShade() }
        binding.previewToneSwatch.setOnClickListener { toggleLightDarkTone() }
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
        binding.openCredentialCenterButton.setOnClickListener {
            startActivity(Intent(this, CredentialDefenseActivity::class.java))
        }
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
                    binding.settingsStatusLabel.visibility = View.GONE
                }
                refreshSettingsUi()
            },
            onDenied = { finish() }
        )
    }

    private fun refreshSettingsUi() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val selectedBitmap = LionThemePrefs.resolveSelectedLionBitmap(this)
        val fillMode = LionThemePrefs.readFillMode(this)
        val toneMode = LionThemePrefs.readToneMode(this)
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

        val availableProfiles = LionThemePrefs.listIdentityProfiles(this, access.paidAccess)
        binding.cycleIdentityProfileButton.isEnabled = availableProfiles.size > 1
        binding.cycleIdentityProfileButton.alpha = if (availableProfiles.size > 1) 1f else 0.60f
        syncingIntroSwitches = true
        binding.settingsIntroReplaySwitch.isChecked = MainActivity.isHomeIntroReplayEveryLaunch(this)
        binding.settingsIntroWidgetMotionSwitch.isChecked = MainActivity.isHomeIntroWidgetMotionEnabled(this)
        syncingIntroSwitches = false
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

    private fun cycleIdentityProfile() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val next = LionThemePrefs.cycleIdentityProfile(
            context = this,
            paidAccess = access.paidAccess
        )
        refreshSettingsUi()
        showSettingsStatus(
            getString(
                R.string.lion_identity_profile_status_template,
                next.label
            )
        )
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

    private fun showSettingsStatus(message: CharSequence) {
        binding.settingsStatusLabel.visibility = View.VISIBLE
        binding.settingsStatusLabel.text = message
    }

    private fun openMainForSettingsAction(action: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_GUARDIAN_SETTINGS_ACTION, action)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
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
        binding.settingThemeShadeValue.setTextColor(palette.textPrimary)
        binding.settingThemeToneValue.setTextColor(palette.textPrimary)
        binding.settingFillModeValue.setTextColor(palette.textPrimary)
        binding.settingLionAssetValue.setTextColor(palette.textPrimary)
        applyPaletteToMaterialCards(binding.root, palette, accentStyle)
        LionThemeViewStyler.applyMaterialButtonPalette(
            root = binding.root,
            palette = palette,
            accentStyle = accentStyle
        )

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
