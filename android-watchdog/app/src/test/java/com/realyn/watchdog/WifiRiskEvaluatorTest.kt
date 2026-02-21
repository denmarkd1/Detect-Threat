package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiRiskEvaluatorTest {

    @Test
    fun `flags high risk when connected posture is open and unstable`() {
        val snapshot = WifiRiskEvaluator.evaluate(
            signals = WifiPostureSignals(
                ssid = "CafeGuest",
                bssidMasked = "aa:**:**:**:**:ff",
                securityType = "open",
                openNearbyCount = 3,
                weakNearbyCount = 2,
                captivePortalDetected = true,
                meteredNetwork = true,
                repeatedSsidChanges = 4,
                permissionSummary = "missing=none, location=on"
            )
        )

        assertEquals("high_risk", snapshot.tier)
        assertTrue(snapshot.score <= 40)
        assertTrue(snapshot.findings.any { it.contains("open", ignoreCase = true) })
        assertTrue(snapshot.recommendations.isNotEmpty())
    }

    @Test
    fun `returns stable tier when posture has no obvious risk signals`() {
        val snapshot = WifiRiskEvaluator.evaluate(
            signals = WifiPostureSignals(
                ssid = "HomeNet",
                bssidMasked = "aa:**:**:**:**:ff",
                securityType = "wpa3",
                openNearbyCount = 0,
                weakNearbyCount = 0,
                captivePortalDetected = false,
                meteredNetwork = false,
                repeatedSsidChanges = 0,
                permissionSummary = "missing=none, location=on"
            )
        )

        assertEquals("stable", snapshot.tier)
        assertTrue(snapshot.score >= 85)
        assertTrue(snapshot.findings.any { it.contains("stable", ignoreCase = true) })
    }
}
