package com.svetlana.home.ai.local

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.svetlana.home.ai.AIModelRegistry
import com.svetlana.home.ai.InstalledModel
import com.svetlana.home.ai.LocalModelManager
import com.svetlana.home.ai.providers.InferenceRuntime
import java.io.File

/**
 * LiteRtLmRuntime — локальный multimodal inference через Google AI Edge
 * LiteRT-LM (аудит §10-12, P0-1).
 *
 * Reference: https://github.com/google-ai-edge/LiteRT-LM (Apache-2.0)
 * API: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
 *
 * Отличия от [LlamaCppRuntime]:
 *  - multimodal: изображение РЕАЛЬНО передаётся в модель (Content.ImageBytes),
 *    а не игнорируется;
 *  - vision-поддержка определяется самой моделью через
 *    [Capabilities.inputModalities], а не декларируется нами;
 *  - CPU / GPU / NPU backends.
 *
 * llama.cpp остаётся text-fallback для GGUF-моделей.
 *
 * Принцип ТЗ §33: библиотека не скачивает модели. Пользователь сам
 * выбирает и скачивает .litertlm файл.
 */
class LiteRtLmRuntime(
    private val context: Context,
    private val modelManager: LocalModelManager,
    private val registry: AIModelRegistry,
    private val activeModelId: () -> String? = { null }
) : InferenceRuntime {

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var loadedModelId: String? = null

    @Volatile
    private var cachedVisionSupport: Boolean = false

    private val lock = Any()

    /**
     * Backend: CPU надёжен везде. GPU требует нативные библиотеки
     * (см. docs/local-ai.md). После device-проверки можно включить GPU.
     */
    private fun chooseBackend(): Backend = Backend.CPU()

    override fun isReady(): Boolean {
        val installed = activeInstalled() ?: return false
        return File(installed.filePath).exists()
    }

    /**
     * Модели, которые этот runtime может загрузить.
     *
     * Критерий — LiteRT-совместимая архитектура/квантование (.litertlm).
     * Реальную поддержку vision сообщает сама модель
     * (Capabilities.inputModalities) — см. supportsVision().
     */
    override fun supportedModelIds(): List<String> = try {
        registry.all()
            .filter { isLitertlmModel(it) }
            .map { it.id }
    } catch (t: Throwable) {
        Log.w(TAG, "supportedModelIds: ${t.message}")
        emptyList()
    }

    /**
     * Признак LiteRT-модели: architecture/backend litertlm.
     */
    private fun isLitertlmModel(model: com.svetlana.home.ai.AIModel): Boolean =
        model.architecture.equals(FORMAT_LITERTLM, ignoreCase = true) ||
            model.backend.equals(FORMAT_LITERTLM, ignoreCase = true)

    /**
     * Vision-поддержка определяется самой моделью (аудит §12) —
     * [Capabilities.inputModalities].vision возвращает реальное
     * значение, а не наш оптимистичный default.
     */
    override fun supportsVision(modelId: String): Boolean {
        if (loadedModelId != modelId) return false
        return cachedVisionSupport
    }

    override fun generate(modelId: String, prompt: String, maxTokens: Int): String = synchronized(lock) {
        val conversation = ensureConversation(modelId) ?: return ""
        try {
            val response = conversation.sendMessage(prompt)
            response.toText()
        } catch (t: Throwable) {
            Log.w(TAG, "generate не удался: ${t.message}")
            ""
        }
    }

    /**
     * Multimodal inference: текст + JPEG (аудит §12).
     *
     * Главное: изображение РЕАЛЬНО передаётся в модель через
     * Content.ImageBytes. Это и было ядром BLOCKED Vision —
     * раньше вызывался text-only chat.
     */
    override fun vision(
        modelId: String,
        prompt: String,
        imageBytes: ByteArray,
        maxTokens: Int
    ): String? = synchronized(lock) {
        val conversation = ensureConversation(modelId) ?: return null
        if (!cachedVisionSupport) {
            Log.w(TAG, "Модель $modelId не поддерживает vision input")
            return null
        }
        try {
            val response = conversation.sendMessage(
                com.google.ai.edge.litertlm.Contents.of(
                    Content.Text(prompt),
                    Content.ImageBytes(imageBytes)
                )
            )
            response.toText()
        } catch (t: Throwable) {
            Log.w(TAG, "vision inference не удался: ${t.message}")
            null
        }
    }

    /**
     * Стриминг ответа — для мгновенного голосового отклика.
     */
    fun streamGenerate(modelId: String, prompt: String): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow {
            val conversation = ensureConversation(modelId) ?: return@flow
            try {
                conversation.sendMessageAsync(prompt).collect { message ->
                    message.toText().takeIf { it.isNotEmpty() }?.let { emit(it) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "streamGenerate не удался: ${t.message}")
            }
        }

    private fun ensureConversation(modelId: String): com.google.ai.edge.litertlm.Conversation? {
        loadedModelId?.let { loaded ->
            if (loaded == modelId && engine != null) {
                return engine?.createConversation()
            }
        }
        val installed = modelManager.byId(modelId)
            ?: throw IllegalStateException("Модель $modelId не установлена")
        val file = File(installed.filePath)
        if (!file.exists()) {
            throw IllegalStateException("Файл модели не найден: ${installed.filePath}")
        }
        releaseInternal()
        return try {
            val config = EngineConfig(
                modelPath = file.absolutePath,
                backend = chooseBackend(),
                cacheDir = context.cacheDir.absolutePath
            )
            val engine = Engine(config)
            engine.initialize()
            this.engine = engine
            this.loadedModelId = modelId
            // Реальные модальности модели — главный источник правды.
            this.cachedVisionSupport = try {
                com.google.ai.edge.litertlm.Capabilities(file.absolutePath).use { caps ->
                    caps.inputModalities().vision
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось получить модальности модели: ${t.message}")
                false
            }
            Log.i(TAG, "Модель $modelId загружена, vision=$cachedVisionSupport")
            engine.createConversation()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось загрузить модель $modelId: ${t.message}")
            null
        }
    }

    /** Извлекает текст из Message (у Message нет прямого .text). */
    private fun Message.toText(): String {
        return try {
            contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
        } catch (t: Throwable) {
            Log.w(TAG, "toText: ${t.message}")
            ""
        }
    }

    fun unload() = synchronized(lock) {
        releaseInternal()
    }

    private fun releaseInternal() {
        try {
            engine?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Ошибка закрытия engine: ${t.message}")
        }
        engine = null
        loadedModelId = null
        cachedVisionSupport = false
    }

    private fun activeInstalled(): InstalledModel? {
        val all = modelManager.list()
        if (all.isEmpty()) return null
        val selected = activeModelId()
        return all.firstOrNull { it.modelId == selected } ?: all.first()
    }

    companion object {
        private const val TAG = "LiteRtLmRuntime"
        private const val FORMAT_LITERTLM = "litertlm"
    }
}
