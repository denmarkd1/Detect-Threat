package com.realyn.watchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val INTEGRATION_MESH_SCHEMA_VERSION = 1
const val CONNECTOR_AUDIT_EVENT_TYPE = "connector_event"
const val CONSENT_ARTIFACT_RECORD_TYPE = "consent_artifact"

data class ConnectorConsentArtifact(
    val schemaVersion: Int,
    val artifactId: String,
    val connectorId: String,
    val connectorType: String,
    val ownerRole: String,
    val ownerId: String,
    val grantedAtEpochMs: Long,
    val grantedAtIso: String,
    val expiresAtEpochMs: Long,
    val revokedAtEpochMs: Long,
    val status: String,
    val proofHash: String,
    val grantedBy: String,
    val appVersion: String,
    val consentScopes: List<String>,
    val actionRef: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("schema_version", schemaVersion)
            .put("artifact_id", artifactId)
            .put("connector_id", connectorId)
            .put("connector_type", connectorType)
            .put("owner_role", ownerRole)
            .put("owner_id", ownerId)
            .put("granted_at_epoch_ms", grantedAtEpochMs)
            .put("granted_at_iso", grantedAtIso)
            .put("expires_at_epoch_ms", expiresAtEpochMs)
            .put("revoked_at_epoch_ms", revokedAtEpochMs)
            .put("status", status)
            .put("proof_hash", proofHash)
            .put("granted_by", grantedBy)
            .put("app_version", appVersion)
            .put("action_ref", actionRef)
            .put("consent_scopes", JSONArray(consentScopes))
    }

    companion object {
        fun fromJson(raw: JSONObject): ConnectorConsentArtifact {
            return ConnectorConsentArtifact(
                schemaVersion = raw.optInt("schema_version", INTEGRATION_MESH_SCHEMA_VERSION),
                artifactId = raw.optString("artifact_id").trim(),
                connectorId = raw.optString("connector_id").trim(),
                connectorType = raw.optString("connector_type").trim(),
                ownerRole = raw.optString("owner_role").trim(),
                ownerId = raw.optString("owner_id").trim(),
                grantedAtEpochMs = raw.optLong("granted_at_epoch_ms", 0L),
                grantedAtIso = raw.optString("granted_at_iso").trim(),
                expiresAtEpochMs = raw.optLong("expires_at_epoch_ms", 0L),
                revokedAtEpochMs = raw.optLong("revoked_at_epoch_ms", 0L),
                status = raw.optString("status", "active").trim(),
                proofHash = raw.optString("proof_hash").trim(),
                grantedBy = raw.optString("granted_by").trim(),
                appVersion = raw.optString("app_version").trim(),
                consentScopes = parseStringArray(raw.optJSONArray("consent_scopes")),
                actionRef = raw.optString("action_ref").trim()
            )
        }
    }
}

data class ConnectorAuditEvent(
    val schemaVersion: Int,
    val eventId: String,
    val eventType: String,
    val recordType: String,
    val connectorId: String,
    val connectorType: String,
    val ownerRole: String,
    val ownerId: String,
    val actorRole: String,
    val actorId: String,
    val recordedAtEpochMs: Long,
    val recordedAtIso: String,
    val outcome: String,
    val consentArtifactId: String,
    val sourceModule: String,
    val details: String,
    val detailsHash: String,
    val riskLevel: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("schema_version", schemaVersion)
            .put("event_id", eventId)
            .put("event_type", eventType)
            .put("record_type", recordType)
            .put("connector_id", connectorId)
            .put("connector_type", connectorType)
            .put("owner_role", ownerRole)
            .put("owner_id", ownerId)
            .put("actor_role", actorRole)
            .put("actor_id", actorId)
            .put("recorded_at_epoch_ms", recordedAtEpochMs)
            .put("recorded_at_iso", recordedAtIso)
            .put("outcome", outcome)
            .put("consent_artifact_id", consentArtifactId)
            .put("source_module", sourceModule)
            .put("details", details)
            .put("details_hash", detailsHash)
            .put("risk_level", riskLevel)
    }

    companion object {
        fun fromJson(raw: JSONObject): ConnectorAuditEvent {
            return ConnectorAuditEvent(
                schemaVersion = raw.optInt("schema_version", INTEGRATION_MESH_SCHEMA_VERSION),
                eventId = raw.optString("event_id").trim(),
                eventType = raw.optString("event_type").trim(),
                recordType = raw.optString("record_type").trim(),
                connectorId = raw.optString("connector_id").trim(),
                connectorType = raw.optString("connector_type").trim(),
                ownerRole = raw.optString("owner_role").trim(),
                ownerId = raw.optString("owner_id").trim(),
                actorRole = raw.optString("actor_role").trim(),
                actorId = raw.optString("actor_id").trim(),
                recordedAtEpochMs = raw.optLong("recorded_at_epoch_ms", 0L),
                recordedAtIso = raw.optString("recorded_at_iso").trim(),
                outcome = raw.optString("outcome").trim(),
                consentArtifactId = raw.optString("consent_artifact_id").trim(),
                sourceModule = raw.optString("source_module").trim(),
                details = raw.optString("details").trim(),
                detailsHash = raw.optString("details_hash").trim(),
                riskLevel = raw.optString("risk_level").trim()
            )
        }
    }
}

data class SmartHomePostureSnapshot(
    val connectorId: String,
    val ownerRole: String,
    val deviceCount: Int,
    val riskScore: Int,
    val findings: List<String>,
    val snapshotAtEpochMs: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("connector_id", connectorId)
            .put("owner_role", ownerRole)
            .put("device_count", deviceCount)
            .put("risk_score", riskScore)
            .put("findings", JSONArray(findings))
            .put("snapshot_at_epoch_ms", snapshotAtEpochMs)
    }

    companion object {
        fun fromJson(raw: JSONObject): SmartHomePostureSnapshot {
            return SmartHomePostureSnapshot(
                connectorId = raw.optString("connector_id").trim(),
                ownerRole = raw.optString("owner_role").trim(),
                deviceCount = raw.optInt("device_count", 0),
                riskScore = raw.optInt("risk_score", 0).coerceIn(0, 100),
                findings = parseStringArray(raw.optJSONArray("findings")),
                snapshotAtEpochMs = raw.optLong("snapshot_at_epoch_ms", 0L)
            )
        }
    }
}

data class SmartHomeConnectorHealth(
    val connectorId: String,
    val status: String,
    val connectedAtEpochMs: Long,
    val lastError: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("connector_id", connectorId)
            .put("status", status)
            .put("connected_at_epoch_ms", connectedAtEpochMs)
            .put("last_error", lastError)
    }

    companion object {
        fun fromJson(raw: JSONObject): SmartHomeConnectorHealth {
            return SmartHomeConnectorHealth(
                connectorId = raw.optString("connector_id").trim(),
                status = raw.optString("status").trim(),
                connectedAtEpochMs = raw.optLong("connected_at_epoch_ms", 0L),
                lastError = raw.optString("last_error").trim()
            )
        }
    }
}

data class VpnProviderConnectionState(
    val providerId: String,
    val state: String,
    val checkedAtEpochMs: Long,
    val details: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("provider_id", providerId)
            .put("state", state)
            .put("checked_at_epoch_ms", checkedAtEpochMs)
            .put("details", details)
    }

    companion object {
        fun fromJson(raw: JSONObject): VpnProviderConnectionState {
            return VpnProviderConnectionState(
                providerId = raw.optString("provider_id").trim(),
                state = raw.optString("state").trim(),
                checkedAtEpochMs = raw.optLong("checked_at_epoch_ms", 0L),
                details = raw.optString("details").trim()
            )
        }
    }
}

data class DigitalKeyRiskFinding(
    val findingType: String,
    val severity: String,
    val message: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("finding_type", findingType)
            .put("severity", severity)
            .put("message", message)
    }

    companion object {
        fun fromJson(raw: JSONObject): DigitalKeyRiskFinding {
            return DigitalKeyRiskFinding(
                findingType = raw.optString("finding_type").trim(),
                severity = raw.optString("severity").trim(),
                message = raw.optString("message").trim()
            )
        }
    }
}

data class DigitalKeyRiskAssessment(
    val assessmentId: String,
    val ownerRole: String,
    val totalRiskScore: Int,
    val overallRiskLevel: String,
    val findings: List<DigitalKeyRiskFinding>,
    val assessedAtEpochMs: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("assessment_id", assessmentId)
            .put("owner_role", ownerRole)
            .put("total_risk_score", totalRiskScore)
            .put("overall_risk_level", overallRiskLevel)
            .put("findings", JSONArray(findings.map { it.toJson() }))
            .put("assessed_at_epoch_ms", assessedAtEpochMs)
    }

    companion object {
        fun fromJson(raw: JSONObject): DigitalKeyRiskAssessment {
            return DigitalKeyRiskAssessment(
                assessmentId = raw.optString("assessment_id").trim(),
                ownerRole = raw.optString("owner_role").trim(),
                totalRiskScore = raw.optInt("total_risk_score", 0).coerceIn(0, 100),
                overallRiskLevel = raw.optString("overall_risk_level").trim(),
                findings = parseFindingArray(raw.optJSONArray("findings")),
                assessedAtEpochMs = raw.optLong("assessed_at_epoch_ms", 0L)
            )
        }
    }
}

object IntegrationMeshAuditStore {
    private const val MAX_LINES_DEFAULT = 3000

    @Synchronized
    fun appendConnectorEvent(context: Context, event: ConnectorAuditEvent) {
        val file = eventFile(context)
        file.appendText(event.toJson().toString() + "\n")
        trimFile(file, MAX_LINES_DEFAULT)
    }

    @Synchronized
    fun appendConsentArtifact(context: Context, artifact: ConnectorConsentArtifact) {
        val file = consentFile(context)
        file.appendText(artifact.toJson().toString() + "\n")
        trimFile(file, MAX_LINES_DEFAULT)
    }

    @Synchronized
    fun readRecentEvents(context: Context, limit: Int = 50): List<ConnectorAuditEvent> {
        val target = limit.coerceIn(1, 200)
        val file = eventFile(context)
        if (!file.exists()) {
            return emptyList()
        }
        return runCatching {
            file.readLines()
                .asReversed()
                .asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching {
                        ConnectorAuditEvent.fromJson(JSONObject(line))
                    }.getOrNull()
                }
                .take(target)
                .toList()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun readConsentArtifacts(context: Context, limit: Int = 30): List<ConnectorConsentArtifact> {
        val target = limit.coerceIn(1, 200)
        val file = consentFile(context)
        if (!file.exists()) {
            return emptyList()
        }
        return runCatching {
            file.readLines()
                .asReversed()
                .asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching {
                        ConnectorConsentArtifact.fromJson(JSONObject(line))
                    }.getOrNull()
                }
                .take(target)
                .toList()
        }.getOrDefault(emptyList())
    }

    fun latestActiveConsent(
        context: Context,
        connectorId: String,
        ownerId: String
    ): ConnectorConsentArtifact? {
        val now = System.currentTimeMillis()
        return readConsentArtifacts(context, 200).firstOrNull { artifact ->
            artifact.connectorId == connectorId &&
                artifact.ownerId == ownerId &&
                artifact.status.equals("active", ignoreCase = true) &&
                artifact.grantedAtEpochMs <= now &&
                (artifact.expiresAtEpochMs == 0L || artifact.expiresAtEpochMs >= now) &&
                artifact.revokedAtEpochMs == 0L
        }
    }

    private fun eventFile(context: Context): File = File(context.filesDir, WatchdogConfig.INTEGRATION_MESH_AUDIT_FILE)
    private fun consentFile(context: Context): File = File(context.filesDir, WatchdogConfig.INTEGRATION_MESH_CONSENT_ARTIFACT_FILE)

    private fun trimFile(file: File, maxLines: Int) {
        val rows = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (rows.size <= maxLines) {
            return
        }
        val trimmed = rows.takeLast(maxLines).joinToString("\n")
        file.writeText("$trimmed\n")
    }
}

fun toIsoUtc(epochMs: Long): String {
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochMs))
}

fun createHash(value: String): String {
    if (value.isBlank()) {
        return ""
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun parseStringArray(raw: JSONArray?): List<String> {
    if (raw == null) {
        return emptyList()
    }
    val values = mutableListOf<String>()
    for (index in 0 until raw.length()) {
        val item = raw.optString(index, "").trim()
        if (item.isNotBlank()) {
            values += item
        }
    }
    return values
}

private fun parseFindingArray(raw: JSONArray?): List<DigitalKeyRiskFinding> {
    if (raw == null) {
        return emptyList()
    }
    val values = mutableListOf<DigitalKeyRiskFinding>()
    for (index in 0 until raw.length()) {
        val item = raw.optJSONObject(index) ?: continue
        values += DigitalKeyRiskFinding.fromJson(item)
    }
    return values
}
