package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import com.svetlana.home.core.ProofStage
import com.svetlana.home.core.ProofStep
import com.svetlana.home.core.StepStatus
import org.junit.Test

class ProofBuilderTest {

    @Test
    fun `full chain ends with verified`() {
        val proof = ProofBuilder().start("PLAN0_TARGET=OPEN_MOBILE_HARNESS")
            .ok(ProofStage.TARGET_APP_IDENTIFIED, "target=com.mobile.harness")
            .ok(ProofStage.PERMISSION_CHECKED, "hands granted")
            .ok(ProofStage.ACTION_ATTEMPTED, "startActivity")
            .ok(ProofStage.ACTION_PERFORMED, "PLAN0_STATUS=ACTION_PERFORMED")
            .ok(ProofStage.RESULT_VERIFIED, "PLAN0_RESULT=VERIFIED")
            .build()

        val stages = proof.map { it.stage }
        assertThat(stages).containsExactly(
            ProofStage.PLAN,
            ProofStage.TARGET_APP_IDENTIFIED,
            ProofStage.PERMISSION_CHECKED,
            ProofStage.ACTION_ATTEMPTED,
            ProofStage.ACTION_PERFORMED,
            ProofStage.RESULT_VERIFIED
        ).inOrder()
        assertThat(proof.all { it.status == StepStatus.OK }).isTrue()
    }

    @Test
    fun `failed chain records failure`() {
        val proof = ProofBuilder().start("PLAN0").failed("hands not active")
        assertThat(proof.last().stage).isEqualTo(ProofStage.RESULT_VERIFIED)
        assertThat(proof.last().status).isEqualTo(StepStatus.FAILED)
    }

    @Test
    fun `command performed is not verified without result`() {
        // ТЗ §19: «команда отправлена» не равна «действие выполнено»
        val proof = ProofBuilder().start("PLAN0")
            .ok(ProofStage.ACTION_ATTEMPTED, "sent")
            .build()
        val verified = proof.any { it.stage == ProofStage.RESULT_VERIFIED && it.status == StepStatus.OK }
        assertThat(verified).isFalse()
    }

    @Test
    fun `proof log is human readable`() {
        val log = ProofBuilder().start("PLAN0").ok(ProofStage.TARGET_APP_IDENTIFIED, "ok").build()
            .joinToString("\n") { "PLAN_${it.stage} ${it.status}" }
        assertThat(log).contains("PLAN_PLAN OK")
        assertThat(log).contains("TARGET_APP_IDENTIFIED OK")
    }

    @Test
    fun `proof steps carry timestamps`() {
        val step = ProofStep(ProofStage.PLAN, StepStatus.OK, "x")
        assertThat(step.timestampMs).isGreaterThan(0L)
    }
}
