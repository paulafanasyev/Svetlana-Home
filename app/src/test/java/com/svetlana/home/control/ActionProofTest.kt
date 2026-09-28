package com.svetlana.home.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит §28: platformAccepted ≠ performed.
 */
class ActionProofTest {

    @Test
    fun `platform accepted without verification is not performed`() {
        val proof = ActionProof(
            planId = "P0", target = "button_send",
            precondition = VerificationState.VERIFIED,
            attempted = true,
            platformAccepted = true,
            postcondition = VerificationState.NOT_CHECKED,
            result = VerificationState.FAILED
        )
        assertTrue("Android принял команду", proof.platformAccepted)
        assertFalse("Но действие не подтверждено", proof.performed)
    }

    @Test
    fun `platform rejected is not performed`() {
        val proof = ActionProof(
            planId = "P0", target = "button_send",
            precondition = VerificationState.VERIFIED,
            attempted = true,
            platformAccepted = false,
            postcondition = VerificationState.FAILED,
            result = VerificationState.FAILED
        )
        assertFalse(proof.performed)
    }

    @Test
    fun `verified postcondition means performed`() {
        val proof = ActionProof(
            planId = "P0", target = "button_send",
            precondition = VerificationState.VERIFIED,
            attempted = true,
            platformAccepted = true,
            postcondition = VerificationState.VERIFIED,
            result = VerificationState.VERIFIED
        )
        assertTrue(proof.performed)
    }

    @Test
    fun `notAttempted is never performed`() {
        val proof = ActionProof.notAttempted("P0", "button_send", "hands off")
        assertFalse(proof.attempted)
        assertFalse(proof.performed)
    }
}
