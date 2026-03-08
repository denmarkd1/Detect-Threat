package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationMeshConfigTest {

    @Test
    fun legacySonAlias_matchesCanonicalChildAllowlistAndRollout() {
        val flag = IntegrationMeshModuleFeatureFlag(
            enabled = true,
            rolloutStage = "internal_test",
            ownerAllowlist = listOf("parent", "child"),
            maxRolloutPercent = 100,
            supportedConnectorIds = listOf("smartthings"),
            requiredScopes = listOf("home:read"),
            requireRedemptionProof = true
        )
        val rollout = IntegrationMeshRolloutConfig(
            enabled = true,
            mode = "staged_percentage",
            currentStage = "internal_test",
            stages = listOf(
                IntegrationMeshRolloutStage(
                    name = "internal_test",
                    enabled = true,
                    maxPercent = 100,
                    ownerRoles = listOf("parent", "child")
                )
            )
        )

        assertTrue(flag.isOwnerAllowed("son"))
        assertEquals("internal_test", rollout.activeStageForOwner("son")?.name)
    }

    @Test
    fun digitalKeyRiskScorer_treatsLegacySonAliasAsChildProfile() {
        val result = DigitalKeyRiskScorer.assess(
            input = DigitalKeyRiskInput(
                ownerRole = "son",
                lockScreenSecure = true,
                biometricReady = true,
                rootTier = RootRiskTier.TRUSTED,
                playDeviceIntegrityReady = true,
                playStrongIntegrityReady = true,
                activeConsentCount = 1,
                staleConsentCount = 0,
                maxPostureRiskScore = 0
            ),
            supportedRiskCategories = setOf("social_engineering_exposure", "prerequisite_gap")
        )

        assertTrue(result.findings.any { it.findingType == "social_engineering_exposure" })
    }

    @Test
    fun smartHomeProviders_includeDeviceTemplatesInDefaultConfig() {
        val config = IntegrationMeshConfigStore.parse(null)

        val provider = config.connectors.smartHome.providers.first { it.id == "smartthings" }
        assertEquals("smartthings", provider.id)
        assertEquals("Samsung TV", provider.deviceTemplates.first().label)
        assertEquals("token", provider.authMode)
        assertEquals("smartthings_rest", provider.inventoryMode)
    }

    @Test
    fun smartHomeProviders_exposeLiveInventoryMetadataHonesty() {
        val config = IntegrationMeshConfigStore.parse(null)

        val homeAssistant = config.connectors.smartHome.providers.first { it.id == "home_assistant" }
        assertEquals("token", homeAssistant.authMode)
        assertEquals("home_assistant_rest", homeAssistant.inventoryMode)
        assertTrue(homeAssistant.requiresInstanceUrl)

        val googleHome = config.connectors.smartHome.providers.first { it.id == "google_home" }
        assertEquals("sdk", googleHome.authMode)
        assertEquals("google_home_sdk", googleHome.inventoryMode)
        assertTrue(googleHome.supportNotice.contains("not bundled", ignoreCase = true))
    }
}
