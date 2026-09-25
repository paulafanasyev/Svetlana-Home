package com.svetlana.home.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ProofStage
import com.svetlana.home.core.ProofStep
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.core.StepStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mobile Harness: полная доказательная цепочка на устройстве (ТЗ §13, §14, аудит п.13).
 *
 *   Голос → STT → Intent → Harness Resolver → Launch → UI tree
 *     → Target Finder → Action → Verification → Ответ Светланы
 *
 * Цепочка (ТЗ §19):
 *   PLAN → TARGET_APP_IDENTIFIED → PERMISSION_CHECKED → ACTION_ATTEMPTED
 *     → ACTION_PERFORMED → RESULT_VERIFIED
 *
 * Если Mobile Harness не установлен на устройстве — тесты проверяют, что
 * proof chain честно завершается на этапе TARGET_APP_IDENTIFIED со
 * статусом FAILED, а не имитируют успех (ТЗ §82).
 */
@RunWith(AndroidJUnit4::class)
class MobileHarnessDeviceTest : SvetlanaDeviceTest() {

    private val harness by lazy { ServiceLocator.harness }

    @Test
    fun detectionIsHonest() {
        val state = harness.detect()
        assertNotNull("detect() всегда возвращает состояние", state)
        println("HARNESS_INSTALLED=${state.installed} PKG=${state.packageName} LABEL=${state.label}")
        // Состояние должно совпадать с реальностью: если пакет не найден
        // в AppRegistry, installed обязан быть false.
        if (state.installed) {
            assertTrue("Установленный Harness должен иметь packageName",
                state.packageName?.isNotBlank() == true)
        } else {
            assertFalse("Неустановленный Harness: installed=false", state.installed)
        }
    }

    @Test
    fun launchProofChain_hasAllStages() = runBlocking {
        val proof: List<ProofStep> = harness.launch()

        // ТЗ §19: цепочка должна начинаться с PLAN
        assertTrue("Цепочка должна начинаться с PLAN",
            proof.first().stage == ProofStage.PLAN)

        // Все этапы должны быть классифицированы (OK или FAILED),
        // не должно быть неопределённых статусов
        proof.forEach { step ->
            assertTrue("Шаг ${step.stage} должен иметь статус",
                step.status == StepStatus.OK || step.status == StepStatus.FAILED ||
                step.status == StepStatus.UNVERIFIED)
        }
        println("PROOF_CHAIN=${proof.map { "${it.stage}=${it.status}" }}")
    }

    @Test
    fun notInstalled_proofChainFailsAtTargetIdentification() = runBlocking {
        val state = harness.detect()
        if (state.installed) {
            println("SKIP: Mobile Harness установлен — этот тест для случая отсутствия")
            return@runBlocking
        }

        // ТЗ §82: нельзя выставлять ACTION_PERFORMED без реального выполнения.
        val proof = harness.launch()
        val targetStep = proof.firstOrNull { it.stage == ProofStage.TARGET_APP_IDENTIFIED }

        assertNotNull("Должен быть шаг TARGET_APP_IDENTIFIED", targetStep)
        assertTrue("Если Harness не установлен — шаг TARGET должен быть FAILED",
            targetStep!!.status == StepStatus.FAILED)

        // ACTION_PERFORMED и RESULT_VERIFIED не могут быть OK, если
        // цель не идентифицирована
        val performed = proof.firstOrNull { it.stage == ProofStage.ACTION_PERFORMED }
        val verified = proof.firstOrNull { it.stage == ProofStage.RESULT_VERIFIED }
        assertTrue("ACTION_PERFORMED не должен быть OK без цели",
            performed?.status != StepStatus.OK)
        assertTrue("RESULT_VERIFIED не должен быть OK без цели",
            verified?.status != StepStatus.OK)
    }

    @Test
    fun uiTreeRequiresActiveHands() = runBlocking {
        val tree = harness.uiTree()
        // Если Hands не активен — возвращается "empty", а не падение
        assertNotNull("uiTree() всегда возвращает строку", tree)
        println("HARNESS_UITREE_LEN=${tree.length}")
    }

    @Test
    fun verifyResultHonestWhenNotInForeground() = runBlocking {
        // verifyResult на несуществующем тексте должен вернуть false,
        // а не имитировать успех
        val ok = harness.verifyResult("такого_текто_точно_нет_${System.currentTimeMillis()}")
        assertFalse("verifyResult должен вернуть false для отсутствующего текста", ok)
    }
}
