package com.realyn.watchdog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

data class WifiPermissionStatus(
    val canReadConnectionState: Boolean,
    val canReadNearbyPosture: Boolean,
    val locationEnabled: Boolean,
    val missingRuntimePermissions: List<String>
)

object WifiPermissionGate {

    fun resolve(context: Context): WifiPermissionStatus {
        val canReadConnection = hasPermission(context, Manifest.permission.ACCESS_WIFI_STATE)

        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)) {
                missing += Manifest.permission.NEARBY_WIFI_DEVICES
            }
        }
        if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }

        val locationEnabled = isLocationEnabled(context)
        val canReadNearby = canReadConnection && missing.isEmpty() && locationEnabled

        return WifiPermissionStatus(
            canReadConnectionState = canReadConnection,
            canReadNearbyPosture = canReadNearby,
            locationEnabled = locationEnabled,
            missingRuntimePermissions = missing
        )
    }

    fun requiredRuntimePermissions(context: Context): Array<String> {
        return resolve(context)
            .missingRuntimePermissions
            .distinct()
            .toTypedArray()
    }

    fun missingPermissionSummary(context: Context): String {
        val status = resolve(context)
        val missing = status.missingRuntimePermissions
            .joinToString(", ") { it.substringAfterLast('.') }
            .ifBlank { "none" }
        val location = if (status.locationEnabled) "on" else "off"
        return "missing=$missing, location=$location"
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }
}
