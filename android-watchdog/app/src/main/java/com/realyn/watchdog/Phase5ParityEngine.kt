package com.realyn.watchdog

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.round

data class AppRiskBoardItem(
    val appRef: String,
    val severity: Severity,
    val score: Int,
    val reasonCodes: List<String>,
    val remediation: String,
    val linkedQueueActionId: String,
    val linkedQueueOwner: String,
    val linkedQueueCategory: String
)

data class ConnectedHomeAnomaly(
    val eventId: String,
    val recordedAtEpochMs: Long,
    val ownerRole: String,
    val ownerId: String,
    val severity: Severity,
    val connectorId: String,
    val eventType: String,
    val outcome: String
)

data class OwnerAccountabilityView(
    val ownerRole: String,
    val anomalyHighCount: Int,
    val anomalyMediumCount: Int,
    val pendingQueueCount: Int
)

data class KpiTelemetrySnapshot(
    val capturedAtEpochMs: Long,
    val meanTimeToRemediateHours: Double,
    val highRiskActionSuccessRate: Double,
    val connectorReliabilityRate: Double,
    val sampleCompletedRemediations: Int,
    val sampleHighRiskActions: Int,
    val sampleConnectorEvents: Int
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("captured_at_epoch_ms", capturedAtEpochMs)
            .put("captured_at_iso", toIsoUtc(capturedAtEpochMs))
            .put("mean_time_to_remediate_hours", meanTimeToRemediateHours)
            .put("high_risk_action_success_rate", highRiskActionSuccessRate)
            .put("connector_reliability_rate", connectorReliabilityRate)
            .put("sample_completed_remediations", sampleCompletedRemediations)
            .put("sample_high_risk_actions", sampleHighRiskActions)
            .put("sample_connector_events", sampleConnectorEvents)
    }

    companion object {
        fun fromJson(payload: JSONObject): KpiTelemetrySnapshot {
            return KpiTelemetrySnapshot(
                capturedAtEpochMs = payload.optLong("captured_at_epoch_ms", 0L).coerceAtLeast(0L),
                meanTimeToRemediateHours = payload.optDouble("mean_time_to_remediate_hours", 0.0),
                highRiskActionSuccessRate = payload.optDouble("high_risk_action_success_rate", 0.0),
                connectorReliabilityRate = payload.optDouble("connector_reliability_rate", 0.0),
                sampleCompletedRemediations = payload.optInt("sample_completed_remediations", 0)
                    .coerceAtLeast(0),
                sampleHighRiskActions = payload.optInt("sample_high_risk_actions", 0).coerceAtLeast(0),
                sampleConnectorEvents = payload.optInt("sample_connector_events", 0).coerceAtLeast(0)
            )
        }
    }
}

object Phase5ParityEngine {

    fun buildAppRiskBoard(
        context: Context,
        queue: List<CredentialAction>
    ): List<AppRiskBoardItem> {
        val triage = SecurityScanner.readLastScamTriage(context)
        val appFindings = triage.findings.filter { finding ->
            finding.sourceType.equals("app", ignoreCase = true) &&
                (finding.severity == Severity.HIGH || finding.severity == Severity.MEDIUM)
        }
        return linkAppFindingsToQueue(appFindings, queue)
    }

    internal fun linkAppFindingsToQueue(
        findings: List<ScamFinding>,
        queue: List<CredentialAction>
    ): List<AppRiskBoardItem> {
        val pendingQueue = prioritizedPendingQueue(queue)
        if (findings.isEmpty()) {
            return emptyList()
        }

        return findings
            .sortedWith(
                compareByDescending<ScamFinding> { severityRank(it.severity) }
                    .thenByDescending { it.score }
            )
            .map { finding ->
                val appRef = finding.sourceRef.trim().lowercase(Locale.US)
                val categoryHint = CredentialPolicy.classifyCategory(url = "", service = appRef)
                val linked = pendingQueue.firstOrNull { action ->
                    action.category.equals(categoryHint, ignoreCase = true)
                } ?: pendingQueue.firstOrNull()
                AppRiskBoardItem(
                    appRef = appRef.ifBlank { "unknown_app" },
                    severity = finding.severity,
                    score = finding.score.coerceIn(0, 100),
                    reasonCodes = finding.reasonCodes.sorted(),
                    remediation = finding.remediation.trim(),
                    linkedQueueActionId = linked?.actionId.orEmpty(),
                    linkedQueueOwner = linked?.owner
                        ?.let { CredentialPolicy.canonicalOwnerId(it) }
                        .orEmpty(),
                    linkedQueueCategory = linked?.category.orEmpty().ifBlank { categoryHint }
                )
            }
    }

    fun buildConnectedHomeAnomalies(
        connectorEvents: List<ConnectorAuditEvent>
    ): List<ConnectedHomeAnomaly> {
        return connectorEvents
            .asSequence()
            .filter { event ->
                event.connectorType.equals("smart_home", ignoreCase = true)
            }
            .mapNotNull { event ->
                val outcome = event.outcome.trim().lowercase(Locale.US)
                val severity = when {
                    event.riskLevel.equals("high", ignoreCase = true) ||
                        outcome in connectorFailureOutcomes -> Severity.HIGH
                    event.riskLevel.equals("medium", ignoreCase = true) -> Severity.MEDIUM
                    else -> null
                } ?: return@mapNotNull null
                ConnectedHomeAnomaly(
                    eventId = event.eventId.ifBlank { createHash("${event.connectorId}|${event.recordedAtEpochMs}") },
                    recordedAtEpochMs = event.recordedAtEpochMs,
                    ownerRole = CredentialPolicy.canonicalOwnerId(event.ownerRole.ifBlank { event.ownerId }),
                    ownerId = event.ownerId.ifBlank { event.ownerRole },
                    severity = severity,
                    connectorId = event.connectorId,
                    eventType = event.eventType,
                    outcome = outcome
                )
            }
            .sortedWith(
                compareByDescending<ConnectedHomeAnomaly> { severityRank(it.severity) }
                    .thenByDescending { it.recordedAtEpochMs }
            )
            .toList()
    }

    fun buildOwnerAccountability(
        anomalies: List<ConnectedHomeAnomaly>,
        queue: List<CredentialAction>
    ): List<OwnerAccountabilityView> {
        val owners = linkedSetOf<String>()
        anomalies.forEach { owners += CredentialPolicy.canonicalOwnerId(it.ownerRole) }
        queue.forEach { owners += CredentialPolicy.canonicalOwnerId(it.owner) }
        if (owners.isEmpty()) {
            owners += "parent"
            owners += "child"
        }

        return owners
            .map { owner ->
                OwnerAccountabilityView(
                    ownerRole = owner,
                    anomalyHighCount = anomalies.count {
                        CredentialPolicy.canonicalOwnerId(it.ownerRole) == owner &&
                            it.severity == Severity.HIGH
                    },
                    anomalyMediumCount = anomalies.count {
                        CredentialPolicy.canonicalOwnerId(it.ownerRole) == owner &&
                            it.severity == Severity.MEDIUM
                    },
                    pendingQueueCount = queue.count {
                        CredentialPolicy.canonicalOwnerId(it.owner) == owner &&
                            !it.status.equals("completed", ignoreCase = true)
                    }
                )
            }
            .sortedBy { ownerSortWeight(it.ownerRole) }
    }

    fun computeKpis(
        queue: List<CredentialAction>,
        connectorEvents: List<ConnectorAuditEvent>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): KpiTelemetrySnapshot {
        val completed = queue.filter { action ->
            action.completedAtEpochMs > 0L &&
                action.completedAtEpochMs >= action.createdAtEpochMs
        }
        val meanHours = if (completed.isEmpty()) {
            0.0
        } else {
            completed
                .map { action ->
                    (action.completedAtEpochMs - action.createdAtEpochMs)
                        .coerceAtLeast(0L) / (60.0 * 60.0 * 1000.0)
                }
                .average()
        }

        val highRiskActions = connectorEvents.filter { event ->
            event.riskLevel.equals("high", ignoreCase = true) ||
                event.eventType.contains("high_risk", ignoreCase = true)
        }
        val highRiskSuccess = highRiskActions.count { event ->
            event.outcome.trim().lowercase(Locale.US) in successfulOutcomes
        }
        val highRiskRate = if (highRiskActions.isEmpty()) {
            100.0
        } else {
            (highRiskSuccess.toDouble() / highRiskActions.size.toDouble()) * 100.0
        }

        val connectorRows = connectorEvents.filter { event ->
            event.recordType.equals(CONNECTOR_AUDIT_EVENT_TYPE, ignoreCase = true)
        }
        val connectorSuccess = connectorRows.count { event ->
            event.outcome.trim().lowercase(Locale.US) in successfulOutcomes
        }
        val connectorRate = if (connectorRows.isEmpty()) {
            100.0
        } else {
            (connectorSuccess.toDouble() / connectorRows.size.toDouble()) * 100.0
        }

        return KpiTelemetrySnapshot(
            capturedAtEpochMs = nowEpochMs,
            meanTimeToRemediateHours = roundOneDecimal(meanHours),
            highRiskActionSuccessRate = roundOneDecimal(highRiskRate),
            connectorReliabilityRate = roundOneDecimal(connectorRate),
            sampleCompletedRemediations = completed.size,
            sampleHighRiskActions = highRiskActions.size,
            sampleConnectorEvents = connectorRows.size
        )
    }

    fun formatPercent(value: Double): String {
        return String.format(Locale.US, "%.1f%%", value.coerceIn(0.0, 100.0))
    }

    fun buildUnifiedEvidenceReport(
        capturedAtEpochMs: Long,
        appRiskBoard: List<AppRiskBoardItem>,
        anomalies: List<ConnectedHomeAnomaly>,
        accountability: List<OwnerAccountabilityView>,
        kpi: KpiTelemetrySnapshot,
        guardianEntries: List<GuardianAlertEntry>,
        connectorEvents: List<ConnectorAuditEvent>,
        queue: List<CredentialAction>
    ): String {
        val pendingQueue = queue.count { !it.status.equals("completed", ignoreCase = true) }
        val queueEvidence = queue
            .filter { !it.status.equals("completed", ignoreCase = true) }
            .take(10)
            .mapIndexed { index, action ->
                "${index + 1}. action=${action.actionId} owner=${CredentialPolicy.canonicalOwnerId(action.owner)} category=${action.category} service=${action.service} status=${action.status}"
            }
            .ifEmpty { listOf("none") }

        val appRiskEvidence = appRiskBoard.take(12).mapIndexed { index, row ->
            val queueLink = if (row.linkedQueueActionId.isBlank()) {
                "none"
            } else {
                "${row.linkedQueueActionId} (${row.linkedQueueOwner}/${row.linkedQueueCategory})"
            }
            "${index + 1}. app=${row.appRef} severity=${row.severity.name} score=${row.score} queue_link=$queueLink"
        }.ifEmpty { listOf("none") }

        val anomalyEvidence = anomalies.take(15).mapIndexed { index, row ->
            val owner = CredentialPolicy.canonicalOwnerId(row.ownerRole)
            val safeOwnerRef = if (row.ownerId.isBlank()) owner else createHash(row.ownerId).take(10)
            "${index + 1}. owner=$owner ref=$safeOwnerRef severity=${row.severity.name} connector=${row.connectorId} event=${row.eventType} outcome=${row.outcome} at=${toIsoUtc(row.recordedAtEpochMs)}"
        }.ifEmpty { listOf("none") }

        val accountabilityEvidence = accountability.mapIndexed { index, row ->
            "${index + 1}. owner=${row.ownerRole} anomalies_high=${row.anomalyHighCount} anomalies_medium=${row.anomalyMediumCount} pending_queue=${row.pendingQueueCount}"
        }.ifEmpty { listOf("none") }

        val guardianEvidence = guardianEntries.take(20).mapIndexed { index, row ->
            val safeSource = createHash("${row.sourceType}|${row.sourceRef}").take(12)
            "${index + 1}. severity=${row.severity.name} score=${row.score} source_type=${row.sourceType} source_hash=$safeSource at=${row.recordedAtIso}"
        }.ifEmpty { listOf("none") }

        val connectorEvidence = connectorEvents.take(20).mapIndexed { index, row ->
            "${index + 1}. connector=${row.connectorType}/${row.connectorId} risk=${row.riskLevel} outcome=${row.outcome} owner=${CredentialPolicy.canonicalOwnerId(row.ownerRole)} details_hash=${row.detailsHash.take(12)} at=${row.recordedAtIso}"
        }.ifEmpty { listOf("none") }

        return buildString {
            appendLine("DT Guardian Unified Evidence Report (non-secret)")
            appendLine("Generated: ${toIsoUtc(capturedAtEpochMs)}")
            appendLine()
            appendLine("KPI telemetry")
            appendLine("- Mean time to remediate (hours): ${kpi.meanTimeToRemediateHours} (n=${kpi.sampleCompletedRemediations})")
            appendLine("- High-risk action success: ${formatPercent(kpi.highRiskActionSuccessRate)} (n=${kpi.sampleHighRiskActions})")
            appendLine("- Connector reliability: ${formatPercent(kpi.connectorReliabilityRate)} (n=${kpi.sampleConnectorEvents})")
            appendLine()
            appendLine("App-risk board linked to remediation queue")
            appendLine("- Board items: ${appRiskBoard.size}")
            appendLine("- Pending queue actions: $pendingQueue")
            appRiskEvidence.forEach { appendLine(it) }
            appendLine()
            appendLine("Connected-home anomaly timeline")
            appendLine("- Anomalies: ${anomalies.size}")
            anomalyEvidence.forEach { appendLine(it) }
            appendLine()
            appendLine("Owner accountability view")
            accountabilityEvidence.forEach { appendLine(it) }
            appendLine()
            appendLine("Guardian feed evidence")
            guardianEvidence.forEach { appendLine(it) }
            appendLine()
            appendLine("Connector evidence")
            connectorEvidence.forEach { appendLine(it) }
            appendLine()
            appendLine("Queue evidence (non-secret fields only)")
            queueEvidence.forEach { appendLine(it) }
        }.trim()
    }

    private fun prioritizedPendingQueue(queue: List<CredentialAction>): List<CredentialAction> {
        return queue
            .asSequence()
            .filter { !it.status.equals("completed", ignoreCase = true) }
            .sortedWith(
                compareBy<CredentialAction>(
                    { ownerSortWeight(CredentialPolicy.canonicalOwnerId(it.owner)) },
                    { categorySortWeight(it.category) },
                    { if (it.dueAtEpochMs > 0L) it.dueAtEpochMs else Long.MAX_VALUE }
                )
            )
            .toList()
    }

    private fun ownerSortWeight(owner: String): Int {
        return when (CredentialPolicy.canonicalOwnerId(owner)) {
            "parent" -> 0
            "child" -> 1
            else -> 2
        }
    }

    private fun categorySortWeight(category: String): Int {
        return when (category.trim().lowercase(Locale.US)) {
            "email" -> 0
            "banking" -> 1
            "social" -> 2
            "developer" -> 3
            "other" -> 4
            else -> 5
        }
    }

    private fun severityRank(severity: Severity): Int {
        return when (severity) {
            Severity.HIGH -> 3
            Severity.MEDIUM -> 2
            Severity.LOW -> 1
            Severity.INFO -> 0
        }
    }

    private fun roundOneDecimal(value: Double): Double {
        return round(value * 10.0) / 10.0
    }

    private val successfulOutcomes = setOf("success", "confirmed", "approved", "connected")
    private val connectorFailureOutcomes = setOf("failed", "degraded", "error", "skipped", "cancelled", "denied")
}

object KpiTelemetryStore {
    private const val MAX_ROWS = 2000

    @Synchronized
    fun appendSnapshot(context: Context, snapshot: KpiTelemetrySnapshot) {
        val file = telemetryFile(context)
        file.appendText(snapshot.toJson().toString() + "\n")
        trim(file)
    }

    @Synchronized
    fun readRecent(context: Context, limit: Int = 30): List<KpiTelemetrySnapshot> {
        val target = limit.coerceIn(1, 200)
        val file = telemetryFile(context)
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
                        KpiTelemetrySnapshot.fromJson(JSONObject(line))
                    }.getOrNull()
                }
                .take(target)
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun telemetryFile(context: Context): File {
        return File(context.filesDir, WatchdogConfig.KPI_TELEMETRY_FILE)
    }

    private fun trim(file: File) {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.size <= MAX_ROWS) {
            return
        }
        file.writeText(lines.takeLast(MAX_ROWS).joinToString("\n") + "\n")
    }
}
