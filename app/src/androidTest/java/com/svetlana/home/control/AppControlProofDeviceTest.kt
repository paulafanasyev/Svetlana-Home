package com.svetlana.home.control

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ProofStage
import com.svetlana.home.core.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AppControlEngine: доказательная цепочка запуска приложений (ТЗ §17, §19, §20,
 * аудит п.7).
 *
 * Ключевое: startActivity() != «приложение открыто». Раньше openApp()
 * выставлял ACTION_PERFORMED сразу после startActivity. Теперь используется
 * LaunchVerifier.awaitForeground(), который реально ждёт перехода.
 *
 * Цепочка: PLAN → TARGET_APP_IDENTIFIED → PERMISSION_CHECKED
 *   → ACTION_ATTEMPTED → ACTION_PERFORMED → RESULT_VERIFIED
 */
@RunWith(AndroidJUnit4::class)
class AppControlProofDeviceTest : SvetlanaDeviceTest() {

    private val engine by lazy { ServiceLocator.controlEngine }

    @Test
    fun openUnresolvableApp_failsHonestly() = runBlocking {
        // Несуществующее приложение не должно запускаться
        val result = engine.openApp("такогоприложениянет_${System.currentTimeMillis()}")

        assertFalse("Запуск несуществующего приложения должен FAIL", result.success)
        assertTrue("Должна быть proof chain", result.proof.isNotEmpty())
        println("PROOF=${result.proof.map { "${it.stage}=${it.status}" }}")
    }

    @Test
    fun openSettingsApp_producesFullProofChain() = runBlocking {
        // Настройки есть на любом Android-устройстве — безопасная цель для теста
        val result = engine.openApp("настройки")

        println("OPEN_SETTINGS_SUCCESS=${result.success}")
        println("PROOF=${result.proof.joinToString(" | ") { "${it.stage}=${it.status}" }}")

        // Цепочка должна содержать ключевые этапы
        val stages = result.proof.map { it.stage }
        assertTrue("Должен быть PLAN", stages.contains(ProofStage.PLAN))
        assertTrue("Должен быть TARGET_APP_IDENTIFIED",
            stages.contains(ProofStage.TARGET_APP_IDENTIFIED))

        // Если запуск успешен — ACTION_PERFORMED и RESULT_VERIFIED должны быть OK
        if (result.success) {
            val performed = result.proof.first { it.stage == ProofStage.ACTION_PERFORMED }
            val verified = result.proof.first { it.stage == ProofStage.RESULT_VERIFIED }
            assertTrue("При успехе ACTION_PERFORMED должен быть OK", performed.status.isOk())
            assertTrue("При успехе RESULT_VERIFIED должен быть OK", verified.status.isOk())
            assertTrue("Сообщение об успехе должно быть на русском", result.message.contains("открыто"))
        }
        // Возвращаемся на главный экран, чтобы не мешать другим тестам
        ServiceLocator.hands.pressHome()
    }

    @Test
    fun clickRequiresActiveHands() = runBlocking {
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
        val bmp = engine.takeScreenshot("Настройки")
        // Скриншот может требовать Hands/API — проверяем, что вызов не падает
        // и возвращает Bitmap при наличии доступа, либо null без него
        println("SCREENSHOT=${if (bmp != null) "OK ${bmp.width}x${bmp.height}" else "null"}")
    }

    private fun com.svetlana.home.core.StepStatus.isOk(): Boolean =
        this == com.svetlana.home.core.StepStatus.OK
}
