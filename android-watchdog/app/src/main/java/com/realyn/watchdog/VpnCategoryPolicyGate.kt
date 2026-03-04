package com.realyn.watchdog

import java.util.Locale

enum class VpnPolicyRequirement {
    NONE,
    CONFIGURED,
    CONNECTED
}

data class VpnCategoryPolicyDecision(
    val category: String,
    val requirement: VpnPolicyRequirement,
    val enforcementMode: String,
    val meetsRequirement: Boolean,
    val blocked: Boolean,
    val assertion: VpnStatusAssertion
)

object VpnCategoryPolicyGate {

    fun evaluate(context: android.content.Context, category: String): VpnCategoryPolicyDecision {
        val policy = IntegrationMeshController.readConfig(context).connectors.vpnBrokers.accountClassPolicy
        val normalizedCategory = category.trim().lowercase(Locale.US)
        val requirement = resolveRequirement(policy, normalizedCategory)
        val assertion = VpnStatusAssertions.resolveCached(context)

        val meetsRequirement = when (requirement) {
            VpnPolicyRequirement.NONE -> true
            VpnPolicyRequirement.CONFIGURED -> assertion.assertion == VpnAssertionState.CONFIGURED ||
                assertion.assertion == VpnAssertionState.CONNECTED
            VpnPolicyRequirement.CONNECTED -> assertion.assertion == VpnAssertionState.CONNECTED
        }

        val enforcementMode = policy.enforcementMode.trim().lowercase(Locale.US)
            .let { if (it == "warn") "warn" else "block" }

        val blocked = policy.enabled &&
            requirement != VpnPolicyRequirement.NONE &&
            !meetsRequirement &&
            enforcementMode == "block"

        return VpnCategoryPolicyDecision(
            category = normalizedCategory,
            requirement = if (policy.enabled) requirement else VpnPolicyRequirement.NONE,
            enforcementMode = enforcementMode,
            meetsRequirement = if (policy.enabled) meetsRequirement else true,
            blocked = blocked,
            assertion = assertion
        )
    }

    private fun resolveRequirement(
        policy: IntegrationMeshVpnAccountClassPolicy,
        category: String
    ): VpnPolicyRequirement {
        if (!policy.enabled) {
            return VpnPolicyRequirement.NONE
        }
        if (policy.connectedRequiredCategories.any { it.equals(category, ignoreCase = true) }) {
            return VpnPolicyRequirement.CONNECTED
        }
        if (policy.configuredRequiredCategories.any { it.equals(category, ignoreCase = true) }) {
            return VpnPolicyRequirement.CONFIGURED
        }
        return VpnPolicyRequirement.NONE
    }
}
