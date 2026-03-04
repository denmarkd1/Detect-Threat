package com.realyn.watchdog

import android.content.Context
import java.util.Locale

data class VpnCachedState(
    val providerId: String,
    val state: String,
    val details: String,
    val checkedAtEpochMs: Long
)

object VpnStateStore {

    private const val PREF_KEY_STATE = "integration_mesh_vpn_state_"
    private const val PREF_KEY_DETAILS = "integration_mesh_vpn_details_"
    private const val PREF_KEY_CHECKED_AT = "integration_mesh_vpn_checked_at_"
    private const val PREF_KEY_PROVIDER = "integration_mesh_vpn_provider_"

    fun read(context: Context, ownerId: String): VpnCachedState {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val keyOwner = normalizedOwner(ownerId)
        return VpnCachedState(
            providerId = prefs.getString("$PREF_KEY_PROVIDER$keyOwner", "")?.trim().orEmpty(),
            state = prefs.getString("$PREF_KEY_STATE$keyOwner", "disconnected")?.trim().orEmpty(),
            details = prefs.getString("$PREF_KEY_DETAILS$keyOwner", "")?.trim().orEmpty(),
            checkedAtEpochMs = prefs.getLong("$PREF_KEY_CHECKED_AT$keyOwner", 0L)
        )
    }

    fun write(
        context: Context,
        ownerId: String,
        providerId: String,
        state: String,
        details: String,
        checkedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val keyOwner = normalizedOwner(ownerId)
        prefs.edit()
            .putString("$PREF_KEY_PROVIDER$keyOwner", providerId.trim().lowercase(Locale.US))
            .putString("$PREF_KEY_STATE$keyOwner", state.trim().lowercase(Locale.US))
            .putString("$PREF_KEY_DETAILS$keyOwner", details.trim())
            .putLong("$PREF_KEY_CHECKED_AT$keyOwner", checkedAtEpochMs)
            .apply()
    }

    fun clear(context: Context, ownerId: String) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val keyOwner = normalizedOwner(ownerId)
        prefs.edit()
            .remove("$PREF_KEY_PROVIDER$keyOwner")
            .remove("$PREF_KEY_STATE$keyOwner")
            .remove("$PREF_KEY_DETAILS$keyOwner")
            .remove("$PREF_KEY_CHECKED_AT$keyOwner")
            .apply()
    }

    private fun normalizedOwner(ownerId: String): String {
        return ownerId.ifBlank { "default_owner" }.trim().lowercase(Locale.US)
    }
}
