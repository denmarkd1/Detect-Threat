package com.realyn.watchdog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager

data class DeviceLocatorLaunchResult(
    val opened: Boolean,
    val usedFallback: Boolean,
    val usedSetup: Boolean
)

object DeviceLocatorLinkLauncher {

    fun openProvider(context: Context, provider: DeviceLocatorProvider): DeviceLocatorLaunchResult {
        if (launchPackage(context, provider.packageName)) {
            return DeviceLocatorLaunchResult(opened = true, usedFallback = false, usedSetup = false)
        }
        if (launchUri(context, provider.deepLinkUri, provider.packageName)) {
            return DeviceLocatorLaunchResult(opened = true, usedFallback = false, usedSetup = false)
        }
        if (launchUri(context, provider.fallbackUri, "")) {
            return DeviceLocatorLaunchResult(opened = true, usedFallback = true, usedSetup = false)
        }
        return DeviceLocatorLaunchResult(opened = false, usedFallback = false, usedSetup = false)
    }

    fun openSetup(context: Context, provider: DeviceLocatorProvider): DeviceLocatorLaunchResult {
        val setupUri = provider.setupUri.ifBlank { provider.fallbackUri }
        if (launchUri(context, setupUri, "")) {
            return DeviceLocatorLaunchResult(opened = true, usedFallback = setupUri == provider.fallbackUri, usedSetup = true)
        }
        return DeviceLocatorLaunchResult(opened = false, usedFallback = false, usedSetup = true)
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

    private fun launchUri(context: Context, uriValue: String, packageName: String): Boolean {
        if (uriValue.isBlank()) {
            return false
        }
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull() ?: return false

        val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName.isNotBlank()) {
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
