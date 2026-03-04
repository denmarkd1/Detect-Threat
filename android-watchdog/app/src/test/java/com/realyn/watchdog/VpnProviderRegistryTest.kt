package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnProviderRegistryTest {

    @Test
    fun resolveProvider_usesRequestedIdOrDefaultFallback() {
        val vpnConfig = buildVpnConfig()

        val requested = VpnProviderRegistry.resolveProvider(vpnConfig, "protonvpn")
        assertEquals("protonvpn", requested.id)

        val fallback = VpnProviderRegistry.resolveProvider(vpnConfig, "missing_provider")
        assertEquals("partner_vpn", fallback.id)
    }

    @Test
    fun paidTierRules_applyForProviderAndGlobalPolicy() {
        val vpnConfig = buildVpnConfig()

        val paidAccess = access(true)
        val freeAccess = access(false)
        val partner = VpnProviderRegistry.resolveProvider(vpnConfig, "partner_vpn")
        val proton = VpnProviderRegistry.resolveProvider(vpnConfig, "protonvpn")

        assertTrue(VpnProviderRegistry.isPaidTierRequired(vpnConfig, partner))
        assertFalse(VpnProviderRegistry.isPaidTierRequired(vpnConfig, proton))

        assertFalse(VpnProviderRegistry.canUseProviderForTier(vpnConfig, partner, freeAccess))
        assertTrue(VpnProviderRegistry.canUseProviderForTier(vpnConfig, partner, paidAccess))
        assertTrue(VpnProviderRegistry.canUseProviderForTier(vpnConfig, proton, freeAccess))
    }

    private fun buildVpnConfig(): IntegrationMeshVpnConfig {
        return IntegrationMeshVpnConfig(
            healthTtlMinutes = 30,
            staleAfterMinutes = 30,
            allowedStatuses = listOf("connected", "connecting", "disconnected", "unknown", "error"),
            defaultProviderId = "partner_vpn",
            providers = listOf(
                IntegrationMeshVpnProvider(
                    id = "partner_vpn",
                    label = "Partner VPN",
                    packageNames = listOf("com.nordvpn.android"),
                    deepLinkUri = "",
                    fallbackUri = "https://play.google.com/store/search?q=vpn&c=apps",
                    setupUri = "https://support.google.com/android/answer/9089766",
                    paidTierRequired = true
                ),
                IntegrationMeshVpnProvider(
                    id = "protonvpn",
                    label = "Proton VPN",
                    packageNames = listOf("org.protonvpn.android"),
                    deepLinkUri = "https://play.google.com/store/apps/details?id=org.protonvpn.android",
                    fallbackUri = "https://play.google.com/store/apps/details?id=org.protonvpn.android",
                    setupUri = "https://protonvpn.com/support",
                    paidTierRequired = false
                )
            ),
            accountClassPolicy = IntegrationMeshVpnAccountClassPolicy(
                enabled = true,
                connectedRequiredCategories = listOf("banking"),
                configuredRequiredCategories = listOf("developer"),
                enforcementMode = "block"
            ),
            paidTierPolicy = IntegrationMeshVpnPaidTierPolicy(
                paidTierRequiredForLinking = false,
                paidOnlyProviderIds = listOf("partner_vpn"),
                disclosureRequired = true
            ),
            disclosurePolicy = IntegrationMeshVpnDisclosurePolicy(
                brokerNotice = "broker",
                providerDataNotice = "provider",
                paidTierNotice = "paid"
            )
        )
    }

    private fun access(paid: Boolean): ResolvedFeatureAccess {
        return ResolvedFeatureAccess(
            tierCode = if (paid) "paid" else "free",
            paidAccess = paid,
            features = FeatureAccessTier(
                credentialRecordsLimit = -1,
                queueActionsLimit = -1,
                breachScansPerDay = -1,
                continuousScanEnabled = true,
                overlayAssistantEnabled = true,
                rotationQueueEnabled = true,
                aiHotlineEnabled = true
            )
        )
    }
}
