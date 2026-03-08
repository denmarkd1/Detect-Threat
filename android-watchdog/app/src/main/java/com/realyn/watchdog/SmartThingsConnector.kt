package com.realyn.watchdog

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale
import java.util.UUID

class SmartThingsConnector(
    private val connectorConfig: IntegrationMeshSmartHomeConfig,
    override val connectorId: String,
    override val connectorLabel: String = "Samsung SmartThings Connector",
    requiredScopes: List<String> = emptyList()
) : SmartHomeConnector {

    override val requiredScopes: List<String> = requiredScopes
        .map { it.trim().lowercase(Locale.US) }
        .filter { it.isNotBlank() }
        .ifEmpty { connectorConfig.defaultScopeSet.ifEmpty { listOf("home:read", "home:devices:read") } }

    override suspend fun ensureConsent(
        context: Context,
        ownerRole: String
    ): ConnectorConsentArtifact? {
        val normalizedOwnerRole = IntegrationMeshController.resolveOwnerRole(context, ownerRole)
        val ownerId = resolveOwnerId(context, normalizedOwnerRole)
        val now = System.currentTimeMillis()
        val previous = IntegrationMeshAuditStore.latestActiveConsent(context, connectorId, ownerId)

        if (isConsentActive(previous, now)) {
            return previous
        }

        if (previous != null && previous.status.equals("active", ignoreCase = true) && previous.revokedAtEpochMs == 0L) {
            revokeArtifact(context, previous, now)
        }

        val artifact = ConnectorConsentArtifact(
            schemaVersion = INTEGRATION_MESH_SCHEMA_VERSION,
            artifactId = UUID.randomUUID().toString(),
            connectorId = connectorId,
            connectorType = "smart_home",
            ownerRole = normalizedOwnerRole,
            ownerId = ownerId,
            grantedAtEpochMs = now,
            grantedAtIso = toIsoUtc(now),
            expiresAtEpochMs = now + CONSENT_TTL_MS,
            revokedAtEpochMs = 0L,
            status = "active",
            proofHash = createHash("$connectorId|$ownerId|smart_home|$now|provider_auth"),
            grantedBy = normalizedOwnerRole,
            appVersion = appVersion(context),
            consentScopes = requiredScopes,
            actionRef = "SmartThingsConnector.ensureConsent"
        )
        IntegrationMeshAuditStore.appendConsentArtifact(context, artifact)
        logConnectorEvent(
            context = context,
            connectorId = connectorId,
            connectorType = "smart_home",
            ownerRole = normalizedOwnerRole,
            ownerId = ownerId,
            actorRole = normalizedOwnerRole,
            actorId = ownerId,
            consentArtifactId = artifact.artifactId,
            eventType = "smart_home.consent.issued",
            recordType = CONSENT_ARTIFACT_RECORD_TYPE,
            outcome = "success",
            sourceModule = "integration_mesh_controller",
            details = "SmartThings consent artifact issued"
        )
        return artifact
    }

    override suspend fun getHealth(
        context: Context,
        consentArtifact: ConnectorConsentArtifact
    ): SmartHomeConnectorHealth {
        val now = System.currentTimeMillis()
        if (!isConsentActive(consentArtifact, now)) {
            logConnectorEvent(
                context = context,
                connectorId = connectorId,
                connectorType = "smart_home",
                ownerRole = consentArtifact.ownerRole,
                ownerId = consentArtifact.ownerId,
                actorRole = consentArtifact.ownerRole,
                actorId = consentArtifact.ownerId,
                consentArtifactId = consentArtifact.artifactId,
                eventType = "smart_home.health.query",
                recordType = CONNECTOR_AUDIT_EVENT_TYPE,
                outcome = "skipped",
                sourceModule = "integration_mesh_controller",
                details = "SmartThings health query skipped: consent is inactive"
            )
            return SmartHomeConnectorHealth(
                connectorId = connectorId,
                status = "disconnected",
                connectedAtEpochMs = 0L,
                lastError = "consent_not_active"
            )
        }

        val isClientInstalled = isSmartHomeClientInstalled(context)
        val provider = resolveProvider(context)
        val credential = provider?.let {
            HomeRiskLiveProviderBroker.readCredential(context, consentArtifact.ownerRole, it)
        }
        val health = when {
            provider == null -> SmartHomeConnectorHealth(
                connectorId = connectorId,
                status = if (isClientInstalled) "app_ready" else "app_missing",
                connectedAtEpochMs = 0L,
                lastError = if (isClientInstalled) "" else "smart_home_app_not_installed"
            )
            !HomeRiskLiveProviderBroker.isCredentialUsable(credential) -> SmartHomeConnectorHealth(
                connectorId = connectorId,
                status = if (isClientInstalled) "authorization_needed" else "app_missing",
                connectedAtEpochMs = 0L,
                lastError = if (isClientInstalled) "smartthings_live_token_missing" else "smart_home_app_not_installed"
            )
            else -> try {
                HomeRiskLiveProviderBroker.fetchInventory(context, consentArtifact.ownerRole, provider)
                SmartHomeConnectorHealth(
                    connectorId = connectorId,
                    status = "connected",
                    connectedAtEpochMs = credential?.linkedAtEpochMs ?: consentArtifact.grantedAtEpochMs,
                    lastError = ""
                )
            } catch (error: Exception) {
                SmartHomeConnectorHealth(
                    connectorId = connectorId,
                    status = "error",
                    connectedAtEpochMs = 0L,
                    lastError = error.message.orEmpty().trim().ifBlank { "inventory_sync_failed" }
                )
            }
        }

        logConnectorEvent(
            context = context,
            connectorId = connectorId,
            connectorType = "smart_home",
            ownerRole = consentArtifact.ownerRole,
            ownerId = consentArtifact.ownerId,
            actorRole = consentArtifact.ownerRole,
            actorId = consentArtifact.ownerId,
            consentArtifactId = consentArtifact.artifactId,
            eventType = "smart_home.health.query",
            recordType = CONNECTOR_AUDIT_EVENT_TYPE,
            outcome = if (health.lastError.isBlank()) "success" else "degraded",
            sourceModule = "integration_mesh_controller",
            details = "smart_home_client_installed=$isClientInstalled status=${health.status} error=${health.lastError}"
        )

        return health
    }

    override suspend fun collectPosture(
        context: Context,
        consentArtifact: ConnectorConsentArtifact
    ): SmartHomePostureSnapshot {
        val now = System.currentTimeMillis()
        if (!isConsentActive(consentArtifact, now)) {
            logConnectorEvent(
                context = context,
                connectorId = connectorId,
                connectorType = "smart_home",
                ownerRole = consentArtifact.ownerRole,
                ownerId = consentArtifact.ownerId,
                actorRole = consentArtifact.ownerRole,
                actorId = consentArtifact.ownerId,
                consentArtifactId = consentArtifact.artifactId,
                eventType = "smart_home.posture.collect",
                recordType = CONNECTOR_AUDIT_EVENT_TYPE,
                outcome = "failed",
                sourceModule = "integration_mesh_controller",
                details = "SmartHome posture collection skipped because consent is inactive"
            )
            return SmartHomePostureSnapshot(
                connectorId = connectorId,
                ownerRole = consentArtifact.ownerRole,
                deviceCount = 0,
                riskScore = 100,
                findings = listOf("consent_not_active"),
                snapshotAtEpochMs = now
            )
        }

        val normalizedOwnerRole = IntegrationMeshController.resolveOwnerRole(context, consentArtifact.ownerRole)
        val appInstalled = isSmartHomeClientInstalled(context)
        val provider = resolveProvider(context)
        val findings = mutableListOf<String>()
        if (connectorConfig.readOnly) {
            findings += "connector_read_only_mode"
        }
        if (!appInstalled) {
            findings += "smart_home_client_not_installed"
        } else {
            findings += "smart_home_client_detected"
        }
        val credential = provider?.let {
            HomeRiskLiveProviderBroker.readCredential(context, normalizedOwnerRole, it)
        }
        val liveDevices = when {
            provider == null -> emptyList()
            !HomeRiskLiveProviderBroker.isCredentialUsable(credential) -> {
                findings += "authorization_pending_provider"
                emptyList()
            }
            else -> try {
                val devices = HomeRiskLiveProviderBroker.fetchInventory(context, normalizedOwnerRole, provider)
                    .take(connectorConfig.maxCachedDevices.coerceAtLeast(1))
                findings += "provider_connected_live"
                findings += "provider_live_inventory_synced"
                devices
            } catch (error: Exception) {
                findings += "authorization_pending_provider"
                emptyList()
            }
        }
        val deviceCount = liveDevices.size
        if (deviceCount == 0) {
            findings += "no_devices_seen_in_connector_snapshot"
        }

        val riskScore = when {
            !HomeRiskLiveProviderBroker.isCredentialUsable(credential) -> if (appInstalled) 52 else 74
            deviceCount == 0 -> if (normalizedOwnerRole == "child") 40 else 28
            else -> if (normalizedOwnerRole == "child") 24 else 12
        }.coerceIn(0, 100)
        if (riskScore >= 35) {
            findings += "remote_home_control_exposure"
        }

        val snapshot = SmartHomePostureSnapshot(
            connectorId = connectorId,
            ownerRole = normalizedOwnerRole,
            deviceCount = deviceCount,
            riskScore = riskScore,
            findings = findings,
            snapshotAtEpochMs = now
        )
        logConnectorEvent(
            context = context,
            connectorId = connectorId,
            connectorType = "smart_home",
            ownerRole = normalizedOwnerRole,
            ownerId = consentArtifact.ownerId,
            actorRole = normalizedOwnerRole,
            actorId = consentArtifact.ownerId,
            consentArtifactId = consentArtifact.artifactId,
            eventType = "smart_home.posture.collected",
            recordType = CONNECTOR_AUDIT_EVENT_TYPE,
            outcome = "success",
            sourceModule = "integration_mesh_controller",
            details = "snapshot device_count=$deviceCount risk=$riskScore app_installed=$appInstalled"
        )
        return snapshot
    }

    override suspend fun revoke(context: Context, consentArtifact: ConnectorConsentArtifact) {
        if (!isConsentActive(consentArtifact, System.currentTimeMillis())) {
            return
        }
        revokeArtifact(context, consentArtifact, System.currentTimeMillis())
    }

    override fun isReadOnlyModeEnabled(): Boolean = connectorConfig.readOnly

    private fun isConsentActive(artifact: ConnectorConsentArtifact?, now: Long): Boolean {
        if (artifact == null) {
            return false
        }
        return artifact.status.equals("active", ignoreCase = true) &&
            artifact.revokedAtEpochMs == 0L &&
            artifact.grantedAtEpochMs <= now &&
            (artifact.expiresAtEpochMs == 0L || artifact.expiresAtEpochMs >= now)
    }

    private fun revokeArtifact(context: Context, consentArtifact: ConnectorConsentArtifact, now: Long) {
        val revokedArtifact = consentArtifact.copy(
            status = "revoked",
            revokedAtEpochMs = now
        )
        IntegrationMeshAuditStore.appendConsentArtifact(context, revokedArtifact)
        logConnectorEvent(
            context = context,
            connectorId = connectorId,
            connectorType = "smart_home",
            ownerRole = consentArtifact.ownerRole,
            ownerId = consentArtifact.ownerId,
            actorRole = consentArtifact.ownerRole,
            actorId = consentArtifact.ownerId,
            consentArtifactId = revokedArtifact.artifactId,
            eventType = "smart_home.consent.revoked",
            recordType = CONSENT_ARTIFACT_RECORD_TYPE,
            outcome = "success",
            sourceModule = "integration_mesh_controller",
            details = "SmartThings consent revoked"
        )
    }

    private fun isSmartHomeClientInstalled(context: Context): Boolean {
        return smartHomePackages.any { isPackageInstalled(context, it) }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrElse { exception ->
            if (exception is PackageManager.NameNotFoundException) false else false
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
            riskLevel = if (connectorConfig.readOnly) "low" else "medium"
        )
        IntegrationMeshAuditStore.appendConnectorEvent(context, event)
    }

    private fun resolveProvider(context: Context): HomeRiskUmbrellaProvider? {
        val config = IntegrationMeshController.readConfig(context)
        return HomeRiskUmbrellaRegistry.listProviders(config).firstOrNull {
            it.connectorId.equals(connectorId, ignoreCase = true) || it.id.equals(connectorId, ignoreCase = true)
        }
    }

    companion object {
        const val CONSENT_TTL_MS = 24L * 60 * 60 * 1000
        private val smartHomePackages = listOf(
            "com.samsung.android.oneconnect",
            "com.google.android.apps.chromecast.app",
            "com.google.android.apps.nest",
            "com.smartthings.android"
        )
    }
}
