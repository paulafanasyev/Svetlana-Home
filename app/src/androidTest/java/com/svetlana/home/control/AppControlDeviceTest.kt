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

    /**
     * Полная proof chain запуска реального приложения (аудит п.7).
     * Цепочка: PLAN → TARGET_APP_IDENTIFIED → PERMISSION_CHECKED
     *   → ACTION_ATTEMPTED → ACTION_PERFORMED → RESULT_VERIFIED.
     * startActivity() != «приложение открыто»: ACTION_PERFORMED выставляется
     * только после реального перехода в foreground (LaunchVerifier).
     */
    @Test
    fun openSettingsApp_producesFullProofChain() = runBlocking {
        val engine = ServiceLocator.controlEngine
        // Настройки есть на любом Android-устройстве — безопасная цель
        val result = engine.openApp("настройки")

        println("OPEN_SETTINGS_SUCCESS=${result.success}")
        println("PROOF=${result.proof.joinToString(" | ") { "${it.stage}=${it.status}" }}")

        val stages = result.proof.map { it.stage }
        assertTrue("Должен быть PLAN", stages.contains(ProofStage.PLAN))
        assertTrue("Должен быть TARGET_APP_IDENTIFIED",
            stages.contains(ProofStage.TARGET_APP_IDENTIFIED))

        if (result.success) {
            val performed = result.proof.first { it.stage == ProofStage.ACTION_PERFORMED }
            val verified = result.proof.first { it.stage == ProofStage.RESULT_VERIFIED }
            assertTrue("При успехе ACTION_PERFORMED должен быть OK",
                performed.status == StepStatus.OK)
            assertTrue("При успехе RESULT_VERIFIED должен быть OK",
                verified.status == StepStatus.OK)
            assertTrue("Сообщение об успехе должно быть на русском",
                result.message.contains("открыто"))
        }
        // Возвращаемся на главный экран, чтобы не мешать другим тестам
        ServiceLocator.hands.pressHome()
    }

    @Test
    fun clickRequiresActiveHandsOrFailsHonestly() = runBlocking {
        val engine = ServiceLocator.controlEngine
        // Click требует Hands; если он выключен — честный отказ, а не имитация
        val result = engine.click("Настройки", "кнопка_которой_нет")
        if (!ServiceLocator.hands.isActive) {
            assertFalse("Click без Hands должен FAIL", result.success)
            assertTrue("Сообщение должно объяснять причину",
                result.message.contains("Hands", ignoreCase = true))
        } else {
            // Hands активен — элемент не найден, честный FAIL
            assertFalse("Несуществующий элемент не должен быть нажат", result.success)
        }
    }

    @Test
    fun takeScreenshotWorksOrFailsHonestly() = runBlocking {
        val engine = ServiceLocator.controlEngine
        val bmp = engine.takeScreenshot("Настройки")
        // Скриншот может требовать Hands/API — вызов не должен падать
        println("SCREENSHOT=${if (bmp != null) "OK ${bmp.width}x${bmp.height}" else "null"}")
    }

    /**
     * ТЗ §20: ACTION_PERFORMED нельзя выставить без реального выполнения.
     */
    @Test
    fun performedNeverClaimedForUnresolvableApp() = runBlocking {
        val engine = ServiceLocator.controlEngine
        val result = engine.openApp("несуществующее_приложение_тест_${System.currentTimeMillis()}")

        val stages = result.proof.map { it.stage }
        assertFalse("Несуществующее приложение не должно быть VERIFIED", result.success)

        if (stages.contains(ProofStage.ACTION_PERFORMED)) {
            val performed = result.proof.first { it.stage == ProofStage.ACTION_PERFORMED }
            assertFalse("ACTION_PERFORMED не может быть OK для несуществующей цели",
                performed.status == StepStatus.OK)
        }
    }
}
