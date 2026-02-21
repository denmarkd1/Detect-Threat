package com.realyn.watchdog

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

enum class GuardianProtectedAction(
    val code: String,
    val defaultReasonCode: String
) {
    CONNECTED_AI_ACTION("connected_ai_action", "connected_ai_mutation"),
    CREDENTIAL_SAVE_CURRENT("credential_save_current", "credential_mutation"),
    CREDENTIAL_QUEUE_ROTATION("credential_queue_rotation", "credential_mutation"),
    CREDENTIAL_CONFIRM_ROTATION("credential_confirm_rotation", "credential_mutation"),
    VAULT_EXPORT("vault_export", "vault_export"),
    VAULT_DELETE("vault_delete", "vault_delete"),
    HIGH_RISK_PHISHING_REMEDIATION("high_risk_phishing", "high_risk_phishing")
}

data class GuardianOverrideSettings(
    val enabled: Boolean,
    val tokenTtlSeconds: Int,
    val requireForVaultExport: Boolean,
    val requireForVaultDelete: Boolean,
    val requireForHighRiskPhishingActions: Boolean,
    val childIdleRelockSeconds: Int
)

object GuardianOverridePolicy {

    private val defaults = GuardianOverrideSettings(
        enabled = true,
        tokenTtlSeconds = 300,
        requireForVaultExport = true,
        requireForVaultDelete = true,
        requireForHighRiskPhishingActions = true,
        childIdleRelockSeconds = 60
    )

    fun load(context: AppCompatActivity): GuardianOverrideSettings {
        val payload = WorkspaceSettingsStore.readPayload(context)
        return parse(payload?.optJSONObject("guardian_override"))
    }

    fun requiresOverride(
        context: AppCompatActivity,
        action: GuardianProtectedAction,
        profileControl: ProfileControlPolicy = PricingPolicy.resolveProfileControl(context)
    ): Boolean {
        val settings = load(context)
        if (!settings.enabled) {
            return false
        }
        if (!profileControl.requiresGuardianApprovalForSensitiveActions) {
            return false
        }

        return when (action) {
            GuardianProtectedAction.VAULT_EXPORT -> settings.requireForVaultExport
            GuardianProtectedAction.VAULT_DELETE -> settings.requireForVaultDelete
            GuardianProtectedAction.HIGH_RISK_PHISHING_REMEDIATION -> settings.requireForHighRiskPhishingActions
            else -> true
        }
    }

    fun requestApproval(
        activity: AppCompatActivity,
        action: GuardianProtectedAction,
        actionLabel: String,
        reasonCode: String = action.defaultReasonCode,
        onApproved: () -> Unit,
        onDenied: () -> Unit = {}
    ) {
        val profileControl = PricingPolicy.resolveProfileControl(activity)
        if (!requiresOverride(activity, action, profileControl)) {
            onApproved()
            return
        }

        val cached = GuardianOverrideTokenStore.readValidToken(activity, action.code)
        if (cached != null) {
            FamilyControlAuditLog.appendGuardianOverrideEvent(
                context = activity,
                actionCode = action.code,
                outcome = "approved_cached",
                reasonCode = cached.reasonCode.ifBlank { reasonCode },
                tokenExpiresAtEpochMs = cached.expiresAtEpochMs
            )
            onApproved()
            return
        }

        val guardianEmail = PrimaryIdentityStore.readProfile(activity).guardianEmail
            .ifBlank { activity.getString(R.string.identity_guardian_not_set) }

        AlertDialog.Builder(activity)
            .setTitle(R.string.guardian_override_confirm_title)
            .setMessage(
                activity.getString(
                    R.string.guardian_override_confirm_message_template,
                    actionLabel,
                    guardianEmail
                )
            )
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val settings = load(activity)
                val token = GuardianOverrideTokenStore.issueToken(
                    context = activity,
                    actionCode = action.code,
                    reasonCode = reasonCode,
                    ttlSeconds = settings.tokenTtlSeconds
                )
                FamilyControlAuditLog.appendGuardianOverrideEvent(
                    context = activity,
                    actionCode = action.code,
                    outcome = "approved",
                    reasonCode = reasonCode,
                    tokenExpiresAtEpochMs = token.expiresAtEpochMs
                )
                onApproved()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                FamilyControlAuditLog.appendGuardianOverrideEvent(
                    context = activity,
                    actionCode = action.code,
                    outcome = "denied",
                    reasonCode = reasonCode
                )
                onDenied()
            }
            .setOnCancelListener {
                FamilyControlAuditLog.appendGuardianOverrideEvent(
                    context = activity,
                    actionCode = action.code,
                    outcome = "dismissed",
                    reasonCode = reasonCode
                )
                onDenied()
            }
            .show()
    }

    fun resolveChildIdleRelockSeconds(context: AppCompatActivity): Int {
        val settings = load(context)
        return settings.childIdleRelockSeconds.coerceIn(15, 15 * 60)
    }

    private fun parse(item: JSONObject?): GuardianOverrideSettings {
        if (item == null) {
            return defaults
        }
        return GuardianOverrideSettings(
            enabled = item.optBoolean("enabled", defaults.enabled),
            tokenTtlSeconds = item.optInt("token_ttl_seconds", defaults.tokenTtlSeconds)
                .coerceIn(30, 15 * 60),
            requireForVaultExport = item.optBoolean(
                "require_for_vault_export",
                defaults.requireForVaultExport
            ),
            requireForVaultDelete = item.optBoolean(
                "require_for_vault_delete",
                defaults.requireForVaultDelete
            ),
            requireForHighRiskPhishingActions = item.optBoolean(
                "require_for_high_risk_phishing_actions",
                defaults.requireForHighRiskPhishingActions
            ),
            childIdleRelockSeconds = item.optInt(
                "child_idle_relock_seconds",
                defaults.childIdleRelockSeconds
            ).coerceIn(15, 15 * 60)
        )
    }
}
