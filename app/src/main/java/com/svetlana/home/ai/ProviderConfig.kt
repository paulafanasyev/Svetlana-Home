package com.svetlana.home.ai

import kotlinx.serialization.Serializable

/**
 * Конфигурация внешнего AI-провайдера (ТЗ §38).
 * API Key хранится отдельно — в SecureKeyStore.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val type: AIProvider.ProviderType = AIProvider.ProviderType.OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val model: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Известные OpenAI-compatible провайдеры, которые пользователь может добавить.
         * Список определён после исследования доступных API.
         */
        val presets: List<ProviderConfig> = listOf(
            ProviderConfig(
                id = "preset-openai", name = "OpenAI",
                baseUrl = "https://api.openai.com", model = "gpt-4o-mini"
            ),
            ProviderConfig(
                id = "preset-groq", name = "Groq",
                baseUrl = "https://api.groq.com/openai", model = "llama-3.3-70b-versatile"
            ),
            ProviderConfig(
                id = "preset-openrouter", name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api", model = "qwen/qwen-2.5-7b-instruct"
            ),
            ProviderConfig(
                id = "preset-together", name = "Together AI",
                baseUrl = "https://api.together.xyz", model = "Qwen/Qwen2.5-7B-Instruct-Turbo"
            ),
            ProviderConfig(
                id = "preset-deepinfra", name = "DeepInfra",
                baseUrl = "https://api.deepinfra.com", model = "Qwen/Qwen2.5-7B-Instruct"
            ),
            ProviderConfig(
                id = "preset-localllama", name = "Локальный llama.cpp сервер",
                baseUrl = "http://127.0.0.1:8080", model = "local"
            )
        )
    }
}
