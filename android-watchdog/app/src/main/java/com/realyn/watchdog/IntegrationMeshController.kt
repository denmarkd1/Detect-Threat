package com.realyn.watchdog

import android.content.Context
import java.util.Locale

object IntegrationMeshController {

    data class IntegrationMeshControllerState(
        val enabled: Boolean,
        val ownerRole: String,
        val ownerId: String,
        val smartHomeConnectorId: String,
        val vpnConnectorId: String,
        val smartHomeConnectorEnabled: Boolean,
        val vpnConnectorEnabled: Boolean
    )

    private var initialized = false
    private var config: IntegrationMeshConfig = IntegrationMeshConfigStore.parse(null)
    private var ownerRole: String = "parent"
    private var ownerId: String = "parent"
    private var smartHomeConnectorImpl: SmartHomeConnector? = null
    private var vpnConnectorImpl: VpnProviderConnector? = null
    private var smartHomeConnectorId: String = "smartthings"
    private var vpnConnectorId: String = "partner_vpn"

    @Synchronized
    fun initialize(context: Context) {
        val nextConfig = runCatching { loadIntegrationMeshConfig(context) }
            .getOrDefault(IntegrationMeshConfigStore.parse(null))

        val nextOwnerRole = resolveOwnerRole(context)
        val nextOwnerId = resolveOwnerId(context, nextOwnerRole)

        config = nextConfig
        ownerRole = nextOwnerRole
        ownerId = nextOwnerId

        smartHomeConnectorId = resolveConnectorId(nextConfig.featureFlags.smartHomeConnector, "smartthings")
        smartHomeConnectorImpl = when {
            IntegrationMeshConfigStore.isModuleEnabled(
                nextConfig,
                IntegrationMeshModule.SMART_HOME_CONNECTOR,
                nextOwnerRole,
                nextOwnerId
            ) -> createSmartHomeConnector(
                nextConfig = nextConfig,
                connectorId = smartHomeConnectorId,
                requiredScopes = nextConfig.featureFlags.smartHomeConnector.requiredScopes
            )
            else -> null
        }

        vpnConnectorId = resolveConnectorId(
            nextConfig.featureFlags.vpnProviderConnector,
            nextConfig.connectors.vpnBrokers.defaultProviderId.ifBlank { "partner_vpn" }
        )
        vpnConnectorImpl = when {
            IntegrationMeshConfigStore.isModuleEnabled(
                nextConfig,
                IntegrationMeshModule.VPN_PROVIDER_CONNECTOR,
                nextOwnerRole,
                nextOwnerId
            ) -> createVpnConnector(
                nextConfig = nextConfig,
                connectorId = vpnConnectorId,
                requiredScopes = nextConfig.featureFlags.vpnProviderConnector.requiredScopes
            )
            else -> null
        }

        initialized = true
    }

    @Synchronized
    fun refresh(context: Context) {
        initialized = false
        initialize(context)
    }

    @Synchronized
    fun isModuleEnabled(context: Context, module: IntegrationMeshModule, roleHint: String = ""): Boolean {
        ensureInitialized(context)
        val effectiveRole = resolveOwnerRole(context, roleHint)
        val effectiveOwnerId = resolveOwnerId(context, effectiveRole)
        return IntegrationMeshConfigStore.isModuleEnabled(config, module, effectiveRole, effectiveOwnerId)
    }

    @Synchronized
    fun getActiveSmartHomeConnector(context: Context): SmartHomeConnector? {
        ensureInitialized(context)
        val effectiveRole = ownerRole
        val effectiveOwnerId = ownerId
        if (!IntegrationMeshConfigStore.isModuleEnabled(config, IntegrationMeshModule.SMART_HOME_CONNECTOR, effectiveRole, effectiveOwnerId)) {
            return null
        }
        return smartHomeConnectorImpl
    }

    @Synchronized
    fun getActiveVpnConnector(context: Context): VpnProviderConnector? {
        ensureInitialized(context)
        val effectiveRole = ownerRole
        val effectiveOwnerId = ownerId
        if (!IntegrationMeshConfigStore.isModuleEnabled(config, IntegrationMeshModule.VPN_PROVIDER_CONNECTOR, effectiveRole, effectiveOwnerId)) {
            return null
        }
        return vpnConnectorImpl
    }

    @Synchronized
    fun readConfig(context: Context): IntegrationMeshConfig {
        ensureInitialized(context)
        return config
    }

    @Synchronized
    fun ownerRole(context: Context): String {
        ensureInitialized(context)
        return ownerRole
    }

    @Synchronized
    fun ownerId(context: Context): String {
        ensureInitialized(context)
        return ownerId
    }

    @Synchronized
    fun snapshot(context: Context): IntegrationMeshControllerState {
        ensureInitialized(context)
        return IntegrationMeshControllerState(
            enabled = config.enabled,
            ownerRole = ownerRole,
            ownerId = ownerId,
            smartHomeConnectorId = smartHomeConnectorId,
            vpnConnectorId = vpnConnectorId,
            smartHomeConnectorEnabled = smartHomeConnectorImpl != null,
            vpnConnectorEnabled = vpnConnectorImpl != null
        )
    }

    fun resolveOwnerRole(context: Context, roleHint: String = ""): String {
        val profile = PrimaryIdentityStore.readProfile(context)
        val candidate = roleHint
            .ifBlank { profile.primaryEmail }
            .ifBlank { profile.familyRole }

        val detectedRole = CredentialPolicy.detectOwner(
            username = candidate,
            policy = CredentialPolicy.loadPolicy(context),
            fallbackOwnerId = profile.familyRole
        )

        return PrimaryIdentityStore.normalizeFamilyRole(detectedRole)
    }

    private fun resolveOwnerId(context: Context, roleHint: String): String {
        val profile = PrimaryIdentityStore.readProfile(context)
        val normalizedRole = if (roleHint.isBlank()) profile.familyRole else roleHint
        return when {
            profile.primaryEmail.isNotBlank() -> profile.primaryEmail
            profile.identityLabel.isNotBlank() -> profile.identityLabel
            else -> normalizedRole.ifBlank { "owner" }
        }
    }

    private fun resolveConnectorId(flag: IntegrationMeshModuleFeatureFlag, defaultId: String): String {
        return flag.supportedConnectorIds.firstOrNull { it.isNotBlank() }?.trim()
            ?.lowercase() ?: defaultId
    }

    private fun createSmartHomeConnector(
        nextConfig: IntegrationMeshConfig,
        connectorId: String,
        requiredScopes: List<String>
    ): SmartHomeConnector {
        return when (connectorId.lowercase(Locale.US)) {
            "smartthings", "smart_home" -> SmartThingsConnector(
                connectorConfig = nextConfig.connectors.smartHome,
                connectorId = connectorId,
                requiredScopes = requiredScopes
            )
            else -> DemoSmartHomeConnector(
                connectorConfig = nextConfig.connectors.smartHome,
                connectorId = connectorId,
                requiredScopes = requiredScopes
            )
        }
    }

    private fun createVpnConnector(
        nextConfig: IntegrationMeshConfig,
        connectorId: String,
        requiredScopes: List<String>
    ): VpnProviderConnector {
        val provider = VpnProviderRegistry.resolveProvider(
            config = nextConfig.connectors.vpnBrokers,
            providerId = connectorId
        )
        val providerId = provider.id.ifBlank { connectorId }
        return when (providerId.lowercase(Locale.US)) {
            "demo_vpn", "mock_vpn", "demo" -> DemoVpnProviderConnector(
                providerConfig = nextConfig.connectors.vpnBrokers,
                providerId = providerId,
                providerLabel = provider.label,
                requiredScopes = requiredScopes
            )
            else -> PartnerVpnProviderConnector(
                providerConfig = nextConfig.connectors.vpnBrokers,
                providerId = providerId,
                providerLabel = provider.label,
                requiredScopes = requiredScopes
            )
        }
    }

    private fun ensureInitialized(context: Context) {
        if (!initialized) {
            initialize(context)
            return
        }

        val currentProfileRole = resolveOwnerRole(context)
        val currentProfileId = resolveOwnerId(context, currentProfileRole)
        if (!currentProfileRole.equals(ownerRole, ignoreCase = true) ||
            !currentProfileId.equals(ownerId, ignoreCase = true)
        ) {
            initialize(context)
        }
    }
}
