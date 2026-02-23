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
import com.realyn.watchdog.theme.LionThemeCatalog
import com.realyn.watchdog.theme.LionThemePalette

class GuardianSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuardianSettingsBinding
    private var accessGateBootstrapped: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsToolbar.setNavigationOnClickListener { finish() }
        binding.cycleThemeShadeButton.setOnClickListener { cycleThemeShade() }
        binding.cycleThemeToneButton.setOnClickListener { cycleThemeTone() }
        binding.cycleFillModeButton.setOnClickListener { cycleFillMode() }
        binding.cycleLionAssetButton.setOnClickListener { cycleLionAsset() }
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
                    binding.settingsStatusLabel.text = getString(R.string.guardian_settings_ready)
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
        val activeAsset = LionThemePrefs.readSelectedProLionAsset(this)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.guardian_settings_lion_asset_default)
        val themeState = LionThemeCatalog.resolveState(
            context = this,
            paidAccess = access.paidAccess,
            selectedLionBitmap = selectedBitmap
        )

        applySettingsTheme(themeState.palette, themeState.isDark)

        binding.settingTierValue.text = if (access.paidAccess) {
            getString(R.string.action_pro_active)
        } else {
            getString(R.string.action_go_pro)
        }
        binding.settingThemeShadeValue.text = getString(themeState.variant.labelRes)
        binding.settingThemeToneValue.text = getString(toneMode.labelRes)
        binding.settingFillModeValue.text = getString(fillMode.labelRes)
        binding.settingLionAssetValue.text = activeAsset

        binding.settingsLionPreview.setFillMode(fillMode)
        binding.settingsLionPreview.setSurfaceTone(themeState.isDark)
        binding.settingsLionPreview.setLionBitmap(selectedBitmap)
        binding.settingsLionPreview.setAccentColor(themeState.palette.accent)
        binding.settingsLionPreview.setIdleState()

        binding.cycleLionAssetButton.isEnabled = access.paidAccess
        binding.cycleLionAssetButton.alpha = if (access.paidAccess) 1f else 0.60f
    }

    private fun cycleThemeShade() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val next = LionThemePrefs.cycleThemeVariant(this, access.paidAccess)
        refreshSettingsUi()
        binding.settingsStatusLabel.text = getString(
            R.string.lion_theme_variant_status_template,
            getString(next.labelRes)
        )
    }

    private fun cycleThemeTone() {
        val next = LionThemePrefs.cycleToneMode(this)
        refreshSettingsUi()
        binding.settingsStatusLabel.text = getString(
            R.string.lion_theme_tone_status_template,
            getString(next.labelRes)
        )
    }

    private fun cycleFillMode() {
        val next = LionThemePrefs.readFillMode(this).next()
        LionThemePrefs.writeFillMode(this, next)
        refreshSettingsUi()
        binding.settingsStatusLabel.text = getString(
            R.string.lion_mode_status_template,
            getString(next.labelRes)
        )
    }

    private fun cycleLionAsset() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        if (!access.paidAccess) {
            binding.settingsStatusLabel.text = getString(R.string.lion_asset_selection_locked)
            return
        }
        val selected = LionThemePrefs.cycleProLionAsset(this)
        refreshSettingsUi()
        binding.settingsStatusLabel.text = if (selected == null) {
            getString(
                R.string.lion_assets_path_template,
                LionThemePrefs.PRO_LION_ASSET_WORKSPACE_PATH
            )
        } else {
            getString(R.string.lion_asset_status_template, selected)
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
            .onFailure { binding.settingsStatusLabel.text = getString(R.string.translation_settings_open_failed) }
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
        isDarkTone: Boolean
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
        binding.settingsTitleLabel.setTextColor(palette.textPrimary)
        binding.settingsSummaryLabel.setTextColor(palette.textSecondary)
        binding.settingsStatusLabel.setTextColor(palette.textSecondary)
        binding.settingTierValue.setTextColor(palette.accent)
        binding.settingThemeShadeValue.setTextColor(palette.textPrimary)
        binding.settingThemeToneValue.setTextColor(palette.textPrimary)
        binding.settingFillModeValue.setTextColor(palette.textPrimary)
        binding.settingLionAssetValue.setTextColor(palette.textPrimary)
        applyPaletteToMaterialCards(binding.root, palette)

        binding.previewBackgroundSwatch.setBackgroundColor(palette.backgroundCenter)
        binding.previewPanelSwatch.setBackgroundColor(palette.panelAlt)
        binding.previewAccentSwatch.setBackgroundColor(palette.accent)
        binding.previewTextSwatch.setBackgroundColor(palette.textPrimary)
    }

    private fun applyPaletteToMaterialCards(view: View, palette: LionThemePalette) {
        if (view is com.google.android.material.card.MaterialCardView) {
            val current = view.cardBackgroundColor.defaultColor
            if (Color.alpha(current) > 0) {
                view.setCardBackgroundColor(palette.panelAlt)
            }
            view.strokeColor = palette.stroke
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyPaletteToMaterialCards(view.getChildAt(index), palette)
            }
        }
    }
}
