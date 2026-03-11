package com.realyn.watchdog

internal object FoundationGuideReturnPromptGate {

    private const val MIN_RETURN_DELAY_MS = 1200L

    fun remainingDelayMs(
        isArmed: Boolean,
        hasPendingTarget: Boolean,
        hasWindowFocus: Boolean,
        isUnlocked: Boolean,
        settingsLaunchedAtMs: Long,
        nowMs: Long
    ): Long? {
        if (!isArmed || !hasPendingTarget || !hasWindowFocus || !isUnlocked) {
            return null
        }
        if (settingsLaunchedAtMs <= 0L) {
            return 0L
        }
        val elapsed = (nowMs - settingsLaunchedAtMs).coerceAtLeast(0L)
        return (MIN_RETURN_DELAY_MS - elapsed).coerceAtLeast(0L)
    }
}
