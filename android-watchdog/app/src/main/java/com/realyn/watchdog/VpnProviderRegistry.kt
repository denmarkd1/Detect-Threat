package com.realyn.watchdog

import java.util.Locale

object VpnProviderRegistry {

    fun listProviders(config: IntegrationMeshVpnConfig): List<IntegrationMeshVpnProvider> {
        return config.providers
            .filter { it.id.isNotBlank() && it.label.isNotBlank() }
            .ifEmpty {
                listOf(
                    IntegrationMeshVpnProvider(
                        id = "system_vpn",
                        label = "System VPN Settings",
                        packageNames = emptyList(),
                        deepLinkUri = "",
                        fallbackUri = "",
                        setupUri = "https://support.google.com/android/answer/9089766",
                        paidTierRequired = false
                    )
                )
            }
    }

    fun resolveProvider(
        config: IntegrationMeshVpnConfig,
        providerId: String
    ): IntegrationMeshVpnProvider {
        val providers = listProviders(config)
        val normalizedId = providerId.trim().lowercase(Locale.US)
        if (normalizedId.isNotBlank()) {
            providers.firstOrNull { it.id.equals(normalizedId, ignoreCase = true) }?.let { return it }
        }

        val defaultId = config.defaultProviderId.trim().lowercase(Locale.US)
        if (defaultId.isNotBlank()) {
            providers.firstOrNull { it.id.equals(defaultId, ignoreCase = true) }?.let { return it }
        }

        return providers.first()
    }

    fun isPaidTierRequired(
        config: IntegrationMeshVpnConfig,
        provider: IntegrationMeshVpnProvider
    ): Boolean {
        return config.paidTierPolicy.paidTierRequiredForLinking ||
            provider.paidTierRequired ||
            config.paidTierPolicy.paidOnlyProviderIds.any { it.equals(provider.id, ignoreCase = true) }
    }

    fun canUseProviderForTier(
        config: IntegrationMeshVpnConfig,
        provider: IntegrationMeshVpnProvider,
        access: ResolvedFeatureAccess
    ): Boolean {
        if (!isPaidTierRequired(config, provider)) {
            return true
        }
        return access.paidAccess
    }
}
