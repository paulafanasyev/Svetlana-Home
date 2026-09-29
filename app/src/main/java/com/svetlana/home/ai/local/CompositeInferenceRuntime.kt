package com.svetlana.home.ai.local

import android.util.Log
import com.svetlana.home.ai.providers.InferenceMetrics
import com.svetlana.home.ai.providers.InferenceRuntime

/**
 * CompositeInferenceRuntime — диспетчер между несколькими локальными
 * runtime'ами (аудит §10-12, P0-1).
 *
 * Раньше проект имел ровно одну реализацию (LlamaCppRuntime), и GGUF был
 * единственным форматом. Теперь добавлен LiteRT-LM (.litertlm) — с
 * мультимодальностью. Выбор runtime'а определяется форматом выбранной
 * модели, а не глобальным флагом.
 *
 * Контракт: если подходящий runtime не может обработать модель, возвращается
 * null — LocalAIProvider честно сообщает «runtime не готов», а не подменяет
 * ответ.
 *
 * ТЗ §33: ни один runtime не скачивает модели. Всё ставится пользователем.
 */
class CompositeInferenceRuntime(
    private val llama: LlamaCppRuntime,
    private val litertlm: LiteRtLmRuntime
) : InferenceRuntime {

    override fun isReady(): Boolean = llama.isReady() || litertlm.isReady()

    override fun isReadyFor(modelId: String): Boolean =
        runtimeFor(modelId)?.isReadyFor(modelId) == true

    /**
     * Поддерживаемые id = объединение по форматам.
     */
    override fun supportedModelIds(): List<String> =
        (llama.supportedModelIds() + litertlm.supportedModelIds()).distinct()

    /**
     * Выбор runtime'а по формату активной модели.
     *
     * Это намеренно зависит от выбранной модели, а не от глобального
     * «лучшего runtime»: пользователь мог установить .litertlm для vision
     * и GGUF для текста — каждый должен идти в свой runtime.
     */
    private fun runtimeFor(modelId: String): InferenceRuntime? {
        // Сначала спрашиваем сами runtime'ы — они знают свои форматы.
        return when {
            litertlm.supportedModelIds().contains(modelId) -> litertlm
            llama.supportedModelIds().contains(modelId) -> llama
            else -> null
        }
    }

    override fun vision(
        modelId: String,
        prompt: String,
        imageBytes: ByteArray,
        maxTokens: Int
    ): String? {
        return try {
            runtimeFor(modelId)?.vision(modelId, prompt, imageBytes, maxTokens)
        } catch (t: Throwable) {
            Log.w(TAG, "composite vision: ${t.message}")
            null
        }
    }

    override fun supportsVision(modelId: String): Boolean =
        litertlm.supportedModelIds().contains(modelId) && litertlm.supportsVision(modelId)

    override fun metrics(modelId: String): InferenceMetrics? =
        runtimeFor(modelId)?.metrics(modelId)

    override fun generate(modelId: String, prompt: String, maxTokens: Int): String {
        val runtime = runtimeFor(modelId)
        return runtime?.generate(modelId, prompt, maxTokens)
            ?: run {
                Log.w(TAG, "Нет runtime для модели $modelId")
                ""
            }
    }

    fun unloadAll() {
        llama.unload()
        litertlm.unload()
    }

    companion object {
        private const val TAG = "CompositeRuntime"
    }
}
