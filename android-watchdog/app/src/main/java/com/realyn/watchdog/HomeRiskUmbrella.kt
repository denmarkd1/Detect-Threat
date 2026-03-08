package com.realyn.watchdog

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

private const val HOME_RISK_UMBRELLA_STATE_FILE = "dt_home_risk_umbrella_state.json"

data class HomeRiskUmbrellaDeviceTemplate(
    val id: String,
    val label: String,
    val deviceType: String
)

data class HomeRiskUmbrellaProvider(
    val id: String,
    val label: String,
    val category: String,
    val connectorId: String,
    val packageNames: List<String>,
    val deepLinkUri: String,
    val fallbackUri: String,
    val setupUri: String,
    val deviceTemplates: List<HomeRiskUmbrellaDeviceTemplate>,
    val authMode: String = "local_only",
    val inventoryMode: String = "local_catalog",
    val apiBaseUrl: String = "",
    val requiresInstanceUrl: Boolean = false,
    val supportNotice: String = ""
)

data class HomeRiskUmbrellaProviderCapability(
    val provider: HomeRiskUmbrellaProvider,
    val appInstalled: Boolean,
    val appLaunchReady: Boolean
)

data class HomeRiskUmbrellaSelection(
    val ownerRole: String,
    val providerId: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("owner_role", ownerRole)
            .put("provider_id", providerId)
    }

    companion object {
        fun fromJson(item: JSONObject): HomeRiskUmbrellaSelection {
            return HomeRiskUmbrellaSelection(
                ownerRole = item.optString("owner_role").trim(),
                providerId = item.optString("provider_id").trim()
            )
        }
    }
}

data class HomeRiskUmbrellaProviderState(
    val ownerRole: String,
    val providerId: String,
    val category: String,
    val authorizedAtEpochMs: Long,
    val authorizationMethod: String,
    val lastOpenedAtEpochMs: Long,
    val lastImportedAtEpochMs: Long,
    val lastScanAtEpochMs: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("owner_role", ownerRole)
            .put("provider_id", providerId)
            .put("category", category)
            .put("authorized_at_epoch_ms", authorizedAtEpochMs)
            .put("authorization_method", authorizationMethod)
            .put("last_opened_at_epoch_ms", lastOpenedAtEpochMs)
            .put("last_imported_at_epoch_ms", lastImportedAtEpochMs)
            .put("last_scan_at_epoch_ms", lastScanAtEpochMs)
    }

    companion object {
        fun fromJson(item: JSONObject): HomeRiskUmbrellaProviderState {
            return HomeRiskUmbrellaProviderState(
                ownerRole = item.optString("owner_role").trim(),
                providerId = item.optString("provider_id").trim(),
                category = item.optString("category").trim(),
                authorizedAtEpochMs = item.optLong("authorized_at_epoch_ms", 0L),
                authorizationMethod = item.optString("authorization_method").trim(),
                lastOpenedAtEpochMs = item.optLong("last_opened_at_epoch_ms", 0L),
                lastImportedAtEpochMs = item.optLong("last_imported_at_epoch_ms", 0L),
                lastScanAtEpochMs = item.optLong("last_scan_at_epoch_ms", 0L)
            )
        }
    }
}

data class HomeRiskUmbrellaProtectedDevice(
    val ownerRole: String,
    val deviceId: String,
    val providerId: String,
    val providerCategory: String,
    val label: String,
    val deviceType: String,
    val protectionEnabled: Boolean,
    val importedAtEpochMs: Long,
    val lastScannedAtEpochMs: Long,
    val source: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("owner_role", ownerRole)
            .put("device_id", deviceId)
            .put("provider_id", providerId)
            .put("provider_category", providerCategory)
            .put("label", label)
            .put("device_type", deviceType)
            .put("protection_enabled", protectionEnabled)
            .put("imported_at_epoch_ms", importedAtEpochMs)
            .put("last_scanned_at_epoch_ms", lastScannedAtEpochMs)
            .put("source", source)
    }

    companion object {
        fun fromJson(item: JSONObject): HomeRiskUmbrellaProtectedDevice {
            return HomeRiskUmbrellaProtectedDevice(
                ownerRole = item.optString("owner_role").trim(),
                deviceId = item.optString("device_id").trim(),
                providerId = item.optString("provider_id").trim(),
                providerCategory = item.optString("provider_category").trim(),
                label = item.optString("label").trim(),
                deviceType = item.optString("device_type").trim(),
                protectionEnabled = item.optBoolean("protection_enabled", false),
                importedAtEpochMs = item.optLong("imported_at_epoch_ms", 0L),
                lastScannedAtEpochMs = item.optLong("last_scanned_at_epoch_ms", 0L),
                source = item.optString("source").trim()
            )
        }
    }
}

data class HomeRiskUmbrellaState(
    val selections: List<HomeRiskUmbrellaSelection>,
    val providerStates: List<HomeRiskUmbrellaProviderState>,
    val protectedDevices: List<HomeRiskUmbrellaProtectedDevice>,
    val updatedAtEpochMs: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put(
                "selections",
                JSONArray().apply {
                    selections.forEach { put(it.toJson()) }
                }
            )
            .put(
                "provider_states",
                JSONArray().apply {
                    providerStates.forEach { put(it.toJson()) }
                }
            )
            .put(
                "protected_devices",
                JSONArray().apply {
                    protectedDevices.forEach { put(it.toJson()) }
                }
            )
            .put("updated_at_epoch_ms", updatedAtEpochMs)
    }

    companion object {
        fun empty(): HomeRiskUmbrellaState {
            return HomeRiskUmbrellaState(
                selections = emptyList(),
                providerStates = emptyList(),
                protectedDevices = emptyList(),
                updatedAtEpochMs = 0L
            )
        }

        fun fromJson(item: JSONObject): HomeRiskUmbrellaState {
            return HomeRiskUmbrellaState(
                selections = parseSelections(item.optJSONArray("selections")),
                providerStates = parseProviderStates(item.optJSONArray("provider_states")),
                protectedDevices = parseProtectedDevices(item.optJSONArray("protected_devices")),
                updatedAtEpochMs = item.optLong("updated_at_epoch_ms", 0L)
            )
        }

        private fun parseSelections(array: JSONArray?): List<HomeRiskUmbrellaSelection> {
            if (array == null) {
                return emptyList()
            }
            val values = mutableListOf<HomeRiskUmbrellaSelection>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching { HomeRiskUmbrellaSelection.fromJson(item) }.getOrNull()?.let { values += it }
            }
            return values
        }

        private fun parseProviderStates(array: JSONArray?): List<HomeRiskUmbrellaProviderState> {
            if (array == null) {
                return emptyList()
            }
            val values = mutableListOf<HomeRiskUmbrellaProviderState>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching { HomeRiskUmbrellaProviderState.fromJson(item) }.getOrNull()?.let { values += it }
            }
            return values
        }

        private fun parseProtectedDevices(array: JSONArray?): List<HomeRiskUmbrellaProtectedDevice> {
            if (array == null) {
                return emptyList()
            }
            val values = mutableListOf<HomeRiskUmbrellaProtectedDevice>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching { HomeRiskUmbrellaProtectedDevice.fromJson(item) }.getOrNull()?.let { values += it }
            }
            return values
        }
    }
}

enum class HomeRiskOnboardingStage {
    INSTALL_PROVIDER,
    AUTHORIZE_PROVIDER,
    IMPORT_DEVICES,
    SELECT_PROTECTION,
    READY_TO_SCAN
}

data class HomeRiskOnboardingProviderStatus(
    val provider: HomeRiskUmbrellaProvider,
    val selected: Boolean,
    val appInstalled: Boolean,
    val appLaunchReady: Boolean,
    val authorized: Boolean,
    val importedDeviceCount: Int,
    val protectedDeviceCount: Int,
    val lastOpenedAtEpochMs: Long,
    val lastImportedAtEpochMs: Long,
    val lastScanAtEpochMs: Long
)

data class HomeRiskOnboardingPlan(
    val stage: HomeRiskOnboardingStage,
    val selectedProviderStatus: HomeRiskOnboardingProviderStatus,
    val providerStatuses: List<HomeRiskOnboardingProviderStatus>,
    val importedDevices: List<HomeRiskUmbrellaProtectedDevice>,
    val protectedDevices: List<HomeRiskUmbrellaProtectedDevice>
) {
    val selectedProvider: HomeRiskUmbrellaProvider
        get() = selectedProviderStatus.provider
}

data class HomeRiskUmbrellaLaunchResult(
    val opened: Boolean,
    val mode: String,
    val usedFallback: Boolean,
    val usedSetup: Boolean
)

object HomeRiskUmbrellaRegistry {

    fun listProviders(config: IntegrationMeshConfig): List<HomeRiskUmbrellaProvider> {
        val smartHomeProviders = config.connectors.smartHome.providers.map { provider ->
            HomeRiskUmbrellaProvider(
                id = provider.id,
                label = provider.label,
                category = provider.category,
                connectorId = provider.connectorId,
                packageNames = provider.packageNames,
                deepLinkUri = provider.deepLinkUri,
                fallbackUri = provider.fallbackUri,
                setupUri = provider.setupUri,
                deviceTemplates = provider.deviceTemplates.map { template ->
                    HomeRiskUmbrellaDeviceTemplate(
                        id = template.id,
                        label = template.label,
                        deviceType = template.deviceType
                    )
                },
                authMode = provider.authMode,
                inventoryMode = provider.inventoryMode,
                apiBaseUrl = provider.apiBaseUrl,
                requiresInstanceUrl = provider.requiresInstanceUrl,
                supportNotice = provider.supportNotice
            )
        }
        val smartFobProviders = (config.connectors.digitalKeys.walletSetupGuidance +
            config.connectors.digitalKeys.manufacturerSetupGuidance)
            .map { provider ->
                HomeRiskUmbrellaProvider(
                    id = provider.id,
                    label = provider.label,
                    category = "smart_fob",
                    connectorId = provider.id,
                    packageNames = provider.packageNames,
                    deepLinkUri = "",
                    fallbackUri = provider.fallbackUri,
                    setupUri = provider.setupUri,
                    deviceTemplates = listOf(
                        HomeRiskUmbrellaDeviceTemplate(
                            id = "${provider.id}_key",
                            label = if (provider.label.contains("wallet", ignoreCase = true)) {
                                "${provider.label} digital key"
                            } else {
                                "${provider.label} smart fob"
                            },
                            deviceType = "smart_fob"
                        )
                    ),
                    authMode = "local_only",
                    inventoryMode = "local_only",
                    apiBaseUrl = "",
                    requiresInstanceUrl = false,
                    supportNotice = "Digital-key and smart-fob providers do not expose a common public inventory API in this build, so Home Risk keeps them in local advisory mode."
                )
            }
        return (smartHomeProviders + smartFobProviders)
            .distinctBy { it.id }
    }

    fun resolveProvider(config: IntegrationMeshConfig, providerId: String): HomeRiskUmbrellaProvider? {
        val normalizedId = providerId.trim().lowercase(Locale.US)
        if (normalizedId.isBlank()) {
            return null
        }
        return listProviders(config).firstOrNull { it.id.equals(normalizedId, ignoreCase = true) }
    }

    fun inspectProvider(context: Context, provider: HomeRiskUmbrellaProvider): HomeRiskUmbrellaProviderCapability {
        val launchPackage = provider.packageNames.firstOrNull { packageName ->
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        }.orEmpty()
        val installedPackage = provider.packageNames.firstOrNull { packageName ->
            isPackageInstalled(context, packageName)
        }.orEmpty()
        return HomeRiskUmbrellaProviderCapability(
            provider = provider,
            appInstalled = installedPackage.isNotBlank(),
            appLaunchReady = launchPackage.isNotBlank()
        )
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        val packageManager = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }
}

object HomeRiskOnboardingPlanner {

    fun plan(
        providerCapabilities: List<HomeRiskUmbrellaProviderCapability>,
        selectedProviderId: String,
        providerStates: List<HomeRiskUmbrellaProviderState>,
        protectedDevices: List<HomeRiskUmbrellaProtectedDevice>
    ): HomeRiskOnboardingPlan? {
        if (providerCapabilities.isEmpty()) {
            return null
        }

        val providerStateById = providerStates.associateBy { it.providerId.lowercase(Locale.US) }
        val statusList = providerCapabilities.map { capability ->
            val providerId = capability.provider.id.lowercase(Locale.US)
            val providerDevices = protectedDevices.filter { it.providerId.equals(providerId, ignoreCase = true) }
            val providerState = providerStateById[providerId]
            HomeRiskOnboardingProviderStatus(
                provider = capability.provider,
                selected = capability.provider.id.equals(selectedProviderId, ignoreCase = true),
                appInstalled = capability.appInstalled,
                appLaunchReady = capability.appLaunchReady,
                authorized = (providerState?.authorizedAtEpochMs ?: 0L) > 0L,
                importedDeviceCount = providerDevices.size,
                protectedDeviceCount = providerDevices.count { it.protectionEnabled },
                lastOpenedAtEpochMs = providerState?.lastOpenedAtEpochMs ?: 0L,
                lastImportedAtEpochMs = providerState?.lastImportedAtEpochMs ?: 0L,
                lastScanAtEpochMs = providerState?.lastScanAtEpochMs ?: 0L
            )
        }
        val selectedStatus = statusList.firstOrNull { it.selected } ?: statusList.maxByOrNull { score(it) } ?: return null
        val importedDevices = protectedDevices.filter {
            it.providerId.equals(selectedStatus.provider.id, ignoreCase = true)
        }
        val protectedSelections = importedDevices.filter { it.protectionEnabled }
        val stage = when {
            !selectedStatus.appInstalled && selectedStatus.provider.packageNames.isNotEmpty() ->
                HomeRiskOnboardingStage.INSTALL_PROVIDER
            !selectedStatus.authorized ->
                HomeRiskOnboardingStage.AUTHORIZE_PROVIDER
            importedDevices.isEmpty() ->
                HomeRiskOnboardingStage.IMPORT_DEVICES
            protectedSelections.isEmpty() ->
                HomeRiskOnboardingStage.SELECT_PROTECTION
            else ->
                HomeRiskOnboardingStage.READY_TO_SCAN
        }

        return HomeRiskOnboardingPlan(
            stage = stage,
            selectedProviderStatus = selectedStatus.copy(selected = true),
            providerStatuses = statusList.map { status ->
                if (status.provider.id.equals(selectedStatus.provider.id, ignoreCase = true)) {
                    status.copy(selected = true)
                } else {
                    status.copy(selected = false)
                }
            },
            importedDevices = importedDevices,
            protectedDevices = protectedSelections
        )
    }

    private fun score(status: HomeRiskOnboardingProviderStatus): Int {
        var score = 0
        if (status.protectedDeviceCount > 0) {
            score += 1000
        }
        if (status.importedDeviceCount > 0) {
            score += 200
        }
        if (status.authorized) {
            score += 50
        }
        if (status.appInstalled) {
            score += 20
        }
        if (status.appLaunchReady) {
            score += 10
        }
        if (status.provider.category.equals("smart_home", ignoreCase = true)) {
            score += 5
        }
        return score
    }
}

object HomeRiskUmbrellaStore {

    @Synchronized
    fun readState(context: Context): HomeRiskUmbrellaState {
        val file = stateFile(context)
        if (!file.exists()) {
            return HomeRiskUmbrellaState.empty()
        }
        val raw = runCatching { file.readText() }.getOrNull().orEmpty().trim()
        if (raw.isBlank()) {
            return HomeRiskUmbrellaState.empty()
        }
        return runCatching {
            HomeRiskUmbrellaState.fromJson(JSONObject(raw))
        }.getOrDefault(HomeRiskUmbrellaState.empty())
    }

    @Synchronized
    fun selectedProviderId(context: Context, ownerRole: String): String {
        val normalizedOwner = normalizeOwner(ownerRole)
        return readState(context).selections.firstOrNull {
            normalizeOwner(it.ownerRole) == normalizedOwner
        }?.providerId.orEmpty()
    }

    @Synchronized
    fun setSelectedProvider(context: Context, ownerRole: String, providerId: String) {
        val normalizedOwner = normalizeOwner(ownerRole)
        val normalizedProviderId = normalizeProviderId(providerId)
        val state = readState(context)
        val now = System.currentTimeMillis()
        val nextSelections = state.selections
            .filterNot { normalizeOwner(it.ownerRole) == normalizedOwner } +
            HomeRiskUmbrellaSelection(
                ownerRole = normalizedOwner,
                providerId = normalizedProviderId
            )
        writeState(
            context = context,
            state = state.copy(
                selections = nextSelections,
                updatedAtEpochMs = now
            )
        )
    }

    @Synchronized
    fun readProviderStates(context: Context, ownerRole: String): List<HomeRiskUmbrellaProviderState> {
        val normalizedOwner = normalizeOwner(ownerRole)
        return readState(context).providerStates.filter {
            normalizeOwner(it.ownerRole) == normalizedOwner
        }
    }

    @Synchronized
    fun readProtectedDevices(
        context: Context,
        ownerRole: String,
        providerId: String = ""
    ): List<HomeRiskUmbrellaProtectedDevice> {
        val normalizedOwner = normalizeOwner(ownerRole)
        val normalizedProviderId = normalizeProviderId(providerId)
        return readState(context).protectedDevices.filter { device ->
            normalizeOwner(device.ownerRole) == normalizedOwner &&
                (normalizedProviderId.isBlank() || normalizeProviderId(device.providerId) == normalizedProviderId)
        }
    }

    @Synchronized
    fun markProviderOpened(context: Context, ownerRole: String, provider: HomeRiskUmbrellaProvider) {
        updateProviderState(context, ownerRole, provider) { current, now ->
            current.copy(lastOpenedAtEpochMs = now)
        }
    }

    @Synchronized
    fun markProviderAuthorized(
        context: Context,
        ownerRole: String,
        provider: HomeRiskUmbrellaProvider,
        authorizationMethod: String
    ) {
        updateProviderState(context, ownerRole, provider) { current, now ->
            current.copy(
                authorizedAtEpochMs = now,
                authorizationMethod = authorizationMethod.trim().ifBlank { "local_confirmed" }
            )
        }
    }

    @Synchronized
    fun clearProviderAuthorization(
        context: Context,
        ownerRole: String,
        provider: HomeRiskUmbrellaProvider
    ) {
        updateProviderState(context, ownerRole, provider) { current, _ ->
            current.copy(
                authorizedAtEpochMs = 0L,
                authorizationMethod = ""
            )
        }
    }

    @Synchronized
    fun replaceImportedTemplateDevices(
        context: Context,
        ownerRole: String,
        provider: HomeRiskUmbrellaProvider,
        templates: List<HomeRiskUmbrellaDeviceTemplate>
    ): List<HomeRiskUmbrellaProtectedDevice> {
        val discoveredDevices = templates.map { template ->
            HomeRiskLiveInventoryDevice(
                deviceId = buildDeviceId(provider.id, template.id),
                label = template.label,
                deviceType = template.deviceType,
                source = "local_catalog"
            )
        }
        return replaceImportedDevices(context, ownerRole, provider, discoveredDevices)
    }

    @Synchronized
    fun replaceImportedDevices(
        context: Context,
        ownerRole: String,
        provider: HomeRiskUmbrellaProvider,
        devices: List<HomeRiskLiveInventoryDevice>
    ): List<HomeRiskUmbrellaProtectedDevice> {
        val normalizedOwner = normalizeOwner(ownerRole)
        val normalizedProviderId = normalizeProviderId(provider.id)
        val state = readState(context)
        val now = System.currentTimeMillis()
        val existingById = state.protectedDevices
            .filter {
                normalizeOwner(it.ownerRole) == normalizedOwner &&
                    normalizeProviderId(it.providerId) == normalizedProviderId
            }
            .associateBy { it.deviceId }
        val nextDevices = devices.map { device ->
            val deviceId = device.deviceId.trim().ifBlank { buildDeviceId(provider.id, device.label) }
            val existing = existingById[deviceId]
            if (existing != null) {
                existing.copy(
                    label = device.label,
                    deviceType = device.deviceType,
                    source = device.source
                )
            } else {
                HomeRiskUmbrellaProtectedDevice(
                    ownerRole = normalizedOwner,
                    deviceId = deviceId,
                    providerId = normalizedProviderId,
                    providerCategory = provider.category,
                    label = device.label,
                    deviceType = device.deviceType,
                    protectionEnabled = false,
                    importedAtEpochMs = now,
                    lastScannedAtEpochMs = 0L,
                    source = device.source
                )
            }
        }
        val remaining = state.protectedDevices.filterNot {
            normalizeOwner(it.ownerRole) == normalizedOwner &&
                normalizeProviderId(it.providerId) == normalizedProviderId
        }
        writeState(
            context = context,
            state = state.copy(
                protectedDevices = remaining + nextDevices,
                updatedAtEpochMs = now
            )
        )
        updateProviderState(context, ownerRole, provider) { current, _ ->
            current.copy(lastImportedAtEpochMs = now)
        }
        return nextDevices
    }

    @Synchronized
    fun updateProtectionSelection(
        context: Context,
        ownerRole: String,
        provider: HomeRiskUmbrellaProvider,
        protectedDeviceIds: Set<String>
    ): List<HomeRiskUmbrellaProtectedDevice> {
        val normalizedOwner = normalizeOwner(ownerRole)
        val normalizedProviderId = normalizeProviderId(provider.id)
        val selectedIds = protectedDeviceIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val state = readState(context)
        val nextDevices = state.protectedDevices.map { device ->
            if (normalizeOwner(device.ownerRole) == normalizedOwner &&
                normalizeProviderId(device.providerId) == normalizedProviderId
            ) {
                device.copy(protectionEnabled = selectedIds.contains(device.deviceId))
            } else {
                device
            }
        }
        writeState(
            context = context,
            state = state.copy(
                protectedDevices = nextDevices,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        return nextDevices.filter {
            normalizeOwner(it.ownerRole) == normalizedOwner &&
                normalizeProviderId(it.providerId) == normalizedProviderId
        }
    }

    @Synchronized
    fun markScanRequested(context: Context, ownerRole: String, provider: HomeRiskUmbrellaProvider) {
        val normalizedOwner = normalizeOwner(ownerRole)
        val normalizedProviderId = normalizeProviderId(provider.id)
        val now = System.currentTimeMillis()
        val state = readState(context)
        val nextDevices = state.protectedDevices.map { device ->
            if (normalizeOwner(device.ownerRole) == normalizedOwner &&
                normalizeProviderId(device.providerId) == normalizedProviderId &&
                device.protectionEnabled
            ) {
                device.copy(lastScannedAtEpochMs = now)
            } else {
                device
            }
        }
        writeState(
            context = context,
            state = state.copy(
                protectedDevices = nextDevices,
                updatedAtEpochMs = now
            )
        )
        updateProviderState(context, ownerRole, provider) { current, _ ->
            current.copy(lastScanAtEpochMs = now)
        }
    }

    private fun updateProviderState(
        context: Context,
        ownerRole: String,
        provider: HomeRiskUmbrellaProvider,
        transform: (HomeRiskUmbrellaProviderState, Long) -> HomeRiskUmbrellaProviderState
    ) {
        val normalizedOwner = normalizeOwner(ownerRole)
        val normalizedProviderId = normalizeProviderId(provider.id)
        val state = readState(context)
        val now = System.currentTimeMillis()
        val current = state.providerStates.firstOrNull {
            normalizeOwner(it.ownerRole) == normalizedOwner &&
                normalizeProviderId(it.providerId) == normalizedProviderId
        } ?: HomeRiskUmbrellaProviderState(
            ownerRole = normalizedOwner,
            providerId = normalizedProviderId,
            category = provider.category,
            authorizedAtEpochMs = 0L,
            authorizationMethod = "",
            lastOpenedAtEpochMs = 0L,
            lastImportedAtEpochMs = 0L,
            lastScanAtEpochMs = 0L
        )
        val nextProviderState = transform(current, now)
        val remaining = state.providerStates.filterNot {
            normalizeOwner(it.ownerRole) == normalizedOwner &&
                normalizeProviderId(it.providerId) == normalizedProviderId
        }
        writeState(
            context = context,
            state = state.copy(
                providerStates = remaining + nextProviderState,
                updatedAtEpochMs = now
            )
        )
    }

    private fun writeState(context: Context, state: HomeRiskUmbrellaState) {
        stateFile(context).writeText(state.toJson().toString())
    }

    private fun normalizeOwner(ownerRole: String): String {
        return PrimaryIdentityStore.normalizeFamilyRole(ownerRole)
    }

    private fun normalizeProviderId(providerId: String): String {
        return providerId.trim().lowercase(Locale.US)
    }

    private fun buildDeviceId(providerId: String, templateId: String): String {
        return "${normalizeProviderId(providerId)}:${templateId.trim().lowercase(Locale.US)}"
    }

    private fun stateFile(context: Context): File {
        return File(context.filesDir, HOME_RISK_UMBRELLA_STATE_FILE)
    }
}

object HomeRiskUmbrellaLauncher {

    fun openProvider(context: Context, provider: HomeRiskUmbrellaProvider): HomeRiskUmbrellaLaunchResult {
        val launchIntent = provider.packageNames.firstNotNullOfOrNull { packageName ->
            context.packageManager.getLaunchIntentForPackage(packageName)
        }
        if (launchIntent != null && startActivity(context, launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            return HomeRiskUmbrellaLaunchResult(
                opened = true,
                mode = "app",
                usedFallback = false,
                usedSetup = false
            )
        }
        if (startUri(context, provider.deepLinkUri)) {
            return HomeRiskUmbrellaLaunchResult(
                opened = true,
                mode = "deep_link",
                usedFallback = false,
                usedSetup = false
            )
        }
        if (startUri(context, provider.fallbackUri)) {
            return HomeRiskUmbrellaLaunchResult(
                opened = true,
                mode = "fallback",
                usedFallback = true,
                usedSetup = false
            )
        }
        if (startUri(context, provider.setupUri)) {
            return HomeRiskUmbrellaLaunchResult(
                opened = true,
                mode = "setup",
                usedFallback = false,
                usedSetup = true
            )
        }
        return HomeRiskUmbrellaLaunchResult(
            opened = false,
            mode = "failed",
            usedFallback = false,
            usedSetup = false
        )
    }

    fun openSetup(context: Context, provider: HomeRiskUmbrellaProvider): HomeRiskUmbrellaLaunchResult {
        if (startUri(context, provider.setupUri.ifBlank { provider.fallbackUri })) {
            return HomeRiskUmbrellaLaunchResult(
                opened = true,
                mode = "setup",
                usedFallback = provider.setupUri.isBlank(),
                usedSetup = true
            )
        }
        return HomeRiskUmbrellaLaunchResult(
            opened = false,
            mode = "failed",
            usedFallback = false,
            usedSetup = true
        )
    }

    private fun startUri(context: Context, rawUri: String): Boolean {
        if (rawUri.isBlank()) {
            return false
        }
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return false
        return startActivity(
            context,
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun startActivity(context: Context, intent: Intent): Boolean {
        val resolvable = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
        if (!resolvable) {
            return false
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
