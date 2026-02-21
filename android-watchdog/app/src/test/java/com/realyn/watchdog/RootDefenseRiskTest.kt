package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDefenseRiskTest {

    @Test
    fun `marks compromised when su binary is detected`() {
        val evidence = RootRiskEvidence(
            suBinaryDetected = true,
            rootManagerPackages = emptySet(),
            testKeysDetected = false,
            roDebuggable = "0",
            roSecure = "1",
            verifiedBootState = "green",
            playIntegritySignal = null
        )

        val evaluation = RootDefense.evaluateRisk(evidence)
        assertEquals(RootRiskTier.COMPROMISED, evaluation.riskTier)
        assertTrue("su_binary_detected" in evaluation.reasonCodes)
    }

    @Test
    fun `marks elevated when only test keys are detected`() {
        val evidence = RootRiskEvidence(
            suBinaryDetected = false,
            rootManagerPackages = emptySet(),
            testKeysDetected = true,
            roDebuggable = "0",
            roSecure = "1",
            verifiedBootState = "green",
            playIntegritySignal = null
        )

        val evaluation = RootDefense.evaluateRisk(evidence)
        assertEquals(RootRiskTier.ELEVATED, evaluation.riskTier)
        assertTrue("build_test_keys_detected" in evaluation.reasonCodes)
    }

    @Test
    fun `marks trusted when no root indicators are detected`() {
        val evidence = RootRiskEvidence(
            suBinaryDetected = false,
            rootManagerPackages = emptySet(),
            testKeysDetected = false,
            roDebuggable = "0",
            roSecure = "1",
            verifiedBootState = "green",
            playIntegritySignal = null
        )

        val evaluation = RootDefense.evaluateRisk(evidence)
        assertEquals(RootRiskTier.TRUSTED, evaluation.riskTier)
        assertEquals(setOf("no_root_indicators_detected"), evaluation.reasonCodes)
    }

    @Test
    fun `marks compromised when Play Integrity says app not recognized`() {
        val playSignal = PlayIntegritySignal(
            source = "test",
            evaluatedAtEpochMs = System.currentTimeMillis(),
            deviceRecognitionVerdicts = setOf("MEETS_DEVICE_INTEGRITY"),
            appRecognitionVerdict = "UNEVALUATED",
            accountLicensingVerdict = "LICENSED"
        )
        val evidence = RootRiskEvidence(
            suBinaryDetected = false,
            rootManagerPackages = emptySet(),
            testKeysDetected = false,
            roDebuggable = "0",
            roSecure = "1",
            verifiedBootState = "green",
            playIntegritySignal = playSignal
        )

        val evaluation = RootDefense.evaluateRisk(evidence)
        assertEquals(RootRiskTier.COMPROMISED, evaluation.riskTier)
        assertTrue("play_app_not_recognized" in evaluation.reasonCodes)
    }
}
