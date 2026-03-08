package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRiskCopyTest {

    @Test
    fun `build posture message replaces raw finding ids with user guidance`() {
        val message = HomeRiskCopy.buildPostureMessage(
            posture = SmartHomePostureSnapshot(
                connectorId = "smartthings",
                ownerRole = "parent",
                deviceCount = 2,
                riskScore = 10,
                findings = listOf(
                    "connector_read_only_mode",
                    "provider_authorized_locally",
                    "protected_devices_selected"
                ),
                snapshotAtEpochMs = 0L
            ),
            connectorLabel = "Samsung SmartThings Connector",
            protectedDeviceLabels = listOf("Samsung TV", "Samsung Refrigerator")
        )

        assertFalse(message.contains("connector_read_only_mode"))
        assertFalse(message.contains("provider_authorized_locally"))
        assertTrue(message.contains("No urgent action is required"))
        assertTrue(message.contains("Samsung SmartThings"))
        assertTrue(message.contains("Protected devices in this local scan"))
        assertTrue(message.contains("Samsung TV"))
    }

    @Test
    fun `resolve posture action opens provider when provider readiness is missing`() {
        val action = HomeRiskCopy.resolvePostureAction(
            SmartHomePostureSnapshot(
                connectorId = "smartthings",
                ownerRole = "parent",
                deviceCount = 0,
                riskScore = 46,
                findings = listOf("authorization_pending_provider"),
                snapshotAtEpochMs = 0L
            )
        )

        assertEquals(HomeRiskCopy.PostureAction.OPEN_PROVIDER, action)
    }

    @Test
    fun `connector display label trims connector suffix for widget text`() {
        assertEquals(
            "Samsung SmartThings",
            HomeRiskCopy.connectorDisplayLabel("Samsung SmartThings Connector", "smartthings")
        )
    }
}
