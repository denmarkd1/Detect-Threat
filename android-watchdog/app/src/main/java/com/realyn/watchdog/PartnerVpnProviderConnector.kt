package com.realyn.watchdog

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import android.provider.Settings
import java.util.Locale
import java.util.UUID

class PartnerVpnProviderConnector(
    private val providerConfig: IntegrationMeshVpnConfig,
    override val providerId: String,
    override val providerLabel: String = "Partner VPN Provider Connector"
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
        val consentActive = isConsentActive(consentArtifact, now)

        if (!consentActive) {
            return VpnProviderConnectionState(
                providerId = providerId,
                state = "disconnected",
                checkedAtEpochMs = now,
                details = "consent_not_active"
            )
        }

        val vpnActive = isVpnTransportActive(context)
        val status = when {
            isStateCacheStale(stateStore.checkedAtEpochMs, now) -> "unknown"
            stateStore.state.equals("connecting", ignoreCase = true) -> "connecting"
            stateStore.state.equals("error", ignoreCase = true) -> "error"
            vpnActive -> "connected"
            else -> sanitizeState(stateStore.state).ifBlank { "disconnected" }
        }

        val details = when (status) {
            "connecting" -> stateStore.details.ifBlank { "partner_vpn_launch_in_progress" }
            "error" -> stateStore.details.ifBlank { "vpn_state_query_error" }
            "unknown" -> "state_cache_stale"
            "connected" -> "system_vpn_transport_detected"
            else -> stateStore.details.ifBlank { "partner_vpn_state_disconnected" }
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
            outcome = if (status == "error") "failed" else "success",
            sourceModule = "integration_mesh_controller",
            details = "provider_state=$status, details=$details"
        )

        return VpnProviderConnectionState(
            providerId = providerId,
            state = status,
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
                details = "Partner VPN launch blocked: consent inactive"
            )
            return
        }

        writeState(context, consentArtifact.ownerId, "connecting", "partner_vpn_launch_requested")

        val launched = launchPartnerPackage(context)
        if (launched) {
            writeState(context, consentArtifact.ownerId, "connected", "partner_vpn_app_launched")
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
                details = "partner_vpn_app_launched"
            )
            return
        }

        if (launchVpnSystemSettings(context)) {
            writeState(context, consentArtifact.ownerId, "connected", "partner_vpn_provider_launched")
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
                details = "partner_vpn_settings_opened"
            )
            return
        }

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
            details = "Unable to launch Partner VPN provider or open VPN settings"
        )
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
            details = "Partner VPN consent revoked and state cleared"
        )
    }

    private fun launchVpnSystemSettings(context: Context): Boolean {
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

    private fun launchPartnerPackage(context: Context): Boolean {
        val packageName = firstInstalledPartnerPackage(context)
            ?: return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching {
            context.startActivity(
                launchIntent.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        }.getOrDefault(false)
    }

    private fun firstInstalledPartnerPackage(context: Context): String? {
        return partnerVpnPackages.firstOrNull { isPackageInstalled(context, it) }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrElse { false }
    }

    private fun isVpnTransportActive(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        @Suppress("DEPRECATION")
        val networkInfo: NetworkInfo? = connectivityManager.activeNetworkInfo
        return networkInfo?.type == ConnectivityManager.TYPE_VPN
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

    companion object {
        private val partnerVpnPackages = listOf(
            "com.nordvpn.android",
            "com.privateinternetaccess.android",
            "com.expressvpn.vpn",
            "com.pia",
            "org.protonvpn.android"
        )
    }
}
