package com.realyn.watchdog

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class LocatorProviderCapability(
    val provider: DeviceLocatorProvider,
    val packageLaunchReady: Boolean,
    val deepLinkReady: Boolean,
    val fallbackReady: Boolean,
    val setupReady: Boolean
) {
    val launchReady: Boolean
        get() = packageLaunchReady || deepLinkReady || fallbackReady
}

data class LocatorCapabilityState(
    val enabled: Boolean,
    val providers: List<LocatorProviderCapability>,
    val primaryEmailLinked: Boolean,
    val primaryEmail: String
) {
    val hasProvidersConfigured: Boolean
        get() = providers.isNotEmpty()

    val hasLaunchableProvider: Boolean
        get() = providers.any { it.launchReady }
}

object LocatorCapabilityViewModel {

    fun resolve(context: Context): LocatorCapabilityState {
        val config = DeviceLocatorProviderRegistry.load(context)
        val identity = PrimaryIdentityStore.readProfile(context)
        val linked = identity.emailLinkedAtEpochMs > 0L && identity.primaryEmail.isNotBlank()

        val providers = config.providers.map { provider ->
            LocatorProviderCapability(
                provider = provider,
                packageLaunchReady = canLaunchPackage(context, provider.packageName),
                deepLinkReady = canResolveUri(context, provider.deepLinkUri, provider.packageName),
                fallbackReady = canResolveUri(context, provider.fallbackUri, ""),
                setupReady = canResolveUri(context, provider.setupUri.ifBlank { provider.fallbackUri }, "")
            )
        }

        return LocatorCapabilityState(
            enabled = config.enabled,
            providers = providers,
            primaryEmailLinked = linked,
            primaryEmail = identity.primaryEmail
        )
    }

    private fun canResolveUri(context: Context, uriValue: String, packageName: String): Boolean {
        if (uriValue.isBlank()) {
            return false
        }
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (packageName.isNotBlank()) {
            intent.setPackage(packageName)
        }
        return runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
    }

    private fun canLaunchPackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) {
            return false
        }
        return runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        }.getOrDefault(false)
    }
}
