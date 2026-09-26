package com.svetlana.home.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.memory.MemoryMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ТЗ §50, §63: LOCAL_ONLY режим и offline-поведение.
 *
 * Ключевое требование аудита (п.21): доказать отсутствие
 * непредусмотренного fallback — данные не должны уходить наружу,
 * когда режим запрещает передачу.
 */
@RunWith(AndroidJUnit4::class)
class LocalOnlyDeviceTest {

    @Before
    fun setUp() {
        ServiceLocator.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }

    @Test
    fun localOnly_blocksEveryExternalBackend() {
        val backends = listOf(AIBackend.EXTERNAL, AIBackend.PERSONAL_SERVER)

        for (backend in backends) {
            for (type in PrivacyDataType.values()) {
                val decision = PrivacyPolicy.decide(type, backend, AIMode.LOCAL_ONLY)
                assertFalse("LOCAL_ONLY должен запрещать $type → $backend", decision.allowed)
            }
        }
    }

    @Test
    fun localOnly_allowsLocalBackend() {
        for (type in PrivacyDataType.values()) {
            val decision = PrivacyPolicy.decide(type, AIBackend.LOCAL, AIMode.LOCAL_ONLY)
            assertTrue("LOCAL_ONLY должен разрешать локальную обработку $type", decision.allowed)
        }
    }

    @Test
    fun localOnly_blockedMessage_isUserFacing() {
        val msg = PrivacyPolicy.localOnlyBlockedMessage()
        assertTrue("Сообщение блокировки должно быть непустым", msg.isNotEmpty())
    }

    @Test
    fun privacyRouter_respectsLocalOnlySetting() = runBlocking {
        // Включаем LOCAL_ONLY в реальных настройках устройства.
        ServiceLocator.settings.setAiMode(AIMode.LOCAL_ONLY)
        val mode = ServiceLocator.settings.aiMode.first()

        assertEquals("Настройка должна сохраниться", AIMode.LOCAL_ONLY.name, mode)

        val canSendText = ServiceLocator.privacyRouter.canSend(PrivacyDataType.TEXT, AIBackend.EXTERNAL)
        assertFalse("Текст не должен уходить внешнему провайдеру в LOCAL_ONLY", canSendText.allowed)

        val canSendScreen = ServiceLocator.privacyRouter.canSend(PrivacyDataType.SCREENSHOT, AIBackend.PERSONAL_SERVER)
        assertFalse("Скриншот не должен уходить на сервер в LOCAL_ONLY", canSendScreen.allowed)
    }

    @Test
    fun localOnly_restoringModeReenablesSend() = runBlocking {
        // Доказательство, что блокировка управляется настройкой, а не
        // захардкожена навсегда.
        ServiceLocator.settings.setAiMode(AIMode.LOCAL_ONLY)
        val blocked = ServiceLocator.privacyRouter.canSend(PrivacyDataType.TEXT, AIBackend.EXTERNAL)

        ServiceLocator.settings.setAiMode(AIMode.EXTERNAL)
        val allowed = ServiceLocator.privacyRouter.canSend(PrivacyDataType.TEXT, AIBackend.EXTERNAL)

        assertFalse("LOCAL_ONLY блокирует", blocked.allowed)
        assertTrue("EXTERNAL режим разрешает текст", allowed.allowed)
    }

    @Test
    fun historyNeverSentToExternalAutomatically() {
        // ТЗ §61: персональная память не уходит внешнему AI автоматически.
        val decision = PrivacyPolicy.decide(
            PrivacyDataType.HISTORY, AIBackend.EXTERNAL, AIMode.EXTERNAL, MemoryMode.LOCAL)
        assertFalse("История не должна автоматически уходить внешнему AI", decision.allowed)
    }
}
