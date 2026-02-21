package com.realyn.watchdog

import android.content.Context
import java.util.Locale

enum class CopilotRoute {
    RUN_ONE_TIME_SCAN,
    RUN_SCAM_TRIAGE,
    OPEN_CREDENTIAL_CENTER,
    OPEN_SUPPORT
}

data class CopilotAction(
    val title: String,
    val rationale: String,
    val route: CopilotRoute
)

data class CopilotBrief(
    val summary: String,
    val rationale: String,
    val actions: List<CopilotAction>,
    val generatedAtEpochMs: Long
)

object AiCopilot {

    fun buildBrief(context: Context): CopilotBrief {
        val rootPosture = SecurityScanner.currentRootPosture(context)
        val scam = SecurityScanner.readLastScamTriage(context)
        val incidents = IncidentStore.summarize(context)
        val featureAccess = PricingPolicy.resolveFeatureAccess(context)
        val profileControl = PricingPolicy.resolveProfileControl(context, featureAccess)

        val riskSignals = mutableListOf<String>()
        if (rootPosture.riskTier == RootRiskTier.COMPROMISED) {
            riskSignals += "root posture compromised"
        } else if (rootPosture.riskTier == RootRiskTier.ELEVATED) {
            riskSignals += "root posture elevated"
        }
        if (scam.highCount > 0) {
            riskSignals += "high scam findings"
        }
        if (incidents.openCount > 0) {
            riskSignals += "open incidents"
        }
        if (riskSignals.isEmpty()) {
            riskSignals += "no urgent indicators"
        }

        val summary = "AI Copilot: ${riskSignals.joinToString(", ")}."

        val rationale = buildString {
            append("Role: ")
            append(
                when (profileControl.roleCode) {
                    "child" -> "child"
                    "family_single" -> "single (family umbrella)"
                    else -> "parent"
                }
            )
            if (profileControl.ageYears >= 0) {
                append(" | age protocol: ${profileControl.ageBandLabel.lowercase(Locale.US)}")
            }
            append(" | scam high=${scam.highCount}, medium=${scam.mediumCount}, low=${scam.lowCount}")
            append(" | incidents open=${incidents.openCount}, in_progress=${incidents.inProgressCount}")
            append(" | root=${rootPosture.riskTier.raw}")
        }

        val actions = mutableListOf<CopilotAction>()
        if (rootPosture.riskTier == RootRiskTier.COMPROMISED || rootPosture.riskTier == RootRiskTier.ELEVATED) {
            actions += CopilotAction(
                title = "Run one-time scan now",
                rationale = "Refresh local evidence after root posture changes before taking sensitive actions.",
                route = CopilotRoute.RUN_ONE_TIME_SCAN
            )
        }
        if (scam.highCount > 0 || scam.mediumCount > 0) {
            actions += CopilotAction(
                title = "Run scam triage and review urgent fixes",
                rationale = "Prioritize malicious links/apps and execute remediation in order.",
                route = CopilotRoute.RUN_SCAM_TRIAGE
            )
        }
        if (incidents.openCount > 0 || incidents.inProgressCount > 0) {
            actions += CopilotAction(
                title = "Open Credential Defense Center",
                rationale = "Work the highest-risk queued credential and incident actions with guarded steps.",
                route = CopilotRoute.OPEN_CREDENTIAL_CENTER
            )
        }
        if (profileControl.requiresGuardianApprovalForSensitiveActions) {
            actions += CopilotAction(
                title = "Review action plan with guardian",
                rationale = "Current age protocol requires guardian confirmation before sensitive changes.",
                route = CopilotRoute.OPEN_SUPPORT
            )
        }
        if (actions.isEmpty()) {
            actions += CopilotAction(
                title = "Run one-time scan for preventive check",
                rationale = "No urgent indicators right now; keep baseline fresh.",
                route = CopilotRoute.RUN_ONE_TIME_SCAN
            )
            actions += CopilotAction(
                title = "Open Credential Defense Center",
                rationale = "Review queued rotations and verify password hygiene.",
                route = CopilotRoute.OPEN_CREDENTIAL_CENTER
            )
        }

        return CopilotBrief(
            summary = summary,
            rationale = rationale,
            actions = actions.distinctBy { it.title }.take(4),
            generatedAtEpochMs = System.currentTimeMillis()
        )
    }
}
