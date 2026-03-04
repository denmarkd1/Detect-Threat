package com.realyn.watchdog

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val UMBRELLA_SETTINGS_FILE = "workspace_settings.json"
private const val UMBRELLA_STATE_FILE = "dt_device_umbrella_state.enc"
private const val UMBRELLA_DEFAULT_SUPPORT_API_BASE = "http://127.0.0.1:8787"
private const val UMBRELLA_KEY_DERIVATION_ITERATIONS = 120_000
private const val UMBRELLA_KEY_SIZE_BITS = 256
private const val UMBRELLA_GCM_TAG_BITS = 128
private const val UMBRELLA_OWNER_PROOF_SEED = "dt-umbrella-owner-v1"
private const val UMBRELLA_DEVICE_FINGERPRINT_SEED = "dt-umbrella-device-v1"

data class DeviceUmbrellaState(
    val sessionId: String,
    val umbrellaId: String,
    val memberId: String,
    val owner: String,
    val linkCode: String,
    val salt: String,
    val memberRole: String,
    val linkedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val qrPayloadUri: String,
    val lastSyncAtEpochMs: Long,
    val lastRemoteEnvelopeCount: Int,
    val lastRemoteHighCount: Int,
    val lastRemoteMediumCount: Int
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("session_id", sessionId)
            .put("umbrella_id", umbrellaId)
            .put("member_id", memberId)
            .put("owner", owner)
            .put("link_code", linkCode)
            .put("salt", salt)
            .put("member_role", memberRole)
            .put("linked_at_epoch_ms", linkedAtEpochMs)
            .put("expires_at_epoch_ms", expiresAtEpochMs)
            .put("qr_payload_uri", qrPayloadUri)
            .put("last_sync_at_epoch_ms", lastSyncAtEpochMs)
            .put("last_remote_envelope_count", lastRemoteEnvelopeCount)
            .put("last_remote_high_count", lastRemoteHighCount)
            .put("last_remote_medium_count", lastRemoteMediumCount)
    }

    companion object {
        fun fromJson(item: JSONObject): DeviceUmbrellaState {
            return DeviceUmbrellaState(
                sessionId = item.optString("session_id").trim(),
                umbrellaId = item.optString("umbrella_id").trim(),
                memberId = item.optString("member_id").trim(),
                owner = item.optString("owner", "parent").trim(),
                linkCode = item.optString("link_code").trim(),
                salt = item.optString("salt").trim(),
                memberRole = item.optString("member_role", "member").trim(),
                linkedAtEpochMs = item.optLong("linked_at_epoch_ms", 0L),
                expiresAtEpochMs = item.optLong("expires_at_epoch_ms", 0L),
                qrPayloadUri = item.optString("qr_payload_uri").trim(),
                lastSyncAtEpochMs = item.optLong("last_sync_at_epoch_ms", 0L),
                lastRemoteEnvelopeCount = item.optInt("last_remote_envelope_count", 0),
                lastRemoteHighCount = item.optInt("last_remote_high_count", 0),
                lastRemoteMediumCount = item.optInt("last_remote_medium_count", 0)
            )
        }
    }
}

data class DeviceUmbrellaCreateResult(
    val state: DeviceUmbrellaState,
    val endpointBase: String
)

data class DeviceUmbrellaSyncResult(
    val syncedAtEpochMs: Long,
    val remoteEnvelopeCount: Int,
    val remoteHighCount: Int,
    val remoteMediumCount: Int,
    val endpointBase: String
)

object DeviceUmbrellaSyncStore {

    @Synchronized
    fun readState(context: Context): DeviceUmbrellaState? {
        val file = stateFile(context)
        if (!file.exists()) {
            return null
        }
        val encrypted = runCatching { file.readBytes() }.getOrNull() ?: return null
        val raw = runCatching { MediaVaultCrypto.decrypt(encrypted) }.getOrNull() ?: return null
        val payload = runCatching { JSONObject(raw.toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        val state = runCatching { DeviceUmbrellaState.fromJson(payload) }.getOrNull() ?: return null
        if (state.sessionId.isBlank() || state.memberId.isBlank() || state.linkCode.isBlank() || state.salt.isBlank()) {
            return null
        }
        return state
    }

    @Synchronized
    fun writeState(context: Context, state: DeviceUmbrellaState) {
        val bytes = state.toJson().toString().toByteArray(Charsets.UTF_8)
        val encrypted = MediaVaultCrypto.encrypt(bytes)
        stateFile(context).writeBytes(encrypted)
    }

    @Synchronized
    fun clearState(context: Context) {
        val file = stateFile(context)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun stateFile(context: Context): File = File(context.filesDir, UMBRELLA_STATE_FILE)
}

object DeviceUmbrellaSyncClient {

    fun createSession(context: Context): DeviceUmbrellaCreateResult {
        val profile = PrimaryIdentityStore.readProfile(context)
        val owner = PrimaryIdentityStore.normalizeFamilyRole(profile.familyRole)
        val ownerProof = buildOwnerProof(profile)
        val deviceFingerprint = buildDeviceFingerprint(context)
        val alias = PrimaryIdentityStore.importDeviceNameOrDefault(context)
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        val createEndpoint = resolveEndpoint(context, "/device-umbrella/session/create")
        val created = postJson(
            endpoint = createEndpoint,
            payload = JSONObject()
                .put("owner", owner)
                .put("owner_proof", ownerProof)
                .put("device_fingerprint", deviceFingerprint)
                .put("device_alias", alias)
                .put("device_model", model)
        )

        val sessionId = created.optString("session_id").trim()
        val umbrellaId = created.optString("umbrella_id").trim()
        val memberId = created.optString("member_id").trim()
        val salt = created.optString("salt").trim()
        val expiresAt = created.optLong("expires_at_epoch_ms", 0L)
        if (sessionId.isBlank() || umbrellaId.isBlank() || memberId.isBlank() || salt.isBlank()) {
            throw IllegalStateException("Invalid umbrella session payload")
        }

        val linkCode = generateLinkCode()
        val codeHash = sha256Hex("$sessionId|${normalizeLinkCode(linkCode)}|$salt")
        val registerEndpoint = resolveEndpoint(context, "/device-umbrella/session/register-code")
        postJson(
            endpoint = registerEndpoint,
            payload = JSONObject()
                .put("session_id", sessionId)
                .put("member_id", memberId)
                .put("code_hash", codeHash)
                .put("owner_proof", ownerProof)
        )

        val qrPayload = buildQrPayloadUri(sessionId = sessionId, linkCode = linkCode, salt = salt)
        val state = DeviceUmbrellaState(
            sessionId = sessionId,
            umbrellaId = umbrellaId,
            memberId = memberId,
            owner = owner,
            linkCode = linkCode,
            salt = salt,
            memberRole = "host",
            linkedAtEpochMs = System.currentTimeMillis(),
            expiresAtEpochMs = expiresAt,
            qrPayloadUri = qrPayload,
            lastSyncAtEpochMs = 0L,
            lastRemoteEnvelopeCount = 0,
            lastRemoteHighCount = 0,
            lastRemoteMediumCount = 0
        )
        DeviceUmbrellaSyncStore.writeState(context, state)
        return DeviceUmbrellaCreateResult(
            state = state,
            endpointBase = resolveEndpointBase(context)
        )
    }

    fun joinSession(context: Context, rawInput: String): DeviceUmbrellaCreateResult {
        val linkCode = extractLinkCode(rawInput)
        if (linkCode.isBlank()) {
            throw IllegalArgumentException("Link code is empty")
        }
        val profile = PrimaryIdentityStore.readProfile(context)
        val ownerProof = buildOwnerProof(profile)
        val deviceFingerprint = buildDeviceFingerprint(context)
        val alias = PrimaryIdentityStore.importDeviceNameOrDefault(context)
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        val joinEndpoint = resolveEndpoint(context, "/device-umbrella/session/join")
        val joined = postJson(
            endpoint = joinEndpoint,
            payload = JSONObject()
                .put("link_code", normalizeLinkCode(linkCode))
                .put("owner_proof", ownerProof)
                .put("device_fingerprint", deviceFingerprint)
                .put("device_alias", alias)
                .put("device_model", model)
        )

        val requestStatus = joined.optString("request_status", "approved")
            .trim()
            .lowercase(Locale.US)
        if (requestStatus == "pending") {
            throw IllegalStateException(context.getString(R.string.device_umbrella_status_pending_host_approval))
        }
        if (requestStatus != "approved") {
            throw IllegalStateException("Join request is not approved")
        }

        val sessionId = joined.optString("session_id").trim()
        val umbrellaId = joined.optString("umbrella_id").trim()
        val memberId = joined.optString("member_id").trim()
        val salt = joined.optString("salt").trim()
        val owner = joined.optString("owner", "parent").trim()
        val expiresAt = joined.optLong("expires_at_epoch_ms", 0L)
        if (sessionId.isBlank() || umbrellaId.isBlank() || memberId.isBlank() || salt.isBlank()) {
            throw IllegalStateException("Invalid umbrella join payload")
        }

        val formattedCode = formatLinkCode(normalizeLinkCode(linkCode))
        val state = DeviceUmbrellaState(
            sessionId = sessionId,
            umbrellaId = umbrellaId,
            memberId = memberId,
            owner = owner.ifBlank { "parent" },
            linkCode = formattedCode,
            salt = salt,
            memberRole = joined.optString("member_role", "member").trim().ifBlank { "member" },
            linkedAtEpochMs = System.currentTimeMillis(),
            expiresAtEpochMs = expiresAt,
            qrPayloadUri = buildQrPayloadUri(sessionId = sessionId, linkCode = formattedCode, salt = salt),
            lastSyncAtEpochMs = 0L,
            lastRemoteEnvelopeCount = 0,
            lastRemoteHighCount = 0,
            lastRemoteMediumCount = 0
        )
        DeviceUmbrellaSyncStore.writeState(context, state)
        return DeviceUmbrellaCreateResult(
            state = state,
            endpointBase = resolveEndpointBase(context)
        )
    }

    fun syncNow(context: Context): DeviceUmbrellaSyncResult {
        val state = DeviceUmbrellaSyncStore.readState(context)
            ?: throw IllegalStateException("No linked umbrella state on this device")

        val key = deriveKey(linkCode = state.linkCode, salt = state.salt)
        val localSnapshot = buildLocalSnapshot(context, state)
        val encryptedEnvelope = encryptPayload(key = key, raw = localSnapshot.toString().toByteArray(Charsets.UTF_8))

        val pushEndpoint = resolveEndpoint(context, "/device-umbrella/sync/push")
        postJson(
            endpoint = pushEndpoint,
            payload = JSONObject()
                .put("session_id", state.sessionId)
                .put("member_id", state.memberId)
                .put("payload_version", 1)
                .put("iv_b64", encryptedEnvelope.iv)
                .put("ciphertext_b64", encryptedEnvelope.ciphertext)
        )

        val pullEndpoint = resolveEndpoint(context, "/device-umbrella/sync/pull")
        val pulled = postJson(
            endpoint = pullEndpoint,
            payload = JSONObject()
                .put("session_id", state.sessionId)
                .put("member_id", state.memberId)
        )

        val envelopes = pulled.optJSONArray("envelopes") ?: JSONArray()
        var remoteHigh = 0
        var remoteMedium = 0
        var remoteCount = 0

        for (index in 0 until envelopes.length()) {
            val envelope = envelopes.optJSONObject(index) ?: continue
            val iv = envelope.optString("iv_b64").trim()
            val ciphertext = envelope.optString("ciphertext_b64").trim()
            if (iv.isBlank() || ciphertext.isBlank()) {
                continue
            }
            val decryptedRaw = runCatching {
                decryptPayload(
                    key = key,
                    ivB64 = iv,
                    ciphertextB64 = ciphertext
                )
            }.getOrNull() ?: continue
            val payload = runCatching { JSONObject(decryptedRaw.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            remoteCount += 1
            remoteHigh += payload.optInt("guardian_alerts_high", 0).coerceAtLeast(0)
            remoteMedium += payload.optInt("guardian_alerts_medium", 0).coerceAtLeast(0)
        }

        val updatedState = state.copy(
            lastSyncAtEpochMs = System.currentTimeMillis(),
            lastRemoteEnvelopeCount = remoteCount,
            lastRemoteHighCount = remoteHigh,
            lastRemoteMediumCount = remoteMedium
        )
        DeviceUmbrellaSyncStore.writeState(context, updatedState)

        return DeviceUmbrellaSyncResult(
            syncedAtEpochMs = updatedState.lastSyncAtEpochMs,
            remoteEnvelopeCount = remoteCount,
            remoteHighCount = remoteHigh,
            remoteMediumCount = remoteMedium,
            endpointBase = resolveEndpointBase(context)
        )
    }

    fun fetchSessionStatus(context: Context): JSONObject {
        val state = DeviceUmbrellaSyncStore.readState(context)
            ?: throw IllegalStateException("No linked umbrella state on this device")
        val endpoint = resolveEndpoint(
            context,
            "/device-umbrella/session/status?session_id=${Uri.encode(state.sessionId)}&member_id=${Uri.encode(state.memberId)}"
        )
        return getJson(endpoint)
    }

    fun listPendingJoinRequests(context: Context): JSONArray {
        val state = DeviceUmbrellaSyncStore.readState(context)
            ?: throw IllegalStateException("No linked umbrella state on this device")
        if (!state.memberRole.equals("host", ignoreCase = true)) {
            throw IllegalStateException("Only host device can review join requests")
        }
        val endpoint = resolveEndpoint(
            context,
            "/device-umbrella/session/join-requests?session_id=${Uri.encode(state.sessionId)}&member_id=${Uri.encode(state.memberId)}"
        )
        val response = getJson(endpoint)
        return response.optJSONArray("requests") ?: JSONArray()
    }

    fun decideJoinRequest(context: Context, requestId: String, approve: Boolean): JSONObject {
        val state = DeviceUmbrellaSyncStore.readState(context)
            ?: throw IllegalStateException("No linked umbrella state on this device")
        if (!state.memberRole.equals("host", ignoreCase = true)) {
            throw IllegalStateException("Only host device can approve join requests")
        }
        val normalizedRequestId = requestId.trim()
        if (normalizedRequestId.isBlank()) {
            throw IllegalArgumentException("request_id is required")
        }
        val endpoint = resolveEndpoint(context, "/device-umbrella/session/join-decision")
        return postJson(
            endpoint = endpoint,
            payload = JSONObject()
                .put("session_id", state.sessionId)
                .put("member_id", state.memberId)
                .put("request_id", normalizedRequestId)
                .put("decision", if (approve) "approve" else "reject")
        )
    }

    private fun buildLocalSnapshot(context: Context, state: DeviceUmbrellaState): JSONObject {
        val wifi = WifiScanSnapshotStore.latest(context)
        val incidents = IncidentStore.summarize(context)
        val recentAlerts = GuardianAlertStore.readRecent(context, limit = 20)
        val pendingQueue = CredentialActionStore.loadQueue(context)
            .count { !it.status.equals("completed", ignoreCase = true) }
        val root = SecurityScanner.currentRootPosture(context)
        val rootReasonSummary = if (root.reasonCodes.isEmpty()) {
            "none"
        } else {
            root.reasonCodes.sorted().joinToString(separator = "|")
        }

        val highAlerts = recentAlerts.count { it.severity == Severity.HIGH }
        val mediumAlerts = recentAlerts.count { it.severity == Severity.MEDIUM }

        return JSONObject()
            .put("captured_at_epoch_ms", System.currentTimeMillis())
            .put("session_id", state.sessionId)
            .put("umbrella_id", state.umbrellaId)
            .put("member_id", state.memberId)
            .put("device_alias", PrimaryIdentityStore.importDeviceNameOrDefault(context))
            .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("android_release", Build.VERSION.RELEASE ?: "")
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("owner", state.owner)
            .put("watchdog_last_scan", SecurityScanner.readLastScanTimestamp(context))
            .put("root_risk_tier", root.riskTier.name.lowercase(Locale.US))
            .put("root_risk_summary", rootReasonSummary)
            .put("wifi_tier", wifi?.tier.orEmpty())
            .put("wifi_score", wifi?.score ?: 0)
            .put("guardian_alerts_high", highAlerts)
            .put("guardian_alerts_medium", mediumAlerts)
            .put("credential_queue_pending", pendingQueue)
            .put("incidents_open", incidents.openCount)
            .put("incidents_in_progress", incidents.inProgressCount)
            .put("incidents_resolved", incidents.resolvedCount)
    }

    private data class EncryptedEnvelope(
        val iv: String,
        val ciphertext: String
    )

    private fun encryptPayload(key: ByteArray, raw: ByteArray): EncryptedEnvelope {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secret = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(raw)
        return EncryptedEnvelope(
            iv = encodeBase64(iv),
            ciphertext = encodeBase64(encrypted)
        )
    }

    private fun decryptPayload(key: ByteArray, ivB64: String, ciphertextB64: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secret = SecretKeySpec(key, "AES")
        val iv = decodeBase64(ivB64)
        val params = GCMParameterSpec(UMBRELLA_GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secret, params)
        return cipher.doFinal(decodeBase64(ciphertextB64))
    }

    private fun deriveKey(linkCode: String, salt: String): ByteArray {
        val normalized = normalizeLinkCode(linkCode)
        if (normalized.length < 8) {
            throw IllegalStateException("Link code is too short")
        }
        val saltBytes = decodeBase64(salt)
        val spec = PBEKeySpec(
            normalized.toCharArray(),
            saltBytes,
            UMBRELLA_KEY_DERIVATION_ITERATIONS,
            UMBRELLA_KEY_SIZE_BITS
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun resolveEndpoint(context: Context, suffix: String): String {
        val base = resolveEndpointBase(context)
        val normalizedSuffix = suffix.trim().ifBlank { "/device-umbrella/session/status" }
        return if (base.endsWith("/api/support")) {
            "$base$normalizedSuffix"
        } else {
            "$base/api/support$normalizedSuffix"
        }
    }

    private fun resolveEndpointBase(context: Context): String {
        val configured = readConfiguredSupportApiBase(context)
        val fallback = context.getString(R.string.support_api_base_url)
            .trim()
            .ifBlank { UMBRELLA_DEFAULT_SUPPORT_API_BASE }

        return configured.ifBlank { fallback }
            .trim()
            .ifBlank { UMBRELLA_DEFAULT_SUPPORT_API_BASE }
            .removeSuffix("/")
    }

    private fun readConfiguredSupportApiBase(context: Context): String {
        val payload = readSettingsPayload(context) ?: return ""
        return payload
            .optJSONObject("support")
            ?.optString("api_base_url", "")
            ?.trim()
            .orEmpty()
    }

    private fun readSettingsPayload(context: Context): JSONObject? {
        val localOverride = File(context.filesDir, UMBRELLA_SETTINGS_FILE)
        val content = when {
            localOverride.exists() -> runCatching { localOverride.readText() }.getOrNull()
            else -> runCatching {
                context.assets.open(UMBRELLA_SETTINGS_FILE).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return null

        return runCatching { JSONObject(content) }.getOrNull()
    }

    private fun postJson(endpoint: String, payload: JSONObject): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            connection.outputStream.use { stream ->
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val source = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = source?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("error") }.getOrDefault("").ifBlank {
                    "HTTP $code"
                }
                throw IllegalStateException(message)
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(endpoint: String): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val source = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = source?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("error") }.getOrDefault("").ifBlank {
                    "HTTP $code"
                }
                throw IllegalStateException(message)
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun generateLinkCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val chars = CharArray(12) { alphabet[random.nextInt(alphabet.length)] }
        return String(chars).chunked(4).joinToString("-")
    }

    private fun extractLinkCode(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.startsWith("dtguardian://", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            val fromUri = uri?.getQueryParameter("link_code").orEmpty().trim()
            if (fromUri.isNotBlank()) {
                return fromUri
            }
        }
        return trimmed
    }

    private fun formatLinkCode(raw: String): String {
        val normalized = normalizeLinkCode(raw)
        return if (normalized.length >= 12) {
            normalized.take(12).chunked(4).joinToString("-")
        } else {
            normalized
        }
    }

    private fun normalizeLinkCode(raw: String): String {
        return raw.trim()
            .uppercase(Locale.US)
            .replace(" ", "")
            .replace("-", "")
            .filter { it.isLetterOrDigit() }
            .take(32)
    }

    private fun buildOwnerProof(profile: IdentityProfile): String {
        val email = profile.primaryEmail.trim().lowercase(Locale.US)
        if (email.isBlank()) {
            throw IllegalStateException("Link a primary email in Credential Center before umbrella pairing")
        }
        return sha256Hex("$UMBRELLA_OWNER_PROOF_SEED|$email")
    }

    private fun buildDeviceFingerprint(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().trim()
        val modelSeed = listOf(
            Build.MANUFACTURER.orEmpty(),
            Build.MODEL.orEmpty(),
            Build.DEVICE.orEmpty()
        ).joinToString(separator = "|") { it.trim().lowercase(Locale.US) }
        return sha256Hex("$UMBRELLA_DEVICE_FINGERPRINT_SEED|$androidId|$modelSeed")
    }

    private fun buildQrPayloadUri(sessionId: String, linkCode: String, salt: String): String {
        return Uri.Builder()
            .scheme("dtguardian")
            .authority("umbrella")
            .appendPath("link")
            .appendQueryParameter("session_id", sessionId)
            .appendQueryParameter("link_code", normalizeLinkCode(linkCode))
            .appendQueryParameter("salt", salt)
            .build()
            .toString()
    }

    private fun sha256Hex(raw: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun encodeBase64(raw: ByteArray): String {
        return Base64.encodeToString(raw, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun decodeBase64(raw: String): ByteArray {
        return Base64.decode(raw, Base64.NO_WRAP or Base64.URL_SAFE)
    }
}
