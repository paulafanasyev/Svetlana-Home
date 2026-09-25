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
        return ProviderCapabilities(
            chat = caps.enabled,
            vision = true,
            embeddings = true,
            translation = true,
            maxContext = 16384
        )
    }

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

    override suspend fun vision(prompt: String, imageBytes: ByteArray): AIResult =
        AIResult(false, "Vision на сервере требует настроенного VLM", AIBackend.PERSONAL_SERVER)

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
