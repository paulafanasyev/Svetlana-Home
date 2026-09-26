package com.svetlana.home.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 (аудит п.1): если внешний ИИ выбран, но провайдер не настроен,
 * Светлана должна честно сказать, как его подключить, а не молча
 * подменять бэкенд (ТЗ §41, §51).
 */
@RunWith(AndroidJUnit4::class)
class UnconfiguredProviderChatDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun chatWithoutProvider_returnsHonestGuidance() = runBlocking {
        // Устанавливаем режим EXTERNAL без настроенного провайдера.
        ServiceLocator.settings.setAiMode(AIMode.EXTERNAL)
        val result = ServiceLocator.aiRouter.chat("Привет")

        // Запрос не должен завершаться успехом — провайдера нет.
        assertFalse("Не должно быть успеха без настроенного провайдера", result.success)

        // Ответ должен направлять пользователя к настройке, а не быть
        // пустым или вводить в заблуждение.
        val text = result.text
        assertTrue(
            "Ответ должен объяснить, как подключить ИИ: $text",
            text.contains("Внешний ИИ не настроен", ignoreCase = true) ||
            text.contains("Не выбран", ignoreCase = true) ||
            text.contains("не настроен", ignoreCase = true)
        )
        println("UNCONFIGURED_CHAT_REPLY=${text.take(120)}")
    }
}
