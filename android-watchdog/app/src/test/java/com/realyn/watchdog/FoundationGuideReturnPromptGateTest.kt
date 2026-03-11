package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoundationGuideReturnPromptGateTest {

    @Test
    fun `returns null until prompt is fully armed`() {
        val delay = FoundationGuideReturnPromptGate.remainingDelayMs(
            isArmed = false,
            hasPendingTarget = true,
            hasWindowFocus = true,
            isUnlocked = true,
            settingsLaunchedAtMs = 10_000L,
            nowMs = 10_500L
        )

        assertNull(delay)
    }

    @Test
    fun `holds prompt during initial settings handoff window`() {
        val delay = FoundationGuideReturnPromptGate.remainingDelayMs(
            isArmed = true,
            hasPendingTarget = true,
            hasWindowFocus = true,
            isUnlocked = true,
            settingsLaunchedAtMs = 10_000L,
            nowMs = 10_300L
        )

        assertEquals(900L, delay)
    }

    @Test
    fun `allows prompt once launch grace period has elapsed`() {
        val delay = FoundationGuideReturnPromptGate.remainingDelayMs(
            isArmed = true,
            hasPendingTarget = true,
            hasWindowFocus = true,
            isUnlocked = true,
            settingsLaunchedAtMs = 10_000L,
            nowMs = 11_500L
        )

        assertEquals(0L, delay)
    }
}
