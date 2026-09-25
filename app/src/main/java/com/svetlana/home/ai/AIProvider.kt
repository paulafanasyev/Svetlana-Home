package com.svetlana.home.ai

import kotlinx.serialization.Serializable

/**
 * Режимы работы AI-маршрутизатора (ТЗ §40).
 * Выбор пользователя не переписывается автоматически при ошибке (ТЗ §41).
 */
@Serializable
enum class AIMode(val label: String) {
    LOCAL_ONLY("Только устройство"),
    LOCAL_FIRST("Локальный в приоритете"),
    AUTO("Автоматический"),
    MY_SERVER("Мой сервер"),
    EXTERNAL("Внешний провайдер");

    companion object {
        fun fromName(name: String?): AIMode? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Тип бэкенда, на котором реально выполняется запрос.
 * Показывается пользователю в ответе «какой ИИ сейчас отвечает».
 */
@Serializable
enum class AIBackend(val humanReadable: String) {
    LOCAL("Сейчас используется локальная модель на устройстве."),
    PERSONAL_SERVER("Сейчас используется ваш удалённый сервер."),
    EXTERNAL("Сейчас используется внешний AI-провайдер."),
    HYBRID("Сейчас используется гибридный режим: предобработка на устройстве, тяжёлый вывод на сервере."),
    NONE("Сейчас активный AI-провайдер не выбран.");

    companion object {
        fun fromName(name: String?): AIBackend? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Результат inference.
 */
data class AIResult(
    val success: Boolean,
    val text: String,
    val backend: AIBackend = AIBackend.NONE,
    val latencyMs: Long = 0L,
    val modelName: String? = null,
    val error: String? = null
)

/**
 * Возможности провайдера.
 */
data class ProviderCapabilities(
    val chat: Boolean,
    val vision: Boolean,
    val embeddings: Boolean,
    val translation: Boolean,
    val maxContext: Int
)

/**
 * AIProvider — абстракция над любым источником ИИ (ТЗ §37).
 * Каждый провайдер — отдельная конфигурация.
 */
interface AIProvider {
    val id: String
    val displayName: String
    val type: ProviderType
    val backend: AIBackend

    @Serializable
    enum class ProviderType {
        LOCAL, OPENAI_COMPATIBLE, PERSONAL_SERVER, ANTHROPIC_COMPATIBLE, CUSTOM
    }

    fun capabilities(): ProviderCapabilities
    fun isConfigured(): Boolean
    fun isAvailable(): Boolean

    suspend fun chat(prompt: String, systemPrompt: String? = null): AIResult
    suspend fun vision(prompt: String, imageBytes: ByteArray): AIResult
    suspend fun testConnection(): AIResult

    /** Секретные данные не логируются и не покидают устройство в открытом виде. */
    fun redactedConfig(): String
}

/**
 * Куда маршрутизировать задачу (ТЗ §47).
 */
enum class RouteDecision { LOCAL, REMOTE, HYBRID, NONE }

/**
 * Тип данных для Privacy Router (ТЗ §49).
 */
enum class PrivacyDataType {
    TEXT, SCREENSHOT, UI_TREE, IMAGE, AUDIO, HISTORY, DOCUMENTS
}
