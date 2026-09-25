package com.svetlana.home.ai.local

import android.content.Context
import android.util.Log
import com.svetlana.home.ai.AIModelRegistry
import com.svetlana.home.ai.LocalModelManager
import com.svetlana.home.ai.providers.InferenceRuntime
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaException
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * LlamaCppRuntime — РЕАЛЬНЫЙ on-device inference runtime на базе llama.cpp.
 *
 * Это единственная реализация InferenceRuntime в проекте. Она:
 *  - работает с GGUF-моделями, которые пользователь установил сам (ТЗ §87);
 *  - загружает модель в нативную память только по явному запросу;
 *  - никогда не скачивает модели сама (за это отвечает LocalModelManager и
 *    только после решения пользователя);
 *  - честно сообщает о состоянии, вместо того чтобы симулировать результат.
 *
 * Потокобезопасность: LlamaModel не потокобезопасен, поэтому все вызовы
 * сериализованы через synchronized. Один активный сеанс одновременно.
 */
class LlamaCppRuntime(
    private val context: Context,
    private val modelManager: LocalModelManager,
    private val registry: AIModelRegistry
) : InferenceRuntime {

    @Volatile
    private var loaded: LlamaModel? = null

    @Volatile
    private var loadedModelId: String? = null

    private val lock = Any()

    init {
        // Нативные библиотеки загружаются AAR автоматически при первом обращении.
        Log.i(TAG, "Llama.cpp runtime инициализирован. System: ${trySystemInfo()}")
    }

    private fun trySystemInfo(): String = try {
        Llama.getSystemInfo()
    } catch (t: Throwable) {
        "unavailable: ${t.message}"
    }

    /**
     * Runtime считается готовым, если установлена хотя бы одна совместимая
     * модель И нативный слой llama.cpp доступен в этой сборке.
     */
    override fun isReady(): Boolean = activeInstalled() != null && isNativeAvailable()

    override fun supportedModelIds(): List<String> = try {
        registry.all().filter { it.backend.equals("llama.cpp", ignoreCase = true) }
            .map { it.id }
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Синхронная генерация. ВАЖНО: вызов блокирующий — llama.cpp работает на
     * Dispatchers.Default внутри, поэтому runBlocking здесь безопасен только
     * если вызывающий уже не в main-потоке. AppControlEngine/AIRouter вызывает
     * это из корутины на фоновом потоке.
     */
    override fun generate(modelId: String, prompt: String, maxTokens: Int): String = synchronized(lock) {
        val installed = modelManager.byId(modelId)
            ?: throw IllegalStateException("Модель $modelId не установлена")
        val file = File(installed.filePath)
        if (!file.exists() || file.length() == 0L) {
            throw IllegalStateException("Файл модели отсутствует или пуст: ${file.absolutePath}")
        }
        ensureLoaded(installed.modelId, file)
        val model = loaded ?: throw IllegalStateException("Модель не загружена")

        val started = System.currentTimeMillis()
        try {
            val result = runBlocking {
                Llama.complete(model, prompt, systemPrompt = DEFAULT_SYSTEM, maxTokens = maxTokens)
            }
            lastTokensPerSecond = result.tokensPerSecond.toDouble()
            lastLatencyMs = System.currentTimeMillis() - started
            Log.i(TAG, "Генерация завершена: ${result.tokensGenerated} токенов, " +
                "${result.tokensPerSecond} ток/с, задержка ${lastLatencyMs}мс")
            result.text
        } catch (e: LlamaException.InferenceFailed) {
            // Модель могла быть повреждена — выгружаем, чтобы следующий вызов
            // попробовал загрузить заново.
            releaseInternal()
            throw IllegalStateException("Inference не удался: ${e.message}", e)
        }
    }

    @Volatile
    private var lastTokensPerSecond: Double = 0.0

    @Volatile
    private var lastLatencyMs: Long = 0L

    fun lastTokensPerSecond(): Double = lastTokensPerSecond
    fun lastLatencyMs(): Long = lastLatencyMs

    private fun ensureLoaded(modelId: String, file: File) {
        if (loaded != null && loadedModelId == modelId) return
        releaseInternal()
        val config = registry.byId(modelId)?.let { model ->
            LlamaConfig(
                contextSize = model.context.coerceAtMost(MAX_CONTEXT),
                threads = optimalThreads(),
                gpuLayers = 0, // CPU-only сборка: GPU offload недоступен
                temperature = 0.7f,
                topP = 0.9f,
                topK = 40,
                seed = -1
            )
        } ?: LlamaConfig(threads = optimalThreads())

        loaded = runBlocking {
            Llama.loadModel(file.absolutePath, config)
        }
        loadedModelId = modelId
        Log.i(TAG, "Модель загружена: $modelId (${file.length() / 1024 / 1024}МБ)")
    }

    private fun releaseInternal() {
        loaded?.let { model ->
            try { Llama.releaseModel(model) } catch (t: Throwable) {
                Log.w(TAG, "Не удалось освободить модель", t)
            }
        }
        loaded = null
        loadedModelId = null
    }

    /**
     * Явная выгрузка модели — вызывается из Model Manager («Остановить»).
     */
    fun unload() = synchronized(lock) {
        releaseInternal()
        Log.i(TAG, "Модель выгружена пользователем")
    }

    private fun activeInstalled() = modelManager.list().firstOrNull()

    private fun optimalThreads(): Int = try {
        val cores = Runtime.getRuntime().availableProcessors()
        (cores - 1).coerceIn(2, 8)
    } catch (t: Throwable) { 4 }

    /**
     * Проверка доступности нативного слоя без загрузки модели.
     * Если llama.cpp не собран для текущего ABI — вернёт false.
     */
    private fun isNativeAvailable(): Boolean = try {
        Llama.getSystemInfo().isNotBlank()
    } catch (t: UnsatisfiedLinkError) {
        Log.w(TAG, "Нативный llama.cpp недоступен на этом ABI", t)
        false
    } catch (t: Throwable) {
        Log.w(TAG, "Не удалось проверить нативный слой", t)
        false
    }

    companion object {
        private const val TAG = "LlamaCppRuntime"
        private const val MAX_CONTEXT = 4096
        private const val DEFAULT_SYSTEM = "Ты — Светлана, персональный ИИ-ассистент на Android. Отвечай на русском языке, кратко и по делу."
    }
}
