package com.realyn.watchdog

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.realyn.watchdog.databinding.ActivityCredentialDefenseBinding
import com.realyn.watchdog.databinding.DialogIdentityProfileBinding
import com.realyn.watchdog.databinding.DialogServiceActionBinding
import com.realyn.watchdog.theme.LionThemeCatalog
import com.realyn.watchdog.theme.LionThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CredentialDefenseActivity : AppCompatActivity() {

    private enum class QueueFilter {
        ALL,
        PENDING,
        OVERDUE,
        COMPLETED;

        fun next(): QueueFilter {
            val values = values()
            return values[(ordinal + 1) % values.size]
        }
    }

    private enum class QueueOwnerFilter {
        ALL,
        PARENT,
        CHILD;

        fun next(): QueueOwnerFilter {
            val values = values()
            return values[(ordinal + 1) % values.size]
        }
    }

    private lateinit var binding: ActivityCredentialDefenseBinding
    private lateinit var policy: WorkspacePolicy

    private var pendingEmailLink: String? = null
    private var serviceDraft = ServiceDraft()
    private var accessGateBootstrapped: Boolean = false
    private var queueFilter: QueueFilter = QueueFilter.ALL
    private var queueOwnerFilter: QueueOwnerFilter = QueueOwnerFilter.ALL
    private var lionFillMode: LionFillMode = LionFillMode.LEFT_TO_RIGHT
    private var lionBusyInProgress: Boolean = false
    private var lionProgressAnimator: ValueAnimator? = null
    private var lionIdleResetRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        binding = ActivityCredentialDefenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        policy = CredentialPolicy.loadPolicy(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.editIdentityButton.setOnClickListener { showIdentityDialog(isOnboarding = false) }
        binding.linkEmailButton.setOnClickListener { startEmailLinkFlow() }
        binding.runPrimaryBreachScanButton.setOnClickListener { runPrimaryBreachScan() }
        binding.openServiceActionButton.setOnClickListener { openServiceActionDialog() }
        binding.refreshQueueButton.setOnClickListener { renderQueue() }
        binding.queueFilterButton.setOnClickListener {
            queueFilter = queueFilter.next()
            renderQueue()
        }
        binding.queueFilterButton.setOnLongClickListener {
            queueOwnerFilter = queueOwnerFilter.next()
            renderQueue()
            Toast.makeText(
                this,
                getString(
                    R.string.queue_owner_filter_switched_template,
                    queueOwnerFilterLabel(queueOwnerFilter)
                ),
                Toast.LENGTH_SHORT
            ).show()
            true
        }
        binding.openAutofillSettingsButton.setOnClickListener {
            val opened = AutofillPasskeyFoundation.openAutofillSettings(this)
            if (!opened) {
                Toast.makeText(this, R.string.autofill_passkey_open_failed, Toast.LENGTH_SHORT).show()
            }
            refreshAutofillPasskeyPanel()
        }
        binding.openPasskeySettingsButton.setOnClickListener {
            val opened = AutofillPasskeyFoundation.openPasskeyProviderSettings(this)
            if (!opened) {
                Toast.makeText(this, R.string.autofill_passkey_open_failed, Toast.LENGTH_SHORT).show()
            }
            refreshAutofillPasskeyPanel()
        }
        binding.lionModeToggleButton.setOnClickListener {
            startActivity(Intent(this, GuardianSettingsActivity::class.java))
        }
        binding.lionModeToggleButton.setOnLongClickListener {
            startActivity(Intent(this, GuardianSettingsActivity::class.java))
            true
        }

        lionFillMode = LionThemePrefs.readFillMode(this)
        refreshLionHeroVisuals()
        binding.lionHeroView.setIdleState()

        enforceAccessGateAndRefresh()
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

    override fun onDestroy() {
        cancelLionProcessingAnimations(resetToIdle = false)
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
                    binding.breachSummaryLabel.text = getString(R.string.breach_summary_idle)
                }
                refreshLionHeroVisuals()
                refreshIdentityCard()
                renderQueue()
                renderVaultSummaryForCurrentDraft()
                refreshAutofillPasskeyPanel()
                maybeShowIdentityOnboarding()
                pendingEmailLink?.let { email ->
                    pendingEmailLink = null
                    showEmailLinkCompletionPrompt(email)
                }
            },
            onDenied = {
                finish()
            }
        )
    }

    private fun maybeShowIdentityOnboarding() {
        if (!PrimaryIdentityStore.hasCompletedOnboarding(this)) {
            showIdentityDialog(isOnboarding = true)
        }
    }

    private fun refreshIdentityCard() {
        val profile = PrimaryIdentityStore.readProfile(this)
        val profileControl = PricingPolicy.resolveProfileControl(this)
        val identityLabel = profile.identityLabel.ifBlank { PrimaryIdentityStore.defaultIdentityLabel() }
        val roleLabel = when (profileControl.roleCode) {
            "child" -> getString(R.string.profile_role_child)
            "family_single" -> getString(R.string.profile_role_family_single)
            else -> getString(R.string.profile_role_parent)
        }
        val guardianLabel = if (profileControl.roleCode == "parent") {
            getString(R.string.identity_guardian_not_required)
        } else {
            if (profile.guardianEmail.isBlank()) {
                getString(R.string.identity_guardian_not_set)
            } else {
                profile.guardianEmail
            }
        }
        val ageYearsLabel = if (profileControl.ageYears >= 0) {
            profileControl.ageYears.toString()
        } else {
            getString(R.string.profile_age_unknown)
        }
        val ageProtocolLabel = getString(
            R.string.identity_age_protocol_template,
            profileControl.ageBandLabel,
            ageYearsLabel
        )
        binding.identitySummaryLabel.text = getString(
            R.string.identity_summary_template,
            identityLabel,
            if (profile.primaryEmail.isBlank()) {
                getString(R.string.identity_email_missing)
            } else {
                profile.primaryEmail
            },
            roleLabel,
            ageProtocolLabel,
            guardianLabel
        )

        val twoFactorRoute = when (profile.twoFactorMethod) {
            "sms" -> getString(
                R.string.two_factor_route_sms_template,
                maskPhone(profile.twoFactorPhone)
            )
            "auth_app" -> getString(
                R.string.two_factor_route_auth_app_template,
                profile.twoFactorAuthApp.ifBlank { getString(R.string.two_factor_auth_app_default) }
            )
            else -> getString(
                R.string.two_factor_route_email_template,
                if (profile.primaryEmail.isBlank()) {
                    getString(R.string.identity_email_missing)
                } else {
                    maskEmail(profile.primaryEmail)
                }
            )
        }
        binding.twoFactorSummaryLabel.text = getString(R.string.two_factor_summary_template, twoFactorRoute)

        val baseStatus = when {
            profile.primaryEmail.isBlank() -> getString(R.string.identity_status_email_missing)
            profile.emailLinkedAtEpochMs <= 0L -> getString(R.string.identity_status_link_required)
            else -> getString(
                R.string.identity_status_linked_template,
                formatDateTime(profile.emailLinkedAtEpochMs)
            )
        }
        binding.identityStatusLabel.text = if (profileControl.graduatedToFamilySingle) {
            "$baseStatus\n${getString(R.string.identity_family_graduation_status)}"
        } else {
            baseStatus
        }
    }

    private fun showIdentityDialog(isOnboarding: Boolean) {
        val existing = PrimaryIdentityStore.readProfile(this)
        val dialogBinding = DialogIdentityProfileBinding.inflate(layoutInflater)

        dialogBinding.identityLabelInput.setText(
            existing.identityLabel.ifBlank { PrimaryIdentityStore.defaultIdentityLabel() }
        )
        dialogBinding.primaryEmailInput.setText(existing.primaryEmail)
        dialogBinding.guardianEmailInput.setText(existing.guardianEmail)
        dialogBinding.childBirthYearInput.setText(
            if (existing.childBirthYear > 0) existing.childBirthYear.toString() else ""
        )
        dialogBinding.twoFactorPhoneInput.setText(existing.twoFactorPhone)
        dialogBinding.twoFactorAuthAppInput.setText(existing.twoFactorAuthApp)

        val checkedMethodId = when (existing.twoFactorMethod) {
            "sms" -> R.id.twoFactorSmsRadio
            "auth_app" -> R.id.twoFactorAuthAppRadio
            else -> R.id.twoFactorEmailRadio
        }
        dialogBinding.twoFactorMethodGroup.check(checkedMethodId)

        val checkedRoleId = when (PrimaryIdentityStore.normalizeFamilyRole(existing.familyRole)) {
            "child" -> R.id.familyRoleChildRadio
            else -> R.id.familyRoleParentRadio
        }
        dialogBinding.familyRoleGroup.check(checkedRoleId)

        fun refreshTwoFactorInputs() {
            val checkedId = dialogBinding.twoFactorMethodGroup.checkedRadioButtonId
            dialogBinding.twoFactorPhoneLayout.visibility =
                if (checkedId == R.id.twoFactorSmsRadio) View.VISIBLE else View.GONE
            dialogBinding.twoFactorAuthAppLayout.visibility =
                if (checkedId == R.id.twoFactorAuthAppRadio) View.VISIBLE else View.GONE
        }

        fun refreshFamilyInputs() {
            val isChild = dialogBinding.familyRoleGroup.checkedRadioButtonId == R.id.familyRoleChildRadio
            dialogBinding.guardianEmailLayout.visibility = if (isChild) View.VISIBLE else View.GONE
            dialogBinding.childBirthYearLayout.visibility = if (isChild) View.VISIBLE else View.GONE
        }

        dialogBinding.twoFactorMethodGroup.setOnCheckedChangeListener { _, _ -> refreshTwoFactorInputs() }
        dialogBinding.familyRoleGroup.setOnCheckedChangeListener { _, _ -> refreshFamilyInputs() }
        refreshTwoFactorInputs()
        refreshFamilyInputs()

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isOnboarding) R.string.identity_onboarding_title else R.string.identity_dialog_title)
            .setMessage(if (isOnboarding) getString(R.string.identity_onboarding_message) else null)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(
                if (isOnboarding) R.string.action_skip else android.R.string.cancel,
                { _, _ ->
                    if (isOnboarding) {
                        val fallbackLabel = existing.identityLabel.ifBlank {
                            PrimaryIdentityStore.defaultIdentityLabel()
                        }
                        PrimaryIdentityStore.writeProfile(
                            context = this,
                            identityLabel = fallbackLabel,
                            primaryEmail = existing.primaryEmail,
                            twoFactorMethod = existing.twoFactorMethod,
                            twoFactorPhone = existing.twoFactorPhone,
                            twoFactorAuthApp = existing.twoFactorAuthApp,
                            familyRole = existing.familyRole,
                            guardianEmail = existing.guardianEmail,
                            childBirthYear = existing.childBirthYear,
                            onboardingComplete = true
                        )
                        refreshIdentityCard()
                    }
                }
            )
            .create()

        dialog.setCancelable(!isOnboarding)
        dialog.setCanceledOnTouchOutside(!isOnboarding)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val identityLabel = dialogBinding.identityLabelInput.text?.toString().orEmpty().trim()
                    .ifBlank { PrimaryIdentityStore.defaultIdentityLabel() }
                val primaryEmail = dialogBinding.primaryEmailInput.text?.toString().orEmpty()
                    .trim().lowercase(Locale.US)
                val twoFactorMethod = when (dialogBinding.twoFactorMethodGroup.checkedRadioButtonId) {
                    R.id.twoFactorSmsRadio -> "sms"
                    R.id.twoFactorAuthAppRadio -> "auth_app"
                    else -> "email"
                }
                val twoFactorPhone = dialogBinding.twoFactorPhoneInput.text?.toString().orEmpty().trim()
                val twoFactorAuthApp = dialogBinding.twoFactorAuthAppInput.text?.toString().orEmpty().trim()
                val familyRole = when (dialogBinding.familyRoleGroup.checkedRadioButtonId) {
                    R.id.familyRoleChildRadio -> "child"
                    else -> "parent"
                }
                val guardianEmail = dialogBinding.guardianEmailInput.text?.toString().orEmpty()
                    .trim().lowercase(Locale.US)
                val childBirthYear = dialogBinding.childBirthYearInput.text?.toString()
                    .orEmpty()
                    .trim()
                    .toIntOrNull()
                    ?: 0
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)

                if (primaryEmail.isNotBlank() && (!primaryEmail.contains("@") || primaryEmail.length < 5)) {
                    Toast.makeText(this, R.string.primary_email_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (familyRole == "child" && guardianEmail.isBlank()) {
                    Toast.makeText(this, R.string.guardian_email_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (guardianEmail.isNotBlank() && (!guardianEmail.contains("@") || guardianEmail.length < 5)) {
                    Toast.makeText(this, R.string.guardian_email_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (familyRole == "child" && childBirthYear == 0) {
                    Toast.makeText(this, R.string.child_birth_year_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (childBirthYear != 0 && (childBirthYear < 1900 || childBirthYear > currentYear)) {
                    Toast.makeText(this, R.string.child_birth_year_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (twoFactorMethod == "email" && primaryEmail.isBlank()) {
                    Toast.makeText(this, R.string.two_factor_requires_email, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (twoFactorMethod == "sms" && twoFactorPhone.isBlank()) {
                    Toast.makeText(this, R.string.two_factor_requires_phone, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val normalizedAuthApp = if (twoFactorMethod == "auth_app") {
                    twoFactorAuthApp.ifBlank { getString(R.string.two_factor_auth_app_default) }
                } else {
                    ""
                }

                val emailChanged = primaryEmail != existing.primaryEmail
                PrimaryIdentityStore.writeProfile(
                    context = this,
                    identityLabel = identityLabel,
                    primaryEmail = primaryEmail,
                    twoFactorMethod = twoFactorMethod,
                    twoFactorPhone = twoFactorPhone,
                    twoFactorAuthApp = normalizedAuthApp,
                    familyRole = familyRole,
                    guardianEmail = guardianEmail,
                    childBirthYear = childBirthYear,
                    onboardingComplete = true
                )
                if (emailChanged) {
                    PrimaryIdentityStore.clearEmailLinkedAt(this)
                }

                refreshIdentityCard()
                Toast.makeText(this, R.string.identity_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()

                if (primaryEmail.isNotBlank() && (isOnboarding || emailChanged || existing.emailLinkedAtEpochMs <= 0L)) {
                    promptLinkEmailNow(primaryEmail)
                }
            }
        }

        dialog.show()
    }

    private fun promptLinkEmailNow(email: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.link_email_prompt_title)
            .setMessage(getString(R.string.link_email_prompt_message_template, email))
            .setPositiveButton(R.string.action_link_now) { _, _ ->
                startEmailLinkFlow(email)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startEmailLinkFlow() {
        val email = PrimaryIdentityStore.readProfile(this).primaryEmail
        if (email.isBlank()) {
            Toast.makeText(this, R.string.link_email_requires_primary, Toast.LENGTH_SHORT).show()
            return
        }
        startEmailLinkFlow(email)
    }

    private fun startEmailLinkFlow(email: String) {
        val loginUrl = providerLoginUrl(email)
        pendingEmailLink = email
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl))
        runCatching { startActivity(intent) }
            .onFailure {
                pendingEmailLink = null
                Toast.makeText(this, R.string.link_email_open_failed, Toast.LENGTH_SHORT).show()
            }
        if (pendingEmailLink != null) {
            Toast.makeText(this, R.string.link_email_opening, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEmailLinkCompletionPrompt(email: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.link_email_complete_title)
            .setMessage(getString(R.string.link_email_complete_message_template, email))
            .setPositiveButton(R.string.action_mark_linked) { _, _ ->
                PrimaryIdentityStore.markEmailLinkedNow(this, email)
                refreshIdentityCard()
                binding.identityStatusLabel.text = getString(
                    R.string.identity_status_linked_template,
                    formatDateTime(System.currentTimeMillis())
                )
                AlertDialog.Builder(this)
                    .setTitle(R.string.breach_scan_prompt_title)
                    .setMessage(R.string.breach_scan_prompt_message)
                    .setPositiveButton(R.string.action_run_primary_breach_scan) { _, _ -> runPrimaryBreachScan() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun providerLoginUrl(email: String): String {
        val domain = email.substringAfter("@", "").lowercase(Locale.US)
        return when {
            domain.endsWith("gmail.com") || domain.endsWith("googlemail.com") ->
                "https://accounts.google.com/ServiceLogin?Email=${Uri.encode(email)}"
            domain.endsWith("outlook.com") || domain.endsWith("hotmail.com") || domain.endsWith("live.com") ||
                domain.endsWith("msn.com") -> "https://login.live.com/"
            domain.endsWith("yahoo.com") || domain.endsWith("ymail.com") -> "https://login.yahoo.com/"
            domain.endsWith("icloud.com") || domain.endsWith("me.com") -> "https://www.icloud.com/mail"
            domain.endsWith("proton.me") || domain.endsWith("protonmail.com") -> "https://account.proton.me/login"
            domain.isNotBlank() -> "https://$domain"
            else -> "https://accounts.google.com/"
        }
    }

    private fun runPrimaryBreachScan() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        val quota = PricingPolicy.breachScanQuota(this)
        if (quota.limitPerDay == 0) {
            showLockedFeatureDialog(
                getString(R.string.feature_locked_breach_title),
                getString(R.string.feature_locked_breach_message)
            )
            return
        }
        if (!quota.allowed) {
            Toast.makeText(
                this,
                getString(
                    R.string.feature_limit_breach_exhausted_template,
                    quota.limitPerDay,
                    access.tierCode
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val profile = PrimaryIdentityStore.readProfile(this)
        if (profile.primaryEmail.isBlank()) {
            Toast.makeText(this, R.string.breach_scan_requires_primary_email, Toast.LENGTH_SHORT).show()
            return
        }
        if (profile.emailLinkedAtEpochMs <= 0L) {
            Toast.makeText(this, R.string.identity_status_link_required, Toast.LENGTH_SHORT).show()
            return
        }

        setCredentialBusy(true)
        binding.breachSummaryLabel.text = getString(R.string.breach_scan_running)

        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                CredentialVaultStore.loadRecords(this@CredentialDefenseActivity)
                    .filter { it.username.equals(profile.primaryEmail, ignoreCase = true) }
            }

            if (records.isEmpty()) {
                binding.breachSummaryLabel.text = getString(R.string.breach_scan_no_records_for_primary)
                setCredentialBusy(false)
                return@launch
            }

            val results = withContext(Dispatchers.IO) {
                records.map { CredentialBreachChecker.checkRecord(it) }
            }

            val checkedAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                results.forEach { item ->
                    if (item.error == null) {
                        CredentialVaultStore.updateBreachStatus(
                            context = this@CredentialDefenseActivity,
                            recordId = item.recordId,
                            pwnedCount = item.pwnedCount,
                            checkedAtEpochMs = checkedAt
                        )
                    }
                }
            }

            val compromised = results.count { it.error == null && it.pwnedCount > 0 }
            val totalPwnedHits = results.sumOf { if (it.error == null) it.pwnedCount else 0 }
            val errors = results.count { it.error != null }

            binding.breachSummaryLabel.text = getString(
                R.string.breach_summary_template,
                results.size,
                compromised,
                totalPwnedHits,
                errors
            )

            PricingPolicy.recordBreachScanUsage(this@CredentialDefenseActivity)
            setCredentialBusy(false)
            Toast.makeText(this@CredentialDefenseActivity, R.string.breach_scan_completed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openServiceActionDialog() {
        val dialogBinding = DialogServiceActionBinding.inflate(layoutInflater)
        val profile = PrimaryIdentityStore.readProfile(this)
        val profileControl = PricingPolicy.resolveProfileControl(this)

        dialogBinding.serviceInput.setText(serviceDraft.service)
        dialogBinding.usernameInput.setText(
            serviceDraft.username.ifBlank { if (profile.primaryEmail.isNotBlank()) profile.primaryEmail else "" }
        )
        dialogBinding.urlInput.setText(serviceDraft.url)
        dialogBinding.currentPasswordInput.setText(serviceDraft.currentPassword)
        dialogBinding.manualNextPasswordInput.setText(serviceDraft.manualNextPassword)

        var generatedPassword = serviceDraft.generatedPassword

        fun selectedNextPassword(): String {
            val manual = dialogBinding.manualNextPasswordInput.text?.toString().orEmpty()
            return if (manual.isNotBlank()) manual else generatedPassword
        }

        fun currentForm(
            requirePassword: Boolean = false,
            requireUrl: Boolean = true
        ): CredentialForm? {
            val service = dialogBinding.serviceInput.text?.toString().orEmpty().trim()
            val username = dialogBinding.usernameInput.text?.toString().orEmpty().trim()
            val url = CredentialPolicy.normalizeUrl(dialogBinding.urlInput.text?.toString().orEmpty())
            val currentPassword = dialogBinding.currentPasswordInput.text?.toString().orEmpty()
            if (service.isBlank() || username.isBlank() || (requireUrl && url.isBlank())) {
                Toast.makeText(this, R.string.queue_fields_required, Toast.LENGTH_SHORT).show()
                return null
            }
            if (requirePassword && currentPassword.isBlank()) {
                Toast.makeText(this, R.string.current_password_required, Toast.LENGTH_SHORT).show()
                return null
            }
            return CredentialForm(service, username, url, currentPassword)
        }

        fun findCurrentRecord(): StoredCredential? {
            val service = dialogBinding.serviceInput.text?.toString().orEmpty().trim()
            val username = dialogBinding.usernameInput.text?.toString().orEmpty().trim()
            if (service.isBlank() || username.isBlank()) {
                return null
            }
            return CredentialVaultStore.findRecord(this, service, username)
        }

        fun refreshDialogState() {
            val service = dialogBinding.serviceInput.text?.toString().orEmpty()
            val username = dialogBinding.usernameInput.text?.toString().orEmpty()
            val url = dialogBinding.urlInput.text?.toString().orEmpty()
            val normalizedUrl = CredentialPolicy.normalizeUrl(url)

            val category = CredentialPolicy.classifyCategory(normalizedUrl, service)
            val policyPreview = PasswordToolkit.resolvePolicyPreview(
                context = this,
                service = service,
                url = normalizedUrl,
                category = category
            )
            dialogBinding.passwordPolicyLabel.text = policyPreview.summary()

            dialogBinding.generatedPasswordLabel.text = if (generatedPassword.isBlank()) {
                getString(R.string.generated_password_placeholder)
            } else {
                generatedPassword
            }

            val selectedPassword = selectedNextPassword()
            dialogBinding.copySelectedPasswordButton.isEnabled = selectedPassword.isNotBlank()
            dialogBinding.copyAndOpenChangeButton.isEnabled = selectedPassword.isNotBlank() && normalizedUrl.isNotBlank()
            dialogBinding.startOverlayButton.visibility = if (
                selectedPassword.isNotBlank() && profileControl.canUseOverlayAssistant
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

            val record = findCurrentRecord()
            val hasStoredCurrent = record?.currentPassword?.isNotBlank() == true
            val hasStoredPrevious = record?.let { CredentialVaultStore.latestDistinctPreviousPassword(it).isNullOrBlank().not() } == true
            val hasPending = record?.pendingPassword?.isNotBlank() == true

            dialogBinding.copyStoredCurrentButton.visibility = if (
                hasStoredCurrent && profileControl.canCopyStoredCredentials
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
            dialogBinding.copyStoredPreviousButton.visibility = if (
                hasStoredPrevious && profileControl.canCopyStoredCredentials
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
            dialogBinding.confirmRotationAppliedButton.visibility = if (hasPending) View.VISIBLE else View.GONE

            val queueReady = selectedPassword.isNotBlank() &&
                dialogBinding.currentPasswordInput.text?.toString().orEmpty().isNotBlank() &&
                service.isNotBlank() && username.isNotBlank() && normalizedUrl.isNotBlank()
            dialogBinding.queueRotationButton.isEnabled = queueReady

            dialogBinding.dialogStatusLabel.text = if (record == null) {
                getString(R.string.vault_summary_not_found)
            } else {
                CredentialVaultStore.formatRecordSummary(record)
            }
        }

        dialogBinding.serviceInput.doAfterTextChanged { refreshDialogState() }
        dialogBinding.usernameInput.doAfterTextChanged { refreshDialogState() }
        dialogBinding.urlInput.doAfterTextChanged { refreshDialogState() }
        dialogBinding.currentPasswordInput.doAfterTextChanged { refreshDialogState() }
        dialogBinding.manualNextPasswordInput.doAfterTextChanged { refreshDialogState() }

        dialogBinding.generatePasswordButton.setOnClickListener {
            val service = dialogBinding.serviceInput.text?.toString().orEmpty()
            val url = CredentialPolicy.normalizeUrl(dialogBinding.urlInput.text?.toString().orEmpty())
            val category = CredentialPolicy.classifyCategory(url, service)
            val result = PasswordToolkit.generateAdaptivePassword(
                context = this,
                service = service,
                url = url,
                category = category
            )
            generatedPassword = result.password
            refreshDialogState()
            Toast.makeText(this, R.string.generated_password_ready, Toast.LENGTH_SHORT).show()
        }

        dialogBinding.copySelectedPasswordButton.setOnClickListener {
            val selected = selectedNextPassword()
            if (selected.isBlank()) {
                Toast.makeText(this, R.string.generated_password_missing, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            withClipboardRootHardening(getString(R.string.overlay_copy_password)) {
                copyToClipboard("DT selected password", selected)
                Toast.makeText(this, R.string.generated_password_copied, Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.copyAndOpenChangeButton.setOnClickListener {
            val selected = selectedNextPassword()
            val targetUrl = CredentialPolicy.normalizeUrl(dialogBinding.urlInput.text?.toString().orEmpty())
            if (selected.isBlank()) {
                Toast.makeText(this, R.string.generated_password_missing, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (targetUrl.isBlank()) {
                Toast.makeText(this, R.string.url_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            withClipboardRootHardening(getString(R.string.overlay_copy_password)) {
                copyToClipboard("DT selected password", selected)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                runCatching { startActivity(intent) }
                    .onFailure { Toast.makeText(this, R.string.overlay_open_site_failed, Toast.LENGTH_SHORT).show() }
            }
        }

        dialogBinding.saveCurrentButton.setOnClickListener {
            withSensitiveRootHardening(getString(R.string.two_factor_action_save_current)) {
                val form = currentForm(requirePassword = true) ?: return@withSensitiveRootHardening
                runWithSecondFactorConfirmation(
                    actionLabel = getString(R.string.two_factor_action_save_current),
                    protectedAction = GuardianProtectedAction.CREDENTIAL_SAVE_CURRENT
                ) {
                    val model = PricingPolicy.load(this)
                    val access = PricingPolicy.resolveFeatureAccess(this, model)
                    val existing = CredentialVaultStore.findRecord(this, form.service, form.username)
                    val records = if (existing == null) CredentialVaultStore.loadRecords(this) else emptyList()
                    val recordLimit = access.features.credentialRecordsLimit
                    if (existing == null && recordLimit >= 0 && records.size >= recordLimit) {
                        showLockedFeatureDialog(
                            getString(R.string.feature_locked_records_title),
                            getString(R.string.feature_locked_records_message, recordLimit)
                        )
                        return@runWithSecondFactorConfirmation
                    }

                    val fallbackOwner = PrimaryIdentityStore.normalizeFamilyRole(
                        PrimaryIdentityStore.readProfile(this).familyRole
                    )
                    val owner = CredentialPolicy.detectOwner(
                        username = form.username,
                        policy = policy,
                        fallbackOwnerId = fallbackOwner
                    )
                    val category = CredentialPolicy.classifyCategory(form.url, form.service)
                    val saved = CredentialVaultStore.saveCurrentCredential(
                        context = this,
                        owner = owner,
                        category = category,
                        service = form.service,
                        username = form.username,
                        url = form.url,
                        currentPassword = form.currentPassword
                    )
                    binding.vaultSummaryLabel.text = CredentialVaultStore.formatRecordSummary(saved)
                    dialogBinding.dialogStatusLabel.text = CredentialVaultStore.formatRecordSummary(saved)
                    Toast.makeText(this, R.string.current_password_saved, Toast.LENGTH_SHORT).show()
                    refreshDialogState()
                }
            }
        }

        dialogBinding.queueRotationButton.setOnClickListener {
            val model = PricingPolicy.load(this)
            val access = PricingPolicy.resolveFeatureAccess(this, model)
            if (!access.features.rotationQueueEnabled) {
                showLockedFeatureDialog(
                    getString(R.string.feature_locked_queue_title),
                    getString(R.string.feature_locked_queue_message)
                )
                return@setOnClickListener
            }

            withSensitiveRootHardening(getString(R.string.two_factor_action_queue_rotation)) {
                val form = currentForm(requirePassword = true) ?: return@withSensitiveRootHardening
                val nextPassword = selectedNextPassword()
                if (nextPassword.isBlank()) {
                    Toast.makeText(this, R.string.queue_requires_next_password, Toast.LENGTH_SHORT).show()
                    return@withSensitiveRootHardening
                }

                runWithSecondFactorConfirmation(
                    actionLabel = getString(R.string.two_factor_action_queue_rotation),
                    protectedAction = GuardianProtectedAction.CREDENTIAL_QUEUE_ROTATION
                ) {
                    val fallbackOwner = PrimaryIdentityStore.normalizeFamilyRole(
                        PrimaryIdentityStore.readProfile(this).familyRole
                    )
                    val owner = CredentialPolicy.detectOwner(
                        username = form.username,
                        policy = policy,
                        fallbackOwnerId = fallbackOwner
                    )
                    val category = CredentialPolicy.classifyCategory(form.url, form.service)
                    val actionType = "rotate_password"
                    val actionId = stableActionId(owner, form.service, form.username, actionType)

                    val existingQueue = CredentialActionStore.loadQueue(this)
                    val pendingCount = existingQueue.count { !it.status.equals("completed", ignoreCase = true) }
                    val queueLimit = access.features.queueActionsLimit
                    if (queueLimit >= 0 && pendingCount >= queueLimit) {
                        showLockedFeatureDialog(
                            getString(R.string.feature_locked_queue_title),
                            getString(R.string.feature_locked_queue_limit_message, queueLimit)
                        )
                        return@runWithSecondFactorConfirmation
                    }
                    if (existingQueue.any {
                            it.actionId == actionId && !it.status.equals("completed", ignoreCase = true)
                        }
                    ) {
                        Toast.makeText(this, R.string.queue_duplicate_action, Toast.LENGTH_SHORT).show()
                        return@runWithSecondFactorConfirmation
                    }

                    val updated = CredentialVaultStore.prepareRotation(
                        context = this,
                        owner = owner,
                        category = category,
                        service = form.service,
                        username = form.username,
                        url = form.url,
                        currentPassword = form.currentPassword,
                        nextPassword = nextPassword
                    )

                    val now = System.currentTimeMillis()
                    val dueAt = now + (3L * 24L * 60L * 60L * 1000L)
                    val appended = CredentialActionStore.appendAction(
                        this,
                        CredentialAction(
                            actionId = actionId,
                            owner = owner,
                            category = category,
                            service = form.service,
                            username = form.username,
                            url = form.url,
                            actionType = actionType,
                            status = "pending",
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now,
                            dueAtEpochMs = dueAt,
                            completedAtEpochMs = 0L,
                            receiptId = ""
                        )
                    )
                    if (!appended) {
                        Toast.makeText(this, R.string.queue_duplicate_action, Toast.LENGTH_SHORT).show()
                        return@runWithSecondFactorConfirmation
                    }

                    binding.vaultSummaryLabel.text = CredentialVaultStore.formatRecordSummary(updated)
                    dialogBinding.dialogStatusLabel.text = CredentialVaultStore.formatRecordSummary(updated)
                    renderQueue()
                    Toast.makeText(this, R.string.queue_action_saved, Toast.LENGTH_SHORT).show()
                    refreshDialogState()
                }
            }
        }

        dialogBinding.startOverlayButton.setOnClickListener {
            if (!profileControl.canUseOverlayAssistant) {
                Toast.makeText(this, R.string.profile_overlay_restricted, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val access = PricingPolicy.resolveFeatureAccess(this)
            if (!access.features.overlayAssistantEnabled) {
                showLockedFeatureDialog(
                    getString(R.string.feature_locked_overlay_title),
                    getString(R.string.feature_locked_overlay_message)
                )
                return@setOnClickListener
            }
            withOverlayRootHardening(getString(R.string.action_start_overlay)) {
                val selected = selectedNextPassword()
                if (selected.isBlank()) {
                    Toast.makeText(this, R.string.generated_password_missing, Toast.LENGTH_SHORT).show()
                    return@withOverlayRootHardening
                }
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                    return@withOverlayRootHardening
                }

                val targetUrl = CredentialPolicy.normalizeUrl(dialogBinding.urlInput.text?.toString().orEmpty())
                val overlayIntent = Intent(this, CredentialOverlayService::class.java).apply {
                    action = WatchdogConfig.ACTION_SHOW_OVERLAY
                    putExtra(WatchdogConfig.EXTRA_OVERLAY_PASSWORD, selected)
                    putExtra(WatchdogConfig.EXTRA_OVERLAY_TARGET_URL, targetUrl)
                }
                startService(overlayIntent)
                Toast.makeText(this, R.string.overlay_started, Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.copyStoredCurrentButton.setOnClickListener {
            if (!profileControl.canCopyStoredCredentials) {
                Toast.makeText(this, R.string.profile_copy_stored_restricted, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val form = currentForm(requirePassword = false, requireUrl = false) ?: return@setOnClickListener
            val record = CredentialVaultStore.findRecord(this, form.service, form.username)
            if (record == null || record.currentPassword.isBlank()) {
                Toast.makeText(this, R.string.stored_current_password_missing, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            withClipboardRootHardening(getString(R.string.overlay_copy_password)) {
                copyToClipboard("DT current stored password", record.currentPassword)
                Toast.makeText(this, R.string.stored_current_password_copied, Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.copyStoredPreviousButton.setOnClickListener {
            if (!profileControl.canCopyStoredCredentials) {
                Toast.makeText(this, R.string.profile_copy_stored_restricted, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val form = currentForm(requirePassword = false, requireUrl = false) ?: return@setOnClickListener
            val record = CredentialVaultStore.findRecord(this, form.service, form.username)
            val previous = record?.let { CredentialVaultStore.latestDistinctPreviousPassword(it) }
            if (previous.isNullOrBlank()) {
                Toast.makeText(this, R.string.stored_previous_password_missing, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            withClipboardRootHardening(getString(R.string.overlay_copy_password)) {
                copyToClipboard("DT previous stored password", previous)
                Toast.makeText(this, R.string.stored_previous_password_copied, Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.confirmRotationAppliedButton.setOnClickListener {
            withSensitiveRootHardening(getString(R.string.two_factor_action_confirm_rotation)) {
                val form = currentForm(requirePassword = false, requireUrl = false)
                    ?: return@withSensitiveRootHardening
                runWithSecondFactorConfirmation(
                    actionLabel = getString(R.string.two_factor_action_confirm_rotation),
                    protectedAction = GuardianProtectedAction.CREDENTIAL_CONFIRM_ROTATION
                ) {
                    val promoted = CredentialVaultStore.promotePendingToCurrent(this, form.service, form.username)
                    if (promoted == null) {
                        Toast.makeText(this, R.string.rotation_applied_missing, Toast.LENGTH_SHORT).show()
                        return@runWithSecondFactorConfirmation
                    }

                    val actionType = "rotate_password"
                    val actionId = stableActionId(promoted.owner, promoted.service, promoted.username, actionType)
                    val completedAction = CredentialActionStore.completeActionWithReceipt(this, actionId)

                    binding.vaultSummaryLabel.text = CredentialVaultStore.formatRecordSummary(promoted)
                    dialogBinding.dialogStatusLabel.text = CredentialVaultStore.formatRecordSummary(promoted)
                    renderQueue()
                    val receipt = completedAction?.receiptId.orEmpty()
                    if (receipt.isBlank()) {
                        Toast.makeText(this, R.string.rotation_applied_confirmed, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.rotation_applied_receipt_template, receipt),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    refreshDialogState()
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.service_action_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnDismissListener {
            serviceDraft = ServiceDraft(
                service = dialogBinding.serviceInput.text?.toString().orEmpty().trim(),
                username = dialogBinding.usernameInput.text?.toString().orEmpty().trim(),
                url = CredentialPolicy.normalizeUrl(dialogBinding.urlInput.text?.toString().orEmpty()),
                currentPassword = dialogBinding.currentPasswordInput.text?.toString().orEmpty(),
                manualNextPassword = dialogBinding.manualNextPasswordInput.text?.toString().orEmpty(),
                generatedPassword = generatedPassword
            )
            renderVaultSummaryForCurrentDraft()
        }

        refreshDialogState()
        dialog.show()
    }

    private fun renderVaultSummaryForCurrentDraft() {
        if (serviceDraft.service.isBlank() || serviceDraft.username.isBlank()) {
            val records = CredentialVaultStore.loadRecords(this)
            binding.vaultSummaryLabel.text = if (records.isEmpty()) {
                getString(R.string.vault_summary_ready)
            } else {
                CredentialVaultStore.formatVaultHealthSummary(records)
            }
            return
        }

        val record = CredentialVaultStore.findRecord(this, serviceDraft.service, serviceDraft.username)
        binding.vaultSummaryLabel.text = if (record == null) {
            getString(R.string.vault_summary_not_found)
        } else {
            CredentialVaultStore.formatRecordSummary(record)
        }
    }

    private fun renderQueue() {
        val now = System.currentTimeMillis()
        val allQueue = prioritizeQueue(CredentialActionStore.loadQueue(this), now)
        val filteredQueue = applyQueueFilter(allQueue, queueFilter, queueOwnerFilter, now)

        val pendingCount = allQueue.count { !it.status.equals("completed", ignoreCase = true) }
        val completedCount = allQueue.count { it.status.equals("completed", ignoreCase = true) }
        val overdueCount = allQueue.count { isOverdue(it, now) }
        val filterLabel = queueFilterLabel(queueFilter)
        val ownerFilterLabel = queueOwnerFilterLabel(queueOwnerFilter)
        binding.queueCountLabel.text = getString(
            R.string.queue_count_detailed_owner_template,
            allQueue.size,
            pendingCount,
            overdueCount,
            completedCount,
            filterLabel,
            ownerFilterLabel
        )
        binding.queueFilterButton.text = getString(
            R.string.queue_filter_button_owner_template,
            filterLabel,
            ownerFilterLabel
        )

        if (allQueue.isEmpty()) {
            binding.queueCard.visibility = View.GONE
            binding.queueSummary.text = getString(R.string.queue_empty)
            return
        }

        binding.queueCard.visibility = View.VISIBLE
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val dueFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        if (filteredQueue.isEmpty()) {
            binding.queueSummary.text = getString(
                R.string.queue_filter_owner_empty_template,
                filterLabel,
                ownerFilterLabel
            )
            return
        }
        val lines = filteredQueue.take(25).mapIndexed { index, item ->
            val timestamp = formatter.format(Date(item.createdAtEpochMs))
            val dueLabel = dueLabel(item, dueFormatter, now)
            val riskReason = queueRiskReason(item, now)
            val priorityPrefix = if (isOverdue(item, now) && isHighPriorityCategory(item.category)) {
                "!! "
            } else {
                ""
            }
            val receipt = if (item.receiptId.isBlank()) {
                ""
            } else {
                " | ${getString(R.string.queue_receipt_template, item.receiptId)}"
            }
            "${index + 1}. ${priorityPrefix}[$timestamp] owner=${item.owner} service=${item.service} username=${item.username} category=${item.category} status=${item.status} | $dueLabel | ${getString(R.string.queue_risk_reason_template, riskReason)}$receipt"
        }
        val remaining = filteredQueue.size - lines.size
        val tail = if (remaining > 0) "\n... and $remaining more actions" else ""
        binding.queueSummary.text = lines.joinToString("\n") + tail
    }

    private fun applyQueueFilter(
        queue: List<CredentialAction>,
        filter: QueueFilter,
        ownerFilter: QueueOwnerFilter,
        nowEpochMs: Long
    ): List<CredentialAction> {
        val statusFiltered = when (filter) {
            QueueFilter.ALL -> queue
            QueueFilter.PENDING -> queue.filter { !it.status.equals("completed", ignoreCase = true) }
            QueueFilter.OVERDUE -> queue.filter { isOverdue(it, nowEpochMs) }
            QueueFilter.COMPLETED -> queue.filter { it.status.equals("completed", ignoreCase = true) }
        }
        return statusFiltered.filter { matchesOwnerFilter(it, ownerFilter) }
    }

    private fun matchesOwnerFilter(item: CredentialAction, ownerFilter: QueueOwnerFilter): Boolean {
        val owner = CredentialPolicy.canonicalOwnerId(item.owner)
        return when (ownerFilter) {
            QueueOwnerFilter.ALL -> true
            QueueOwnerFilter.PARENT -> owner == "parent"
            QueueOwnerFilter.CHILD -> owner == "child"
        }
    }

    private fun isOverdue(item: CredentialAction, nowEpochMs: Long): Boolean {
        if (item.status.equals("completed", ignoreCase = true)) {
            return false
        }
        return item.dueAtEpochMs > 0L && item.dueAtEpochMs < nowEpochMs
    }

    private fun dueLabel(
        item: CredentialAction,
        formatter: SimpleDateFormat,
        nowEpochMs: Long
    ): String {
        if (item.dueAtEpochMs <= 0L) {
            return getString(R.string.queue_no_due)
        }
        val dueDate = formatter.format(Date(item.dueAtEpochMs))
        return if (isOverdue(item, nowEpochMs)) {
            val overdueDays = ((nowEpochMs - item.dueAtEpochMs).coerceAtLeast(0L) / (24L * 60L * 60L * 1000L))
                .toInt()
                .coerceAtLeast(1)
            getString(R.string.queue_due_overdue_template, dueDate, overdueDays)
        } else {
            getString(R.string.queue_due_template, dueDate)
        }
    }

    private fun queueFilterLabel(filter: QueueFilter): String {
        return when (filter) {
            QueueFilter.ALL -> getString(R.string.queue_filter_all)
            QueueFilter.PENDING -> getString(R.string.queue_filter_pending)
            QueueFilter.OVERDUE -> getString(R.string.queue_filter_overdue)
            QueueFilter.COMPLETED -> getString(R.string.queue_filter_completed)
        }
    }

    private fun queueOwnerFilterLabel(filter: QueueOwnerFilter): String {
        return when (filter) {
            QueueOwnerFilter.ALL -> getString(R.string.queue_owner_filter_all)
            QueueOwnerFilter.PARENT -> getString(R.string.profile_role_parent).lowercase(Locale.US)
            QueueOwnerFilter.CHILD -> getString(R.string.profile_role_child).lowercase(Locale.US)
        }
    }

    private fun queueRiskReason(item: CredentialAction, nowEpochMs: Long): String {
        if (item.status.equals("completed", ignoreCase = true)) {
            return getString(R.string.queue_risk_reason_completed)
        }
        val overdue = isOverdue(item, nowEpochMs)
        val highPriority = isHighPriorityCategory(item.category)
        return when {
            overdue && highPriority -> getString(
                R.string.queue_risk_reason_overdue_high_priority_template,
                item.category
            )

            overdue -> getString(R.string.queue_risk_reason_overdue)
            highPriority -> getString(
                R.string.queue_risk_reason_high_priority_template,
                item.category
            )

            else -> getString(R.string.queue_risk_reason_standard)
        }
    }

    private fun isHighPriorityCategory(category: String): Boolean {
        val normalized = category.trim().lowercase(Locale.US)
        return normalized == "email" || normalized == "banking"
    }

    private fun queueUrgencyRank(item: CredentialAction, nowEpochMs: Long): Int {
        val completed = item.status.equals("completed", ignoreCase = true)
        if (completed) {
            return 4
        }
        val overdue = isOverdue(item, nowEpochMs)
        val highPriority = isHighPriorityCategory(item.category)
        return when {
            overdue && highPriority -> 0
            overdue -> 1
            highPriority -> 2
            else -> 3
        }
    }

    private fun prioritizeQueue(
        queue: List<CredentialAction>,
        nowEpochMs: Long
    ): List<CredentialAction> {
        return queue.sortedWith(
            compareBy<CredentialAction> { queueUrgencyRank(it, nowEpochMs) }
                .thenBy { CredentialPolicy.categorySortKey(it.category, policy) }
                .thenBy { if (it.dueAtEpochMs > 0L) it.dueAtEpochMs else Long.MAX_VALUE }
                .thenByDescending { it.createdAtEpochMs }
        )
    }

    private fun refreshAutofillPasskeyPanel() {
        val status = AutofillPasskeyFoundation.evaluate(this)
        val recommendation = if (status.passkeyReady) {
            getString(R.string.autofill_passkey_reco_ready)
        } else {
            getString(R.string.autofill_passkey_reco_setup)
        }
        binding.autofillPasskeySummaryLabel.text = getString(
            R.string.autofill_passkey_ready_template,
            status.summary(),
            recommendation
        )
    }

    private fun setCredentialBusy(busy: Boolean) {
        binding.editIdentityButton.isEnabled = !busy
        binding.linkEmailButton.isEnabled = !busy
        binding.runPrimaryBreachScanButton.isEnabled = !busy
        binding.openServiceActionButton.isEnabled = !busy
        binding.refreshQueueButton.isEnabled = !busy
        binding.queueFilterButton.isEnabled = !busy
        binding.openAutofillSettingsButton.isEnabled = !busy
        binding.openPasskeySettingsButton.isEnabled = !busy
        binding.lionModeToggleButton.isEnabled = !busy
        if (busy) {
            beginLionProcessingAnimation()
        } else {
            completeLionProcessingAnimation()
        }
    }

    private fun cycleLionFillMode() {
        lionFillMode = lionFillMode.next()
        LionThemePrefs.writeFillMode(this, lionFillMode)
        refreshLionHeroVisuals()
        Toast.makeText(
            this,
            getString(R.string.lion_mode_status_template, getString(lionFillMode.labelRes)),
            Toast.LENGTH_SHORT
        ).show()
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
        applyCredentialTheme(themeState.palette, themeState.isDark)
        binding.lionHeroView.setFillMode(lionFillMode)
        binding.lionHeroView.setSurfaceTone(themeState.isDark)
        binding.lionHeroView.setLionBitmap(selectedBitmap)
        binding.lionHeroView.setAccentColor(themeState.palette.accent)
        binding.lionModeToggleButton.text = getString(R.string.action_guardian_settings)
    }

    private fun applyCredentialTheme(
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
        binding.toolbar.setBackgroundColor(palette.backgroundEnd)
        binding.toolbar.setTitleTextColor(palette.textPrimary)
        binding.toolbar.navigationIcon?.mutate()?.setTint(palette.accent)
        binding.lionModeToggleButton.setTextColor(palette.accent)
        applyPaletteToMaterialCards(binding.root, palette)
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

    private fun runWithSecondFactorConfirmation(
        actionLabel: String,
        protectedAction: GuardianProtectedAction,
        onConfirmed: () -> Unit
    ) {
        val profile = PrimaryIdentityStore.readProfile(this)
        val profileControl = PricingPolicy.resolveProfileControl(this)
        if (!profile.onboardingComplete) {
            showIdentityDialog(isOnboarding = true)
            return
        }

        val route = when (profile.twoFactorMethod) {
            "sms" -> getString(
                R.string.two_factor_route_sms_template,
                maskPhone(profile.twoFactorPhone)
            )
            "auth_app" -> getString(
                R.string.two_factor_route_auth_app_template,
                profile.twoFactorAuthApp.ifBlank { getString(R.string.two_factor_auth_app_default) }
            )
            else -> getString(
                R.string.two_factor_route_email_template,
                maskEmail(profile.primaryEmail)
            )
        }
        val guardianNote = if (profileControl.requiresGuardianApprovalForSensitiveActions) {
            getString(
                R.string.two_factor_guardian_confirmation_template,
                if (profile.guardianEmail.isBlank()) {
                    getString(R.string.identity_guardian_not_set)
                } else {
                    profile.guardianEmail
                }
            )
        } else {
            getString(R.string.two_factor_owner_confirmation_note)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.two_factor_confirm_title)
            .setMessage(
                getString(
                    R.string.two_factor_confirm_message_template,
                    actionLabel,
                    route,
                    guardianNote
                )
            )
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                GuardianOverridePolicy.requestApproval(
                    activity = this,
                    action = protectedAction,
                    actionLabel = actionLabel,
                    reasonCode = "credential_two_factor",
                    onApproved = onConfirmed
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun withSensitiveRootHardening(actionLabel: String, onAllowed: () -> Unit) {
        val posture = SecurityScanner.currentRootPosture(this)
        val hardening = RootDefense.resolveHardeningPolicy(posture)
        if (!hardening.requireSensitiveActionConfirmation) {
            onAllowed()
            return
        }
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
            .setPositiveButton(R.string.action_confirm) { _, _ -> onAllowed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun withClipboardRootHardening(actionLabel: String, onAllowed: () -> Unit) {
        val posture = SecurityScanner.currentRootPosture(this)
        val hardening = RootDefense.resolveHardeningPolicy(posture)
        if (!hardening.allowClipboardActions) {
            showRootHardeningBlockedDialog(
                message = getString(R.string.root_hardening_clipboard_blocked_message),
                titleRes = R.string.root_hardening_clipboard_blocked_title
            )
            return
        }
        if (!hardening.requireSensitiveActionConfirmation) {
            onAllowed()
            return
        }
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
            .setPositiveButton(R.string.action_confirm) { _, _ -> onAllowed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun withOverlayRootHardening(actionLabel: String, onAllowed: () -> Unit) {
        val posture = SecurityScanner.currentRootPosture(this)
        val hardening = RootDefense.resolveHardeningPolicy(posture)
        if (!hardening.allowOverlayAssistant) {
            showRootHardeningBlockedDialog(
                getString(
                    R.string.root_hardening_overlay_blocked_message,
                    formatRootReasons(posture.reasonCodes)
                )
            )
            return
        }
        if (!hardening.requireSensitiveActionConfirmation) {
            onAllowed()
            return
        }
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
            .setPositiveButton(R.string.action_confirm) { _, _ -> onAllowed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRootHardeningBlockedDialog(
        message: String,
        titleRes: Int = R.string.root_hardening_block_title
    ) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
        Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_SHORT).show()
    }

    private fun showLockedFeatureDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.action_open_billing) { _, _ ->
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.support_center_url))
                )
                runCatching { startActivity(intent) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun stableActionId(owner: String, service: String, username: String, actionType: String): String {
        val ownerHashKey = CredentialPolicy.ownerHashKey(owner)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ownerHashKey|$service|$username|$actionType".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    private fun formatDateTime(epochMs: Long): String {
        if (epochMs <= 0L) {
            return "-"
        }
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
    }

    private fun maskEmail(email: String): String {
        val value = email.trim()
        if (!value.contains("@")) {
            return value.ifBlank { "not set" }
        }
        val local = value.substringBefore("@")
        val domain = value.substringAfter("@")
        val maskedLocal = when {
            local.length <= 2 -> "${local.firstOrNull() ?: '*'}*"
            else -> "${local.take(2)}***"
        }
        return "$maskedLocal@$domain"
    }

    private fun maskPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length <= 4) {
            return phone.ifBlank { "not set" }
        }
        return "***${digits.takeLast(4)}"
    }

    private data class CredentialForm(
        val service: String,
        val username: String,
        val url: String,
        val currentPassword: String
    )

    private data class ServiceDraft(
        val service: String = "",
        val username: String = "",
        val url: String = "",
        val currentPassword: String = "",
        val manualNextPassword: String = "",
        val generatedPassword: String = ""
    )
}
