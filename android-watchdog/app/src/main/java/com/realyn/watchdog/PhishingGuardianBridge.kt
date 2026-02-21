package com.realyn.watchdog

import android.content.Context

object PhishingGuardianBridge {

    fun maybeEmitHighRiskAlert(context: Context, result: PhishingTriageResult) {
        if (result.severity != Severity.HIGH) {
            return
        }

        val profileControl = PricingPolicy.resolveProfileControl(context)
        if (!profileControl.requiresGuardianApprovalForSensitiveActions) {
            return
        }

        if (!PhishingTriageEngine.config(context).highRiskAutoAlert) {
            return
        }

        GuardianAlertStore.appendManualEntry(
            context = context,
            severity = Severity.HIGH,
            score = result.riskScore,
            title = "High-risk phishing triage detected",
            sourceType = "phishing_triage",
            sourceRef = result.sourceRef,
            remediation = result.suggestedActions.firstOrNull()
                ?: "Avoid entering credentials and verify with the official provider."
        )
    }
}
