package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideRuntimePolicyTest {

    @Test
    fun emulatorDescriptorPrefersAggressiveGenericProfileOverPixelRule() {
        val profile = GuideRuntimePolicy.resolveProfileForDescriptor(
            descriptor = "google sdk_gphone64_x86_64 generic emulator",
            manufacturerRaw = "Google",
            brandRaw = "google"
        )

        assertEquals(GuideDeviceFamily.GENERIC, profile.family)
        assertTrue(profile.eagerForegroundGuideLaunch)
        assertTrue(profile.pinnedGuideNotification)
        assertTrue(profile.fallbackGuideNotification)
    }

    @Test
    fun pixelDescriptorKeepsConservativePixelProfile() {
        val profile = GuideRuntimePolicy.resolveProfileForDescriptor(
            descriptor = "google pixel 8 pro panther",
            manufacturerRaw = "Google",
            brandRaw = "google"
        )

        assertEquals(GuideDeviceFamily.PIXEL, profile.family)
        assertFalse(profile.eagerForegroundGuideLaunch)
        assertFalse(profile.pinnedGuideNotification)
        assertFalse(profile.fallbackGuideNotification)
    }

    @Test
    fun commercialUnknownBuildUsesAggressiveGenericProfile() {
        val profile = GuideRuntimePolicy.resolveProfileForDescriptor(
            descriptor = "motorola edge 50 fusion",
            manufacturerRaw = "motorola",
            brandRaw = "motorola"
        )

        assertEquals(GuideDeviceFamily.GENERIC, profile.family)
        assertTrue(profile.eagerForegroundGuideLaunch)
        assertTrue(profile.pinnedGuideNotification)
        assertTrue(profile.fallbackGuideNotification)
    }

    @Test
    fun blankDescriptorFallsBackToConservativeGenericProfile() {
        val profile = GuideRuntimePolicy.resolveProfileForDescriptor(
            descriptor = "",
            manufacturerRaw = "",
            brandRaw = ""
        )

        assertEquals(GuideDeviceFamily.GENERIC, profile.family)
        assertFalse(profile.eagerForegroundGuideLaunch)
        assertFalse(profile.pinnedGuideNotification)
        assertFalse(profile.fallbackGuideNotification)
    }
}
