package com.realyn.watchdog

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build

enum class VpnAssertionState {
    CONFIGURED,
    CONNECTED,
    STALE,
    UNKNOWN
}

data class VpnStatusAssertion(
    val providerId: String,
    val providerLabel: String,
    val assertion: VpnAssertionState,
    val rawState: String,
    val details: String,
    val checkedAtEpochMs: Long,
    val reasonCode: String,
    val enabled: Boolean,
    val consentActive: Boolean
)

object VpnStatusAssertions {

    suspend fun resolve(context: Context): VpnStatusAssertion {
        val state = IntegrationMeshController.snapshot(context)
        val config = IntegrationMeshController.readConfig(context).connectors.vpnBrokers
        val provider = VpnProviderRegistry.resolveProvider(config, state.vpnConnectorId)
        val enabled = IntegrationMeshController.isModuleEnabled(
            context = context,
            module = IntegrationMeshModule.VPN_PROVIDER_CONNECTOR
        )
        if (!enabled) {
            return VpnStatusAssertion(
                providerId = provider.id,
                providerLabel = provider.label,
                assertion = VpnAssertionState.UNKNOWN,
                rawState = "not_configured",
                details = "vpn_rollout_disabled",
                checkedAtEpochMs = 0L,
                reasonCode = "rollout_disabled",
                enabled = false,
                consentActive = false
            )
        }

        val connector = IntegrationMeshController.getActiveVpnConnector(context)
            ?: return VpnStatusAssertion(
                providerId = provider.id,
                providerLabel = provider.label,
                assertion = VpnAssertionState.UNKNOWN,
                rawState = "unavailable",
                details = "vpn_connector_missing",
                checkedAtEpochMs = 0L,
                reasonCode = "connector_missing",
                enabled = true,
                consentActive = false
            )

        val consent = IntegrationMeshAuditStore.latestActiveConsent(
            context = context,
            connectorId = state.vpnConnectorId,
            ownerId = state.ownerId
        ) ?: return VpnStatusAssertion(
            providerId = provider.id,
            providerLabel = provider.label,
            assertion = VpnAssertionState.UNKNOWN,
            rawState = "authorization_needed",
            details = "vpn_consent_missing",
            checkedAtEpochMs = 0L,
            reasonCode = "consent_missing",
            enabled = true,
            consentActive = false
        )

        val connection = runCatching {
            connector.queryConnectionState(context, consent)
        }.getOrElse {
            return resolveCached(context).copy(
                reasonCode = "query_failed"
            )
        }

        val now = System.currentTimeMillis()
        val staleMillis = config.staleAfterMinutes.coerceAtLeast(1).toLong() * 60L * 1000L
        val isStale = connection.checkedAtEpochMs > 0L && now - connection.checkedAtEpochMs > staleMillis
        val hasTransport = isVpnTransportActive(context)

        val mapped = mapState(
            rawState = connection.state,
            details = connection.details,
            isStale = isStale,
            hasTransport = hasTransport
        )

        return VpnStatusAssertion(
            providerId = connection.providerId.ifBlank { provider.id },
            providerLabel = provider.label,
            assertion = mapped.first,
            rawState = connection.state,
            details = connection.details,
            checkedAtEpochMs = connection.checkedAtEpochMs,
            reasonCode = mapped.second,
            enabled = true,
            consentActive = true
        )
    }

    fun resolveCached(context: Context): VpnStatusAssertion {
        val state = IntegrationMeshController.snapshot(context)
        val config = IntegrationMeshController.readConfig(context).connectors.vpnBrokers
        val provider = VpnProviderRegistry.resolveProvider(config, state.vpnConnectorId)
        val enabled = IntegrationMeshController.isModuleEnabled(
            context = context,
            module = IntegrationMeshModule.VPN_PROVIDER_CONNECTOR
        )
        if (!enabled) {
            return VpnStatusAssertion(
                providerId = provider.id,
                providerLabel = provider.label,
                assertion = VpnAssertionState.UNKNOWN,
                rawState = "not_configured",
                details = "vpn_rollout_disabled",
                checkedAtEpochMs = 0L,
                reasonCode = "rollout_disabled",
                enabled = false,
                consentActive = false
            )
        }

        val connector = IntegrationMeshController.getActiveVpnConnector(context)
        if (connector == null) {
            return VpnStatusAssertion(
                providerId = provider.id,
                providerLabel = provider.label,
                assertion = VpnAssertionState.UNKNOWN,
                rawState = "unavailable",
                details = "vpn_connector_missing",
                checkedAtEpochMs = 0L,
                reasonCode = "connector_missing",
                enabled = true,
                consentActive = false
            )
        }

        val consent = IntegrationMeshAuditStore.latestActiveConsent(
            context = context,
            connectorId = state.vpnConnectorId,
            ownerId = state.ownerId
        )
        if (consent == null) {
            return VpnStatusAssertion(
                providerId = provider.id,
                providerLabel = provider.label,
                assertion = VpnAssertionState.UNKNOWN,
                rawState = "authorization_needed",
                details = "vpn_consent_missing",
                checkedAtEpochMs = 0L,
                reasonCode = "consent_missing",
                enabled = true,
                consentActive = false
            )
        }

        val cached = VpnStateStore.read(context, state.ownerId)
        val now = System.currentTimeMillis()
        val staleMillis = config.staleAfterMinutes.coerceAtLeast(1).toLong() * 60L * 1000L
        val isStale = cached.checkedAtEpochMs > 0L && now - cached.checkedAtEpochMs > staleMillis
        val hasTransport = isVpnTransportActive(context)

        val rawState = when {
            hasTransport -> "connected"
            cached.state.isBlank() -> "unknown"
            else -> cached.state
        }
        val details = when {
            hasTransport -> "system_vpn_transport_detected"
            cached.details.isBlank() -> "cached_vpn_state_unavailable"
            else -> cached.details
        }

        val mapped = mapState(
            rawState = rawState,
            details = details,
            isStale = isStale,
            hasTransport = hasTransport
        )

        return VpnStatusAssertion(
            providerId = cached.providerId.ifBlank { provider.id },
            providerLabel = provider.label,
            assertion = mapped.first,
            rawState = rawState,
            details = details,
            checkedAtEpochMs = cached.checkedAtEpochMs,
            reasonCode = mapped.second,
            enabled = true,
            consentActive = true
        )
    }

    private fun mapState(
        rawState: String,
        details: String,
        isStale: Boolean,
        hasTransport: Boolean
    ): Pair<VpnAssertionState, String> {
        val normalizedState = rawState.trim().lowercase()
        val normalizedDetails = details.trim().lowercase()

        if (hasTransport || normalizedState == "connected") {
            return VpnAssertionState.CONNECTED to "connected"
        }
        if (isStale || normalizedDetails.contains("stale")) {
            return VpnAssertionState.STALE to "stale"
        }
        if (normalizedState == "connecting" || normalizedState == "disconnected") {
            return VpnAssertionState.CONFIGURED to "configured"
        }

        return VpnAssertionState.UNKNOWN to if (normalizedState.isBlank()) {
            "unknown"
        } else {
            "state_$normalizedState"
        }
    }

    fun isVpnTransportActive(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        @Suppress("DEPRECATION")
        val networkInfo: NetworkInfo? = connectivityManager.activeNetworkInfo
        @Suppress("DEPRECATION")
        return networkInfo?.type == ConnectivityManager.TYPE_VPN
    }
}
