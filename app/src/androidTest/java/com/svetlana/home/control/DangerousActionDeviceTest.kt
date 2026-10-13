package com.svetlana.home.control

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ServiceLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Опасные действия на устройстве (ТЗ §58, аудит п.27).
 *
 * Проверяем реальную блокирующую цепочку:
 *   «Света, отправь SMS Ивану»
 *     → ActionRouter.execute(action, confirmed=false)
 *     → riskOf == DANGEROUS
 *     → действие НЕ выполняется
 *     → requiresUserConfirmation = true
 *     → в истории записан запрос подтверждения
 *
 * Текст вопроса — на языке интерфейса (на CI-эмуляторе — английский).
 */
@RunWith(AndroidJUnit4::class)
class DangerousActionDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun smsWithoutConfirmation_isBlockedNotExecuted() {
        val router = ServiceLocator.actionRouter
        val action = SvetlanaAction.SendMessage("Иван", "тестовое сообщение")

        val result = runBlocking { router.execute(action, confirmed = false) }

        // Ключевое: действие НЕ выполнено
        assertFalse("Отправка SMS не должна выполняться без подтверждения",
            result.success)
        // Требуется подтверждение пользователя
        assertTrue("Должен быть выставлен requiresUserConfirmation",
            result.requiresUserConfirmation)
        // Сообщение содержит указание на подтверждение и точные параметры
        assertTrue("Ответ должен предлагать подтверждение: ${result.message}",
            result.message.contains("подтверд", ignoreCase = true) ||
                result.message.contains("confirm", ignoreCase = true))
        assertTrue("В вопросе должен быть адресат: ${result.message}",
            result.message.contains("Иван"))
    }

    @Test
    fun dangerousActionsClassifiedCorrectly() {
        val engine = ServiceLocator.controlEngine
        assertEquals("SMS должно быть DANGEROUS",
            ActionRisk.DANGEROUS, engine.riskOf(SvetlanaAction.SendMessage("Иван", "текст")))
        assertEquals("Звонок должен быть DANGEROUS",
            ActionRisk.DANGEROUS, engine.riskOf(SvetlanaAction.MakeCall("Иван")))
    }

    @Test
    fun englishCommandStillBlocked() {
        val router = ServiceLocator.actionRouter
        val result = runBlocking { router.route("call Mom") }
        assertTrue("Английская команда должна разобраться", result != null)
        assertFalse("Звонок не должен выполняться без подтверждения", result!!.success)
        assertTrue("Нужно подтверждение", result.requiresUserConfirmation)
    }

    @Test
    fun confirmationRequestRecordedInHistory() {
        val router = ServiceLocator.actionRouter
        val historyBefore = ServiceLocator.historyManager.all()
            .count { it.category == com.svetlana.home.memory.HistoryCategory.CONFIRMATIONS }

        runBlocking { router.execute(SvetlanaAction.MakeCall("Иван"), confirmed = false) }

        val historyAfter = ServiceLocator.historyManager.all()
            .count { it.category == com.svetlana.home.memory.HistoryCategory.CONFIRMATIONS }
        // Запрос подтверждения должен попасть в историю
        assertTrue("Запрос подтверждения должен быть записан в историю",
            historyAfter > historyBefore)
    }
}
