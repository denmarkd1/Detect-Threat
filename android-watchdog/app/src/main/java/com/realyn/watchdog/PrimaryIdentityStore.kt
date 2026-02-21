package com.realyn.watchdog

import android.content.Context
import java.util.Locale

object PrimaryIdentityStore {

    private const val KEY_PRIMARY_EMAIL = "primary_email"

    fun readPrimaryEmail(context: Context): String {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PRIMARY_EMAIL, "")?.trim().orEmpty()
    }

    fun writePrimaryEmail(context: Context, email: String) {
        val normalized = email.trim().lowercase(Locale.US)
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PRIMARY_EMAIL, normalized).apply()
    }
}
