package com.realyn.watchdog

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialPolicyOwnerDetectionTest {

    @Test
    fun detectOwner_matchesExactAndDomainPatterns() {
        val policy = WorkspacePolicy(
            owners = listOf(
                OwnerRule(
                    id = "parent",
                    emailPatterns = listOf("danieldenmark71@gmail.com")
                ),
                OwnerRule(
                    id = "child",
                    emailPatterns = listOf("@school.edu")
                )
            ),
            priorityCategories = listOf("email", "banking", "social", "developer", "other")
        )

        assertEquals(
            "parent",
            CredentialPolicy.detectOwner("danieldenmark71@gmail.com", policy, fallbackOwnerId = "child")
        )
        assertEquals(
            "child",
            CredentialPolicy.detectOwner("student@school.edu", policy, fallbackOwnerId = "parent")
        )
    }

    @Test
    fun detectOwner_supportsWildcardPatternsAndFallbackRole() {
        val policy = WorkspacePolicy(
            owners = listOf(
                OwnerRule(
                    id = "parent",
                    emailPatterns = listOf("daniel*@live.com")
                ),
                OwnerRule(
                    id = "child",
                    emailPatterns = emptyList()
                )
            ),
            priorityCategories = listOf("email", "banking", "social", "developer", "other")
        )

        assertEquals(
            "parent",
            CredentialPolicy.detectOwner("danieldenmark@live.com", policy, fallbackOwnerId = "child")
        )
        assertEquals(
            "child",
            CredentialPolicy.detectOwner("unknown@example.com", policy, fallbackOwnerId = "child")
        )
    }
}
