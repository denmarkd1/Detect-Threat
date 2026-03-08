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
                deviceCount = 226,
                riskScore = 10,
                findings = listOf("connector_read_only_mode", "smart_home_client_detected"),
                snapshotAtEpochMs = 0L
            ),
            connectorLabel = "Samsung SmartThings Connector"
        )

        assertFalse(message.contains("connector_read_only_mode"))
        assertFalse(message.contains("smart_home_client_detected"))
        assertTrue(message.contains("No urgent action is required"))
        assertTrue(message.contains("Samsung SmartThings"))
        assertTrue(message.contains("Back to home"))
    }

    @Test
    fun `resolve posture action opens smartthings when client is missing`() {
        val action = HomeRiskCopy.resolvePostureAction(
            SmartHomePostureSnapshot(
                connectorId = "smartthings",
                ownerRole = "parent",
                deviceCount = 0,
                riskScore = 46,
                findings = listOf("smart_home_client_not_installed"),
                snapshotAtEpochMs = 0L
            )
        )

        assertEquals(HomeRiskCopy.PostureAction.OPEN_SMARTTHINGS, action)
    }

    @Test
    fun `connector display label trims connector suffix for widget text`() {
        assertEquals(
            "Samsung SmartThings",
            HomeRiskCopy.connectorDisplayLabel("Samsung SmartThings Connector", "smartthings")
        )
    }
}
