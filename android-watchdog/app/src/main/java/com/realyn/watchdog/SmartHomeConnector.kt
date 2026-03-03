package com.realyn.watchdog

import android.content.Context

interface SmartHomeConnector {
    val connectorId: String
    val connectorLabel: String
    val requiredScopes: List<String>

    suspend fun ensureConsent(
        context: Context,
        ownerRole: String
    ): ConnectorConsentArtifact?

    suspend fun getHealth(
        context: Context,
        consentArtifact: ConnectorConsentArtifact
    ): SmartHomeConnectorHealth

    suspend fun collectPosture(
        context: Context,
        consentArtifact: ConnectorConsentArtifact
    ): SmartHomePostureSnapshot

    suspend fun revoke(context: Context, consentArtifact: ConnectorConsentArtifact)

    fun isReadOnlyModeEnabled(): Boolean = true
}
