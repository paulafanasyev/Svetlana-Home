package com.svetlana.home.ai.providers

import com.svetlana.home.ai.AIModel
import com.svetlana.home.ai.AIProvider
import com.svetlana.home.ai.AIResult
import com.svetlana.home.ai.AIBackend
import com.svetlana.home.ai.ProviderCapabilities
import com.svetlana.home.core.SvetlanaStatus

/**
 * LocalAIProvider — локальный ИИ на устройстве.
 *
 * Работает только если пользователь САМ установил модель (ТЗ §87).
 * Если модели нет — провайдер честно сообщает, что локальный ИИ не настроен,
 * и никаких скрытых загрузок не происходит.
 *
 * Реальный inference выполняется через подключаемый InferenceRuntime
 * (llama.cpp / onnxruntime). Если runtime недоступен в сборке, провайдер
 * сообщает об этом и не пытается симулировать результат.
 */
class LocalAIProvider(
    private val modelManager: com.svetlana.home.ai.LocalModelManager,
    private val registry: com.svetlana.home.ai.AIModelRegistry,
    private val runtime: InferenceRuntime?
) : AIProvider {

    override val id: String = "local"
    override val displayName: String = "Локальный ИИ"
    override val type: AIProvider.ProviderType = AIProvider.ProviderType.LOCAL
    override val backend: AIBackend = AIBackend.LOCAL

    override fun capabilities(): ProviderCapabilities =
        ProviderCapabilities(
            chat = isAvailable(),
            vision = false,
            embeddings = false,
            translation = false,
            maxContext = activeModel()?.context ?: 0
        )

    override fun isConfigured(): Boolean = modelManager.list().isNotEmpty()

    override fun isAvailable(): Boolean = activeModel() != null && runtime?.isReady() == true

    private fun activeModel(): AIModel? {
        val installed = modelManager.list().firstOrNull() ?: return null
        return registry.byId(installed.modelId)
    }

    override suspend fun chat(prompt: String, systemPrompt: String?): AIResult {
        val model = activeModel()
            ?: return AIResult(false, "Локальная модель не установлена. Я могу предложить совместимые варианты — скажите «Света, какой ИИ может работать на моём телефоне?».", AIBackend.LOCAL)
        val rt = runtime
            ?: return AIResult(false, "Локальный inference runtime недоступен в этой сборке. Модель установлена: ${model.name}.", AIBackend.LOCAL, modelName = model.name)

        val started = System.currentTimeMillis()
        return try {
            val output = rt.generate(model.id, prompt, maxTokens = 256)
            AIResult(true, output, AIBackend.LOCAL,
                latencyMs = System.currentTimeMillis() - started, modelName = model.name)
        } catch (t: Throwable) {
            AIResult(false, "Локальная модель не смогла ответить: ${t.message}", AIBackend.LOCAL,
                modelName = model.name, error = t.message)
        }
    }

    override suspend fun vision(prompt: String, imageBytes: ByteArray): AIResult =
        AIResult(false, "Локальные VLM недоступны в этой сборке", AIBackend.LOCAL)

    override suspend fun testConnection(): AIResult {
        val model = activeModel()
        return if (model == null) {
            AIResult(false, "Локальная модель не установлена — это нормальное состояние", AIBackend.LOCAL)
        } else if (runtime?.isReady() == true) {
            AIResult(true, "Локальный runtime готов. Модель: ${model.name}", AIBackend.LOCAL, modelName = model.name)
        } else {
            AIResult(false, "Модель установлена (${model.name}), runtime не подключён. Статус: ${SvetlanaStatus.NOT_PROVEN}", AIBackend.LOCAL)
        }
    }

    override fun redactedConfig(): String = "local://${activeModel()?.name ?: "none"}"
}

/**
 * Runtime локального inference. Подключается извне (llama.cpp / onnxruntime).
 * В этой сборке по умолчанию недоступен — приложение честно это сообщает.
 */
interface InferenceRuntime {
    fun isReady(): Boolean
    fun supportedModelIds(): List<String>
    fun generate(modelId: String, prompt: String, maxTokens: Int): String
}
