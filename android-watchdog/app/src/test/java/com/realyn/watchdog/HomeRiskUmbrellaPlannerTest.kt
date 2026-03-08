package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRiskUmbrellaPlannerTest {

    @Test
    fun `planner prefers installed smart home provider when nothing is selected`() {
        val smartThings = HomeRiskUmbrellaProvider(
            id = "smartthings",
            label = "Samsung SmartThings",
            category = "smart_home",
            connectorId = "smartthings",
            packageNames = listOf("com.smartthings.android"),
            deepLinkUri = "",
            fallbackUri = "",
            setupUri = "",
            deviceTemplates = listOf(
                HomeRiskUmbrellaDeviceTemplate(
                    id = "samsung_tv",
                    label = "Samsung TV",
                    deviceType = "tv"
                )
            )
        )
        val wallet = HomeRiskUmbrellaProvider(
            id = "google_wallet",
            label = "Google Wallet",
            category = "smart_fob",
            connectorId = "google_wallet",
            packageNames = listOf("com.google.android.apps.walletnfcrel"),
            deepLinkUri = "",
            fallbackUri = "",
            setupUri = "",
            deviceTemplates = listOf(
                HomeRiskUmbrellaDeviceTemplate(
                    id = "google_wallet_key",
                    label = "Google Wallet digital key",
                    deviceType = "smart_fob"
                )
            )
        )

        val plan = HomeRiskOnboardingPlanner.plan(
            providerCapabilities = listOf(
                HomeRiskUmbrellaProviderCapability(
                    provider = smartThings,
                    appInstalled = true,
                    appLaunchReady = true
                ),
                HomeRiskUmbrellaProviderCapability(
                    provider = wallet,
                    appInstalled = false,
                    appLaunchReady = false
                )
            ),
            selectedProviderId = "",
            providerStates = emptyList(),
            protectedDevices = emptyList()
        )

        assertEquals("smartthings", plan?.selectedProvider?.id)
        assertEquals(HomeRiskOnboardingStage.AUTHORIZE_PROVIDER, plan?.stage)
    }

    @Test
    fun `planner advances to ready when provider is authorized and protected devices exist`() {
        val provider = HomeRiskUmbrellaProvider(
            id = "smartthings",
            label = "Samsung SmartThings",
            category = "smart_home",
            connectorId = "smartthings",
            packageNames = listOf("com.smartthings.android"),
            deepLinkUri = "",
            fallbackUri = "",
            setupUri = "",
            deviceTemplates = listOf(
                HomeRiskUmbrellaDeviceTemplate(
                    id = "samsung_tv",
                    label = "Samsung TV",
                    deviceType = "tv"
                )
            )
        )
        val protectedDevice = HomeRiskUmbrellaProtectedDevice(
            ownerRole = "parent",
            deviceId = "smartthings:samsung_tv",
            providerId = "smartthings",
            providerCategory = "smart_home",
            label = "Samsung TV",
            deviceType = "tv",
            protectionEnabled = true,
            importedAtEpochMs = 1L,
            lastScannedAtEpochMs = 0L,
            source = "local_catalog"
        )

        val plan = HomeRiskOnboardingPlanner.plan(
            providerCapabilities = listOf(
                HomeRiskUmbrellaProviderCapability(
                    provider = provider,
                    appInstalled = true,
                    appLaunchReady = true
                )
            ),
            selectedProviderId = "smartthings",
            providerStates = listOf(
                HomeRiskUmbrellaProviderState(
                    ownerRole = "parent",
                    providerId = "smartthings",
                    category = "smart_home",
                    authorizedAtEpochMs = 10L,
                    authorizationMethod = "local_confirmed",
                    lastOpenedAtEpochMs = 0L,
                    lastImportedAtEpochMs = 0L,
                    lastScanAtEpochMs = 0L
                )
            ),
            protectedDevices = listOf(protectedDevice)
        )

        assertEquals(HomeRiskOnboardingStage.READY_TO_SCAN, plan?.stage)
        assertTrue(plan?.protectedDevices?.any { it.label == "Samsung TV" } == true)
    }
}
