package com.svetlana.home.ai.providers

import com.svetlana.home.ai.AIBackend
import com.svetlana.home.ai.AIProvider
import com.svetlana.home.ai.AIResult
import com.svetlana.home.ai.ProviderCapabilities
import com.svetlana.home.server.PersonalServerManager

/**
 * PersonalServerProvider — ИИ на сервере пользователя (ТЗ §42).
 * Обёртка над PersonalServerManager для единого интерфейса AIProvider.
 */
class PersonalServerProvider(
    private val serverManager: PersonalServerManager
) : AIProvider {

    override val id: String = "personal-server"
    override val displayName: String = "Мой сервер Светланы"
    override val type: AIProvider.ProviderType = AIProvider.ProviderType.PERSONAL_SERVER
    override val backend: AIBackend = AIBackend.PERSONAL_SERVER

    override fun capabilities(): ProviderCapabilities {
        val caps = serverManager.config()
        // Vision заявляем только если сервер реально сообщил VLM-модель.
        // Аудит: нельзя заявлять capability, которого нет — это давало
        // ложный переход на серверный vision, который потом падал.
        val hasVlm = VLM_HINTS.any { hint ->
            serverManager.lastCapabilities().models.any { it.contains(hint, ignoreCase = true) }
        }
        return ProviderCapabilities(
            chat = caps.enabled,
            vision = hasVlm,
            embeddings = true,
            maxContext = 16384
        )
    }

    private val VLM_HINTS = listOf("vision", "vlm", "llava", "qwen-vl", "gemma-vl", "image")

    override fun isConfigured(): Boolean =
        serverManager.config().baseUrl.isNotBlank() && serverManager.config().enabled

    override fun isAvailable(): Boolean = isConfigured()

    override suspend fun chat(prompt: String, systemPrompt: String?): AIResult {
        if (!isConfigured()) {
            return AIResult(false, "Мой сервер не подключён. Подключите его в разделе «Мой сервер Светланы».", AIBackend.PERSONAL_SERVER)
        }
        val started = System.currentTimeMillis()
        val result = serverManager.inference(prompt)
            ?: return AIResult(false, "Сервер не ответил. Проверьте подключение.", AIBackend.PERSONAL_SERVER,
                latencyMs = System.currentTimeMillis() - started)
        return AIResult(true, result, AIBackend.PERSONAL_SERVER,
            latencyMs = System.currentTimeMillis() - started)
    }

    /**
     * Vision на сервере пользователя (аудит §9).
     *
     * Отправляет изображение на /vlm endpoint сервера. Серверная часть
     * (VLM-провайдер) реализуется отдельно — приложение только передаёт
     * данные и возвращает результат. Если сервер не сообщил VLM-модель
     * в capabilities, запрос отклоняется заранее, чтобы не отправлять
     * картинку впустую.
     */
    override suspend fun vision(prompt: String, imageBytes: ByteArray): AIResult {
        if (!capabilities().vision) {
            return AIResult(false,
                "На вашем сервере не обнаружена VLM-модель. Установите vision-модель на сервер.",
                AIBackend.PERSONAL_SERVER)
        }
        val started = System.currentTimeMillis()
        return try {
            val result = serverManager.visionInference(prompt, imageBytes)
            if (result != null) {
                AIResult(true, result, AIBackend.PERSONAL_SERVER,
                    latencyMs = System.currentTimeMillis() - started)
            } else {
                AIResult(false, "Сервер не обработал изображение", AIBackend.PERSONAL_SERVER,
                    latencyMs = System.currentTimeMillis() - started)
            }
        } catch (t: Throwable) {
            AIResult(false, "Ошибка vision на сервере: ${t.message}", AIBackend.PERSONAL_SERVER,
                latencyMs = System.currentTimeMillis() - started, error = t.message)
        }
    }

    override suspend fun testConnection(): AIResult {
        val ok = serverManager.healthCheck()
        return if (ok) AIResult(true, "Сервер доступен", AIBackend.PERSONAL_SERVER)
        else AIResult(false, "Сервер недоступен", AIBackend.PERSONAL_SERVER)
    }

    override fun redactedConfig(): String {
        val c = serverManager.config()
        return "${c.name} @ ${c.baseUrl} token=${if (serverManager.token().isNullOrEmpty()) "нет" else "скрыт"}"
    }
}
