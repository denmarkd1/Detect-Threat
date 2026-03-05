package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class StartupTrustedPackageRule(
    val packageName: String,
    val label: String,
    val mode: String,
    val suppressUninstallAction: Boolean,
    val allowWithoutRuntimeAbuse: Boolean,
    val notes: String
)

data class StartupPersistencePolicy(
    val requireRuntimeAbuseForHigh: Boolean,
    val trustedPackages: Map<String, StartupTrustedPackageRule>
) {
    fun ruleFor(packageName: String): StartupTrustedPackageRule? {
        val normalized = packageName.trim().lowercase(Locale.US)
        if (normalized.isBlank()) {
            return null
        }
        return trustedPackages[normalized]
    }
}

object StartupPersistencePolicyGate {

    private val defaults = StartupPersistencePolicy(
        requireRuntimeAbuseForHigh = true,
        trustedPackages = emptyMap()
    )

    fun load(context: Context): StartupPersistencePolicy {
        return parsePayload(WorkspaceSettingsStore.readPayload(context))
    }

    internal fun parsePayload(payload: JSONObject?): StartupPersistencePolicy {
        val section = payload?.optJSONObject("startup_persistence")
        if (section == null) {
            return defaults
        }
        return StartupPersistencePolicy(
            requireRuntimeAbuseForHigh = section.optBoolean(
                "require_runtime_abuse_for_high",
                defaults.requireRuntimeAbuseForHigh
            ),
            trustedPackages = parseTrustedPackages(
                section.optJSONArray("trusted_packages")
            )
        )
    }

    private fun parseTrustedPackages(array: JSONArray?): Map<String, StartupTrustedPackageRule> {
        if (array == null) {
            return emptyMap()
        }
        val rules = linkedMapOf<String, StartupTrustedPackageRule>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("package").trim().lowercase(Locale.US)
            if (packageName.isBlank()) {
                continue
            }
            rules[packageName] = StartupTrustedPackageRule(
                packageName = packageName,
                label = item.optString("label").trim(),
                mode = item.optString("mode", "integration_observe").trim().ifBlank {
                    "integration_observe"
                },
                suppressUninstallAction = item.optBoolean("suppress_uninstall_action", true),
                allowWithoutRuntimeAbuse = item.optBoolean("allow_without_runtime_abuse", true),
                notes = item.optString("notes").trim()
            )
        }
        return rules
    }
}
