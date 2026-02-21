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

enum class IncidentStatus(val raw: String) {
    OPEN("open"),
    IN_PROGRESS("in_progress"),
    RESOLVED("resolved");

    companion object {
        fun fromRaw(value: String?): IncidentStatus {
            return when (value?.trim()?.lowercase(Locale.US)) {
                IN_PROGRESS.raw -> IN_PROGRESS
                RESOLVED.raw -> RESOLVED
                else -> OPEN
            }
        }
    }
}

data class IncidentRecord(
    val incidentId: String,
    val severity: Severity,
    val title: String,
    val details: String,
    val status: IncidentStatus,
    val occurrenceCount: Int,
    val firstSeenAtEpochMs: Long,
    val lastSeenAtEpochMs: Long,
    val statusUpdatedAtEpochMs: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("incidentId", incidentId)
            .put("severity", severity.name)
            .put("title", title)
            .put("details", details)
            .put("status", status.raw)
            .put("occurrenceCount", occurrenceCount)
            .put("firstSeenAtEpochMs", firstSeenAtEpochMs)
            .put("lastSeenAtEpochMs", lastSeenAtEpochMs)
            .put("statusUpdatedAtEpochMs", statusUpdatedAtEpochMs)
    }

    companion object {
        fun fromJson(item: JSONObject): IncidentRecord {
            val severity = runCatching {
                Severity.valueOf(item.optString("severity", Severity.MEDIUM.name))
            }.getOrDefault(Severity.MEDIUM)

            return IncidentRecord(
                incidentId = item.optString("incidentId"),
                severity = severity,
                title = item.optString("title"),
                details = item.optString("details"),
                status = IncidentStatus.fromRaw(item.optString("status")),
                occurrenceCount = item.optInt("occurrenceCount", 1).coerceAtLeast(1),
                firstSeenAtEpochMs = item.optLong("firstSeenAtEpochMs", System.currentTimeMillis()),
                lastSeenAtEpochMs = item.optLong("lastSeenAtEpochMs", System.currentTimeMillis()),
                statusUpdatedAtEpochMs = item.optLong("statusUpdatedAtEpochMs", System.currentTimeMillis())
            )
        }
    }
}

data class IncidentSummary(
    val openCount: Int,
    val inProgressCount: Int,
    val resolvedCount: Int,
    val preview: String
)

object IncidentStore {

    private fun stateFile(context: Context): File = File(context.filesDir, WatchdogConfig.INCIDENT_STATE_FILE)
    private fun eventFile(context: Context): File = File(context.filesDir, WatchdogConfig.INCIDENT_EVENT_LOG_FILE)

    @Synchronized
    fun syncFromScan(context: Context, result: ScanResult) {
        val scanTime = result.snapshot.scannedAtEpochMs
        val alerts = result.alerts.filter { it.severity != Severity.INFO }
        if (alerts.isEmpty()) {
            return
        }

        val byId = loadInternal(context).associateBy { it.incidentId }.toMutableMap()

        alerts.forEach { alert ->
            val incidentId = incidentIdFor(alert)
            val existing = byId[incidentId]

            if (existing == null) {
                val created = IncidentRecord(
                    incidentId = incidentId,
                    severity = alert.severity,
                    title = alert.title,
                    details = alert.details,
                    status = IncidentStatus.OPEN,
                    occurrenceCount = 1,
                    firstSeenAtEpochMs = scanTime,
                    lastSeenAtEpochMs = scanTime,
                    statusUpdatedAtEpochMs = scanTime
                )
                byId[incidentId] = created
                appendEvent(
                    context = context,
                    timestampEpochMs = scanTime,
                    incident = created,
                    eventType = "incident_created",
                    fromStatus = null,
                    toStatus = IncidentStatus.OPEN
                )
            } else {
                val reopened = existing.status == IncidentStatus.RESOLVED
                val updated = existing.copy(
                    details = if (alert.details.isBlank()) existing.details else alert.details,
                    occurrenceCount = existing.occurrenceCount + 1,
                    lastSeenAtEpochMs = scanTime,
                    status = if (reopened) IncidentStatus.OPEN else existing.status,
                    statusUpdatedAtEpochMs = if (reopened) scanTime else existing.statusUpdatedAtEpochMs
                )
                byId[incidentId] = updated

                if (reopened) {
                    appendEvent(
                        context = context,
                        timestampEpochMs = scanTime,
                        incident = updated,
                        eventType = "incident_reopened_on_signal",
                        fromStatus = IncidentStatus.RESOLVED,
                        toStatus = IncidentStatus.OPEN
                    )
                }
            }
        }

        saveInternal(context, sortForStorage(byId.values.toList()))
    }

    @Synchronized
    fun loadIncidents(context: Context): List<IncidentRecord> {
        return sortForDisplay(loadInternal(context))
    }

    @Synchronized
    fun markNextOpenInProgress(context: Context): IncidentRecord? {
        val incidents = loadInternal(context)
        val target = incidents
            .filter { it.status == IncidentStatus.OPEN }
            .maxByOrNull { it.lastSeenAtEpochMs }
            ?: return null

        return updateStatus(context, incidents, target.incidentId, IncidentStatus.IN_PROGRESS)
    }

    @Synchronized
    fun resolveNextActive(context: Context): IncidentRecord? {
        val incidents = loadInternal(context)
        val inProgress = incidents
            .filter { it.status == IncidentStatus.IN_PROGRESS }
            .maxByOrNull { it.lastSeenAtEpochMs }

        val target = inProgress ?: incidents
            .filter { it.status == IncidentStatus.OPEN }
            .maxByOrNull { it.lastSeenAtEpochMs }
            ?: return null

        return updateStatus(context, incidents, target.incidentId, IncidentStatus.RESOLVED)
    }

    @Synchronized
    fun reopenLatestResolved(context: Context): IncidentRecord? {
        val incidents = loadInternal(context)
        val target = incidents
            .filter { it.status == IncidentStatus.RESOLVED }
            .maxByOrNull { it.statusUpdatedAtEpochMs }
            ?: return null

        return updateStatus(context, incidents, target.incidentId, IncidentStatus.OPEN)
    }

    @Synchronized
    fun summarize(context: Context): IncidentSummary {
        val incidents = loadInternal(context)
        val openCount = incidents.count { it.status == IncidentStatus.OPEN }
        val inProgressCount = incidents.count { it.status == IncidentStatus.IN_PROGRESS }
        val resolvedCount = incidents.count { it.status == IncidentStatus.RESOLVED }

        val primary = sortForDisplay(incidents)
            .take(3)
            .map { incident ->
                val shortId = incident.incidentId.take(8)
                val status = incident.status.raw
                val severity = incident.severity.name.lowercase(Locale.US)
                "[$shortId] $status/$severity ${incident.title}"
            }

        val preview = if (primary.isEmpty()) {
            "No incident records yet."
        } else {
            primary.joinToString("\n")
        }

        return IncidentSummary(
            openCount = openCount,
            inProgressCount = inProgressCount,
            resolvedCount = resolvedCount,
            preview = preview
        )
    }

    private fun updateStatus(
        context: Context,
        incidents: List<IncidentRecord>,
        incidentId: String,
        newStatus: IncidentStatus
    ): IncidentRecord? {
        val now = System.currentTimeMillis()
        var updatedIncident: IncidentRecord? = null

        val updated = incidents.map { incident ->
            if (incident.incidentId != incidentId) {
                incident
            } else {
                val next = incident.copy(
                    status = newStatus,
                    statusUpdatedAtEpochMs = now
                )
                updatedIncident = next
                next
            }
        }

        val incident = updatedIncident ?: return null
        saveInternal(context, sortForStorage(updated))

        appendEvent(
            context = context,
            timestampEpochMs = now,
            incident = incident,
            eventType = "incident_status_changed",
            fromStatus = incidents.firstOrNull { it.incidentId == incidentId }?.status,
            toStatus = newStatus
        )

        return incident
    }

    private fun loadInternal(context: Context): List<IncidentRecord> {
        val file = stateFile(context)
        if (!file.exists()) {
            return emptyList()
        }

        val raw = runCatching { file.readText() }.getOrDefault("")
        if (raw.isBlank()) {
            return emptyList()
        }

        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val items = mutableListOf<IncidentRecord>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            items += IncidentRecord.fromJson(item)
        }
        return items
    }

    private fun saveInternal(context: Context, incidents: List<IncidentRecord>) {
        val array = JSONArray()
        incidents.forEach { array.put(it.toJson()) }
        stateFile(context).writeText(array.toString(2))
    }

    private fun appendEvent(
        context: Context,
        timestampEpochMs: Long,
        incident: IncidentRecord,
        eventType: String,
        fromStatus: IncidentStatus?,
        toStatus: IncidentStatus?
    ) {
        val row = JSONObject()
            .put("atEpochMs", timestampEpochMs)
            .put("atIso", formatIsoTime(timestampEpochMs))
            .put("incidentId", incident.incidentId)
            .put("eventType", eventType)
            .put("severity", incident.severity.name)
            .put("title", sanitizeLine(incident.title))
            .put("details", sanitizeLine(incident.details).take(400))
            .put("occurrenceCount", incident.occurrenceCount)

        if (fromStatus != null) {
            row.put("fromStatus", fromStatus.raw)
        }
        if (toStatus != null) {
            row.put("toStatus", toStatus.raw)
        }

        eventFile(context).appendText(row.toString() + "\n")
    }

    private fun incidentIdFor(alert: WatchdogAlert): String {
        val raw = "${alert.severity}|${alert.title}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    private fun sortForStorage(incidents: List<IncidentRecord>): List<IncidentRecord> {
        return incidents.sortedByDescending { it.lastSeenAtEpochMs }
    }

    private fun sortForDisplay(incidents: List<IncidentRecord>): List<IncidentRecord> {
        return incidents.sortedWith(
            compareBy<IncidentRecord> { statusOrder(it.status) }
                .thenByDescending { it.lastSeenAtEpochMs }
        )
    }

    private fun statusOrder(status: IncidentStatus): Int {
        return when (status) {
            IncidentStatus.OPEN -> 0
            IncidentStatus.IN_PROGRESS -> 1
            IncidentStatus.RESOLVED -> 2
        }
    }

    private fun sanitizeLine(value: String): String {
        return value.replace("\n", " ").replace("\r", " ").replace("\"", "'").trim()
    }

    private fun formatIsoTime(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMs))
    }
}
