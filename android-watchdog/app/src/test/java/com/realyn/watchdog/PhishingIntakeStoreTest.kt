package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhishingIntakeStoreTest {

    @Test
    fun `safe preview stores metadata only`() {
        val preview = PhishingIntakeStore.buildSafePreview(
            input = "password=SuperSecret123 otp=998877",
            extractedUrls = listOf("https://example.com/login")
        )

        assertEquals("chars=34, urls=1, redacted=true", preview)
        assertFalse(preview.contains("SuperSecret123"))
        assertFalse(preview.contains("998877"))
    }
}
