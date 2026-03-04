package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5ParityEngineTest {

    @Test
    fun linkAppFindingsToQueue_prefersMatchingCategory() {
        val findings = listOf(
            ScamFinding(
                findingId = "f1",
                sourceType = "app",
                sourceRef = "com.chase.mobile.security",
                severity = Severity.HIGH,
                score = 88,
                reasonCodes = setOf("risky_app_keyword:banklogin"),
                title = "Potential scam app pattern",
                remediation = "Uninstall"
            )
        )
        val queue = listOf(
            action(actionId = "q-email", owner = "parent", category = "email", status = "pending"),
            action(actionId = "q-bank", owner = "parent", category = "banking", status = "pending")
        )

        val board = Phase5ParityEngine.linkAppFindingsToQueue(findings, queue)

        assertEquals(1, board.size)
        assertEquals("q-bank", board.first().linkedQueueActionId)
        assertEquals("banking", board.first().linkedQueueCategory)
    }

    @Test
    fun computeKpis_calculatesMttrAndRates() {
        val base = 1_700_000_000_000L
        val queue = listOf(
            action(
                actionId = "a1",
                status = "completed",
                createdAt = base,
                completedAt = base + (2L * 60L * 60L * 1000L)
            ),
            action(
                actionId = "a2",
                status = "completed",
                createdAt = base,
                completedAt = base + (4L * 60L * 60L * 1000L)
            )
        )
        val events = listOf(
            connectorEvent(eventId = "e1", outcome = "success", riskLevel = "high"),
            connectorEvent(eventId = "e2", outcome = "failed", riskLevel = "high"),
            connectorEvent(eventId = "e3", outcome = "success", riskLevel = "low"),
            connectorEvent(eventId = "e4", outcome = "connected", riskLevel = "medium")
        )

        val snapshot = Phase5ParityEngine.computeKpis(queue, events, nowEpochMs = base + 1)

        assertEquals(3.0, snapshot.meanTimeToRemediateHours, 0.0001)
        assertEquals(50.0, snapshot.highRiskActionSuccessRate, 0.0001)
        assertEquals(75.0, snapshot.connectorReliabilityRate, 0.0001)
    }

    @Test
    fun buildConnectedHomeAnomalies_filtersAndNormalizesOwner() {
        val events = listOf(
            connectorEvent(
                eventId = "ok",
                connectorType = "smart_home",
                outcome = "success",
                riskLevel = "low",
                ownerRole = "parent"
            ),
            connectorEvent(
                eventId = "bad",
                connectorType = "smart_home",
                outcome = "failed",
                riskLevel = "low",
                ownerRole = "son"
            ),
            connectorEvent(
                eventId = "vpn",
                connectorType = "vpn",
                outcome = "failed",
                riskLevel = "high",
                ownerRole = "parent"
            )
        )

        val anomalies = Phase5ParityEngine.buildConnectedHomeAnomalies(events)

        assertEquals(1, anomalies.size)
        assertEquals("bad", anomalies.first().eventId)
        assertEquals("child", anomalies.first().ownerRole)
        assertEquals(Severity.HIGH, anomalies.first().severity)
    }

    @Test
    fun buildOwnerAccountability_countsByOwner() {
        val anomalies = listOf(
            ConnectedHomeAnomaly(
                eventId = "h1",
                recordedAtEpochMs = 1L,
                ownerRole = "parent",
                ownerId = "parent@example.com",
                severity = Severity.HIGH,
                connectorId = "smartthings",
                eventType = "smart_home.posture.collect",
                outcome = "failed"
            ),
            ConnectedHomeAnomaly(
                eventId = "m1",
                recordedAtEpochMs = 2L,
                ownerRole = "child",
                ownerId = "child@example.com",
                severity = Severity.MEDIUM,
                connectorId = "smartthings",
                eventType = "smart_home.health.query",
                outcome = "degraded"
            )
        )
        val queue = listOf(
            action(actionId = "p1", owner = "parent", category = "email", status = "pending"),
            action(actionId = "c1", owner = "child", category = "social", status = "pending"),
            action(actionId = "c2", owner = "child", category = "social", status = "completed")
        )

        val accountability = Phase5ParityEngine.buildOwnerAccountability(anomalies, queue)

        val parent = accountability.first { it.ownerRole == "parent" }
        val child = accountability.first { it.ownerRole == "child" }
        assertEquals(1, parent.anomalyHighCount)
        assertEquals(0, parent.anomalyMediumCount)
        assertEquals(1, parent.pendingQueueCount)
        assertEquals(0, child.anomalyHighCount)
        assertEquals(1, child.anomalyMediumCount)
        assertEquals(1, child.pendingQueueCount)
        assertTrue(accountability.size >= 2)
    }

    private fun action(
        actionId: String,
        owner: String = "parent",
        category: String = "email",
        status: String = "pending",
        createdAt: Long = 1_700_000_000_000L,
        completedAt: Long = 0L
    ): CredentialAction {
        return CredentialAction(
            actionId = actionId,
            owner = owner,
            category = category,
            service = "svc-$actionId",
            username = "user",
            url = "https://example.com",
            actionType = "rotate_password",
            status = status,
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = createdAt,
            dueAtEpochMs = createdAt + 1000L,
            completedAtEpochMs = completedAt,
            receiptId = ""
        )
    }

    private fun connectorEvent(
        eventId: String,
        connectorType: String = "smart_home",
        outcome: String = "success",
        riskLevel: String = "low",
        ownerRole: String = "parent"
    ): ConnectorAuditEvent {
        return ConnectorAuditEvent(
            schemaVersion = INTEGRATION_MESH_SCHEMA_VERSION,
            eventId = eventId,
            eventType = "smart_home.posture.collected",
            recordType = CONNECTOR_AUDIT_EVENT_TYPE,
            connectorId = "smartthings",
            connectorType = connectorType,
            ownerRole = ownerRole,
            ownerId = "$ownerRole@example.com",
            actorRole = ownerRole,
            actorId = "$ownerRole@example.com",
            recordedAtEpochMs = 1_700_000_000_000L,
            recordedAtIso = "2026-03-04T00:00:00Z",
            outcome = outcome,
            consentArtifactId = "",
            sourceModule = "test",
            details = "detail",
            detailsHash = "hash",
            riskLevel = riskLevel
        )
    }
}
