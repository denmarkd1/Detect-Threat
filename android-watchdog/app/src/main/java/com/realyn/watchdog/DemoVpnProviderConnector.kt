package com.realyn.watchdog

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import java.util.Locale
import java.util.UUID

class DemoVpnProviderConnector(
    private val providerConfig: IntegrationMeshVpnConfig,
    override val providerId: String,
    override val providerLabel: String = "Demo VPN Provider"
) : VpnProviderConnector {

    private val prefsKeyState = "integration_mesh_vpn_state_"
    private val prefsKeyDetails = "integration_mesh_vpn_details_"
    private val prefsKeyCheckedAt = "integration_mesh_vpn_checked_at_"
    private val allowedStatuses = providerConfig.allowedStatuses.ifEmpty {
        listOf("connected", "connecting", "disconnected", "unknown", "error")
    }

    override suspend fun queryConnectionState(
        context: Context,
        consentArtifact: ConnectorConsentArtifact
    ): VpnProviderConnectionState {
        val stateStore = readState(context, consentArtifact.ownerId)
        val now = System.currentTimeMillis()

        if (!isConsentActive(consentArtifact, now)) {
            return VpnProviderConnectionState(
                providerId = providerId,
                state = "disconnected",
                checkedAtEpochMs = now,
                details = "consent_not_active"
            )
        }

        val status = when {
            stateStore.state.equals("connecting", ignoreCase = true) -> "connecting"
            stateStore.state.equals("error", ignoreCase = true) -> "error"
            isStateCacheStale(stateStore.checkedAtEpochMs, now) -> "unknown"
            else -> stateStore.state.ifBlank { "disconnected" }
        }

        val details = if (status == "unknown") {
            "state_cache_stale"
        } else {
            stateStore.details.ifBlank { "demo_connection_state" }
        }

        logConnectorEvent(
            context = context,
            connectorId = providerId,
            connectorType = "vpn_provider",
            ownerRole = consentArtifact.ownerRole,
            ownerId = consentArtifact.ownerId,
            actorRole = consentArtifact.ownerRole,
            actorId = consentArtifact.ownerId,
            consentArtifactId = consentArtifact.artifactId,
            eventType = "vpn_provider.connection.state",
            recordType = CONNECTOR_AUDIT_EVENT_TYPE,
            outcome = "success",
            sourceModule = "integration_mesh_controller",
            details = "state=$status, details=${stateStore.details.ifBlank { "none" }}"
        )

        return VpnProviderConnectionState(
            providerId = providerId,
            state = sanitizeState(status),
            checkedAtEpochMs = now,
            details = details
        )
    }

    override suspend fun launchProvider(context: Context, consentArtifact: ConnectorConsentArtifact) {
        val now = System.currentTimeMillis()
        if (!isConsentActive(consentArtifact, now)) {
            logConnectorEvent(
                context = context,
                connectorId = providerId,
                connectorType = "vpn_provider",
                ownerRole = consentArtifact.ownerRole,
                ownerId = consentArtifact.ownerId,
                actorRole = consentArtifact.ownerRole,
                actorId = consentArtifact.ownerId,
                consentArtifactId = consentArtifact.artifactId,
                eventType = "vpn_provider.launch",
                recordType = CONNECTOR_AUDIT_EVENT_TYPE,
                outcome = "blocked",
                sourceModule = "integration_mesh_controller",
                details = "Launch blocked: consent inactive"
            )
            return
        }

        writeState(context, consentArtifact.ownerId, "connecting", "provider_launch_requested")

        val launched = openVpnSettings(context)
        if (launched) {
            writeState(context, consentArtifact.ownerId, "connected", "connected_via_settings")
            logConnectorEvent(
                context = context,
                connectorId = providerId,
                connectorType = "vpn_provider",
                ownerRole = consentArtifact.ownerRole,
                ownerId = consentArtifact.ownerId,
                actorRole = consentArtifact.ownerRole,
                actorId = consentArtifact.ownerId,
                consentArtifactId = consentArtifact.artifactId,
                eventType = "vpn_provider.launch",
                recordType = CONNECTOR_AUDIT_EVENT_TYPE,
                outcome = "success",
                sourceModule = "integration_mesh_controller",
                details = "Launched Android VPN settings"
            )
        } else {
            writeState(context, consentArtifact.ownerId, "error", "launch_failed")
            logConnectorEvent(
                context = context,
                connectorId = providerId,
                connectorType = "vpn_provider",
                ownerRole = consentArtifact.ownerRole,
                ownerId = consentArtifact.ownerId,
                actorRole = consentArtifact.ownerRole,
                actorId = consentArtifact.ownerId,
                consentArtifactId = consentArtifact.artifactId,
                eventType = "vpn_provider.launch",
                recordType = CONNECTOR_AUDIT_EVENT_TYPE,
                outcome = "failed",
                sourceModule = "integration_mesh_controller",
                details = "Unable to resolve or launch Android VPN settings"
            )
        }
    }

    override suspend fun revoke(context: Context, consentArtifact: ConnectorConsentArtifact) {
        val now = System.currentTimeMillis()
        if (!isConsentActive(consentArtifact, now)) {
            return
        }

        val revokedArtifact = consentArtifact.copy(
            status = "revoked",
            revokedAtEpochMs = now
        )
        IntegrationMeshAuditStore.appendConsentArtifact(context, revokedArtifact)
        writeState(context, consentArtifact.ownerId, "disconnected", "consent_revoked")

        logConnectorEvent(
            context = context,
            connectorId = providerId,
            connectorType = "vpn_provider",
            ownerRole = consentArtifact.ownerRole,
            ownerId = consentArtifact.ownerId,
            actorRole = consentArtifact.ownerRole,
            actorId = consentArtifact.ownerId,
            consentArtifactId = revokedArtifact.artifactId,
            eventType = "vpn_provider.consent.revoked",
            recordType = CONSENT_ARTIFACT_RECORD_TYPE,
            outcome = "success",
            sourceModule = "integration_mesh_controller",
            details = "VPN consent revoked and state cleared"
        )
    }

    private fun openVpnSettings(context: Context): Boolean {
        val settingsIntent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val canResolve = runCatching {
            context.packageManager.resolveActivity(settingsIntent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrElse { false }

        if (!canResolve) {
            return false
        }

        return runCatching {
            context.startActivity(settingsIntent)
            true
        }.getOrDefault(false)
    }

    private fun readState(context: Context, ownerId: String): VpnState {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val keyOwner = normalizedOwner(ownerId)
        val keyState = prefs.getString("$prefsKeyState$keyOwner", "disconnected")?.trim().orEmpty()
        val keyDetails = prefs.getString("$prefsKeyDetails$keyOwner", "")?.trim().orEmpty()
        val keyCheckedAt = prefs.getLong("$prefsKeyCheckedAt$keyOwner", 0L)
        return VpnState(
            state = sanitizeState(keyState),
            details = keyDetails,
            checkedAtEpochMs = keyCheckedAt
        )
    }

    private fun writeState(context: Context, ownerId: String, state: String, details: String) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val safeState = sanitizeState(state)
        val now = System.currentTimeMillis()
        val keyOwner = normalizedOwner(ownerId)

        prefs.edit()
            .putString("$prefsKeyState$keyOwner", safeState)
            .putString("$prefsKeyDetails$keyOwner", details)
            .putLong("$prefsKeyCheckedAt$keyOwner", now)
            .apply()
    }

    private fun isConsentActive(consentArtifact: ConnectorConsentArtifact, now: Long): Boolean {
        return consentArtifact.status.equals("active", ignoreCase = true) &&
            consentArtifact.revokedAtEpochMs == 0L &&
            consentArtifact.grantedAtEpochMs <= now &&
            (consentArtifact.expiresAtEpochMs == 0L || consentArtifact.expiresAtEpochMs >= now)
    }

    private fun isStateCacheStale(lastCheckedAtMs: Long, now: Long): Boolean {
        val ttlMillis = providerConfig.healthTtlMinutes.coerceAtLeast(1).toLong() * 60L * 1000L
        return lastCheckedAtMs > 0L && now - lastCheckedAtMs > ttlMillis
    }

    private fun sanitizeState(raw: String): String {
        val lower = raw.trim().lowercase(Locale.US)
        return when {
            allowedStatuses.any { it.equals(lower, ignoreCase = true) } -> lower
            else -> "unknown"
        }
    }

    private fun normalizedOwner(ownerId: String): String {
        return ownerId.ifBlank { "default_owner" }.trim().lowercase(Locale.US)
    }

    private fun logConnectorEvent(
        context: Context,
        connectorId: String,
        connectorType: String,
        ownerRole: String,
        ownerId: String,
        actorRole: String,
        actorId: String,
        consentArtifactId: String,
        eventType: String,
        recordType: String,
        outcome: String,
        sourceModule: String,
        details: String
    ) {
        val now = System.currentTimeMillis()
        val event = ConnectorAuditEvent(
            schemaVersion = INTEGRATION_MESH_SCHEMA_VERSION,
            eventId = UUID.randomUUID().toString(),
            eventType = eventType,
            recordType = recordType,
            connectorId = connectorId,
            connectorType = connectorType,
            ownerRole = ownerRole,
            ownerId = ownerId,
            actorRole = actorRole,
            actorId = actorId,
            recordedAtEpochMs = now,
            recordedAtIso = toIsoUtc(now),
            outcome = outcome,
            consentArtifactId = consentArtifactId,
            sourceModule = sourceModule,
            details = details,
            detailsHash = createHash("$connectorId|$eventType|$outcome|$details|$now"),
            riskLevel = "low"
        )
        IntegrationMeshAuditStore.appendConnectorEvent(context, event)
    }

    private data class VpnState(
        val state: String,
        val details: String,
        val checkedAtEpochMs: Long
    )
}
