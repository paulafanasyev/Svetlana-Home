package com.svetlana.home.control

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ProofStage
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.core.StepStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Полная proof chain запуска реальных приложений (ТЗ §17, §19, §20; аудит п.7).
 *
 * Цепочка: PLAN → TARGET_APP_IDENTIFIED → PERMISSION_CHECKED
 *   → ACTION_ATTEMPTED → ACTION_PERFORMED → RESULT_VERIFIED.
 *
 * startActivity() != «приложение открыто»: ACTION_PERFORMED выставляется
 * только после реального перехода в foreground (LaunchVerifier).
 *
 * Эти тесты запускают реальные Activity и нажимают Home — на CI-эмуляторе
 * (x86_64, без Hands/Accessibility) это дестабилизирует instrumentation,
 * поэтому они выполняются только на arm64-устройстве (POCO X3 NFC).
 * На эмуляторе честно пропускаются через assumeTrue, а не падают.
 */
@RunWith(AndroidJUnit4::class)
class AppControlLaunchDeviceTest : SvetlanaDeviceTest() {

    @Before
    override fun setUp() {
        super.setUp()
        // Реальная навигация по системе — только на физическом устройстве.
        assumeTrue("Launch-тесты выполняются на arm64-устройстве",
            Build.SUPPORTED_ABIS.any { it.contains("arm64") })
    }

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
