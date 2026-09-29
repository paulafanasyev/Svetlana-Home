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
    private val runtime: InferenceRuntime?,
    /**
     * Идентификатор модели, которую ПОЛЬЗОВАТЕЛЬ выбрал основной
     * («Сделать основной»). Без этого провайдер брал бы первую установленную
     * модель — это баг: пользователь выбрал B, установлены A и B, а runtime
     * грузил A (аудит п.6).
     */
    private val activeModelId: () -> String? = { null }
) : AIProvider {

    override val id: String = "local"
    override val displayName: String = "Локальный ИИ"
    override val type: AIProvider.ProviderType = AIProvider.ProviderType.LOCAL
    override val backend: AIBackend = AIBackend.LOCAL

    override fun capabilities(): ProviderCapabilities =
        ProviderCapabilities(
            chat = isAvailable(),
            // Аудит §12: локальная vision реальна, если активная модель
            // сама сообщает vision-модальность (Capabilities.inputModalities).
            vision = supportsVision(),
            embeddings = false,
            maxContext = activeModel()?.context ?: 0
        )

    override fun isConfigured(): Boolean = modelManager.list().isNotEmpty()

    override fun isAvailable(): Boolean {
        val model = activeModel() ?: return false
        return runtime?.isReadyFor(model.id) == true
    }

    private fun activeModel(): AIModel? {
        // Пользователь мог явно выбрать основную модель («Сделать основной»).
        // Только её и используем. Если выбор не сделан — берём первую
        // установленную (это её нормальное значение по умолчанию).
        val selected = activeModelId()
        val installed = selected
            ?.let { modelManager.byId(it) }
            ?: modelManager.list().firstOrNull()
            ?: return null
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
            if (output.isBlank()) {
                AIResult(false, "Локальный runtime не вернул ответа", AIBackend.LOCAL,
                    latencyMs = System.currentTimeMillis() - started, modelName = model.name)
            } else {
                AIResult(true, output, AIBackend.LOCAL,
                    latencyMs = System.currentTimeMillis() - started, modelName = model.name)
            }
        } catch (t: Throwable) {
            AIResult(false, "Локальная модель не смогла ответить: ${t.message}", AIBackend.LOCAL,
                modelName = model.name, error = t.message)
        }
    }

    /**
     * Аудит §12: on-device vision. Изображение обрабатывается локальной
     * мультимодальной моделью (.litertlm) и НЕ покидает устройство —
     * это единственный путь vision в режиме LOCAL_ONLY.
     *
     * Раньше всегда возвращался отказ — Local Vision был NOT PROVEN.
     */
    override suspend fun vision(prompt: String, imageBytes: ByteArray): AIResult {
        val model = activeModel()
            ?: return AIResult(false, "Локальная модель не установлена", AIBackend.LOCAL)
        // Do not pre-reject a cold LiteRT-LM model: its real
        // Capabilities.inputModalities() is discovered during first load.
        val result = visionInference(prompt, imageBytes)
        return if (result != null) {
            AIResult(true, result, AIBackend.LOCAL, modelName = model.name)
        } else {
            AIResult(false, "Локальный vision inference не выполнен", AIBackend.LOCAL, modelName = model.name)
        }
    }

    override suspend fun testConnection(): AIResult {
        val model = activeModel()
        return if (model == null) {
            AIResult(false, "Локальная модель не установлена — это нормальное состояние", AIBackend.LOCAL)
        } else if (runtime?.isReadyFor(model.id) == true) {
            AIResult(true, "Локальный runtime готов. Модель: ${model.name}", AIBackend.LOCAL, modelName = model.name)
        } else {
            val reason = when {
                runtime == null -> "runtime не подключён к этой сборке"
                !isNativeReady() -> "нативный llama.cpp недоступен на этом устройстве (нужен arm64-v8a)"
                else -> "модель установлена, но не загружена"
            }
            AIResult(false, "Модель установлена (${model.name}), $reason. Статус: ${SvetlanaStatus.NOT_PROVEN}",
                AIBackend.LOCAL, modelName = model.name)
        }
    }

    private fun isNativeReady(): Boolean {
        return try {
            val model = activeModel() ?: return false
            runtime?.isReadyFor(model.id) == true
        } catch (t: Throwable) {
            false
        }
    }

    override fun redactedConfig(): String = "local://${activeModel()?.name ?: "none"}"

    /**
     * ТЗ §48: «Света, какой ИИ сейчас отвечает?» — локальный бэкенд сообщает
     * фактическое состояние, а не красивую заглушку.
     */
    fun describeBackend(): String {
        val model = activeModel()
        return when {
            model == null -> "Локальная модель не установлена."
            runtime?.isReadyFor(model.id) == true ->
                "Сейчас используется локальная модель на устройстве: ${model.name}."
            else -> "Локальная модель установлена (${model.name}), но runtime не готов."
        }
    }

    /**
     * ТЗ §34: «Остановить» — выгружает модель из памяти.
     */
    fun unload() {
        (runtime as? com.svetlana.home.ai.local.CompositeInferenceRuntime)?.unloadAll()
        (runtime as? com.svetlana.home.ai.local.LlamaCppRuntime)?.unload()
        (runtime as? com.svetlana.home.ai.local.LiteRtLmRuntime)?.unload()
    }

    companion object {
        private const val TAG = "LocalAIProvider"
    }

    /**
     * Аудит §12: on-device vision (LOCAL_ONLY). Изображение обрабатывается
     * локальной мультимодальной моделью и не покидает устройство.
     *
     * @return описание или null, если локальная модель не поддерживает vision.
     */
    fun visionInference(prompt: String, imageBytes: ByteArray): String? {
        val model = activeModel() ?: return null
        val rt = runtime ?: return null
        if (!rt.isReadyFor(model.id)) return null
        return try {
            rt.vision(model.id, prompt, imageBytes, maxTokens = 256)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "on-device vision не удался: ${t.message}")
            null
        }
    }

    /**
     * Аудит §12: поддерживает ли активная локальная модель vision.
     * Решает сама модель (Capabilities.inputModalities), а не мы.
     */
    fun supportsVision(): Boolean {
        val model = activeModel() ?: return false
        val rt = runtime ?: return false
        return rt.supportsVision(model.id)
    }
}

/**
 * Runtime локального inference. Реализация — LlamaCppRuntime (llama.cpp, GGUF).
 * Интерфейс оставлен, чтобы можно было подключить и другие runtime'ы
 * (onnxruntime для STT/TTS и т.д.) без переделки провайдера.
 */
interface InferenceRuntime {
    fun isReady(): Boolean
    fun supportedModelIds(): List<String>

    /**
     * Readiness for one concrete model. Implementations may expose several
     * runtimes in one process, so global isReady() is not enough.
     */
    fun isReadyFor(modelId: String): Boolean =
        isReady() && supportedModelIds().contains(modelId)
    fun generate(modelId: String, prompt: String, maxTokens: Int): String

    /**
     * Multimodal inference: текст + изображение (аудит §12).
     *
     * Реализации без vision возвращают null — это честный отказ,
     * а не text-only подмена (которая была главным багом Vision).
     */
    fun vision(modelId: String, prompt: String, imageBytes: ByteArray, maxTokens: Int): String? = null

    /** Поддерживает ли runtime vision для данной модели. */
    /** Поддерживает ли runtime vision для данной модели. */
    fun supportsVision(modelId: String): Boolean = false

    /**
     * Последние фактические inference-метрики конкретной модели.
     * Runtime может не уметь измерять скорость — тогда возвращается null,
     * но сам inference по-прежнему может быть подтверждён непустым ответом.
     */
    fun metrics(modelId: String): InferenceMetrics? = null
}

data class InferenceMetrics(
    val firstTokenMs: Long = 0L,
    val tokensPerSecond: Double = 0.0
)
