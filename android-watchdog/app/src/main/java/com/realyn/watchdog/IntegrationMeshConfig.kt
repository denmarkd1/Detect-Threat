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

    private fun rolloutSlot(raw: String): Int {
        val seed = raw.trim().lowercase(Locale.US).ifBlank { "owner" }
        return (seed.hashCode() and Int.MAX_VALUE) % 100
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

data class IntegrationMeshVpnProvider(
    val id: String,
    val label: String,
    val packageNames: List<String>,
    val deepLinkUri: String,
    val fallbackUri: String,
    val setupUri: String,
    val paidTierRequired: Boolean
)

data class IntegrationMeshVpnAccountClassPolicy(
    val enabled: Boolean,
    val connectedRequiredCategories: List<String>,
    val configuredRequiredCategories: List<String>,
    val enforcementMode: String
)

data class IntegrationMeshVpnPaidTierPolicy(
    val paidTierRequiredForLinking: Boolean,
    val paidOnlyProviderIds: List<String>,
    val disclosureRequired: Boolean
)

data class IntegrationMeshVpnDisclosurePolicy(
    val brokerNotice: String,
    val providerDataNotice: String,
    val paidTierNotice: String
)

data class IntegrationMeshVpnConfig(
    val healthTtlMinutes: Int,
    val staleAfterMinutes: Int,
    val allowedStatuses: List<String>,
    val defaultProviderId: String,
    val providers: List<IntegrationMeshVpnProvider>,
    val accountClassPolicy: IntegrationMeshVpnAccountClassPolicy,
    val paidTierPolicy: IntegrationMeshVpnPaidTierPolicy,
    val disclosurePolicy: IntegrationMeshVpnDisclosurePolicy
)

data class IntegrationMeshDigitalKeyGuidanceProvider(
    val id: String,
    val label: String,
    val packageNames: List<String>,
    val setupUri: String,
    val fallbackUri: String
)

data class IntegrationMeshDigitalKeyConfig(
    val supportedRiskCategories: List<String>,
    val requireParentApprovalForShare: Boolean,
    val requireParentApprovalForRemoteCommands: Boolean,
    val requireLockScreenForHighRiskActions: Boolean,
    val requireBiometricForHighRiskActions: Boolean,
    val requireStrongIntegrityForHighRiskActions: Boolean,
    val blockHighRiskActionsOnElevatedIntegrity: Boolean,
    val walletSetupGuidance: List<IntegrationMeshDigitalKeyGuidanceProvider>,
    val manufacturerSetupGuidance: List<IntegrationMeshDigitalKeyGuidanceProvider>
)

fun loadIntegrationMeshConfig(context: Context): IntegrationMeshConfig {
    val payload = WorkspaceSettingsStore.readPayload(context)
    val raw = payload?.optJSONObject("integration_mesh")
    return IntegrationMeshConfigStore.parse(raw)
}

object IntegrationMeshConfigStore {
    private val defaultVpnProviders = listOf(
        IntegrationMeshVpnProvider(
            id = "partner_vpn",
            label = "Partner VPN",
            packageNames = listOf(
                "com.nordvpn.android",
                "com.privateinternetaccess.android",
                "com.expressvpn.vpn",
                "com.pia",
                "org.protonvpn.android"
            ),
            deepLinkUri = "",
            fallbackUri = "https://play.google.com/store/search?q=vpn&c=apps",
            setupUri = "https://support.google.com/android/answer/9089766",
            paidTierRequired = true
        ),
        IntegrationMeshVpnProvider(
            id = "protonvpn",
            label = "Proton VPN",
            packageNames = listOf("org.protonvpn.android"),
            deepLinkUri = "https://play.google.com/store/apps/details?id=org.protonvpn.android",
            fallbackUri = "https://play.google.com/store/apps/details?id=org.protonvpn.android",
            setupUri = "https://protonvpn.com/support",
            paidTierRequired = false
        ),
        IntegrationMeshVpnProvider(
            id = "system_vpn",
            label = "System VPN Settings",
            packageNames = emptyList(),
            deepLinkUri = "",
            fallbackUri = "",
            setupUri = "https://support.google.com/android/answer/9089766",
            paidTierRequired = false
        )
    )

    private val defaultDigitalKeyWalletProviders = listOf(
        IntegrationMeshDigitalKeyGuidanceProvider(
            id = "google_wallet",
            label = "Google Wallet",
            packageNames = listOf("com.google.android.apps.walletnfcrel"),
            setupUri = "https://support.google.com/wallet/answer/13314575",
            fallbackUri = "https://play.google.com/store/apps/details?id=com.google.android.apps.walletnfcrel"
        ),
        IntegrationMeshDigitalKeyGuidanceProvider(
            id = "samsung_wallet",
            label = "Samsung Wallet",
            packageNames = listOf("com.samsung.android.spay"),
            setupUri = "https://www.samsung.com/us/support/mobile/mobile-wallet/",
            fallbackUri = "https://galaxystore.samsung.com/prepost/000005790062?langCd=en"
        )
    )

    private val defaultDigitalKeyManufacturerProviders = listOf(
        IntegrationMeshDigitalKeyGuidanceProvider(
            id = "hyundai_digital_key",
            label = "Hyundai Digital Key",
            packageNames = listOf("com.hyundaiusa.bluelink"),
            setupUri = "https://owners.hyundaiusa.com/us/en/resources/technology-and-navigation/introducing-all-new-digital-key",
            fallbackUri = "https://play.google.com/store/search?q=hyundai%20bluelink&c=apps"
        ),
        IntegrationMeshDigitalKeyGuidanceProvider(
            id = "kia_connect_digital_key",
            label = "Kia Connect",
            packageNames = listOf("com.myuvo.link"),
            setupUri = "https://owners.kia.com/content/owners/en/uvo-link.html",
            fallbackUri = "https://play.google.com/store/search?q=kia%20connect&c=apps"
        ),
        IntegrationMeshDigitalKeyGuidanceProvider(
            id = "bmw_digital_key",
            label = "BMW Digital Key",
            packageNames = listOf("de.bmw.connected.mobile20.na"),
            setupUri = "https://www.bmwusa.com/explore/connecteddrive.html",
            fallbackUri = "https://play.google.com/store/search?q=bmw%20connected&c=apps"
        )
    )

    private val default = IntegrationMeshConfig(
        enabled = true,
        schemaVersion = 1,
        featureFlags = IntegrationMeshFeatureFlags(
            smartHomeConnector = IntegrationMeshModuleFeatureFlag(
                enabled = true,
                rolloutStage = "internal_test",
                ownerAllowlist = listOf("parent", "child", "son"),
                maxRolloutPercent = 100,
                supportedConnectorIds = listOf("smartthings"),
                requiredScopes = listOf("home:read", "home:devices:read"),
                requireRedemptionProof = true
            ),
            vpnProviderConnector = IntegrationMeshModuleFeatureFlag(
                enabled = true,
                rolloutStage = "internal_test",
                ownerAllowlist = listOf("parent", "child", "son"),
                maxRolloutPercent = 100,
                supportedConnectorIds = listOf("partner_vpn", "protonvpn", "system_vpn"),
                requiredScopes = listOf("vpn:launch", "vpn:status"),
                requireRedemptionProof = true
            ),
            digitalKeyRiskAdapter = IntegrationMeshModuleFeatureFlag(
                enabled = true,
                rolloutStage = "internal_test",
                ownerAllowlist = listOf("parent", "child", "son"),
                maxRolloutPercent = 100,
                supportedConnectorIds = listOf("local_digital_key_guardrails"),
                requiredScopes = listOf("digital_key:advisory", "digital_key:high_risk_prompt"),
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
                    maxPercent = 100,
                    ownerRoles = listOf("parent", "child", "son")
                ),
                IntegrationMeshRolloutStage(
                    name = "closed_test",
                    enabled = false,
                    maxPercent = 25,
                    ownerRoles = listOf("parent", "child", "son")
                ),
                IntegrationMeshRolloutStage(
                    name = "production",
                    enabled = false,
                    maxPercent = 5,
                    ownerRoles = listOf("parent", "child", "son")
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
                staleAfterMinutes = 30,
                allowedStatuses = listOf("connected", "connecting", "disconnected", "unknown", "error"),
                defaultProviderId = "partner_vpn",
                providers = defaultVpnProviders,
                accountClassPolicy = IntegrationMeshVpnAccountClassPolicy(
                    enabled = true,
                    connectedRequiredCategories = listOf("banking"),
                    configuredRequiredCategories = listOf("developer"),
                    enforcementMode = "block"
                ),
                paidTierPolicy = IntegrationMeshVpnPaidTierPolicy(
                    paidTierRequiredForLinking = false,
                    paidOnlyProviderIds = listOf("partner_vpn"),
                    disclosureRequired = true
                ),
                disclosurePolicy = IntegrationMeshVpnDisclosurePolicy(
                    brokerNotice = "DT Guardian brokers VPN provider launch and status checks. It does not run a device VPN tunnel.",
                    providerDataNotice = "Provider account terms, privacy policy, and retention controls are managed by the selected VPN provider.",
                    paidTierNotice = "Some provider launch paths require a paid protection tier."
                )
            ),
            digitalKeys = IntegrationMeshDigitalKeyConfig(
                supportedRiskCategories = listOf(
                    "unverified_remote_unlock",
                    "sudden_privilege_change",
                    "location_restriction_violation",
                    "stale_consents",
                    "prerequisite_gap",
                    "social_engineering_exposure"
                ),
                requireParentApprovalForShare = true,
                requireParentApprovalForRemoteCommands = true,
                requireLockScreenForHighRiskActions = true,
                requireBiometricForHighRiskActions = true,
                requireStrongIntegrityForHighRiskActions = true,
                blockHighRiskActionsOnElevatedIntegrity = false,
                walletSetupGuidance = defaultDigitalKeyWalletProviders,
                manufacturerSetupGuidance = defaultDigitalKeyManufacturerProviders
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
            ownerAllowlist = parseIdentifierList(payload.optJSONArray("owner_allowlist"), fallback.ownerAllowlist),
            maxRolloutPercent = payload.optInt("max_rollout_percent", fallback.maxRolloutPercent).coerceIn(0, 100),
            supportedConnectorIds = parseIdentifierList(payload.optJSONArray("supported_connector_ids"), fallback.supportedConnectorIds),
            requiredScopes = parseIdentifierList(payload.optJSONArray("required_scopes"), fallback.requiredScopes),
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
                    ownerRoles = parseIdentifierList(stageObject.optJSONArray("owner_roles"), emptyList())
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
            redactFields = parseIdentifierList(payload.optJSONArray("redact_fields"), default.audit.redactFields)
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
            allowedConnectorIds = parseIdentifierList(payload.optJSONArray("allowed_connector_ids"), fallback.allowedConnectorIds),
            readOnly = payload.optBoolean("read_only", fallback.readOnly),
            maxCachedDevices = payload.optInt("max_cached_devices", fallback.maxCachedDevices).coerceAtLeast(1),
            defaultScopeSet = parseIdentifierList(payload.optJSONArray("default_scope_set"), fallback.defaultScopeSet)
        )
    }

    private fun parseVpnConnectorConfig(
        payload: JSONObject?,
        fallback: IntegrationMeshVpnConfig
    ): IntegrationMeshVpnConfig {
        if (payload == null) {
            return fallback
        }

        val providers = parseVpnProviders(payload.optJSONArray("providers"), fallback.providers)
        val defaultProviderId = normalizeIdentifier(
            payload.optString("default_provider_id", fallback.defaultProviderId)
        ).ifBlank {
            providers.firstOrNull()?.id ?: fallback.defaultProviderId
        }

        val accountClassPolicy = parseVpnAccountClassPolicy(
            payload.optJSONObject("account_class_policy"),
            fallback.accountClassPolicy
        )
        val paidTierPolicy = parseVpnPaidTierPolicy(
            payload.optJSONObject("paid_tier_policy"),
            fallback.paidTierPolicy
        )
        val disclosurePolicy = parseVpnDisclosurePolicy(
            payload.optJSONObject("disclosures"),
            fallback.disclosurePolicy
        )

        return IntegrationMeshVpnConfig(
            healthTtlMinutes = payload.optInt("health_ttl_minutes", fallback.healthTtlMinutes).coerceAtLeast(1),
            staleAfterMinutes = payload.optInt("stale_after_minutes", fallback.staleAfterMinutes).coerceAtLeast(1),
            allowedStatuses = parseIdentifierList(payload.optJSONArray("allowed_statuses"), fallback.allowedStatuses),
            defaultProviderId = defaultProviderId,
            providers = providers,
            accountClassPolicy = accountClassPolicy,
            paidTierPolicy = paidTierPolicy,
            disclosurePolicy = disclosurePolicy
        )
    }

    private fun parseVpnProviders(array: JSONArray?, fallback: List<IntegrationMeshVpnProvider>): List<IntegrationMeshVpnProvider> {
        if (array == null) {
            return fallback
        }
        val providers = mutableListOf<IntegrationMeshVpnProvider>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            parseVpnProvider(item)?.let { providers += it }
        }
        return if (providers.isEmpty()) fallback else providers
    }

    private fun parseVpnProvider(item: JSONObject): IntegrationMeshVpnProvider? {
        val id = normalizeIdentifier(item.optString("id", ""))
        val label = item.optString("label", "").trim().replace("\n", " ").replace("\r", " ")
        if (id.isBlank() || label.isBlank()) {
            return null
        }
        val packageNames = parseIdentifierList(item.optJSONArray("package_names"), emptyList())
            .map { it.replace("[^a-z0-9._]".toRegex(), "") }
            .filter { it.isNotBlank() }

        val deepLinkUri = parseUri(
            firstNonBlank(
                item.optString("deep_link_uri", ""),
                item.optString("deepLinkUri", ""),
                item.optString("uri", "")
            )
        )
        val fallbackUri = parseUri(
            firstNonBlank(
                item.optString("fallback_uri", ""),
                item.optString("fallbackUri", "")
            )
        )
        val setupUri = parseUri(
            firstNonBlank(
                item.optString("setup_uri", ""),
                item.optString("setupUri", ""),
                fallbackUri
            )
        )

        return IntegrationMeshVpnProvider(
            id = id,
            label = label.take(72),
            packageNames = packageNames,
            deepLinkUri = deepLinkUri,
            fallbackUri = fallbackUri,
            setupUri = setupUri,
            paidTierRequired = item.optBoolean("paid_tier_required", false)
        )
    }

    private fun parseVpnAccountClassPolicy(
        payload: JSONObject?,
        fallback: IntegrationMeshVpnAccountClassPolicy
    ): IntegrationMeshVpnAccountClassPolicy {
        if (payload == null) {
            return fallback
        }

        val enforcementMode = payload.optString("enforcement_mode", fallback.enforcementMode)
            .trim()
            .lowercase(Locale.US)
            .let { mode ->
                if (mode == "warn") "warn" else "block"
            }

        return IntegrationMeshVpnAccountClassPolicy(
            enabled = payload.optBoolean("enabled", fallback.enabled),
            connectedRequiredCategories = parseIdentifierList(
                payload.optJSONArray("connected_required_categories"),
                fallback.connectedRequiredCategories
            ),
            configuredRequiredCategories = parseIdentifierList(
                payload.optJSONArray("configured_required_categories"),
                fallback.configuredRequiredCategories
            ),
            enforcementMode = enforcementMode
        )
    }

    private fun parseVpnPaidTierPolicy(
        payload: JSONObject?,
        fallback: IntegrationMeshVpnPaidTierPolicy
    ): IntegrationMeshVpnPaidTierPolicy {
        if (payload == null) {
            return fallback
        }
        return IntegrationMeshVpnPaidTierPolicy(
            paidTierRequiredForLinking = payload.optBoolean(
                "paid_tier_required_for_linking",
                fallback.paidTierRequiredForLinking
            ),
            paidOnlyProviderIds = parseIdentifierList(
                payload.optJSONArray("paid_only_provider_ids"),
                fallback.paidOnlyProviderIds
            ),
            disclosureRequired = payload.optBoolean("disclosure_required", fallback.disclosureRequired)
        )
    }

    private fun parseVpnDisclosurePolicy(
        payload: JSONObject?,
        fallback: IntegrationMeshVpnDisclosurePolicy
    ): IntegrationMeshVpnDisclosurePolicy {
        if (payload == null) {
            return fallback
        }
        return IntegrationMeshVpnDisclosurePolicy(
            brokerNotice = payload.optString("broker_notice", fallback.brokerNotice).trim(),
            providerDataNotice = payload.optString("provider_data_notice", fallback.providerDataNotice).trim(),
            paidTierNotice = payload.optString("paid_tier_notice", fallback.paidTierNotice).trim()
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
            supportedRiskCategories = parseIdentifierList(
                payload.optJSONArray("supported_risk_categories"),
                fallback.supportedRiskCategories
            ),
            requireParentApprovalForShare = payload.optBoolean(
                "require_parent_approval_for_share",
                fallback.requireParentApprovalForShare
            ),
            requireParentApprovalForRemoteCommands = payload.optBoolean(
                "require_parent_approval_for_remote_commands",
                fallback.requireParentApprovalForRemoteCommands
            ),
            requireLockScreenForHighRiskActions = payload.optBoolean(
                "require_lock_screen_for_high_risk_actions",
                fallback.requireLockScreenForHighRiskActions
            ),
            requireBiometricForHighRiskActions = payload.optBoolean(
                "require_biometric_for_high_risk_actions",
                fallback.requireBiometricForHighRiskActions
            ),
            requireStrongIntegrityForHighRiskActions = payload.optBoolean(
                "require_strong_integrity_for_high_risk_actions",
                fallback.requireStrongIntegrityForHighRiskActions
            ),
            blockHighRiskActionsOnElevatedIntegrity = payload.optBoolean(
                "block_high_risk_actions_on_elevated_integrity",
                fallback.blockHighRiskActionsOnElevatedIntegrity
            ),
            walletSetupGuidance = parseDigitalKeyGuidanceProviders(
                payload.optJSONArray("wallet_setup_guidance"),
                fallback.walletSetupGuidance
            ),
            manufacturerSetupGuidance = parseDigitalKeyGuidanceProviders(
                payload.optJSONArray("manufacturer_setup_guidance"),
                fallback.manufacturerSetupGuidance
            )
        )
    }

    private fun parseDigitalKeyGuidanceProviders(
        array: JSONArray?,
        fallback: List<IntegrationMeshDigitalKeyGuidanceProvider>
    ): List<IntegrationMeshDigitalKeyGuidanceProvider> {
        if (array == null) {
            return fallback
        }
        val providers = mutableListOf<IntegrationMeshDigitalKeyGuidanceProvider>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            parseDigitalKeyGuidanceProvider(item)?.let { providers += it }
        }
        return if (providers.isEmpty()) fallback else providers
    }

    private fun parseDigitalKeyGuidanceProvider(item: JSONObject): IntegrationMeshDigitalKeyGuidanceProvider? {
        val id = normalizeIdentifier(item.optString("id", ""))
        val label = item.optString("label", "").trim().replace("\n", " ").replace("\r", " ")
        if (id.isBlank() || label.isBlank()) {
            return null
        }

        val packageNames = parseIdentifierList(item.optJSONArray("package_names"), emptyList())
            .map { it.replace("[^a-z0-9._]".toRegex(), "") }
            .filter { it.isNotBlank() }

        val setupUri = parseUri(
            firstNonBlank(
                item.optString("setup_uri", ""),
                item.optString("setupUri", ""),
                item.optString("uri", "")
            )
        )
        val fallbackUri = parseUri(
            firstNonBlank(
                item.optString("fallback_uri", ""),
                item.optString("fallbackUri", "")
            )
        )

        return IntegrationMeshDigitalKeyGuidanceProvider(
            id = id,
            label = label.take(72),
            packageNames = packageNames,
            setupUri = setupUri,
            fallbackUri = fallbackUri
        )
    }

    private fun parseIdentifierList(array: JSONArray?, fallback: List<String>): List<String> {
        if (array == null) {
            return fallback
        }
        val values = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val item = normalizeIdentifier(array.optString(index, ""))
            if (item.isNotBlank()) {
                values += item
            }
        }
        return if (values.isEmpty()) fallback else values
    }

    private fun normalizeIdentifier(raw: String): String {
        return raw.trim().lowercase(Locale.US)
    }

    private fun parseUri(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) {
            return ""
        }
        val lower = value.lowercase(Locale.US)
        return when {
            lower.startsWith("https://") -> value
            lower.startsWith("intent://") -> value
            else -> ""
        }
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
}
