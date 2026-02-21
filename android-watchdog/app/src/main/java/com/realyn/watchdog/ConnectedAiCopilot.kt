package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class ConnectedAiPolicy(
    val enabled: Boolean,
    val allowUserSubscriptionLink: Boolean,
    val requireExplicitActionConfirmation: Boolean,
    val providerAllowlist: List<String>,
    val modelAllowlist: List<String>,
    val maxActions: Int,
    val blockCoreMutationIntents: Boolean,
    val defensiveScopeOnly: Boolean
) {
    fun isProviderAllowed(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.US)
        return providerAllowlist.any { it.equals(normalized, ignoreCase = true) }
    }

    fun isModelAllowed(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            return false
        }
        return modelAllowlist.any { it.equals(normalized, ignoreCase = true) }
    }
}

data class ConnectedAiLinkState(
    val enabled: Boolean,
    val provider: String,
    val model: String,
    val linkedAtEpochMs: Long,
    val keyFingerprint: String,
    val sessionKeyLoaded: Boolean
)

data class ConnectedCopilotResult(
    val brief: CopilotBrief,
    val connected: Boolean,
    val provider: String,
    val model: String,
    val warning: String
)

object ConnectedAiPolicyStore {

    private const val SETTINGS_FILE_NAME = "workspace_settings.json"

    private val defaultProviders = listOf("openai")
    private val defaultModels = listOf(
        "gpt-4.1-mini",
        "gpt-4o-mini",
        "gpt-4.1"
    )

    private val defaultPolicy = ConnectedAiPolicy(
        enabled = true,
        allowUserSubscriptionLink = true,
        requireExplicitActionConfirmation = true,
        providerAllowlist = defaultProviders,
        modelAllowlist = defaultModels,
        maxActions = 4,
        blockCoreMutationIntents = true,
        defensiveScopeOnly = true
    )

    fun load(context: Context): ConnectedAiPolicy {
        val payload = readSettingsPayload(context) ?: return defaultPolicy
        val connected = payload
            .optJSONObject("copilot")
            ?.optJSONObject("connected_ai")
            ?: return defaultPolicy

        val providers = parseAllowlist(
            connected.optJSONArray("provider_allowlist"),
            defaultProviders
        )
        val models = parseAllowlist(
            connected.optJSONArray("model_allowlist"),
            defaultModels
        )

        return ConnectedAiPolicy(
            enabled = connected.optBoolean("enabled", defaultPolicy.enabled),
            allowUserSubscriptionLink = connected.optBoolean(
                "allow_user_subscription_link",
                defaultPolicy.allowUserSubscriptionLink
            ),
            requireExplicitActionConfirmation = connected.optBoolean(
                "require_explicit_action_confirmation",
                defaultPolicy.requireExplicitActionConfirmation
            ),
            providerAllowlist = providers,
            modelAllowlist = models,
            maxActions = connected.optInt("max_actions", defaultPolicy.maxActions).coerceIn(1, 6),
            blockCoreMutationIntents = connected.optBoolean(
                "block_core_mutation_intents",
                defaultPolicy.blockCoreMutationIntents
            ),
            defensiveScopeOnly = connected.optBoolean(
                "defensive_scope_only",
                defaultPolicy.defensiveScopeOnly
            )
        )
    }

    private fun parseAllowlist(raw: JSONArray?, defaults: List<String>): List<String> {
        if (raw == null) {
            return defaults
        }
        val items = linkedSetOf<String>()
        for (index in 0 until raw.length()) {
            val value = raw.optString(index).trim()
            if (value.isBlank()) {
                continue
            }
            items += value
        }
        return if (items.isEmpty()) defaults else items.toList()
    }

    private fun readSettingsPayload(context: Context): JSONObject? {
        val localOverride = File(context.filesDir, SETTINGS_FILE_NAME)
        val content = when {
            localOverride.exists() -> runCatching { localOverride.readText() }.getOrNull()
            else -> runCatching {
                context.assets.open(SETTINGS_FILE_NAME).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return null

        return runCatching { JSONObject(content) }.getOrNull()
    }
}

object ConnectedAiLinkStore {

    private const val KEY_CONNECTED_AI_ENABLED = "connected_ai_enabled"
    private const val KEY_CONNECTED_AI_PROVIDER = "connected_ai_provider"
    private const val KEY_CONNECTED_AI_MODEL = "connected_ai_model"
    private const val KEY_CONNECTED_AI_LINKED_AT = "connected_ai_linked_at_epoch_ms"
    private const val KEY_CONNECTED_AI_KEY_FINGERPRINT = "connected_ai_key_fingerprint"

    @Volatile
    private var sessionApiKey: String = ""

    @Volatile
    private var sessionProvider: String = ""

    @Volatile
    private var sessionModel: String = ""

    fun read(context: Context): ConnectedAiLinkState {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val provider = prefs.getString(KEY_CONNECTED_AI_PROVIDER, "openai").orEmpty().trim().lowercase(Locale.US)
        val model = prefs.getString(KEY_CONNECTED_AI_MODEL, "gpt-4.1-mini").orEmpty().trim()
        val enabled = prefs.getBoolean(KEY_CONNECTED_AI_ENABLED, false)
        val linkedAt = prefs.getLong(KEY_CONNECTED_AI_LINKED_AT, 0L).coerceAtLeast(0L)
        val fingerprint = prefs.getString(KEY_CONNECTED_AI_KEY_FINGERPRINT, "").orEmpty().trim()
        val sessionLoaded = sessionApiKey.isNotBlank() &&
            provider.equals(sessionProvider, ignoreCase = true) &&
            model.equals(sessionModel, ignoreCase = true)
        return ConnectedAiLinkState(
            enabled = enabled,
            provider = provider.ifBlank { "openai" },
            model = model.ifBlank { "gpt-4.1-mini" },
            linkedAtEpochMs = linkedAt,
            keyFingerprint = fingerprint,
            sessionKeyLoaded = sessionLoaded
        )
    }

    fun link(
        context: Context,
        provider: String,
        model: String,
        apiKey: String
    ): ConnectedAiLinkState {
        val normalizedProvider = provider.trim().lowercase(Locale.US).ifBlank { "openai" }
        val normalizedModel = model.trim().ifBlank { "gpt-4.1-mini" }
        val normalizedKey = apiKey.trim()
        val now = System.currentTimeMillis()

        context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONNECTED_AI_ENABLED, true)
            .putString(KEY_CONNECTED_AI_PROVIDER, normalizedProvider)
            .putString(KEY_CONNECTED_AI_MODEL, normalizedModel)
            .putLong(KEY_CONNECTED_AI_LINKED_AT, now)
            .putString(KEY_CONNECTED_AI_KEY_FINGERPRINT, fingerprint(normalizedKey))
            .apply()

        sessionApiKey = normalizedKey
        sessionProvider = normalizedProvider
        sessionModel = normalizedModel

        appendAudit(
            context = context,
            event = "connected_ai_linked",
            detail = "provider=$normalizedProvider model=$normalizedModel"
        )
        return read(context)
    }

    fun updateSessionKey(context: Context, apiKey: String): ConnectedAiLinkState {
        val state = read(context)
        if (!state.enabled) {
            return state
        }
        val normalizedKey = apiKey.trim()
        if (normalizedKey.isBlank()) {
            return state
        }
        sessionApiKey = normalizedKey
        sessionProvider = state.provider
        sessionModel = state.model
        context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONNECTED_AI_KEY_FINGERPRINT, fingerprint(normalizedKey))
            .apply()
        appendAudit(
            context = context,
            event = "connected_ai_session_key_updated",
            detail = "provider=${state.provider} model=${state.model}"
        )
        return read(context)
    }

    fun unlink(context: Context) {
        context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONNECTED_AI_ENABLED, false)
            .remove(KEY_CONNECTED_AI_PROVIDER)
            .remove(KEY_CONNECTED_AI_MODEL)
            .remove(KEY_CONNECTED_AI_LINKED_AT)
            .remove(KEY_CONNECTED_AI_KEY_FINGERPRINT)
            .apply()
        sessionApiKey = ""
        sessionProvider = ""
        sessionModel = ""
        appendAudit(context = context, event = "connected_ai_unlinked", detail = "")
    }

    fun readSessionApiKey(provider: String, model: String): String {
        val matches = provider.trim().equals(sessionProvider, ignoreCase = true) &&
            model.trim().equals(sessionModel, ignoreCase = true)
        if (!matches) {
            return ""
        }
        return sessionApiKey
    }

    private fun fingerprint(value: String): String {
        if (value.isBlank()) {
            return ""
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte)
        }.take(16)
    }

    private fun appendAudit(context: Context, event: String, detail: String) {
        val line = buildString {
            append(System.currentTimeMillis())
            append("|")
            append(event.trim())
            append("|")
            append(detail.trim())
            append("\n")
        }
        runCatching {
            File(context.filesDir, WatchdogConfig.COPILOT_AUDIT_LOG_FILE).appendText(line)
        }
    }
}

object ConnectedAiCopilotClient {

    private const val SETTINGS_FILE_NAME = "workspace_settings.json"
    private const val DEFAULT_SUPPORT_API_BASE = "http://127.0.0.1:8787"

    fun requestBrief(
        context: Context,
        localBrief: CopilotBrief,
        profileControl: ProfileControlPolicy,
        policy: ConnectedAiPolicy,
        linkState: ConnectedAiLinkState
    ): ConnectedCopilotResult {
        if (!policy.enabled || !policy.allowUserSubscriptionLink) {
            return ConnectedCopilotResult(
                brief = localBrief,
                connected = false,
                provider = "",
                model = "",
                warning = "Connected AI is disabled by workspace policy."
            )
        }
        if (!linkState.enabled) {
            return ConnectedCopilotResult(
                brief = localBrief,
                connected = false,
                provider = "",
                model = "",
                warning = "Connected AI link is not enabled."
            )
        }
        if (!policy.isProviderAllowed(linkState.provider) || !policy.isModelAllowed(linkState.model)) {
            return ConnectedCopilotResult(
                brief = localBrief,
                connected = false,
                provider = linkState.provider,
                model = linkState.model,
                warning = "Connected AI provider/model is blocked by policy."
            )
        }

        val sessionKey = ConnectedAiLinkStore.readSessionApiKey(linkState.provider, linkState.model)
        if (sessionKey.isBlank()) {
            return ConnectedCopilotResult(
                brief = localBrief,
                connected = false,
                provider = linkState.provider,
                model = linkState.model,
                warning = "Session API key is required before connected AI can run."
            )
        }

        val rootPosture = SecurityScanner.currentRootPosture(context)
        val triage = SecurityScanner.readLastScamTriage(context)
        val incidents = IncidentStore.summarize(context)
        val payload = JSONObject()
            .put("connected_ai_enabled", true)
            .put("provider", linkState.provider)
            .put("model", linkState.model)
            .put(
                "policy",
                JSONObject()
                    .put("max_actions", policy.maxActions)
                    .put("block_core_mutation_intents", policy.blockCoreMutationIntents)
                    .put("defensive_scope_only", policy.defensiveScopeOnly)
                    .put(
                        "require_explicit_action_confirmation",
                        policy.requireExplicitActionConfirmation
                    )
            )
            .put(
                "context",
                JSONObject()
                    .put("local_summary", localBrief.summary)
                    .put("local_rationale", localBrief.rationale)
                    .put("profile_role", profileControl.roleCode)
                    .put("age_band", profileControl.ageBandCode)
                    .put("requires_guardian_approval", profileControl.requiresGuardianApprovalForSensitiveActions)
                    .put("root_risk", rootPosture.riskTier.raw)
                    .put("scam_high", triage.highCount)
                    .put("scam_medium", triage.mediumCount)
                    .put("scam_low", triage.lowCount)
                    .put("incident_open", incidents.openCount)
                    .put("incident_in_progress", incidents.inProgressCount)
            )
            .put("local_actions", toActionArray(localBrief.actions))

        val endpoint = resolveCopilotEndpoint(context)
        val response = runCatching { postCopilotBrief(endpoint, payload, sessionKey) }.getOrNull()
            ?: return ConnectedCopilotResult(
                brief = localBrief,
                connected = false,
                provider = linkState.provider,
                model = linkState.model,
                warning = "Connected AI unavailable. Using local copilot guidance."
            )

        val parsed = parseRemoteBrief(
            raw = response,
            fallback = localBrief,
            maxActions = policy.maxActions
        )
        return ConnectedCopilotResult(
            brief = parsed,
            connected = response.optString("mode", "").equals("connected", ignoreCase = true),
            provider = response.optString("provider", linkState.provider),
            model = response.optString("model", linkState.model),
            warning = cleanLine(response.optString("warning", ""), 180)
        )
    }

    private fun postCopilotBrief(
        endpoint: String,
        payload: JSONObject,
        apiKey: String
    ): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            connection.outputStream.use { stream ->
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRemoteBrief(raw: JSONObject, fallback: CopilotBrief, maxActions: Int): CopilotBrief {
        val summary = cleanLine(raw.optString("summary", fallback.summary), 240)
            .ifBlank { fallback.summary }
        val rationale = cleanLine(raw.optString("rationale", fallback.rationale), 500)
            .ifBlank { fallback.rationale }

        val actions = mutableListOf<CopilotAction>()
        val array = raw.optJSONArray("actions")
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val route = parseRoute(item.optString("route")) ?: continue
                val title = cleanLine(item.optString("title"), 96)
                val actionRationale = cleanLine(item.optString("rationale"), 220)
                if (title.isBlank() || actionRationale.isBlank()) {
                    continue
                }
                actions += CopilotAction(
                    title = title,
                    rationale = actionRationale,
                    route = route
                )
            }
        }

        val effectiveActions = if (actions.isEmpty()) {
            fallback.actions
        } else {
            actions.distinctBy { it.title }.take(maxActions.coerceIn(1, 6))
        }
        return CopilotBrief(
            summary = summary,
            rationale = rationale,
            actions = effectiveActions,
            generatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun toActionArray(actions: List<CopilotAction>): JSONArray {
        val array = JSONArray()
        actions.forEach { action ->
            array.put(
                JSONObject()
                    .put("title", action.title)
                    .put("rationale", action.rationale)
                    .put("route", routeCode(action.route))
            )
        }
        return array
    }

    private fun routeCode(route: CopilotRoute): String {
        return when (route) {
            CopilotRoute.RUN_ONE_TIME_SCAN -> "RUN_ONE_TIME_SCAN"
            CopilotRoute.RUN_SCAM_TRIAGE -> "RUN_SCAM_TRIAGE"
            CopilotRoute.OPEN_CREDENTIAL_CENTER -> "OPEN_CREDENTIAL_CENTER"
            CopilotRoute.OPEN_SUPPORT -> "OPEN_SUPPORT"
        }
    }

    private fun parseRoute(raw: String): CopilotRoute? {
        return when (raw.trim().uppercase(Locale.US)) {
            "RUN_ONE_TIME_SCAN" -> CopilotRoute.RUN_ONE_TIME_SCAN
            "RUN_SCAM_TRIAGE" -> CopilotRoute.RUN_SCAM_TRIAGE
            "OPEN_CREDENTIAL_CENTER" -> CopilotRoute.OPEN_CREDENTIAL_CENTER
            "OPEN_SUPPORT" -> CopilotRoute.OPEN_SUPPORT
            else -> null
        }
    }

    private fun cleanLine(value: String, maxChars: Int): String {
        return value.replace("\n", " ")
            .replace("\r", " ")
            .trim()
            .take(maxChars)
    }

    private fun resolveCopilotEndpoint(context: Context): String {
        val configuredBase = readConfiguredSupportApiBase(context)
        val fallbackBase = context.getString(R.string.support_api_base_url)
            .trim()
            .ifBlank { DEFAULT_SUPPORT_API_BASE }
        val base = configuredBase.ifBlank { fallbackBase }
            .trim()
            .ifBlank { DEFAULT_SUPPORT_API_BASE }
            .removeSuffix("/")
        return if (base.endsWith("/api/support")) {
            "$base/copilot/brief"
        } else {
            "$base/api/support/copilot/brief"
        }
    }

    private fun readConfiguredSupportApiBase(context: Context): String {
        val payload = readSettingsPayload(context) ?: return ""
        return payload.optJSONObject("support")
            ?.optString("api_base_url", "")
            ?.trim()
            .orEmpty()
    }

    private fun readSettingsPayload(context: Context): JSONObject? {
        val localOverride = File(context.filesDir, SETTINGS_FILE_NAME)
        val content = when {
            localOverride.exists() -> runCatching { localOverride.readText() }.getOrNull()
            else -> runCatching {
                context.assets.open(SETTINGS_FILE_NAME).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return null

        return runCatching { JSONObject(content) }.getOrNull()
    }
}
