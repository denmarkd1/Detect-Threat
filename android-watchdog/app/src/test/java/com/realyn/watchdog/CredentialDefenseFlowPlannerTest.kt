package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialDefenseFlowPlannerTest {

    @Test
    fun `requires identity before anything else`() {
        val plan = CredentialDefenseFlowPlanner.plan(
            primaryEmail = "",
            emailLinked = false,
            foundationComplete = false,
            sweepState = CredentialDefenseSweepState(
                ranForCurrentEmail = false,
                matchedRecordCount = 0,
                compromisedRecordCount = 0
            )
        )

        assertEquals(CredentialDefenseNextStage.COMPLETE_IDENTITY, plan.nextStage)
        assertFalse(plan.serviceActionUnlocked)
    }

    @Test
    fun `requires email linking after identity is saved`() {
        val plan = CredentialDefenseFlowPlanner.plan(
            primaryEmail = "parent@example.com",
            emailLinked = false,
            foundationComplete = true,
            sweepState = CredentialDefenseSweepState(
                ranForCurrentEmail = false,
                matchedRecordCount = 0,
                compromisedRecordCount = 0
            )
        )

        assertEquals(CredentialDefenseNextStage.LINK_PRIMARY_EMAIL, plan.nextStage)
        assertFalse(plan.serviceActionUnlocked)
    }

    @Test
    fun `requires foundation before scan`() {
        val plan = CredentialDefenseFlowPlanner.plan(
            primaryEmail = "parent@example.com",
            emailLinked = true,
            foundationComplete = false,
            sweepState = CredentialDefenseSweepState(
                ranForCurrentEmail = false,
                matchedRecordCount = 0,
                compromisedRecordCount = 0
            )
        )

        assertEquals(CredentialDefenseNextStage.COMPLETE_FOUNDATION, plan.nextStage)
        assertFalse(plan.serviceActionUnlocked)
    }

    @Test
    fun `requires scan before service actions`() {
        val plan = CredentialDefenseFlowPlanner.plan(
            primaryEmail = "parent@example.com",
            emailLinked = true,
            foundationComplete = true,
            sweepState = CredentialDefenseSweepState(
                ranForCurrentEmail = false,
                matchedRecordCount = 0,
                compromisedRecordCount = 0
            )
        )

        assertEquals(CredentialDefenseNextStage.RUN_BREACH_SCAN, plan.nextStage)
        assertFalse(plan.serviceActionUnlocked)
    }

    @Test
    fun `unlocks service action after empty sweep so user can seed first credential`() {
        val plan = CredentialDefenseFlowPlanner.plan(
            primaryEmail = "parent@example.com",
            emailLinked = true,
            foundationComplete = true,
            sweepState = CredentialDefenseSweepState(
                ranForCurrentEmail = true,
                matchedRecordCount = 0,
                compromisedRecordCount = 0
            )
        )

        assertEquals(CredentialDefenseNextStage.SAVE_FIRST_CREDENTIAL, plan.nextStage)
        assertTrue(plan.serviceActionUnlocked)
    }

    @Test
    fun `keeps service actions ready after scan finds linked records`() {
        val plan = CredentialDefenseFlowPlanner.plan(
            primaryEmail = "parent@example.com",
            emailLinked = true,
            foundationComplete = true,
            sweepState = CredentialDefenseSweepState(
                ranForCurrentEmail = true,
                matchedRecordCount = 3,
                compromisedRecordCount = 1
            )
        )

        assertEquals(CredentialDefenseNextStage.SERVICE_ACTIONS_READY, plan.nextStage)
        assertTrue(plan.serviceActionUnlocked)
    }
}
