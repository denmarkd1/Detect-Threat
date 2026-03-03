package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class IntegrationMeshModule {
    SMART_HOME_CONNECTOR,
    VPN_PROVIDER_CONNECTOR,
    DIGITAL_KEY_RISK_ADAPTER
}

data class IntegrationMeshConfig(
    val enabled: Boolean,
    val schemaVersion: Int,
    val featureFlags: IntegrationMeshFeatureFlags,
    val rollout: IntegrationMeshRolloutConfig,
    val audit: IntegrationMeshAuditSettings,
    val connectors: IntegrationMeshConnectorCatalog
)

data class IntegrationMeshFeatureFlags(
    val smartHomeConnector: IntegrationMeshModuleFeatureFlag,
    val vpnProviderConnector: IntegrationMeshModuleFeatureFlag,
    val digitalKeyRiskAdapter: IntegrationMeshModuleFeatureFlag
) {
    fun forModule(module: IntegrationMeshModule): IntegrationMeshModuleFeatureFlag {
        return when (module) {
            IntegrationMeshModule.SMART_HOME_CONNECTOR -> smartHomeConnector
            IntegrationMeshModule.VPN_PROVIDER_CONNECTOR -> vpnProviderConnector
            IntegrationMeshModule.DIGITAL_KEY_RISK_ADAPTER -> digitalKeyRiskAdapter
        }
    }
}

data class IntegrationMeshModuleFeatureFlag(
    val enabled: Boolean,
    val rolloutStage: String,
    val ownerAllowlist: List<String>,
    val maxRolloutPercent: Int,
    val supportedConnectorIds: List<String>,
    val requiredScopes: List<String>,
    val requireRedemptionProof: Boolean
) {
    fun isOwnerAllowed(ownerRole: String): Boolean {
        val role = ownerRole.lowercase(Locale.US)
        return ownerAllowlist.isEmpty() || ownerAllowlist.contains(role)
    }

    fun isRolloutAllowed(stage: IntegrationMeshRolloutStage?, rolloutKey: String): Boolean {
        if (!enabled || maxRolloutPercent <= 0) {
            return false
        }
        if (rolloutStage.isNotBlank() && stage != null && !rolloutStage.equals(stage.name, ignoreCase = true)) {
            return false
        }
        if (stage == null || !stage.enabled || stage.maxPercent <= 0) {
            return false
        }
        if (maxRolloutPercent > stage.maxPercent) {
            return false
        }
        if (rolloutKey.isBlank()) {
            return true
        }
        return rolloutSlot(rolloutKey) < maxRolloutPercent
    }
}

data class IntegrationMeshRolloutConfig(
    val enabled: Boolean,
    val mode: String,
    val currentStage: String,
    val stages: List<IntegrationMeshRolloutStage>
) {
    fun activeStageForOwner(ownerRole: String): IntegrationMeshRolloutStage? {
        if (!enabled) {
            return null
        }
        val role = ownerRole.lowercase(Locale.US)
        val current = stages.firstOrNull { it.name.equals(currentStage, ignoreCase = true) } ?: return null
        return if (current.ownerRoles.isEmpty() || current.ownerRoles.contains(role)) {
            current
        } else {
            null
        }
    }
}

data class IntegrationMeshRolloutStage(
    val name: String,
    val enabled: Boolean,
    val maxPercent: Int,
    val ownerRoles: List<String>
)

data class IntegrationMeshAuditSettings(
    val enabled: Boolean,
    val eventRetentionDays: Int,
    val maxEvents: Int,
    val schemaVersion: Int,
    val redactFields: List<String>
)

data class IntegrationMeshConnectorCatalog(
    val smartHome: IntegrationMeshSmartHomeConfig,
    val vpnBrokers: IntegrationMeshVpnConfig,
    val digitalKeys: IntegrationMeshDigitalKeyConfig
)

data class IntegrationMeshSmartHomeConfig(
    val allowedConnectorIds: List<String>,
    val readOnly: Boolean,
    val maxCachedDevices: Int,
    val defaultScopeSet: List<String>
)

data class IntegrationMeshVpnConfig(
    val healthTtlMinutes: Int,
    val allowedStatuses: List<String>
)

data class IntegrationMeshDigitalKeyConfig(
    val supportedRiskCategories: List<String>,
    val requireParentApprovalForShare: Boolean
)

fun loadIntegrationMeshConfig(context: Context): IntegrationMeshConfig {
    val payload = WorkspaceSettingsStore.readPayload(context)
    val raw = payload?.optJSONObject("integration_mesh")
    return IntegrationMeshConfigStore.parse(raw)
}

object IntegrationMeshConfigStore {
    private val default = IntegrationMeshConfig(
        enabled = true,
        schemaVersion = 1,
        featureFlags = IntegrationMeshFeatureFlags(
            smartHomeConnector = IntegrationMeshModuleFeatureFlag(
                enabled = false,
                rolloutStage = "internal_test",
                ownerAllowlist = listOf("parent", "child"),
                maxRolloutPercent = 0,
                supportedConnectorIds = listOf("smartthings"),
                requiredScopes = listOf("home:read", "home:devices:read"),
                requireRedemptionProof = true
            ),
            vpnProviderConnector = IntegrationMeshModuleFeatureFlag(
                enabled = false,
                rolloutStage = "internal_test",
                ownerAllowlist = listOf("parent", "child"),
                maxRolloutPercent = 0,
                supportedConnectorIds = listOf("partner_vpn"),
                requiredScopes = emptyList(),
                requireRedemptionProof = true
            ),
            digitalKeyRiskAdapter = IntegrationMeshModuleFeatureFlag(
                enabled = false,
                rolloutStage = "internal_test",
                ownerAllowlist = listOf("parent", "child"),
                maxRolloutPercent = 0,
                supportedConnectorIds = emptyList(),
                requiredScopes = emptyList(),
                requireRedemptionProof = true
            )
        ),
        rollout = IntegrationMeshRolloutConfig(
            enabled = true,
            mode = "staged_percentage",
            currentStage = "internal_test",
            stages = listOf(
                IntegrationMeshRolloutStage(
                    name = "internal_test",
                    enabled = true,
                    maxPercent = 10,
                    ownerRoles = listOf("parent")
                ),
                IntegrationMeshRolloutStage(
                    name = "closed_test",
                    enabled = false,
                    maxPercent = 25,
                    ownerRoles = listOf("parent", "child")
                ),
                IntegrationMeshRolloutStage(
                    name = "production",
                    enabled = false,
                    maxPercent = 5,
                    ownerRoles = listOf("parent", "child")
                )
            )
        ),
        audit = IntegrationMeshAuditSettings(
            enabled = true,
            eventRetentionDays = 365,
            maxEvents = 3000,
            schemaVersion = 1,
            redactFields = listOf(
                "access_token",
                "refresh_token",
                "authorization_code",
                "id_token",
                "device_secret"
            )
        ),
        connectors = IntegrationMeshConnectorCatalog(
            smartHome = IntegrationMeshSmartHomeConfig(
                allowedConnectorIds = listOf("smartthings"),
                readOnly = true,
                maxCachedDevices = 250,
                defaultScopeSet = listOf("home:read", "lock:read")
            ),
            vpnBrokers = IntegrationMeshVpnConfig(
                healthTtlMinutes = 30,
                allowedStatuses = listOf("connected", "connecting", "disconnected", "unknown", "error")
            ),
            digitalKeys = IntegrationMeshDigitalKeyConfig(
                supportedRiskCategories = listOf(
                    "unverified_remote_unlock",
                    "sudden_privilege_change",
                    "location_restriction_violation",
                    "stale_consents"
                ),
                requireParentApprovalForShare = true
            )
        )
    )

    fun parse(payload: JSONObject?): IntegrationMeshConfig {
        if (payload == null) {
            return default
        }

        val featureFlags = parseFeatureFlags(payload.optJSONObject("feature_flags"))
        val rollout = parseRollout(payload.optJSONObject("rollout"))
        val audit = parseAudit(payload.optJSONObject("audit"))
        val connectors = parseConnectorCatalog(payload.optJSONObject("connectors"))

        return IntegrationMeshConfig(
            enabled = payload.optBoolean("enabled", default.enabled),
            schemaVersion = payload.optInt("schema_version", default.schemaVersion),
            featureFlags = featureFlags,
            rollout = rollout,
            audit = audit,
            connectors = connectors
        )
    }

    private fun parseFeatureFlags(payload: JSONObject?): IntegrationMeshFeatureFlags {
        if (payload == null) {
            return default.featureFlags
        }

        val smartHome = parseModuleFeatureFlag(
            payload.optJSONObject("smart_home_connector"),
            default.featureFlags.smartHomeConnector
        )
        val vpnProvider = parseModuleFeatureFlag(
            payload.optJSONObject("vpn_provider_connector"),
            default.featureFlags.vpnProviderConnector
        )
        val digitalKey = parseModuleFeatureFlag(
            payload.optJSONObject("digital_key_risk_adapter"),
            default.featureFlags.digitalKeyRiskAdapter
        )

        return IntegrationMeshFeatureFlags(
            smartHomeConnector = smartHome,
            vpnProviderConnector = vpnProvider,
            digitalKeyRiskAdapter = digitalKey
        )
    }

    private fun parseModuleFeatureFlag(payload: JSONObject?, fallback: IntegrationMeshModuleFeatureFlag): IntegrationMeshModuleFeatureFlag {
        if (payload == null) {
            return fallback
        }

        return IntegrationMeshModuleFeatureFlag(
            enabled = payload.optBoolean("enabled", fallback.enabled),
            rolloutStage = payload.optString("rollout_stage", fallback.rolloutStage).trim(),
            ownerAllowlist = parseStringList(payload.optJSONArray("owner_allowlist"), fallback.ownerAllowlist),
            maxRolloutPercent = payload.optInt("max_rollout_percent", fallback.maxRolloutPercent).coerceIn(0, 100),
            supportedConnectorIds = parseStringList(payload.optJSONArray("supported_connector_ids"), fallback.supportedConnectorIds),
            requiredScopes = parseStringList(payload.optJSONArray("required_scopes"), fallback.requiredScopes),
            requireRedemptionProof = payload.optBoolean("require_redemption_proof", fallback.requireRedemptionProof)
        )
    }

    private fun parseRollout(payload: JSONObject?): IntegrationMeshRolloutConfig {
        if (payload == null) {
            return default.rollout
        }
        val stages = mutableListOf<IntegrationMeshRolloutStage>()
        val stageArray = payload.optJSONArray("stages")
        if (stageArray != null) {
            for (index in 0 until stageArray.length()) {
                val stageObject = stageArray.optJSONObject(index) ?: continue
                stages += IntegrationMeshRolloutStage(
                    name = stageObject.optString("name", "").trim(),
                    enabled = stageObject.optBoolean("enabled", false),
                    maxPercent = stageObject.optInt("max_percent", 0).coerceIn(0, 100),
                    ownerRoles = parseStringList(stageObject.optJSONArray("owner_roles"), emptyList())
                )
            }
        }
        return IntegrationMeshRolloutConfig(
            enabled = payload.optBoolean("enabled", default.rollout.enabled),
            mode = payload.optString("mode", default.rollout.mode).trim(),
            currentStage = payload.optString("current_stage", default.rollout.currentStage).trim(),
            stages = stages.ifEmpty { default.rollout.stages }
        )
    }

    private fun parseAudit(payload: JSONObject?): IntegrationMeshAuditSettings {
        if (payload == null) {
            return default.audit
        }
        return IntegrationMeshAuditSettings(
            enabled = payload.optBoolean("enabled", default.audit.enabled),
            eventRetentionDays = payload.optInt("event_retention_days", default.audit.eventRetentionDays).coerceAtLeast(1),
            maxEvents = payload.optInt("max_events", default.audit.maxEvents).coerceAtLeast(1),
            schemaVersion = payload.optInt("schema_version", default.audit.schemaVersion).coerceAtLeast(1),
            redactFields = parseStringList(payload.optJSONArray("redact_fields"), default.audit.redactFields)
        )
    }

    private fun parseConnectorCatalog(payload: JSONObject?): IntegrationMeshConnectorCatalog {
        if (payload == null) {
            return default.connectors
        }

        val smartHome = parseSmartHomeConnectorConfig(
            payload.optJSONObject("smart_home"),
            default.connectors.smartHome
        )
        val vpn = parseVpnConnectorConfig(
            payload.optJSONObject("vpn_brokers"),
            default.connectors.vpnBrokers
        )
        val digitalKey = parseDigitalKeyConfig(
            payload.optJSONObject("digital_keys"),
            default.connectors.digitalKeys
        )

        return IntegrationMeshConnectorCatalog(
            smartHome = smartHome,
            vpnBrokers = vpn,
            digitalKeys = digitalKey
        )
    }

    private fun parseSmartHomeConnectorConfig(
        payload: JSONObject?,
        fallback: IntegrationMeshSmartHomeConfig
    ): IntegrationMeshSmartHomeConfig {
        if (payload == null) {
            return fallback
        }
        return IntegrationMeshSmartHomeConfig(
            allowedConnectorIds = parseStringList(payload.optJSONArray("allowed_connector_ids"), fallback.allowedConnectorIds),
            readOnly = payload.optBoolean("read_only", fallback.readOnly),
            maxCachedDevices = payload.optInt("max_cached_devices", fallback.maxCachedDevices).coerceAtLeast(1),
            defaultScopeSet = parseStringList(payload.optJSONArray("default_scope_set"), fallback.defaultScopeSet)
        )
    }

    private fun parseVpnConnectorConfig(
        payload: JSONObject?,
        fallback: IntegrationMeshVpnConfig
    ): IntegrationMeshVpnConfig {
        if (payload == null) {
            return fallback
        }
        return IntegrationMeshVpnConfig(
            healthTtlMinutes = payload.optInt("health_ttl_minutes", fallback.healthTtlMinutes).coerceAtLeast(1),
            allowedStatuses = parseStringList(payload.optJSONArray("allowed_statuses"), fallback.allowedStatuses)
        )
    }

    private fun parseDigitalKeyConfig(
        payload: JSONObject?,
        fallback: IntegrationMeshDigitalKeyConfig
    ): IntegrationMeshDigitalKeyConfig {
        if (payload == null) {
            return fallback
        }
        return IntegrationMeshDigitalKeyConfig(
            supportedRiskCategories = parseStringList(payload.optJSONArray("supported_risk_categories"), fallback.supportedRiskCategories),
            requireParentApprovalForShare = payload.optBoolean(
                "require_parent_approval_for_share",
                fallback.requireParentApprovalForShare
            )
        )
    }

    private fun parseStringList(array: JSONArray?, fallback: List<String>): List<String> {
        if (array == null) {
            return fallback
        }
        val values = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optString(index, "").trim()
            if (item.isNotBlank()) {
                values += item.lowercase(Locale.US)
            }
        }
        return if (values.isEmpty()) fallback else values
    }

    fun isModuleEnabled(
        config: IntegrationMeshConfig,
        module: IntegrationMeshModule,
        ownerRole: String,
        ownerId: String
    ): Boolean {
        val stage = config.rollout.activeStageForOwner(ownerRole)
        val flag = config.featureFlags.forModule(module)
        if (!config.enabled || !flag.enabled) {
            return false
        }
        if (!flag.isOwnerAllowed(ownerRole)) {
            return false
        }
        if (!flag.isRolloutAllowed(stage, ownerId)) {
            return false
        }
        return true
    }

    private fun rolloutSlot(raw: String): Int {
        val seed = raw.trim().lowercase(Locale.US).ifBlank { "owner" }
        return (seed.hashCode() and Int.MAX_VALUE) % 100
    }
}
