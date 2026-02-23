package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class DeviceLocatorProvider(
    val id: String,
    val label: String,
    val deepLinkUri: String,
    val fallbackUri: String,
    val setupUri: String,
    val packageName: String
)

data class DeviceLocatorLinksConfig(
    val enabled: Boolean,
    val providers: List<DeviceLocatorProvider>
)

object DeviceLocatorProviderRegistry {

    private val defaultProviders = listOf(
        DeviceLocatorProvider(
            id = "google_find_my_device",
            label = "Google Find My Device",
            deepLinkUri = "https://www.google.com/android/find/",
            fallbackUri = "https://www.google.com/android/find/",
            setupUri = "https://support.google.com/android/answer/3265955",
            packageName = "com.google.android.apps.adm"
        ),
        DeviceLocatorProvider(
            id = "samsung_smartthings_find",
            label = "Samsung SmartThings Find",
            deepLinkUri = "https://smartthingsfind.samsung.com/",
            fallbackUri = "https://smartthingsfind.samsung.com/",
            setupUri = "https://www.samsung.com/us/support/answer/ANS00088182/",
            packageName = "com.samsung.android.oneconnect"
        ),
        DeviceLocatorProvider(
            id = "xiaomi_find_device",
            label = "Xiaomi Find Device",
            deepLinkUri = "https://i.mi.com/",
            fallbackUri = "https://i.mi.com/",
            setupUri = "https://www.mi.com/global/support/article/KA-19417/",
            packageName = "com.miui.cloudservice"
        )
    )

    private val defaults = DeviceLocatorLinksConfig(
        enabled = true,
        providers = defaultProviders
    )

    fun load(context: Context): DeviceLocatorLinksConfig {
        val payload = WorkspaceSettingsStore.readPayload(context)
        return parseConfig(payload?.optJSONObject("device_locator_links"))
    }

    internal fun parseConfig(item: JSONObject?): DeviceLocatorLinksConfig {
        if (item == null) {
            return defaults
        }

        val enabled = item.optBoolean("enabled", defaults.enabled)
        val providers = parseProviders(item.optJSONArray("providers"))
        return DeviceLocatorLinksConfig(
            enabled = enabled,
            providers = providers
        )
    }

    private fun parseProviders(array: JSONArray?): List<DeviceLocatorProvider> {
        if (array == null) {
            return emptyList()
        }
        val providers = mutableListOf<DeviceLocatorProvider>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            parseProvider(item)?.let { providers += it }
        }
        return providers
    }

    private fun parseProvider(item: JSONObject): DeviceLocatorProvider? {
        val id = normalizeId(item.optString("id", ""))
        val label = sanitizeLabel(item.optString("label", ""))
        val deepLinkUri = sanitizeUri(
            firstNonBlank(
                item.optString("uri", ""),
                item.optString("deep_link_uri", ""),
                item.optString("deepLinkUri", "")
            )
        )
        val fallbackUri = sanitizeUri(
            firstNonBlank(
                item.optString("fallback_uri", ""),
                item.optString("fallbackUri", "")
            )
        )
        val setupUri = sanitizeUri(
            firstNonBlank(
                item.optString("setup_uri", ""),
                item.optString("setupUri", ""),
                fallbackUri
            )
        )
        val packageName = sanitizePackage(
            firstNonBlank(
                item.optString("package_name", ""),
                item.optString("packageName", "")
            )
        )

        if (label.isBlank()) {
            return null
        }
        if (deepLinkUri.isBlank() && fallbackUri.isBlank()) {
            return null
        }

        return DeviceLocatorProvider(
            id = if (id.isBlank()) label.lowercase(Locale.US).replace(" ", "_") else id,
            label = label,
            deepLinkUri = deepLinkUri,
            fallbackUri = fallbackUri,
            setupUri = setupUri,
            packageName = packageName
        )
    }

    private fun firstNonBlank(vararg values: String): String {
        values.forEach { value ->
            val trimmed = value.trim()
            if (trimmed.isNotBlank()) {
                return trimmed
            }
        }
        return ""
    }

    private fun sanitizeLabel(raw: String): String {
        return raw.trim().replace("\n", " ").replace("\r", " ").take(72)
    }

    private fun normalizeId(raw: String): String {
        return raw.trim()
            .lowercase(Locale.US)
            .replace("[^a-z0-9_\\-]".toRegex(), "")
            .take(64)
    }

    private fun sanitizePackage(raw: String): String {
        return raw.trim()
            .lowercase(Locale.US)
            .replace("[^a-z0-9._]".toRegex(), "")
            .take(80)
    }

    private fun sanitizeUri(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) {
            return ""
        }
        val lowercase = value.lowercase(Locale.US)
        val allowed = lowercase.startsWith("https://") ||
            lowercase.startsWith("intent://")
        return if (allowed) value else ""
    }
}
