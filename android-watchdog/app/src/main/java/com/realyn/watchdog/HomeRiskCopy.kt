package com.realyn.watchdog

import java.util.Locale

internal object HomeRiskCopy {

    internal enum class PostureAction {
        OPEN_SETUP,
        OPEN_PROVIDER
    }

    fun buildPostureMessage(
        posture: SmartHomePostureSnapshot,
        connectorLabel: String = "",
        importedDeviceLabels: List<String> = emptyList(),
        protectedDeviceLabels: List<String> = emptyList()
    ): String {
        val connectorDisplay = connectorDisplayLabel(connectorLabel, posture.connectorId)
        val action = resolvePostureAction(posture)
        val nextStepLines = when (action) {
            PostureAction.OPEN_PROVIDER -> listOf(
                "1. Tap Open provider if you want to confirm app readiness or reopen the selected ecosystem.",
                "2. Tap Back to home if you only wanted to review this snapshot."
            )

            PostureAction.OPEN_SETUP -> listOf(
                "1. No urgent action is required unless you want to refresh or adjust Home Risk protection.",
                "2. Tap Home Risk setup if you want to review provider readiness for this profile.",
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
            if (protectedDeviceLabels.isNotEmpty()) {
                appendLine("Protected devices in this local scan")
                protectedDeviceLabels.distinct().take(5).forEachIndexed { index, label ->
                    appendLine("${index + 1}. $label")
                }
                val remaining = protectedDeviceLabels.distinct().size - protectedDeviceLabels.distinct().take(5).size
                if (remaining > 0) {
                    appendLine("${protectedDeviceLabels.distinct().take(5).size + 1}. +$remaining more protected devices selected")
                }
                appendLine()
            } else if (importedDeviceLabels.isNotEmpty()) {
                appendLine("Imported devices awaiting protection")
                importedDeviceLabels.distinct().take(5).forEachIndexed { index, label ->
                    appendLine("${index + 1}. $label")
                }
                val remaining = importedDeviceLabels.distinct().size - importedDeviceLabels.distinct().take(5).size
                if (remaining > 0) {
                    appendLine("${importedDeviceLabels.distinct().take(5).size + 1}. +$remaining more imported devices")
                }
                appendLine()
            }
            appendLine("Technical details (optional)")
            appendLine("Connector: $connectorDisplay")
            appendLine("Owner profile: ${posture.ownerRole.ifBlank { "owner" }}")
            append("Readiness sample: ${posture.deviceCount}")
        }.trim()
    }

    fun resolvePostureAction(posture: SmartHomePostureSnapshot): PostureAction {
        return if (
            posture.findings.any { it.equals("smart_home_client_not_installed", ignoreCase = true) } ||
            posture.findings.any { it.equals("provider_app_missing", ignoreCase = true) } ||
            posture.findings.any { it.equals("authorization_pending_provider", ignoreCase = true) }
        ) {
            PostureAction.OPEN_PROVIDER
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
            "provider_app_detected" ->
                "$connectorDisplay was detected on this phone, so local umbrella checks can stay anchored to that provider."
            "provider_app_missing" ->
                "$connectorDisplay is not ready on this phone yet. Install or reopen the provider before adding devices under Home Risk."
            "provider_authorized_locally" ->
                "$connectorDisplay has been marked ready for local Home Risk onboarding."
            "provider_connected_live" ->
                "$connectorDisplay is connected with a stored provider token and can sync live inventory."
            "authorization_pending_provider" ->
                "$connectorDisplay still needs sign-in or local readiness confirmation before new devices can be imported."
            "no_devices_seen_in_connector_snapshot" ->
                "No connected-home devices were found in the latest local snapshot. Reopen Home Risk setup after checking $connectorDisplay."
            "devices_imported_locally" ->
                "Device import is saved locally so you can decide what stays under Home Risk protection."
            "provider_live_inventory_synced" ->
                "Live provider inventory was included in the latest Home Risk snapshot."
            "no_protected_devices_selected" ->
                "Imported devices exist, but none are protected yet. Choose the devices you want included in Home Risk scans."
            "protected_devices_selected" ->
                "Protected devices are selected and will be included in local Home Risk scans."
            "local_provider_snapshot" ->
                "This build is using local provider readiness and imported-device selections instead of live cloud telemetry."
            "smart_fob_provider_selected" ->
                "A smart fob or digital-key provider is included under the Home Risk umbrella."
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
