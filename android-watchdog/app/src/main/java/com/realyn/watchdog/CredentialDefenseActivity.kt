package com.realyn.watchdog

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.realyn.watchdog.databinding.ActivityCredentialDefenseBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CredentialDefenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCredentialDefenseBinding
    private lateinit var policy: WorkspacePolicy
    private var generatedPassword: String = ""

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
        binding.savePrimaryEmailButton.setOnClickListener { savePrimaryEmail() }
        binding.runPrimaryBreachScanButton.setOnClickListener { runPrimaryBreachScan() }
        binding.saveCredentialButton.setOnClickListener { saveCurrentCredential() }
        binding.generatePasswordButton.setOnClickListener { generatePassword() }
        binding.copyPasswordButton.setOnClickListener { copyGeneratedPassword() }
        binding.copyPendingPasswordButton.setOnClickListener { copyPendingPassword() }
        binding.confirmRotationAppliedButton.setOnClickListener { confirmRotationApplied() }
        binding.copyStoredCurrentButton.setOnClickListener { copyStoredCurrentPassword() }
        binding.copyStoredPreviousButton.setOnClickListener { copyStoredPreviousPassword() }
        binding.openTargetUrlButton.setOnClickListener { openTargetUrl() }
        binding.queueRotationButton.setOnClickListener { queueRotationAction() }
        binding.startOverlayButton.setOnClickListener { startOverlayAssist() }
        binding.refreshQueueButton.setOnClickListener { renderQueue() }

        binding.serviceInput.doAfterTextChanged { refreshUiForFormChanges() }
        binding.usernameInput.doAfterTextChanged { refreshUiForFormChanges() }
        binding.urlInput.doAfterTextChanged { refreshUiForFormChanges() }

        loadPrimaryIdentityUi()
        refreshUiForFormChanges()
        renderQueue()
    }

    override fun onResume() {
        super.onResume()
        renderQueue()
        renderVaultSummary()
    }

    private fun loadPrimaryIdentityUi() {
        val email = PrimaryIdentityStore.readPrimaryEmail(this)
        if (email.isNotBlank()) {
            binding.primaryEmailInput.setText(email)
            if (binding.usernameInput.text.isNullOrBlank()) {
                binding.usernameInput.setText(email)
            }
            binding.primaryEmailStatusLabel.text = getString(R.string.primary_email_saved_template, email)
        } else {
            binding.primaryEmailStatusLabel.text = getString(R.string.primary_email_not_set)
        }
        binding.breachSummaryLabel.text = getString(R.string.breach_summary_idle)
    }

    private fun refreshUiForFormChanges() {
        refreshClassifications()
        renderPasswordPolicyPreview()
        renderVaultSummary()
    }

    private fun savePrimaryEmail() {
        val email = binding.primaryEmailInput.text?.toString().orEmpty().trim().lowercase(Locale.US)
        if (!email.contains("@") || email.length < 5) {
            Toast.makeText(this, R.string.primary_email_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        PrimaryIdentityStore.writePrimaryEmail(this, email)
        if (binding.usernameInput.text.isNullOrBlank()) {
            binding.usernameInput.setText(email)
        }

        binding.primaryEmailStatusLabel.text = getString(R.string.primary_email_saved_template, email)
        refreshClassifications()
        Toast.makeText(this, R.string.primary_email_saved, Toast.LENGTH_SHORT).show()
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
            val tierLabel = featureTierLabel(access.tierCode)
            Toast.makeText(
                this,
                getString(
                    R.string.feature_limit_breach_exhausted_template,
                    quota.limitPerDay,
                    tierLabel
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val primaryEmail = PrimaryIdentityStore.readPrimaryEmail(this)
        if (primaryEmail.isBlank()) {
            Toast.makeText(this, R.string.breach_scan_requires_primary_email, Toast.LENGTH_SHORT).show()
            return
        }

        setCredentialBusy(true)
        binding.breachSummaryLabel.text = getString(R.string.breach_scan_running)

        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                CredentialVaultStore.loadRecords(this@CredentialDefenseActivity)
                    .filter { it.username.equals(primaryEmail, ignoreCase = true) }
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

            if (compromised > 0) {
                binding.vaultSummaryLabel.text = getString(R.string.breach_summary_action_needed)
            }

            PricingPolicy.recordBreachScanUsage(this@CredentialDefenseActivity)
            setCredentialBusy(false)
            renderVaultSummary()
            Toast.makeText(this@CredentialDefenseActivity, R.string.breach_scan_completed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun generatePassword() {
        val service = binding.serviceInput.text?.toString().orEmpty()
        val url = binding.urlInput.text?.toString().orEmpty()
        val category = CredentialPolicy.classifyCategory(url, service)
        val result = PasswordToolkit.generateAdaptivePassword(
            context = this,
            service = service,
            url = url,
            category = category
        )

        generatedPassword = result.password
        binding.generatedPasswordLabel.text = generatedPassword
        binding.generatedPasswordLabel.contentDescription = getString(R.string.generated_password_content_description)
        binding.passwordPolicyLabel.text = result.policy.summary()
        Toast.makeText(this, R.string.generated_password_ready, Toast.LENGTH_SHORT).show()
    }

    private fun copyGeneratedPassword() {
        if (generatedPassword.isBlank()) {
            Toast.makeText(this, R.string.generated_password_missing, Toast.LENGTH_SHORT).show()
            return
        }

        copyToClipboard("DT generated password", generatedPassword)
        Toast.makeText(this, R.string.generated_password_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyPendingPassword() {
        val form = normalizedForm(allowMissingPassword = true, requireUrl = false) ?: return
        val record = CredentialVaultStore.findRecord(this, form.service, form.username)
        val pending = record?.pendingPassword?.takeIf { it.isNotBlank() }
        if (pending.isNullOrBlank()) {
            Toast.makeText(this, R.string.pending_password_missing, Toast.LENGTH_SHORT).show()
            return
        }

        copyToClipboard("DT pending next password", pending)
        Toast.makeText(this, R.string.pending_password_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyStoredCurrentPassword() {
        val form = normalizedForm(allowMissingPassword = true, requireUrl = false) ?: return
        val record = CredentialVaultStore.findRecord(this, form.service, form.username)
        if (record == null || record.currentPassword.isBlank()) {
            Toast.makeText(this, R.string.stored_current_password_missing, Toast.LENGTH_SHORT).show()
            return
        }

        copyToClipboard("DT current stored password", record.currentPassword)
        Toast.makeText(this, R.string.stored_current_password_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyStoredPreviousPassword() {
        val form = normalizedForm(allowMissingPassword = true, requireUrl = false) ?: return
        val record = CredentialVaultStore.findRecord(this, form.service, form.username)
        if (record == null) {
            Toast.makeText(this, R.string.stored_previous_password_missing, Toast.LENGTH_SHORT).show()
            return
        }

        val previous = CredentialVaultStore.latestDistinctPreviousPassword(record)
        if (previous.isNullOrBlank()) {
            Toast.makeText(this, R.string.stored_previous_password_missing, Toast.LENGTH_SHORT).show()
            return
        }

        copyToClipboard("DT previous stored password", previous)
        Toast.makeText(this, R.string.stored_previous_password_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmRotationApplied() {
        val form = normalizedForm(allowMissingPassword = true, requireUrl = false) ?: return
        val promoted = CredentialVaultStore.promotePendingToCurrent(this, form.service, form.username)
        if (promoted == null) {
            Toast.makeText(this, R.string.rotation_applied_missing, Toast.LENGTH_SHORT).show()
            return
        }

        val owner = promoted.owner
        val actionType = "rotate_password"
        val actionId = stableActionId(owner, promoted.service, promoted.username, actionType)
        CredentialActionStore.updateActionStatus(this, actionId, "completed")

        renderVaultSummary()
        renderQueue()
        Toast.makeText(this, R.string.rotation_applied_confirmed, Toast.LENGTH_SHORT).show()
    }

    private fun saveCurrentCredential() {
        val form = normalizedForm(requirePassword = true) ?: return
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
            return
        }

        val owner = CredentialPolicy.detectOwner(form.username, policy)
        val category = CredentialPolicy.classifyCategory(form.url, form.service)

        CredentialVaultStore.saveCurrentCredential(
            context = this,
            owner = owner,
            category = category,
            service = form.service,
            username = form.username,
            url = form.url,
            currentPassword = form.currentPassword
        )

        renderVaultSummary()
        Toast.makeText(this, R.string.current_password_saved, Toast.LENGTH_SHORT).show()
    }

    private fun openTargetUrl() {
        val normalized = CredentialPolicy.normalizeUrl(binding.urlInput.text?.toString().orEmpty())
        if (normalized.isBlank()) {
            Toast.makeText(this, R.string.url_required, Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.overlay_open_site_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun queueRotationAction() {
        val model = PricingPolicy.load(this)
        val access = PricingPolicy.resolveFeatureAccess(this, model)
        if (!access.features.rotationQueueEnabled) {
            showLockedFeatureDialog(
                getString(R.string.feature_locked_queue_title),
                getString(R.string.feature_locked_queue_message)
            )
            return
        }

        val form = normalizedForm(requirePassword = true) ?: return

        if (generatedPassword.isBlank()) {
            Toast.makeText(this, R.string.queue_requires_generated_password, Toast.LENGTH_SHORT).show()
            return
        }

        val owner = CredentialPolicy.detectOwner(form.username, policy)
        val category = CredentialPolicy.classifyCategory(form.url, form.service)
        val actionType = "rotate_password"
        val actionId = stableActionId(owner, form.service, form.username, actionType)

        val existing = CredentialActionStore.loadQueue(this)
        val pendingCount = existing.count { !it.status.equals("completed", ignoreCase = true) }
        val queueLimit = access.features.queueActionsLimit
        if (queueLimit >= 0 && pendingCount >= queueLimit) {
            showLockedFeatureDialog(
                getString(R.string.feature_locked_queue_title),
                getString(R.string.feature_locked_queue_limit_message, queueLimit)
            )
            return
        }

        if (existing.any { it.actionId == actionId }) {
            Toast.makeText(this, R.string.queue_duplicate_action, Toast.LENGTH_SHORT).show()
            return
        }

        CredentialVaultStore.prepareRotation(
            context = this,
            owner = owner,
            category = category,
            service = form.service,
            username = form.username,
            url = form.url,
            currentPassword = form.currentPassword,
            nextPassword = generatedPassword
        )

        val now = System.currentTimeMillis()
        val action = CredentialAction(
            actionId = actionId,
            owner = owner,
            category = category,
            service = form.service,
            username = form.username,
            url = form.url,
            actionType = actionType,
            status = "pending",
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )

        CredentialActionStore.appendAction(this, action)
        renderVaultSummary()
        renderQueue()
        Toast.makeText(this, R.string.queue_action_saved, Toast.LENGTH_SHORT).show()
    }

    private fun startOverlayAssist() {
        val access = PricingPolicy.resolveFeatureAccess(this)
        if (!access.features.overlayAssistantEnabled) {
            showLockedFeatureDialog(
                getString(R.string.feature_locked_overlay_title),
                getString(R.string.feature_locked_overlay_message)
            )
            return
        }

        if (generatedPassword.isBlank()) {
            Toast.makeText(this, R.string.generated_password_missing, Toast.LENGTH_SHORT).show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        val url = CredentialPolicy.normalizeUrl(binding.urlInput.text?.toString().orEmpty())
        val overlayIntent = Intent(this, CredentialOverlayService::class.java).apply {
            action = WatchdogConfig.ACTION_SHOW_OVERLAY
            putExtra(WatchdogConfig.EXTRA_OVERLAY_PASSWORD, generatedPassword)
            putExtra(WatchdogConfig.EXTRA_OVERLAY_TARGET_URL, url)
        }

        startService(overlayIntent)
        Toast.makeText(this, R.string.overlay_started, Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
        Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_SHORT).show()
    }

    private fun refreshClassifications() {
        val username = binding.usernameInput.text?.toString().orEmpty()
        val service = binding.serviceInput.text?.toString().orEmpty()
        val url = binding.urlInput.text?.toString().orEmpty()

        val owner = CredentialPolicy.detectOwner(username, policy)
        val category = CredentialPolicy.classifyCategory(url, service)

        binding.detectedOwnerLabel.text = getString(R.string.detected_owner_template, owner)
        binding.detectedCategoryLabel.text = getString(R.string.detected_category_template, category)

        val order = policy.priorityCategories.joinToString(" > ")
        val access = PricingPolicy.resolveFeatureAccess(this)
        val queueLimit = if (access.features.queueActionsLimit < 0) {
            getString(R.string.pricing_limit_unlimited)
        } else {
            access.features.queueActionsLimit.toString()
        }
        val recordsLimit = if (access.features.credentialRecordsLimit < 0) {
            getString(R.string.pricing_limit_unlimited)
        } else {
            access.features.credentialRecordsLimit.toString()
        }
        val breachLimit = if (access.features.breachScansPerDay < 0) {
            getString(R.string.pricing_limit_unlimited)
        } else {
            access.features.breachScansPerDay.toString()
        }
        val base = getString(R.string.priority_order_template, order)
        val tier = featureTierLabel(access.tierCode)
        val featureSummary = getString(
            R.string.credential_access_summary_template,
            tier,
            recordsLimit,
            queueLimit,
            breachLimit
        )
        binding.priorityOrderLabel.text = "$base\n$featureSummary"
    }

    private fun renderPasswordPolicyPreview() {
        val service = binding.serviceInput.text?.toString().orEmpty()
        val url = binding.urlInput.text?.toString().orEmpty()
        val category = CredentialPolicy.classifyCategory(url, service)
        val resolved = PasswordToolkit.resolvePolicyPreview(
            context = this,
            service = service,
            url = url,
            category = category
        )
        binding.passwordPolicyLabel.text = resolved.summary()
    }

    private fun renderVaultSummary() {
        val form = normalizedForm(allowMissingPassword = true, requireUrl = false) ?: run {
            binding.vaultSummaryLabel.text = getString(R.string.vault_summary_fill_service_username)
            return
        }

        val record = CredentialVaultStore.findRecord(this, form.service, form.username)
        binding.vaultSummaryLabel.text = if (record == null) {
            getString(R.string.vault_summary_not_found)
        } else {
            CredentialVaultStore.formatRecordSummary(record)
        }
    }

    private fun renderQueue() {
        val queue = CredentialActionStore.loadQueue(this)
            .sortedWith(
                compareBy<CredentialAction> {
                    CredentialPolicy.categorySortKey(it.category, policy)
                }.thenByDescending { it.createdAtEpochMs }
            )

        val pendingCount = queue.count { !it.status.equals("completed", ignoreCase = true) }
        binding.queueCountLabel.text = getString(R.string.queue_count_template, queue.size, pendingCount)

        if (queue.isEmpty()) {
            binding.queueSummary.text = getString(R.string.queue_empty)
            return
        }

        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val lines = queue.take(25).mapIndexed { index, item ->
            val timestamp = formatter.format(Date(item.createdAtEpochMs))
            "${index + 1}. [$timestamp] owner=${item.owner} category=${item.category} service=${item.service} username=${item.username} status=${item.status}"
        }

        val remaining = queue.size - lines.size
        val tail = if (remaining > 0) {
            "\n... and $remaining more actions"
        } else {
            ""
        }

        binding.queueSummary.text = lines.joinToString("\n") + tail
    }

    private fun setCredentialBusy(busy: Boolean) {
        binding.savePrimaryEmailButton.isEnabled = !busy
        binding.runPrimaryBreachScanButton.isEnabled = !busy
        binding.saveCredentialButton.isEnabled = !busy
        binding.generatePasswordButton.isEnabled = !busy
        binding.copyPasswordButton.isEnabled = !busy
        binding.copyPendingPasswordButton.isEnabled = !busy
        binding.confirmRotationAppliedButton.isEnabled = !busy
        binding.copyStoredCurrentButton.isEnabled = !busy
        binding.copyStoredPreviousButton.isEnabled = !busy
        binding.openTargetUrlButton.isEnabled = !busy
        binding.queueRotationButton.isEnabled = !busy
        binding.startOverlayButton.isEnabled = !busy
        binding.refreshQueueButton.isEnabled = !busy
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun stableActionId(owner: String, service: String, username: String, actionType: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$owner|$service|$username|$actionType".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    private fun featureTierLabel(tierCode: String): String {
        return when (tierCode.lowercase(Locale.US)) {
            "lifetime" -> getString(R.string.pricing_tier_lifetime)
            "trial" -> getString(R.string.pricing_tier_trial)
            "paid" -> getString(R.string.pricing_tier_paid)
            else -> getString(R.string.pricing_tier_free)
        }
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

    private fun normalizedForm(
        requirePassword: Boolean = false,
        allowMissingPassword: Boolean = false,
        requireUrl: Boolean = true
    ): CredentialForm? {
        val service = binding.serviceInput.text?.toString().orEmpty().trim()
        val username = binding.usernameInput.text?.toString().orEmpty().trim()
        val url = CredentialPolicy.normalizeUrl(binding.urlInput.text?.toString().orEmpty())
        val currentPassword = binding.currentPasswordInput.text?.toString().orEmpty()

        val missingRequiredField = service.isBlank() || username.isBlank() || (requireUrl && url.isBlank())
        if (missingRequiredField) {
            if (requirePassword || !allowMissingPassword) {
                Toast.makeText(this, R.string.queue_fields_required, Toast.LENGTH_SHORT).show()
            }
            return null
        }

        if (requirePassword && currentPassword.isBlank()) {
            Toast.makeText(this, R.string.current_password_required, Toast.LENGTH_SHORT).show()
            return null
        }

        if (!allowMissingPassword && currentPassword.isBlank()) {
            Toast.makeText(this, R.string.current_password_required, Toast.LENGTH_SHORT).show()
            return null
        }

        return CredentialForm(
            service = service,
            username = username,
            url = url,
            currentPassword = currentPassword
        )
    }

    private data class CredentialForm(
        val service: String,
        val username: String,
        val url: String,
        val currentPassword: String
    )
}
