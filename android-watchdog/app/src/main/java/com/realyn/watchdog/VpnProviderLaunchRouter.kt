package com.realyn.watchdog

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

data class VpnProviderLaunchResult(
    val opened: Boolean,
    val launchMode: String,
    val launchTarget: String,
    val providerId: String,
    val providerLabel: String
)

object VpnProviderLaunchRouter {

    fun openProvider(context: Context, provider: IntegrationMeshVpnProvider): VpnProviderLaunchResult {
        provider.packageNames.forEach { packageName ->
            if (launchPackage(context, packageName)) {
                return VpnProviderLaunchResult(
                    opened = true,
                    launchMode = "package",
                    launchTarget = packageName,
                    providerId = provider.id,
                    providerLabel = provider.label
                )
            }
        }

        if (launchUri(context, provider.deepLinkUri, provider.packageNames)) {
            return VpnProviderLaunchResult(
                opened = true,
                launchMode = "deep_link",
                launchTarget = provider.deepLinkUri,
                providerId = provider.id,
                providerLabel = provider.label
            )
        }

        if (launchUri(context, provider.fallbackUri, emptyList())) {
            return VpnProviderLaunchResult(
                opened = true,
                launchMode = "fallback_uri",
                launchTarget = provider.fallbackUri,
                providerId = provider.id,
                providerLabel = provider.label
            )
        }

        if (openSystemSettings(context)) {
            return VpnProviderLaunchResult(
                opened = true,
                launchMode = "system_settings",
                launchTarget = Settings.ACTION_VPN_SETTINGS,
                providerId = provider.id,
                providerLabel = provider.label
            )
        }

        return VpnProviderLaunchResult(
            opened = false,
            launchMode = "none",
            launchTarget = "",
            providerId = provider.id,
            providerLabel = provider.label
        )
    }

    fun openSetup(context: Context, provider: IntegrationMeshVpnProvider): VpnProviderLaunchResult {
        val setupUri = provider.setupUri.ifBlank { provider.fallbackUri }
        if (launchUri(context, setupUri, emptyList())) {
            return VpnProviderLaunchResult(
                opened = true,
                launchMode = "setup_uri",
                launchTarget = setupUri,
                providerId = provider.id,
                providerLabel = provider.label
            )
        }
        return openProvider(context, provider)
    }

    private fun openSystemSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startIfResolvable(context, intent)
    }

    private fun launchPackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) {
            return false
        }
        val launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startIfResolvable(context, launchIntent)
    }

    private fun launchUri(context: Context, uriValue: String, packageNames: List<String>): Boolean {
        if (uriValue.isBlank()) {
            return false
        }
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull() ?: return false
        val base = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        packageNames.forEach { packageName ->
            val packaged = Intent(base).setPackage(packageName)
            if (startIfResolvable(context, packaged)) {
                return true
            }
        }

        return startIfResolvable(context, base)
    }

    private fun startIfResolvable(context: Context, intent: Intent): Boolean {
        val resolvable = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
        if (!resolvable) {
            return false
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
