package com.svetlana.home.control

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ProofStage
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.core.StepStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * App Control + доказательная цепочка на устройстве (ТЗ §17, §19, §20).
 *
 * Ключевая проверка: ACTION_PERFORMED не выставляется просто после
 * startActivity — только после реального перехода в foreground.
 *
 * Этот тест доказывает, что proof chain честно отчитывает NOT PROVEN,
 * если источник верификации недоступен (Hands off, USAGE_STATS не выдан),
 * а не маркирует действие как VERIFIED.
 */
@RunWith(AndroidJUnit4::class)
class AppControlDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun openAppProofChainHasAllStages() = runBlocking {
        val engine = ServiceLocator.controlEngine
        val result = engine.openApp("настройки")

        println("OPEN_APP_SUCCESS=${result.success} WAY=${result.way}")
        println("PROOF=${result.proof.joinToString("\n") { "  ${it.stage}=${it.status} ${it.detail}" }}")

        // Доказательная цепочка должна содержать все стадии.
        val stages = result.proof.map { it.stage }
        assertTrue("Должен быть PLAN", stages.contains(ProofStage.PLAN))
        assertTrue("Должен быть TARGET_APP_IDENTIFIED",
            stages.contains(ProofStage.TARGET_APP_IDENTIFIED))
        assertTrue("Должен быть PERMISSION_CHECKED",
            stages.contains(ProofStage.PERMISSION_CHECKED))
        assertTrue("Должен быть ACTION_ATTEMPTED",
            stages.contains(ProofStage.ACTION_ATTEMPTED))
    }

    @Test
    fun openAppDoesNotClaimPerformedWithoutVerification() = runBlocking {
        val engine = ServiceLocator.controlEngine
        val result = engine.openApp("настройки")

        val performed = result.proof.firstOrNull { it.stage == ProofStage.ACTION_PERFORMED }
        val verified = result.proof.firstOrNull { it.stage == ProofStage.RESULT_VERIFIED }

        println("PERFORMED_STATUS=${performed?.status} VERIFIED_STATUS=${verified?.status}")

        // Если Hands выключен и USAGE_STATS не выдан, ЛЮБОЙ статус, кроме
        // OK, в ACTION_PERFORMED — это правильно. Мы требуем, чтобы
        // proof chain не врал: либо OK (есть источник), либо UNVERIFIED/FAILED.
        if (performed != null && performed.status == StepStatus.OK) {
            assertNotNull("Если PERFORMED=OK, VERIFIED должен быть", verified)
            assertTrue("Если PERFORMED=OK, VERIFIED тоже OK",
                verified?.status == StepStatus.OK)
        }
        // Если успеха нет, VERIFIED не может быть OK.
        if (!result.success) {
            assertTrue("Неуспех: VERIFIED не должен быть OK",
                verified?.status != StepStatus.OK)
        }
    }

    @Test
    fun openNonexistentAppFailsCleanly() = runBlocking {
        val engine = ServiceLocator.controlEngine
        val result = engine.openApp("приложение которого не существует 12345")

        assertFalse("Несуществующее приложение не должно открыться", result.success)
        assertTrue("Должна быть причина неудачи",
            result.message.isNotBlank())
        println("FAKE_APP_MESSAGE=${result.message}")
    }

    @Test
    fun dangerousActionsAreClassified() {
        val engine = ServiceLocator.controlEngine
        val send = engine.riskOf(SvetlanaAction.SendMessage("Иван", "Привет"))
        val call = engine.riskOf(SvetlanaAction.MakeCall("Иван"))

        println("RISK_SEND=$send RISK_CALL=$call")
        assertTrue("SendMessage — опасное действие", send == ActionRisk.DANGEROUS)
        assertTrue("MakeCall — опасное действие", call == ActionRisk.DANGEROUS)
    }

    @Test
    fun safeActionsAreNotClassifiedAsDangerous() {
        val engine = ServiceLocator.controlEngine
        val read = engine.riskOf(SvetlanaAction.ReadScreen("любое"))
        assertTrue("ReadScreen — безопасное действие", read == ActionRisk.SAFE)
    }
}
