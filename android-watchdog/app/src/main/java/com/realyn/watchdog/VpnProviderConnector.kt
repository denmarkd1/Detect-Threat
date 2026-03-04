package com.realyn.watchdog

import android.content.Context

interface VpnProviderConnector {
    val providerId: String
    val providerLabel: String

    suspend fun ensureConsent(
        context: Context,
        ownerRole: String
    ): ConnectorConsentArtifact?

    suspend fun queryConnectionState(
        context: Context,
        consentArtifact: ConnectorConsentArtifact
    ): VpnProviderConnectionState

    suspend fun launchProvider(
        context: Context,
        consentArtifact: ConnectorConsentArtifact
    ): VpnProviderLaunchResult

    suspend fun revoke(context: Context, consentArtifact: ConnectorConsentArtifact)
}
