package com.svetlana.home.ai.providers

import android.content.Context
import com.svetlana.home.ai.AIBackend
import com.svetlana.home.ai.AIProvider
import com.svetlana.home.ai.AIResult
import com.svetlana.home.ai.ProviderCapabilities
import com.svetlana.home.ai.ProviderConfig
import com.svetlana.home.store.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible провайдер (ТЗ §37).
 *
 * Работает с любым endpoint, поддерживающим /v1/chat/completions:
 * OpenAI, Groq, Together, OpenRouter, vLLM, llama.cpp server и др.
 *
 * API Key хранится в SecureKeyStore (Android Keystore) и не попадает
 * ни в репозиторий, ни в логи, ни в аналитику.
 */
class OpenAiCompatibleProvider(
    private val context: Context,
    private val config: ProviderConfig
) : AIProvider {

    override val id: String get() = config.id
    override val displayName: String get() = config.name
    override val type: AIProvider.ProviderType = AIProvider.ProviderType.OPENAI_COMPATIBLE
    override val backend: AIBackend = AIBackend.EXTERNAL

    private val keyStore = SecureKeyStore(context)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun apiKey(): String? = keyStore.get(SecureKeyStore.PROVIDER_KEY_PREFIX + config.id)

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        chat = true,
        vision = config.model.contains("vision", ignoreCase = true) ||
                config.model.contains("gpt-4o", ignoreCase = true),
        embeddings = true,
        translation = true,
        maxContext = 8192
    )

    override fun isConfigured(): Boolean =
        config.baseUrl.isNotBlank() && apiKey()?.isNotBlank() == true && config.model.isNotBlank()

    override fun isAvailable(): Boolean = isConfigured()

    override suspend fun chat(prompt: String, systemPrompt: String?): AIResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext AIResult(false, "Провайдер не настроен: укажите endpoint, модель и API Key", AIBackend.EXTERNAL)
        }
        val started = System.currentTimeMillis()
        try {
            val payload = buildJsonObject {
                put("model", config.model)
                put("messages", buildJsonArray {
                    systemPrompt?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
                    add(buildJsonObject { put("role", "user"); put("content", prompt) })
                })
                put("temperature", 0.7)
                put("max_tokens", 512)
            }.toString()

            val response = post("${config.baseUrl.trimEnd('/')}/v1/chat/completions", payload)
                ?: return@withContext AIResult(false, "Нет ответа от провайдера", AIBackend.EXTERNAL,
                    latencyMs = System.currentTimeMillis() - started)

            val root = json.parseToJsonElement(response).jsonObject
            val content = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: return@withContext AIResult(false, "Пустой ответ провайдера", AIBackend.EXTERNAL)
            AIResult(true, content, AIBackend.EXTERNAL,
                latencyMs = System.currentTimeMillis() - started, modelName = config.model)
        } catch (t: Throwable) {
            AIResult(false, "Ошибка провайдера: ${t.message}", AIBackend.EXTERNAL,
                latencyMs = System.currentTimeMillis() - started, error = t.message)
        }
    }

    override suspend fun vision(prompt: String, imageBytes: ByteArray): AIResult =
        AIResult(false, "Vision-запросы требуют модели с поддержкой изображений", AIBackend.EXTERNAL)

    override suspend fun testConnection(): AIResult {
        if (!isConfigured()) return AIResult(false, "Провайдер не настроен", AIBackend.EXTERNAL)
        return chat("Ответь одним словом: работает.")
    }

    override fun redactedConfig(): String =
        "${config.name} @ ${config.baseUrl} model=${config.model} key=${if (apiKey().isNullOrEmpty()) "нет" else "скрыт"}"

    private fun post(url: String, payload: String): String? {
        val key = apiKey() ?: return null
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }
}
