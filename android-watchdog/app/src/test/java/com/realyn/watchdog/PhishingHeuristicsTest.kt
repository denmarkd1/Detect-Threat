package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhishingHeuristicsTest {

    @Test
    fun `returns zero risk for blank input`() {
        val result = PhishingHeuristics.evaluate("   ")

        assertEquals(0, result.riskScore)
        assertTrue(result.reasons.isEmpty())
        assertTrue(result.extractedUrls.isEmpty())
    }

    @Test
    fun `detects risky keywords payload and url in suspicious message`() {
        val input =
            "Urgent: verify your wallet now at http://secure-login.example/reset?token=abc and send otp=123456"

        val result = PhishingHeuristics.evaluate(input)

        assertTrue(result.riskScore >= 50)
        assertTrue(result.reasons.contains("keyword:urgent"))
        assertTrue(result.reasons.contains("keyword:verify"))
        assertTrue(result.reasons.contains("keyword:wallet"))
        assertTrue(result.reasons.contains("sensitive_query_payload"))
        assertTrue(result.extractedUrls.any { it.startsWith("http://") })
    }

    @Test
    fun `keeps risk low for normal trusted https destination`() {
        val input = "Review your account activity at https://accounts.google.com/security"

        val result = PhishingHeuristics.evaluate(input)

        assertEquals(0, result.riskScore)
        assertTrue(result.reasons.isEmpty())
        assertTrue(result.extractedUrls.any { it.contains("accounts.google.com") })
    }
}
