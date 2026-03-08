package com.realyn.watchdog

import java.util.Locale

internal object HomeRiskCopy {

    internal enum class PostureAction {
        OPEN_SETUP,
        OPEN_SMARTTHINGS
    }

    fun buildPostureMessage(
        posture: SmartHomePostureSnapshot,
        connectorLabel: String = ""
    ): String {
        val connectorDisplay = connectorDisplayLabel(connectorLabel, posture.connectorId)
        val action = resolvePostureAction(posture)
        val nextStepLines = when (action) {
            PostureAction.OPEN_SMARTTHINGS -> listOf(
                "1. Tap Install/Open SmartThings to refresh local readiness checks on this phone.",
                "2. Tap Back to home if you only wanted to review this snapshot."
            )

            PostureAction.OPEN_SETUP -> listOf(
                "1. No urgent action is required unless you want to refresh or reconnect Home Risk.",
                "2. Tap Home Risk setup if you want to review connector readiness for this profile.",
                "3. Tap Back to home to leave this snapshot."
            )
        }
        val summaryLines = posture.findings
            .distinct()
            .ifEmpty { listOf("no_active_findings") }
            .mapIndexed { index, finding ->
                "${index + 1}. ${describeFinding(finding, connectorDisplay)}"
            }

        return buildString {
            appendLine("This screen shows a read-only local snapshot of your Home Risk posture.")
            appendLine("Current score: ${posture.riskScore}/100")
            appendLine()
            appendLine("What to do now")
            nextStepLines.forEach { appendLine(it) }
            appendLine()
            appendLine("What this snapshot found")
            summaryLines.forEach { appendLine(it) }
            appendLine()
            appendLine("Technical details (optional)")
            appendLine("Connector: $connectorDisplay")
            appendLine("Owner profile: ${posture.ownerRole.ifBlank { "owner" }}")
            append("Readiness sample: ${posture.deviceCount}")
        }.trim()
    }

    fun resolvePostureAction(posture: SmartHomePostureSnapshot): PostureAction {
        return if (posture.findings.any { it.equals("smart_home_client_not_installed", ignoreCase = true) }) {
            PostureAction.OPEN_SMARTTHINGS
        } else {
            PostureAction.OPEN_SETUP
        }
    }

    fun connectorDisplayLabel(connectorLabel: String, connectorId: String): String {
        val label = connectorLabel.trim().ifBlank { fallbackConnectorLabel(connectorId) }
        return label.removeSuffix(" Connector").trim().ifBlank { "Smart home" }
    }

    internal fun describeFinding(finding: String, connectorDisplay: String = "Smart home"): String {
        return when (finding.trim().lowercase(Locale.US)) {
            "no_active_findings" -> "No active home-risk issues were found in this local snapshot."
            "connector_read_only_mode" ->
                "DT Guardian is using $connectorDisplay in read-only mode, so this screen reviews status only and does not change your home account."
            "smart_home_client_detected" ->
                "$connectorDisplay was detected on this phone, so local readiness checks can run here."
            "smart_home_client_not_installed" ->
                "$connectorDisplay was not found on this phone. Install or open it if you want richer local readiness checks."
            "no_devices_seen_in_connector_snapshot" ->
                "No connected-home devices were found in the latest local snapshot. Reopen Home Risk setup after checking $connectorDisplay."
            "no_devices_seen_in_demo_window" ->
                "No demo devices were visible in the latest local snapshot."
            "remote_home_control_exposure" ->
                "Remote home-control access looks broad enough that this profile deserves a closer review."
            "consent_not_active" ->
                "Home Risk setup needs to be refreshed before this snapshot can update again."
            "demographic_guardrail" ->
                "Extra guardrails were applied because this is a child-focused profile."
            else -> prettifyFindingId(finding)
        }
    }

    private fun fallbackConnectorLabel(connectorId: String): String {
        return when (connectorId.trim().lowercase(Locale.US)) {
            "smartthings" -> "SmartThings"
            else -> prettifyFindingId(connectorId)
        }
    }

    private fun prettifyFindingId(raw: String): String {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) {
            return "Unknown status"
        }
        return cleaned
            .split('_', '-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.lowercase(Locale.US).replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase(Locale.US)
                    } else {
                        char.toString()
                    }
                }
            }
            .ifBlank { cleaned }
    }
}
