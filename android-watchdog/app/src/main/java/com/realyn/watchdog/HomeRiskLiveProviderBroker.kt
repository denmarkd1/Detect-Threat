package com.realyn.watchdog

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val HOME_RISK_PROVIDER_CREDENTIAL_FILE = "dt_home_risk_provider_credentials.enc"
private const val HOME_RISK_PROVIDER_CREDENTIAL_ALIAS = "dt_home_risk_provider_credentials"
private const val HOME_RISK_PROVIDER_CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val HOME_RISK_PROVIDER_TAG_LENGTH_BITS = 128
private const val HOME_RISK_PROVIDER_KEYSTORE = "AndroidKeyStore"
private const val SMARTTHINGS_DEFAULT_API_BASE = "https://api.smartthings.com/v1"

data class HomeRiskProviderCredential(
    val ownerRole: String,
    val providerId: String,
    val authMode: String,
    val apiBaseUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val linkedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val accountLabel: String,
    val lastValidatedAtEpochMs: Long,
    val lastSyncAtEpochMs: Long,
    val lastError: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("owner_role", ownerRole)
            .put("provider_id", providerId)
            .put("auth_mode", authMode)
            .put("api_base_url", apiBaseUrl)
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken)
            .put("token_type", tokenType)
            .put("linked_at_epoch_ms", linkedAtEpochMs)
            .put("expires_at_epoch_ms", expiresAtEpochMs)
            .put("account_label", accountLabel)
            .put("last_validated_at_epoch_ms", lastValidatedAtEpochMs)
            .put("last_sync_at_epoch_ms", lastSyncAtEpochMs)
            .put("last_error", lastError)
    }

    companion object {
        fun fromJson(item: JSONObject): HomeRiskProviderCredential {
            return HomeRiskProviderCredential(
                ownerRole = item.optString("owner_role").trim(),
                providerId = item.optString("provider_id").trim(),
                authMode = item.optString("auth_mode").trim(),
                apiBaseUrl = item.optString("api_base_url").trim(),
                accessToken = item.optString("access_token").trim(),
                refreshToken = item.optString("refresh_token").trim(),
                tokenType = item.optString("token_type", "Bearer").trim(),
                linkedAtEpochMs = item.optLong("linked_at_epoch_ms", 0L),
                expiresAtEpochMs = item.optLong("expires_at_epoch_ms", 0L),
                accountLabel = item.optString("account_label").trim(),
                lastValidatedAtEpochMs = item.optLong("last_validated_at_epoch_ms", 0L),
                lastSyncAtEpochMs = item.optLong("last_sync_at_epoch_ms", 0L),
                lastError = item.optString("last_error").trim()
            )
        }
    }
}

data class HomeRiskLiveInventoryDevice(
    val deviceId: String,
    val label: String,
    val deviceType: String,
    val source: String
)

data class HomeRiskLiveConnectionResult(
    val authorized: Boolean,
    val devices: List<HomeRiskLiveInventoryDevice>,
    val accountLabel: String,
    val message: String
)

private class HomeRiskProviderHttpException(
    val statusCode: Int,
    override val message: String
) : IllegalStateException(message)

object HomeRiskProviderCredentialStore {

    @Synchronized
    fun readCredential(
        context: Context,
        ownerRole: String,
        providerId: String
    ): HomeRiskProviderCredential? {
        val normalizedOwner = normalizeOwner(ownerRole)
        val normalizedProviderId = normalizeProviderId(providerId)
        return readAll(context).firstOrNull {
            normalizeOwner(it.ownerRole) == normalizedOwner &&
                normalizeProviderId(it.providerId) == normalizedProviderId
        }
    }

    @Synchronized
    fun saveCredential(context: Context, credential: HomeRiskProviderCredential) {
        val normalizedOwner = normalizeOwner(credential.ownerRole)
        val normalizedProviderId = normalizeProviderId(credential.providerId)
        val records = readAll(context)
            .filterNot {
                normalizeOwner(it.ownerRole) == normalizedOwner &&
                    normalizeProviderId(it.providerId) == normalizedProviderId
            } + credential.copy(
            ownerRole = normalizedOwner,
            providerId = normalizedProviderId
        )
        writeAll(context, records)
    }

    @Synchronized
    fun clearCredential(context: Context, ownerRole: String, providerId: String) {
        val normalizedOwner = normalizeOwner(ownerRole)
        val normalizedProviderId = normalizeProviderId(providerId)
        val records = readAll(context).filterNot {
            normalizeOwner(it.ownerRole) == normalizedOwner &&
                normalizeProviderId(it.providerId) == normalizedProviderId
        }
        writeAll(context, records)
    }

    @Synchronized
    fun updateLastError(
        context: Context,
        ownerRole: String,
        providerId: String,
        message: String
    ) {
        val existing = readCredential(context, ownerRole, providerId) ?: return
        saveCredential(
            context,
            existing.copy(
                lastError = message.trim(),
                lastValidatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    @Synchronized
    fun updateLastSync(
        context: Context,
        ownerRole: String,
        providerId: String,
        syncAtEpochMs: Long,
        accountLabel: String = ""
    ) {
        val existing = readCredential(context, ownerRole, providerId) ?: return
        saveCredential(
            context,
            existing.copy(
                lastSyncAtEpochMs = syncAtEpochMs,
                lastValidatedAtEpochMs = maxOf(existing.lastValidatedAtEpochMs, syncAtEpochMs),
                accountLabel = accountLabel.ifBlank { existing.accountLabel },
                lastError = ""
            )
        )
    }

    private fun readAll(context: Context): List<HomeRiskProviderCredential> {
        val file = credentialFile(context)
        if (!file.exists()) {
            return emptyList()
        }
        val encrypted = runCatching { file.readBytes() }.getOrNull() ?: return emptyList()
        if (encrypted.isEmpty()) {
            return emptyList()
        }
        val payload = runCatching {
            String(decrypt(encrypted), Charsets.UTF_8)
        }.getOrNull().orEmpty()
        if (payload.isBlank()) {
            return emptyList()
        }
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
        val items = root.optJSONArray("credentials") ?: return emptyList()
        val records = mutableListOf<HomeRiskProviderCredential>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            runCatching { HomeRiskProviderCredential.fromJson(item) }.getOrNull()?.let { records += it }
        }
        return records
    }

    private fun writeAll(context: Context, records: List<HomeRiskProviderCredential>) {
        val payload = JSONObject()
            .put("version", 1)
            .put("updated_at_epoch_ms", System.currentTimeMillis())
            .put(
                "credentials",
                JSONArray().apply {
                    records.forEach { put(it.toJson()) }
                }
            )
        val encrypted = encrypt(payload.toString(2).toByteArray(Charsets.UTF_8))
        credentialFile(context).writeBytes(encrypted)
    }

    private fun encrypt(plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(HOME_RISK_PROVIDER_CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainBytes)
        val buffer = ByteBuffer.allocate(4 + iv.size + cipherBytes.size)
        buffer.putInt(iv.size)
        buffer.put(iv)
        buffer.put(cipherBytes)
        return buffer.array()
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(payload)
        val ivSize = buffer.int
        if (ivSize <= 0 || ivSize > 32) {
            throw IllegalStateException("invalid iv size")
        }
        val iv = ByteArray(ivSize)
        buffer.get(iv)
        val cipherBytes = ByteArray(buffer.remaining())
        buffer.get(cipherBytes)
        val cipher = Cipher.getInstance(HOME_RISK_PROVIDER_CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(HOME_RISK_PROVIDER_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(HOME_RISK_PROVIDER_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(HOME_RISK_PROVIDER_CREDENTIAL_ALIAS, null)
        if (existing is SecretKey) {
            return existing
        }
        val keyGenerator = KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            HOME_RISK_PROVIDER_KEYSTORE
        )
        keyGenerator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                HOME_RISK_PROVIDER_CREDENTIAL_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun credentialFile(context: Context): File {
        return File(context.filesDir, HOME_RISK_PROVIDER_CREDENTIAL_FILE)
    }

    private fun normalizeOwner(ownerRole: String): String {
        return PrimaryIdentityStore.normalizeFamilyRole(ownerRole)
    }

    private fun normalizeProviderId(providerId: String): String {
        return providerId.trim().lowercase(Locale.US)
    }
}

object HomeRiskLiveProviderBroker {

    fun supportsLiveAuth(provider: HomeRiskUmbrellaProvider): Boolean {
        return provider.authMode.equals("token", ignoreCase = true)
    }

    fun supportsLiveInventory(provider: HomeRiskUmbrellaProvider): Boolean {
        return when (provider.inventoryMode.lowercase(Locale.US)) {
            "smartthings_rest",
            "home_assistant_rest" -> true
            else -> false
        }
    }

    fun requiresInstanceUrl(provider: HomeRiskUmbrellaProvider): Boolean {
        return provider.requiresInstanceUrl
    }

    fun supportNotice(provider: HomeRiskUmbrellaProvider): String {
        return provider.supportNotice.trim()
    }

    fun readCredential(
        context: Context,
        ownerRole: String,
        provider: HomeRiskUmbrellaProvider
    ): HomeRiskProviderCredential? {
        return HomeRiskProviderCredentialStore.readCredential(context, ownerRole, provider.id)
    }

    fun isCredentialUsable(credential: HomeRiskProviderCredential?, now: Long = System.currentTimeMillis()): Boolean {
        if (credential == null) {
            return false
        }
        if (credential.accessToken.isBlank()) {
            return false
        }
        if (credential.expiresAtEpochMs > 0L && credential.expiresAtEpochMs < now) {
            return false
        }
        if (credential.lastError.equals("invalid_auth", ignoreCase = true)) {
            return false
        }
        return true
    }

    suspend fun connectWithToken(
        context: Context,
        ownerRole: String,
        provider: HomeRiskUmbrellaProvider,
        rawBaseUrl: String,
        rawToken: String
    ): HomeRiskLiveConnectionResult {
        val normalizedOwner = PrimaryIdentityStore.normalizeFamilyRole(ownerRole)
        val token = rawToken.trim()
        if (token.isBlank()) {
            throw IllegalStateException("Provider token is required.")
        }
        return when (provider.inventoryMode.lowercase(Locale.US)) {
            "smartthings_rest" -> {
                val baseUrl = provider.apiBaseUrl.ifBlank { SMARTTHINGS_DEFAULT_API_BASE }.removeSuffix("/")
                val devices = fetchSmartThingsInventory(baseUrl, token)
                val now = System.currentTimeMillis()
                HomeRiskProviderCredentialStore.saveCredential(
                    context,
                    HomeRiskProviderCredential(
                        ownerRole = normalizedOwner,
                        providerId = provider.id,
                        authMode = provider.authMode,
                        apiBaseUrl = baseUrl,
                        accessToken = token,
                        refreshToken = "",
                        tokenType = "Bearer",
                        linkedAtEpochMs = now,
                        expiresAtEpochMs = now + SmartThingsConnector.CONSENT_TTL_MS,
                        accountLabel = provider.label,
                        lastValidatedAtEpochMs = now,
                        lastSyncAtEpochMs = now,
                        lastError = ""
                    )
                )
                HomeRiskLiveConnectionResult(
                    authorized = true,
                    devices = devices,
                    accountLabel = provider.label,
                    message = "SmartThings live inventory connected."
                )
            }

            "home_assistant_rest" -> {
                val baseUrl = normalizeInstanceBaseUrl(rawBaseUrl)
                val config = getJsonObject("$baseUrl/api/config", token)
                val devices = fetchHomeAssistantInventory(baseUrl, token)
                val now = System.currentTimeMillis()
                val accountLabel = config.optString("location_name").trim().ifBlank {
                    Uri.parse(baseUrl).host.orEmpty().ifBlank { provider.label }
                }
                HomeRiskProviderCredentialStore.saveCredential(
                    context,
                    HomeRiskProviderCredential(
                        ownerRole = normalizedOwner,
                        providerId = provider.id,
                        authMode = provider.authMode,
                        apiBaseUrl = baseUrl,
                        accessToken = token,
                        refreshToken = "",
                        tokenType = "Bearer",
                        linkedAtEpochMs = now,
                        expiresAtEpochMs = now + (10L * 365 * 24 * 60 * 60 * 1000),
                        accountLabel = accountLabel,
                        lastValidatedAtEpochMs = now,
                        lastSyncAtEpochMs = now,
                        lastError = ""
                    )
                )
                HomeRiskLiveConnectionResult(
                    authorized = true,
                    devices = devices,
                    accountLabel = accountLabel,
                    message = "Home Assistant live inventory connected."
                )
            }

            else -> throw IllegalStateException(
                provider.supportNotice.ifBlank { "${provider.label} does not expose live inventory in this build." }
            )
        }
    }

    suspend fun fetchInventory(
        context: Context,
        ownerRole: String,
        provider: HomeRiskUmbrellaProvider
    ): List<HomeRiskLiveInventoryDevice> {
        val credential = readCredential(context, ownerRole, provider)
            ?: throw IllegalStateException("Provider connection is not configured.")
        if (!isCredentialUsable(credential)) {
            throw IllegalStateException("Provider connection needs to be refreshed.")
        }
        return try {
            val devices = when (provider.inventoryMode.lowercase(Locale.US)) {
                "smartthings_rest" -> fetchSmartThingsInventory(
                    credential.apiBaseUrl.ifBlank { SMARTTHINGS_DEFAULT_API_BASE },
                    credential.accessToken
                )
                "home_assistant_rest" -> fetchHomeAssistantInventory(
                    credential.apiBaseUrl,
                    credential.accessToken
                )
                else -> throw IllegalStateException(
                    provider.supportNotice.ifBlank { "${provider.label} does not expose live inventory in this build." }
                )
            }
            HomeRiskProviderCredentialStore.updateLastSync(
                context = context,
                ownerRole = ownerRole,
                providerId = provider.id,
                syncAtEpochMs = System.currentTimeMillis(),
                accountLabel = credential.accountLabel
            )
            devices
        } catch (error: HomeRiskProviderHttpException) {
            if (error.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                error.statusCode == HttpURLConnection.HTTP_FORBIDDEN
            ) {
                HomeRiskProviderCredentialStore.updateLastError(context, ownerRole, provider.id, "invalid_auth")
                HomeRiskUmbrellaStore.clearProviderAuthorization(context, ownerRole, provider)
                throw IllegalStateException("Provider authorization expired or was rejected. Reconnect the provider.")
            }
            HomeRiskProviderCredentialStore.updateLastError(
                context,
                ownerRole,
                provider.id,
                error.message.ifBlank { "inventory_sync_failed" }
            )
            throw error
        } catch (error: Exception) {
            HomeRiskProviderCredentialStore.updateLastError(
                context,
                ownerRole,
                provider.id,
                error.message.orEmpty().ifBlank { "inventory_sync_failed" }
            )
            throw error
        }
    }

    internal fun parseSmartThingsDevices(payload: JSONObject): List<HomeRiskLiveInventoryDevice> {
        val items = payload.optJSONArray("items") ?: JSONArray()
        val devices = mutableListOf<HomeRiskLiveInventoryDevice>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val deviceId = item.optString("deviceId").trim()
            val label = item.optString("label").trim()
                .ifBlank { item.optString("name").trim() }
                .ifBlank { deviceId }
            if (deviceId.isBlank() || label.isBlank()) {
                continue
            }
            val deviceType = mapSmartThingsDeviceType(
                rawType = item.optString("deviceTypeName").trim().ifBlank {
                    item.optString("type").trim()
                },
                label = label
            )
            devices += HomeRiskLiveInventoryDevice(
                deviceId = deviceId,
                label = label,
                deviceType = deviceType,
                source = "smartthings_rest"
            )
        }
        return devices
    }

    internal fun parseHomeAssistantStates(payload: JSONArray): List<HomeRiskLiveInventoryDevice> {
        val devices = mutableListOf<HomeRiskLiveInventoryDevice>()
        for (index in 0 until payload.length()) {
            val item = payload.optJSONObject(index) ?: continue
            val entityId = item.optString("entity_id").trim()
            val domain = entityId.substringBefore('.', "").trim().lowercase(Locale.US)
            if (entityId.isBlank() || domain !in HOME_ASSISTANT_RELEVANT_DOMAINS) {
                continue
            }
            val attributes = item.optJSONObject("attributes") ?: JSONObject()
            val label = attributes.optString("friendly_name").trim().ifBlank {
                entityId.substringAfter('.', entityId).replace('_', ' ')
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }
            devices += HomeRiskLiveInventoryDevice(
                deviceId = entityId,
                label = label,
                deviceType = mapHomeAssistantDomain(domain, label),
                source = "home_assistant_rest"
            )
        }
        return devices.distinctBy { it.deviceId }
    }

    private fun fetchSmartThingsInventory(baseUrl: String, token: String): List<HomeRiskLiveInventoryDevice> {
        val payload = getJsonObject("${baseUrl.removeSuffix("/")}/devices", token)
        return parseSmartThingsDevices(payload)
    }

    private fun fetchHomeAssistantInventory(baseUrl: String, token: String): List<HomeRiskLiveInventoryDevice> {
        val payload = getJsonArray("${baseUrl.removeSuffix("/")}/api/states", token)
        return parseHomeAssistantStates(payload)
    }

    private fun getJsonObject(url: String, token: String): JSONObject {
        val response = request(url, token)
        val body = response.body.trim()
        if (!body.startsWith("{")) {
            throw IllegalStateException("Expected JSON object response from provider.")
        }
        return JSONObject(body)
    }

    private fun getJsonArray(url: String, token: String): JSONArray {
        val response = request(url, token)
        val body = response.body.trim()
        if (!body.startsWith("[")) {
            throw IllegalStateException("Expected JSON array response from provider.")
        }
        return JSONArray(body)
    }

    private data class HttpResponse(
        val statusCode: Int,
        val body: String
    )

    private fun request(url: String, token: String): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            requestMethod = "GET"
            doInput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }
        try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                throw HomeRiskProviderHttpException(
                    statusCode = statusCode,
                    message = parseErrorMessage(body, statusCode)
                )
            }
            return HttpResponse(statusCode = statusCode, body = body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseErrorMessage(body: String, statusCode: Int): String {
        val payload = runCatching { JSONObject(body) }.getOrNull()
        val description = payload?.optString("error_description").orEmpty().trim()
        val error = payload?.optString("error").orEmpty().trim()
        val message = payload?.optString("message").orEmpty().trim()
        return description.ifBlank { message }.ifBlank { error }.ifBlank {
            "Provider request failed with HTTP $statusCode."
        }
    }

    private fun normalizeInstanceBaseUrl(rawValue: String): String {
        val trimmed = rawValue.trim().removeSuffix("/")
        if (trimmed.isBlank()) {
            throw IllegalStateException("Instance URL is required.")
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            ?: throw IllegalStateException("Instance URL is invalid.")
        val scheme = uri.scheme.orEmpty().lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            throw IllegalStateException("Instance URL must start with http:// or https://")
        }
        if (uri.host.isNullOrBlank()) {
            throw IllegalStateException("Instance URL must include a host name.")
        }
        return trimmed
    }

    private fun mapSmartThingsDeviceType(rawType: String, label: String): String {
        val normalized = "$rawType $label".lowercase(Locale.US)
        return when {
            normalized.contains("tv") -> "tv"
            normalized.contains("fridge") || normalized.contains("refrigerator") ||
                normalized.contains("washer") || normalized.contains("dryer") ||
                normalized.contains("oven") || normalized.contains("dishwasher") -> "appliance"
            normalized.contains("thermostat") -> "thermostat"
            normalized.contains("camera") || normalized.contains("doorbell") -> "camera"
            normalized.contains("lock") -> "lock"
            normalized.contains("sensor") -> "sensor"
            normalized.contains("hub") || normalized.contains("station") -> "hub"
            normalized.contains("tag") || normalized.contains("tracker") -> "tracker"
            normalized.contains("speaker") || normalized.contains("display") -> "display"
            normalized.contains("vacuum") -> "vacuum"
            else -> "device"
        }
    }

    private fun mapHomeAssistantDomain(domain: String, label: String): String {
        val normalizedLabel = label.lowercase(Locale.US)
        return when (domain) {
            "media_player" -> when {
                normalizedLabel.contains("tv") -> "tv"
                normalizedLabel.contains("display") -> "display"
                else -> "media"
            }
            "climate",
            "water_heater" -> "thermostat"
            "camera" -> "camera"
            "lock" -> "lock"
            "cover" -> "blind"
            "binary_sensor",
            "sensor" -> "sensor"
            "vacuum" -> "vacuum"
            "switch",
            "light",
            "fan",
            "humidifier",
            "button" -> "device"
            "alarm_control_panel" -> "security"
            "update" -> "system"
            else -> "device"
        }
    }

    private val HOME_ASSISTANT_RELEVANT_DOMAINS = setOf(
        "alarm_control_panel",
        "binary_sensor",
        "button",
        "camera",
        "climate",
        "cover",
        "fan",
        "humidifier",
        "light",
        "lock",
        "media_player",
        "sensor",
        "switch",
        "update",
        "vacuum",
        "water_heater"
    )
}
