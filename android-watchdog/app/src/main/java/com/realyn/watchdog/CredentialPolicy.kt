package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale

data class OwnerRule(
    val id: String,
    val emailPatterns: List<String>
)

data class WorkspacePolicy(
    val owners: List<OwnerRule>,
    val priorityCategories: List<String>
)

object CredentialPolicy {

    private const val SETTINGS_FILE_NAME = "workspace_settings.json"
    private const val OWNER_PARENT = "parent"
    private const val OWNER_CHILD = "child"
    private const val OWNER_LEGACY_SON = "son"

    private val defaultOwners = listOf(
        OwnerRule(id = OWNER_PARENT, emailPatterns = emptyList()),
        OwnerRule(id = OWNER_CHILD, emailPatterns = emptyList())
    )

    private val defaultPriorityCategories = listOf(
        "email",
        "banking",
        "social",
        "developer",
        "other"
    )

    fun loadPolicy(context: Context): WorkspacePolicy {
        val payload = readPolicyPayload(context) ?: return WorkspacePolicy(defaultOwners, defaultPriorityCategories)

        val owners = parseOwners(payload.optJSONArray("owners"))
        val categories = parsePriorityCategories(payload.optJSONArray("priority_categories"))

        return WorkspacePolicy(
            owners = if (owners.isEmpty()) defaultOwners else owners,
            priorityCategories = if (categories.isEmpty()) defaultPriorityCategories else categories
        )
    }

    fun detectOwner(username: String, policy: WorkspacePolicy): String {
        val value = username.trim().lowercase(Locale.US)
        policy.owners.forEach { owner ->
            owner.emailPatterns.forEach { pattern ->
                if (pattern.isNotBlank() && value.contains(pattern.lowercase(Locale.US))) {
                    return canonicalOwnerId(owner.id)
                }
            }
        }
        return canonicalOwnerId(policy.owners.firstOrNull()?.id ?: OWNER_PARENT)
    }

    fun canonicalOwnerId(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            OWNER_LEGACY_SON, "kid" -> OWNER_CHILD
            OWNER_PARENT -> OWNER_PARENT
            OWNER_CHILD -> OWNER_CHILD
            else -> raw.trim().ifBlank { OWNER_PARENT }.lowercase(Locale.US)
        }
    }

    fun ownerHashKey(raw: String): String {
        val canonical = canonicalOwnerId(raw)
        return if (canonical == OWNER_CHILD) OWNER_LEGACY_SON else canonical
    }

    fun classifyCategory(url: String, service: String): String {
        val domain = domainFromUrl(url)
        val label = "$domain $service".lowercase(Locale.US)

        return when {
            listOf("gmail", "outlook", "yahoo", "proton", "mail", "email").any { it in label } -> "email"
            listOf("bank", "chase", "wellsfargo", "capitalone", "paypal", "amex").any { it in label } -> "banking"
            listOf("facebook", "instagram", "x.com", "twitter", "reddit", "tiktok", "snapchat").any { it in label } -> "social"
            listOf("github", "gitlab", "bitbucket", "aws", "azure", "cloudflare").any { it in label } -> "developer"
            else -> "other"
        }
    }

    fun normalizeUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) {
            return ""
        }
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "https://$value"
        }
    }

    fun categorySortKey(category: String, policy: WorkspacePolicy): Int {
        val index = policy.priorityCategories.indexOf(category)
        return if (index >= 0) index else policy.priorityCategories.size
    }

    private fun domainFromUrl(url: String): String {
        val normalized = normalizeUrl(url)
        if (normalized.isBlank()) {
            return ""
        }

        return try {
            val host = URI(normalized).host.orEmpty().lowercase(Locale.US)
            host.removePrefix("www.")
        } catch (_: Exception) {
            ""
        }
    }

    private fun readPolicyPayload(context: Context): JSONObject? {
        val localOverride = File(context.filesDir, SETTINGS_FILE_NAME)
        val content = when {
            localOverride.exists() -> localOverride.readText()
            else -> runCatching { context.assets.open(SETTINGS_FILE_NAME).bufferedReader().use { it.readText() } }
                .getOrNull()
        } ?: return null

        return runCatching { JSONObject(content) }.getOrNull()
    }

    private fun parseOwners(array: JSONArray?): List<OwnerRule> {
        if (array == null) {
            return emptyList()
        }
        val owners = mutableListOf<OwnerRule>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = canonicalOwnerId(item.optString("id"))
            if (id.isBlank()) {
                continue
            }
            val patternsArray = item.optJSONArray("email_patterns") ?: JSONArray()
            val patterns = mutableListOf<String>()
            for (j in 0 until patternsArray.length()) {
                val pattern = patternsArray.optString(j).trim()
                if (pattern.isNotBlank()) {
                    patterns.add(pattern)
                }
            }
            if (owners.none { it.id == id }) {
                owners.add(OwnerRule(id = id, emailPatterns = patterns))
            }
        }

        return owners
    }

    private fun parsePriorityCategories(array: JSONArray?): List<String> {
        if (array == null) {
            return emptyList()
        }
        val categories = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim().lowercase(Locale.US)
            if (value.isNotBlank()) {
                categories.add(value)
            }
        }
        return categories
    }
}
