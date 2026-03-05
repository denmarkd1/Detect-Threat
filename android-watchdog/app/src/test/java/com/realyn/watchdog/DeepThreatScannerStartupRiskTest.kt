package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepThreatScannerStartupRiskTest {

    @Test
    fun evaluateStartupRisk_downgradesHighWithoutRuntimeSignalsWhenRequired() {
        val result = DeepThreatScanner.evaluateStartupRisk(
            hasBootReceiver = true,
            hasOverlay = true,
            hasAccessibilityService = true,
            hasDeviceAdminReceiver = true,
            hasActiveAccessibilityService = false,
            hasActiveDeviceAdmin = false,
            riskyPermissions = setOf(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_MEDIA_IMAGES"
            ),
            unknownInstaller = true,
            keywordHit = null,
            trustedRule = null,
            requireRuntimeAbuseForHigh = true
        )
        assertNotNull(result)
        assertEquals(Severity.MEDIUM, result!!.severity)
        assertTrue(result.score <= 69)
        assertTrue(result.signalNotes.any { it.contains("downgraded", ignoreCase = true) })
    }

    @Test
    fun evaluateStartupRisk_trustedPackageWithoutRuntimeSignalsMovesToInfo() {
        val trustedRule = StartupTrustedPackageRule(
            packageName = "com.netqin.ps",
            label = "Vault",
            mode = "integration_observe",
            suppressUninstallAction = true,
            allowWithoutRuntimeAbuse = true,
            notes = "trusted"
        )
        val result = DeepThreatScanner.evaluateStartupRisk(
            hasBootReceiver = true,
            hasOverlay = true,
            hasAccessibilityService = true,
            hasDeviceAdminReceiver = true,
            hasActiveAccessibilityService = false,
            hasActiveDeviceAdmin = false,
            riskyPermissions = setOf(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_MEDIA_IMAGES"
            ),
            unknownInstaller = true,
            keywordHit = null,
            trustedRule = trustedRule,
            requireRuntimeAbuseForHigh = true
        )
        assertNotNull(result)
        assertEquals(Severity.INFO, result!!.severity)
        assertTrue(result.score <= 28)
        assertTrue(result.trustedIntegrationApplied)
        assertTrue(result.signalNotes.any { it.contains("trusted integration", ignoreCase = true) })
    }

    @Test
    fun evaluateStartupRisk_trustedPackageWithRuntimeAbuseStaysHigh() {
        val trustedRule = StartupTrustedPackageRule(
            packageName = "com.netqin.ps",
            label = "Vault",
            mode = "integration_observe",
            suppressUninstallAction = true,
            allowWithoutRuntimeAbuse = true,
            notes = "trusted"
        )
        val result = DeepThreatScanner.evaluateStartupRisk(
            hasBootReceiver = true,
            hasOverlay = true,
            hasAccessibilityService = true,
            hasDeviceAdminReceiver = true,
            hasActiveAccessibilityService = false,
            hasActiveDeviceAdmin = true,
            riskyPermissions = setOf(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_MEDIA_IMAGES"
            ),
            unknownInstaller = true,
            keywordHit = null,
            trustedRule = trustedRule,
            requireRuntimeAbuseForHigh = true
        )
        assertNotNull(result)
        assertEquals(Severity.HIGH, result!!.severity)
        assertFalse(result.trustedIntegrationApplied)
        assertTrue(result.signalNotes.any { it.contains("active device-admin", ignoreCase = true) })
    }

    @Test
    fun evaluateStartupRisk_runtimePrivilegeWithoutCorroborationDowngradesHigh() {
        val result = DeepThreatScanner.evaluateStartupRisk(
            hasBootReceiver = true,
            hasOverlay = false,
            hasAccessibilityService = false,
            hasDeviceAdminReceiver = true,
            hasActiveAccessibilityService = false,
            hasActiveDeviceAdmin = true,
            riskyPermissions = setOf(
                "android.permission.CAMERA",
                "android.permission.READ_MEDIA_IMAGES"
            ),
            unknownInstaller = false,
            keywordHit = null,
            trustedRule = null,
            requireRuntimeAbuseForHigh = true
        )
        assertNotNull(result)
        assertEquals(Severity.MEDIUM, result!!.severity)
        assertTrue(result.score <= 69)
        assertTrue(result.signalNotes.any { it.contains("corroboration", ignoreCase = true) })
    }

}
