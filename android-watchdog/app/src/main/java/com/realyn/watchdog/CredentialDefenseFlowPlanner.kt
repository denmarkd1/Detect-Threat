package com.realyn.watchdog

data class CredentialDefenseSweepState(
    val ranForCurrentEmail: Boolean,
    val matchedRecordCount: Int,
    val compromisedRecordCount: Int
)

enum class CredentialDefenseNextStage {
    COMPLETE_IDENTITY,
    LINK_PRIMARY_EMAIL,
    COMPLETE_FOUNDATION,
    RUN_BREACH_SCAN,
    SAVE_FIRST_CREDENTIAL,
    SERVICE_ACTIONS_READY
}

data class CredentialDefenseFlowPlan(
    val nextStage: CredentialDefenseNextStage,
    val serviceActionUnlocked: Boolean,
    val foundationComplete: Boolean
)

object CredentialDefenseFlowPlanner {

    fun plan(
        primaryEmail: String,
        emailLinked: Boolean,
        foundationComplete: Boolean,
        sweepState: CredentialDefenseSweepState
    ): CredentialDefenseFlowPlan {
        if (primaryEmail.isBlank()) {
            return CredentialDefenseFlowPlan(
                nextStage = CredentialDefenseNextStage.COMPLETE_IDENTITY,
                serviceActionUnlocked = false,
                foundationComplete = foundationComplete
            )
        }
        if (!emailLinked) {
            return CredentialDefenseFlowPlan(
                nextStage = CredentialDefenseNextStage.LINK_PRIMARY_EMAIL,
                serviceActionUnlocked = false,
                foundationComplete = foundationComplete
            )
        }
        if (!foundationComplete) {
            return CredentialDefenseFlowPlan(
                nextStage = CredentialDefenseNextStage.COMPLETE_FOUNDATION,
                serviceActionUnlocked = false,
                foundationComplete = false
            )
        }
        if (!sweepState.ranForCurrentEmail) {
            return CredentialDefenseFlowPlan(
                nextStage = CredentialDefenseNextStage.RUN_BREACH_SCAN,
                serviceActionUnlocked = false,
                foundationComplete = true
            )
        }
        if (sweepState.matchedRecordCount <= 0) {
            return CredentialDefenseFlowPlan(
                nextStage = CredentialDefenseNextStage.SAVE_FIRST_CREDENTIAL,
                serviceActionUnlocked = true,
                foundationComplete = true
            )
        }
        return CredentialDefenseFlowPlan(
            nextStage = CredentialDefenseNextStage.SERVICE_ACTIONS_READY,
            serviceActionUnlocked = true,
            foundationComplete = true
        )
    }
}
