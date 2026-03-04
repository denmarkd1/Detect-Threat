package com.realyn.watchdog

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale
import java.util.UUID

class DemoVpnProviderConnector(
    private val providerConfig: IntegrationMeshVpnConfig,
    override val providerId: String,
    override val providerLabel: String = "Demo VPN Provider",
    private val requiredScopes: List<String> = listOf("vpn:launch", "vpn:status")
) : VpnProviderConnector {

    private val allowedStatuses = providerConfig.allowedStatuses.ifEmpty {
        listOf("connected", "connecting", "disconnected", "unknown", "error")
    }
    private val consentScopes = requiredScopes
        .map { it.trim().lowercase(Locale.US) }
        .filter { it.isNotBlank() }
        .ifEmpty { listOf("vpn:launch", "vpn:status") }

    override suspend fun ensureConsent(
        context: Context,
        ownerRole: String
    ): ConnectorConsentArtifact? {
        val normalizedOwnerRole = IntegrationMeshController.resolveOwnerRole(context, ownerRole)
        val ownerId = resolveOwnerId(context, normalizedOwnerRole)
        val now = System.currentTimeMillis()
        val previous = IntegrationMeshAuditStore.latestActiveConsent(context, providerId, ownerId)

        if (isConsentActive(previous, now)) {
            return previous
        }

        if (previous != null && previous.status.equals("active", ignoreCase = true) && previous.revokedAtEpochMs == 0L) {
            revokeArtifact(context, previous, now)
        }

        val artifact = ConnectorConsentArtifact(
            schemaVersion = INTEGRATION_MESH_SCHEMA_VERSION,
            artifactId = UUID.randomUUID().toString(),
            connectorId = providerId,
            connectorType = "vpn_provider",
            ownerRole = normalizedOwnerRole,
            ownerId = ownerId,
            grantedAtEpochMs = now,
            grantedAtIso = toIsoUtc(now),
            expiresAtEpochMs = now + CONSENT_TTL_MS,
            revokedAtEpochMs = 0L,
            status = "active",
            proofHash = createHash("$providerId|$ownerId|vpn_provider|$now|demo"),
            grantedBy = normalizedOwnerRole,
            appVersion = appVersion(context),
            consentScopes = consentScopes,
            actionRef = "DemoVpnProviderConnector.ensureConsent"
        )
        IntegrationMeshAuditStore.appendConsentArtifact(context, artifact)

        logConnectorEvent(
            context = context,
            connectorId = providerId,
            connectorType = "vpn_provider",
            ownerRole = normalizedOwnerRole,
            ownerId = ownerId,
            actorRole = normalizedOwnerRole,
            actorId = ownerId,
            consentArtifactId = artifact.artifactId,
            eventType = "vpn_provider.consent.issued",
            recordType = CONSENT_ARTIFACT_RECORD_TYPE,
            outcome = "success",
            sourceModule = "integration_mesh_controller",
            details = "Demo VPN consent issued"
        )

        return artifact
    }

    override suspend fun queryConnectionState(
        context: Context,
        consentArtifact: ConnectorConsentArtifact
    ): VpnProviderConnectionState {
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

        val cached = VpnStateStore.read(context, consentArtifact.ownerId)
        val hasTransport = VpnStatusAssertions.isVpnTransportActive(context)
        val staleMillis = providerConfig.staleAfterMinutes.coerceAtLeast(1).toLong() * 60L * 1000L
        val stale = cached.checkedAtEpochMs > 0L && now - cached.checkedAtEpochMs > staleMillis

        val baseState = when {
            hasTransport -> "connected"
            cached.state.isBlank() -> "disconnected"
            else -> cached.state
        }

        val normalizedState = when {
            stale -> "unknown"
            else -> sanitizeState(baseState)
        }

        val details = when {
            stale -> "state_cache_stale"
            hasTransport -> "system_vpn_transport_detected"
            normalizedState == "connecting" -> cached.details.ifBlank { "demo_launch_in_progress" }
            normalizedState == "error" -> cached.details.ifBlank { "demo_query_error" }
            normalizedState == "disconnected" -> cached.details.ifBlank { "demo_disconnected" }
            else -> cached.details.ifBlank { "demo_state_unknown" }
        }

        VpnStateStore.write(
            context = context,
            ownerId = consentArtifact.ownerId,
            providerId = providerId,
            state = normalizedState,
            details = details,
            checkedAtEpochMs = now
        )

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
            outcome = if (normalizedState == "error") "failed" else "success",
            sourceModule = "integration_mesh_controller",
            details = "demo_state=$normalizedState, details=$details"
        )

        return VpnProviderConnectionState(
            providerId = providerId,
            state = normalizedState,
            checkedAtEpochMs = now,
            details = details
        )
    }

    override suspend fun launchProvider(
        context: Context,
        consentArtifact: ConnectorConsentArtifact
    ): VpnProviderLaunchResult {
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
            return VpnProviderLaunchResult(
                opened = false,
                launchMode = "blocked_consent",
                launchTarget = "",
                providerId = providerId,
                providerLabel = providerLabel
            )
        }

        val provider = VpnProviderRegistry.resolveProvider(providerConfig, providerId)
        val access = PricingPolicy.resolveFeatureAccess(context)
        if (!VpnProviderRegistry.canUseProviderForTier(providerConfig, provider, access)) {
            VpnStateStore.write(
                context = context,
                ownerId = consentArtifact.ownerId,
                providerId = provider.id,
                state = "error",
                details = "paid_tier_required"
            )
            logConnectorEvent(
                context = context,
                connectorId = provider.id,
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
                details = "Demo launch blocked: paid tier required"
            )
            return VpnProviderLaunchResult(
                opened = false,
                launchMode = "blocked_paid_tier",
                launchTarget = provider.id,
                providerId = provider.id,
                providerLabel = provider.label
            )
        }

        VpnStateStore.write(
            context = context,
            ownerId = consentArtifact.ownerId,
            providerId = provider.id,
            state = "connecting",
            details = "demo_launch_requested"
        )

        val launchResult = VpnProviderLaunchRouter.openSetup(context, provider)
        if (!launchResult.opened) {
            VpnStateStore.write(
                context = context,
                ownerId = consentArtifact.ownerId,
                providerId = provider.id,
                state = "error",
                details = "launch_failed"
            )
            logConnectorEvent(
                context = context,
                connectorId = provider.id,
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
                details = "Unable to open demo provider or VPN settings"
            )
            return launchResult
        }

        val hasTransport = VpnStatusAssertions.isVpnTransportActive(context)
        val resolvedState = if (hasTransport) "connected" else "connecting"
        val resolvedDetails = if (hasTransport) {
            "system_vpn_transport_detected"
        } else {
            "demo_provider_opened"
        }

        VpnStateStore.write(
            context = context,
            ownerId = consentArtifact.ownerId,
            providerId = provider.id,
            state = resolvedState,
            details = resolvedDetails
        )

        logConnectorEvent(
            context = context,
            connectorId = provider.id,
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
            details = "demo provider launch mode=${launchResult.launchMode} target=${launchResult.launchTarget}"
        )

        return launchResult
    }

    override suspend fun revoke(context: Context, consentArtifact: ConnectorConsentArtifact) {
        val now = System.currentTimeMillis()
        if (!isConsentActive(consentArtifact, now)) {
            return
        }

        revokeArtifact(context, consentArtifact, now)
        VpnStateStore.clear(context, consentArtifact.ownerId)

        logConnectorEvent(
            context = context,
            connectorId = providerId,
            connectorType = "vpn_provider",
            ownerRole = consentArtifact.ownerRole,
            ownerId = consentArtifact.ownerId,
            actorRole = consentArtifact.ownerRole,
            actorId = consentArtifact.ownerId,
            consentArtifactId = consentArtifact.artifactId,
            eventType = "vpn_provider.consent.revoked",
            recordType = CONSENT_ARTIFACT_RECORD_TYPE,
            outcome = "success",
            sourceModule = "integration_mesh_controller",
            details = "VPN consent revoked and state cleared"
        )
    }

    private fun revokeArtifact(context: Context, consentArtifact: ConnectorConsentArtifact, now: Long) {
        val revokedArtifact = consentArtifact.copy(
            status = "revoked",
            revokedAtEpochMs = now
        )
        IntegrationMeshAuditStore.appendConsentArtifact(context, revokedArtifact)
    }

    private fun isConsentActive(consentArtifact: ConnectorConsentArtifact?, now: Long): Boolean {
        if (consentArtifact == null) {
            return false
        }
        return consentArtifact.status.equals("active", ignoreCase = true) &&
            consentArtifact.revokedAtEpochMs == 0L &&
            consentArtifact.grantedAtEpochMs <= now &&
            (consentArtifact.expiresAtEpochMs == 0L || consentArtifact.expiresAtEpochMs >= now)
    }

    private fun sanitizeState(raw: String): String {
        val lower = raw.trim().lowercase(Locale.US)
        return if (allowedStatuses.any { it.equals(lower, ignoreCase = true) }) {
            lower
        } else {
            "unknown"
        }
    }

    private fun resolveOwnerId(context: Context, ownerRole: String): String {
        val profile = PrimaryIdentityStore.readProfile(context)
        if (ownerRole.equals(profile.familyRole, ignoreCase = true) && profile.primaryEmail.isNotBlank()) {
            return profile.primaryEmail
        }
        return profile.identityLabel.ifBlank { ownerRole.ifBlank { "owner" } }
    }

    private fun appVersion(context: Context): String {
        val packageManager = context.packageManager
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(context.packageName, 0).versionCode
            }
        }.getOrElse { 0L }
        return packageInfo.toString()
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

    companion object {
        private const val CONSENT_TTL_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
