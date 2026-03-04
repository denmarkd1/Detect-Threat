package com.realyn.watchdog

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDigitalKeyRiskAdapterTest {

    @Test
    fun assess_reportsHighRiskWhenCorePrerequisitesFail() {
        val input = DigitalKeyRiskInput(
            ownerRole = "parent",
            lockScreenSecure = false,
            biometricReady = false,
            rootTier = RootRiskTier.COMPROMISED,
            playDeviceIntegrityReady = false,
            playStrongIntegrityReady = false,
            activeConsentCount = 0,
            staleConsentCount = 1,
            maxPostureRiskScore = 78
        )

        val result = DigitalKeyRiskScorer.assess(
            input = input,
            supportedRiskCategories = setOf(
                "unverified_remote_unlock",
                "sudden_privilege_change",
                "location_restriction_violation",
                "stale_consents",
                "prerequisite_gap",
                "social_engineering_exposure"
            )
        )

        assertTrue(result.score >= 70)
        assertTrue(result.findings.any { it.findingType == "unverified_remote_unlock" })
        assertTrue(result.findings.any { it.findingType == "prerequisite_gap" })
    }

    @Test
    fun assess_includesSocialEngineeringExposureForMinorRole() {
        val input = DigitalKeyRiskInput(
            ownerRole = "child",
            lockScreenSecure = true,
            biometricReady = true,
            rootTier = RootRiskTier.TRUSTED,
            playDeviceIntegrityReady = true,
            playStrongIntegrityReady = true,
            activeConsentCount = 1,
            staleConsentCount = 0,
            maxPostureRiskScore = 20
        )

        val result = DigitalKeyRiskScorer.assess(
            input = input,
            supportedRiskCategories = setOf("social_engineering_exposure")
        )

        assertTrue(result.findings.any { it.findingType == "social_engineering_exposure" })
    }

    @Test
    fun assess_staysLowForTrustedProfileWithHealthyChecks() {
        val input = DigitalKeyRiskInput(
            ownerRole = "parent",
            lockScreenSecure = true,
            biometricReady = true,
            rootTier = RootRiskTier.TRUSTED,
            playDeviceIntegrityReady = true,
            playStrongIntegrityReady = true,
            activeConsentCount = 2,
            staleConsentCount = 0,
            maxPostureRiskScore = 10
        )

        val result = DigitalKeyRiskScorer.assess(
            input = input,
            supportedRiskCategories = setOf(
                "unverified_remote_unlock",
                "sudden_privilege_change",
                "location_restriction_violation",
                "stale_consents",
                "prerequisite_gap",
                "social_engineering_exposure"
            )
        )

        assertTrue(result.score < 35)
    }
}
