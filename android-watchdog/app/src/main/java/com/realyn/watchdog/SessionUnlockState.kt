package com.realyn.watchdog

object SessionUnlockState {

    private var unlockedAtEpochMs: Long = 0L
    private var lastInteractionEpochMs: Long = 0L
    private var backgroundedAtEpochMs: Long = 0L

    fun markUnlocked(nowEpochMs: Long = System.currentTimeMillis()) {
        unlockedAtEpochMs = nowEpochMs
        lastInteractionEpochMs = nowEpochMs
        backgroundedAtEpochMs = 0L
    }

    fun markUserInteraction(nowEpochMs: Long = System.currentTimeMillis()) {
        if (unlockedAtEpochMs > 0L) {
            lastInteractionEpochMs = nowEpochMs
            backgroundedAtEpochMs = 0L
        }
    }

    fun markBackgrounded(nowEpochMs: Long = System.currentTimeMillis()) {
        if (unlockedAtEpochMs > 0L) {
            backgroundedAtEpochMs = nowEpochMs
        }
    }

    fun clear() {
        unlockedAtEpochMs = 0L
        lastInteractionEpochMs = 0L
        backgroundedAtEpochMs = 0L
    }

    fun isUnlocked(): Boolean = unlockedAtEpochMs > 0L

    fun shouldRequireUnlock(idleRelockSeconds: Int, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (unlockedAtEpochMs <= 0L) {
            return true
        }

        val timeoutMs = idleRelockSeconds.coerceAtLeast(15) * 1000L
        val interactionAnchor = when {
            lastInteractionEpochMs > 0L -> lastInteractionEpochMs
            else -> unlockedAtEpochMs
        }

        if (backgroundedAtEpochMs > 0L) {
            val backgroundElapsed = nowEpochMs - backgroundedAtEpochMs
            if (backgroundElapsed >= timeoutMs) {
                return true
            }
        }

        val idleElapsed = nowEpochMs - interactionAnchor
        return idleElapsed >= timeoutMs
    }
}
