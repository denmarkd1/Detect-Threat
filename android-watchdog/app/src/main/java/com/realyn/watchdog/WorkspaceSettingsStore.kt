package com.realyn.watchdog

import android.content.Context
import org.json.JSONObject
import java.io.File

object WorkspaceSettingsStore {

    private const val SETTINGS_FILE_NAME = "workspace_settings.json"

    fun readPayload(context: Context): JSONObject? {
        val localOverride = File(context.filesDir, SETTINGS_FILE_NAME)
        val content = when {
            localOverride.exists() -> localOverride.readText()
            else -> runCatching {
                context.assets.open(SETTINGS_FILE_NAME).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return null

        return runCatching { JSONObject(content) }.getOrNull()
    }
}
