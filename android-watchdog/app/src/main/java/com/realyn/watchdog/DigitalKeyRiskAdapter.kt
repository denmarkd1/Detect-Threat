package com.realyn.watchdog

import android.content.Context

interface DigitalKeyRiskAdapter {
    val adapterId: String
    val adapterLabel: String

    suspend fun assessRisk(
        context: Context,
        ownerRole: String,
        consentArtifacts: List<ConnectorConsentArtifact>,
        postureSnapshots: List<SmartHomePostureSnapshot>
    ): DigitalKeyRiskAssessment
}
