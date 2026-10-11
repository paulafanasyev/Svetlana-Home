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
 * LiteRtLmRuntime — локальный multimodal inference через Google AI Edge LiteRT-LM.
 *
 * Reference: https://github.com/google-ai-edge/LiteRT-LM (Apache-2.0)
 *
 * Безопасность: модель считается готовой только если её файл проходит проверку SHA-256 по реестру,
 * а перед загрузкой в Engine файл хешируется заново ([LocalModelManager.verifyIntegrity]).
 * Библиотека не скачивает модели (ТЗ §33).
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

    private fun chooseBackend(): Backend = Backend.CPU()

    override fun isReady(): Boolean {
        val installed = activeInstalled() ?: return false
        return File(installed.filePath).exists() && modelManager.verifyIntegrity(installed.modelId, fresh = false)
    }

    override fun isReadyFor(modelId: String): Boolean {
        val installed = modelManager.byId(modelId) ?: return false
        val model = registry.byId(modelId) ?: return false
        if (!isLitertlmModel(model)) return false
        return File(installed.filePath).exists() &&
            installed.filePath.endsWith(".litertlm", ignoreCase = true) &&
            modelManager.verifyIntegrity(modelId, fresh = false)
    }

    override fun supportedModelIds(): List<String> = try {
        registry.all()
            .filter { isLitertlmModel(it) && it.runtimeImplemented }
            .map { it.id }
    } catch (t: Throwable) {
        Log.w(TAG, "supportedModelIds: ${t.message}")
        emptyList()
    }

    private fun isLitertlmModel(model: com.svetlana.home.ai.AIModel): Boolean =
        model.architecture.equals(FORMAT_LITERTLM, ignoreCase = true) ||
            model.backend.equals(FORMAT_LITERTLM, ignoreCase = true)

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
        if (!modelManager.verifyIntegrity(modelId)) {
            Log.w(TAG, "Модель $modelId не прошла проверку контрольной суммы — не загружаю")
            return null
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
        val selected = activeModelId() ?: return null
        val model = registry.byId(selected) ?: return null
        if (!isLitertlmModel(model)) return null
        return modelManager.byId(selected)
    }

    companion object {
        private const val TAG = "LiteRtLmRuntime"
        private const val FORMAT_LITERTLM = "litertlm"
    }
}
