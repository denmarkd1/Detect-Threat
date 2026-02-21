package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamShieldTest {

    @Test
    fun triageSnapshot_prioritizesUrgentActions() {
        val triage = ScamTriageSnapshot(
            evaluatedAtEpochMs = 1_700_000_000_000,
            findings = listOf(
                ScamFinding(
                    findingId = "high-url",
                    sourceType = "url",
                    sourceRef = "phish.example",
                    severity = Severity.HIGH,
                    score = 82,
                    reasonCodes = setOf("non_https_transport"),
                    title = "Suspicious URL risk",
                    remediation = "Open the official app manually."
                ),
                ScamFinding(
                    findingId = "med-app",
                    sourceType = "app",
                    sourceRef = "com.example.spy",
                    severity = Severity.MEDIUM,
                    score = 55,
                    reasonCodes = setOf("risky_app_keyword:spy"),
                    title = "Potential scam app pattern",
                    remediation = "Review app legitimacy."
                )
            )
        )

        assertEquals(1, triage.highCount)
        assertEquals(1, triage.mediumCount)
        assertEquals(0, triage.lowCount)
        assertEquals(2, triage.urgentActions(limit = 3).size)
    }

    @Test
    fun analyzeUrl_returnsNullForNormalHttpsDomain() {
        val finding = ScamShield.analyzeUrl(
            url = "https://accounts.google.com",
            sourceRef = "unit-test"
        )

        assertNull(finding)
    }

    @Test
    fun analyzePackage_flagsSuspiciousPackageKeywords() {
        val finding = ScamShield.analyzePackage("com.example.spy.keylog.remotecontrol")

        assertNotNull(finding)
        assertEquals(Severity.HIGH, finding?.severity)
        assertTrue(finding?.reasonCodes?.any { it.startsWith("risky_app_keyword:") } == true)
    }

    @Test
    fun guardianFingerprint_isStableAcrossFindingOrder() {
        val first = ScamFinding(
            findingId = "a",
            sourceType = "url",
            sourceRef = "a",
            severity = Severity.MEDIUM,
            score = 55,
            reasonCodes = setOf("risky_url_keywords"),
            title = "A",
            remediation = "A"
        )
        val second = ScamFinding(
            findingId = "b",
            sourceType = "app",
            sourceRef = "b",
            severity = Severity.HIGH,
            score = 80,
            reasonCodes = setOf("risky_app_keyword:spy"),
            title = "B",
            remediation = "B"
        )

        val firstOrder = GuardianAlertStore.findingsFingerprint(listOf(first, second))
        val secondOrder = GuardianAlertStore.findingsFingerprint(listOf(second, first))

        assertEquals(firstOrder, secondOrder)
    }
}
