package com.realyn.watchdog

import android.content.Context
import org.json.JSONObject

data class MediaVaultPolicy(
    val enabled: Boolean,
    val maxItemsFree: Int,
    val retentionDaysDeleted: Int,
    val requireGuardianForDelete: Boolean
)

data class MediaVaultAccessCheck(
    val allowed: Boolean,
    val reasonCode: String,
    val maxItemsFree: Int = -1
)

object MediaVaultPolicyGate {

    private val defaults = MediaVaultPolicy(
        enabled = true,
        maxItemsFree = 5,
        retentionDaysDeleted = 7,
        requireGuardianForDelete = true
    )

    fun load(context: Context): MediaVaultPolicy {
        val payload = WorkspaceSettingsStore.readPayload(context)
        val mediaVault = payload?.optJSONObject("media_vault")
        val guardian = payload?.optJSONObject("guardian_override")
        return MediaVaultPolicy(
            enabled = mediaVault?.optBoolean("enabled", defaults.enabled) ?: defaults.enabled,
            maxItemsFree = mediaVault?.optInt("max_items_free", defaults.maxItemsFree)
                ?.coerceIn(1, 200)
                ?: defaults.maxItemsFree,
            retentionDaysDeleted = mediaVault?.optInt(
                "retention_days_deleted",
                defaults.retentionDaysDeleted
            )?.coerceIn(1, 365) ?: defaults.retentionDaysDeleted,
            requireGuardianForDelete = guardian?.optBoolean(
                "require_for_vault_delete",
                defaults.requireGuardianForDelete
            ) ?: defaults.requireGuardianForDelete
        )
    }

    fun canAccessVault(context: Context): MediaVaultAccessCheck {
        val policy = load(context)
        if (!policy.enabled) {
            return MediaVaultAccessCheck(allowed = false, reasonCode = "disabled")
        }
        if (!isUnlockSatisfied(context)) {
            return MediaVaultAccessCheck(allowed = false, reasonCode = "locked")
        }
        return MediaVaultAccessCheck(allowed = true, reasonCode = "ok")
    }

    fun canImport(context: Context, activeItemCount: Int): MediaVaultAccessCheck {
        val base = canAccessVault(context)
        if (!base.allowed) {
            return base
        }
        val policy = load(context)
        val access = PricingPolicy.resolveFeatureAccess(context)
        if (!access.paidAccess && activeItemCount >= policy.maxItemsFree) {
            return MediaVaultAccessCheck(
                allowed = false,
                reasonCode = "free_limit",
                maxItemsFree = policy.maxItemsFree
            )
        }
        return MediaVaultAccessCheck(allowed = true, reasonCode = "ok")
    }

    fun requiresGuardianForDelete(context: Context): Boolean {
        val profileControl = PricingPolicy.resolveProfileControl(context)
        if (!profileControl.requiresGuardianApprovalForSensitiveActions) {
            return false
        }
        return load(context).requireGuardianForDelete
    }

    private fun isUnlockSatisfied(context: Context): Boolean {
        val appLockEnabled = parseAppLockEnabled(WorkspaceSettingsStore.readPayload(context))
        if (!appLockEnabled) {
            return true
        }
        return SessionUnlockState.isUnlocked()
    }

    private fun parseAppLockEnabled(payload: JSONObject?): Boolean {
        val appLock = payload?.optJSONObject("app_lock") ?: return true
        return appLock.optBoolean("enabled", true)
    }
}
