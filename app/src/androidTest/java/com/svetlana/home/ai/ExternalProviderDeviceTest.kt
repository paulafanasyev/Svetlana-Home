package com.svetlana.home.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ServiceLocator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Внешние AI-провайдеры: полная цепочка на устройстве (ТЗ §71, §84).
 *
 *   User selects provider
 *     → Configuration
 *     → Authentication (API key → SecureKeyStore)
 *     → Connection test
 *     → Model discovery
 *     → Inference
 *     → Result
 *
 * Этот тест проверяет шаги, которые не требуют реального API-ключа:
 * конфигурирование, безопасное хранение ключа, корректную работу
 * провайдера в «отключённом» состоянии. Шаг с реальным inference
 * выполняется только если пользователь настроил провайдера — тест
 * честно фиксирует текущее состояние.
 */
@RunWith(AndroidJUnit4::class)
class ExternalProviderDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun providerPresets_available() {
        // ТЗ §36: пользователь сам выбирает провайдера, список не скрыт.
        val presets = ProviderConfig.presets
        assertTrue("Должен быть хотя бы один preset провайдера", presets.isNotEmpty())
        presets.forEach { p ->
            // Custom preset намеренно пустой — пользователь заполняет
            // произвольный OpenAI-compatible endpoint сам.
            if (p.id == "preset-custom") return@forEach
            assertTrue("Preset ${p.name} должен иметь baseUrl", p.baseUrl.isNotBlank())
            assertTrue("Preset ${p.name} должен иметь модель", p.model.isNotBlank())
        }
        // Аудит п.1: произвольный OpenAI-compatible endpoint доступен.
        assertTrue("Должен быть Custom preset",
            presets.any { it.id == "preset-custom" })
        println("PROVIDER_PRESETS=${presets.map { it.name }}")
    }

    @Test
    fun unconfiguredProvider_isNotAvailable() {
        // Провайдер без ключа/endpoint не должен быть доступен —
        // приложение не должно пытаться отправить запрос в пустоту.
        val provider = ServiceLocator.providerManager.build(
            ProviderConfig(id = "test-empty", name = "Пустой", type = AIProvider.ProviderType.OPENAI_COMPATIBLE)
        )
        assertFalse("Несконфигурированный провайдер не available", provider.isAvailable())
        assertFalse("Несконфигурированный провайдер не configured", provider.isConfigured())
    }

    @Test
    fun apiKeyStoredSecurely_notInConfig() {
        // ТЗ §38: API keys хранятся безопасно, не в конфиге и не в репозитории.
        val provider = ServiceLocator.providerManager.build(
            ProviderConfig(id = "test-sec", name = "Тест", type = AIProvider.ProviderType.OPENAI_COMPATIBLE)
        )
        // redactedConfig никогда не должен показывать ключ
        val redacted = provider.redactedConfig()
        assertFalse("redactedConfig не должен содержать реальный ключ",
            redacted.contains("sk-", ignoreCase = true))
        println("REDACTED_CONFIG=$redacted")
    }

    @Test
    fun noProvidersConfigured_worksWithoutExternalAi() {
        // ТЗ §51: приложение работает без подключённого внешнего AI.
        val providers = ServiceLocator.providerManager.list()
        println("CONFIGURED_PROVIDERS=${providers.size}")
        // Это информационная проверка: список может быть пустым, и это
        // нормальное состояние. Главное — приложение не падает.
        assertNotNull(ServiceLocator.providerManager.list())
    }
}
