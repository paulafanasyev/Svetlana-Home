package com.svetlana.home.ai.providers

import android.content.Context
import com.svetlana.home.ai.AIBackend
import com.svetlana.home.ai.AIProvider
import com.svetlana.home.ai.AIResult
import com.svetlana.home.ai.ProviderCapabilities
import com.svetlana.home.ai.ProviderConfig
import com.svetlana.home.store.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    private val keyStore = SecureKeyStore.create(context)
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
        maxContext = 8192
    )

    /**
     * Отдельный клиент для стриминга: длинный readTimeout, чтобы долго
     * живущее SSE-соединение не закрывалось посреди генерации.
     */
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /**
     * Стриминг ответа через SSE (data: chunks).
     *
     * Требование: голос начинает говорить ответ сразу, не дожидаясь
     * полного завершения генерации. Дельты отдаются по мере поступления.
     */
    override fun supportsStreaming(): Boolean = true

    override fun chatStream(prompt: String, systemPrompt: String?): Flow<String> = flow {
        if (!isConfigured()) return@flow
        val payload = buildJsonObject {
            put("model", config.model)
            put("messages", buildJsonArray {
                systemPrompt?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            })
            put("temperature", 0.7)
            put("max_tokens", 512)
            put("stream", true)
        }.toString()

        val key = apiKey() ?: return@flow
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${normalizedEndpoint()}/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        try {
            streamClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@flow
                val source = response.body?.source() ?: return@flow
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val delta = parseDelta(data) ?: continue
                    if (delta.isNotEmpty()) emit(delta)
                }
            }
        } catch (t: Throwable) {
            // Стриминг оборвался — накопленное уже отдано.
        }
    }

    private fun parseDelta(data: String): String? = try {
        json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content
    } catch (t: Throwable) { null }

    /**
     * Нормализация endpoint (аудит п.3 — «тест не проходит для рабочих ключей»).
     *
     * Пользователь может ввести как базу «https://api.openai.com», так и
     * канонический «https://api.openai.com/v1» из документации OpenAI.
     * Раньше второй вариант давал двойной «/v1/v1/chat/completions» → 404.
     * Теперь оба варианта работают.
     */
    private fun normalizedEndpoint(): String = normalizeEndpoint(config.baseUrl)
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

            val response = post("${normalizedEndpoint()}/chat/completions", payload)
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
        // Подключение проверяется без модели: endpoint + ключ → /models.
        // Модель пользователь выберет на следующем шаге, и именно она
        // проверяется реальным inference в testModel().
        val key = apiKey()
            ?: return AIResult(false, "API Key не задан", AIBackend.EXTERNAL)
        if (config.baseUrl.isBlank())
            return AIResult(false, "Endpoint не задан", AIBackend.EXTERNAL)
        val started = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url("${normalizedEndpoint()}/models")
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AIResult(false, "HTTP ${response.code}: ключ или endpoint отклонены",
                        AIBackend.EXTERNAL, latencyMs = System.currentTimeMillis() - started)
                } else {
                    val n = listModels().size
                    AIResult(true, "Подключено. Доступно моделей: $n",
                        AIBackend.EXTERNAL, latencyMs = System.currentTimeMillis() - started)
                }
            }
        } catch (t: Throwable) {
            AIResult(false, "Ошибка соединения: ${t.message}", AIBackend.EXTERNAL,
                latencyMs = System.currentTimeMillis() - started)
        }
    }

    /**
     * Получение реального списка моделей с сервера (ТЗ §38 аудита).
     * Endpoint → Authentication → /models → список.
     *
     * HTTP 200 на /models ещё не означает, что ключ годится для inference:
     * поэтому список моделей — отдельный шаг, а проверка выбранной модели
     * идёт через testModel() с реальным inference.
     */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val key = apiKey() ?: return@withContext emptyList()
        try {
            val request = Request.Builder()
                .url("${normalizedEndpoint()}/models")
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                root["data"]?.jsonArray?.mapNotNull { el ->
                    el.jsonObject["id"]?.jsonPrimitive?.content
                } ?: emptyList()
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Проверка конкретной модели реальным inference.
     * Цепочка: выбрать модель → тестовый запрос → реальный ответ → VERIFIED.
     */
    suspend fun testModel(modelId: String): AIResult {
        val payload = buildJsonObject {
            put("model", modelId)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "Ответь одним словом: работает.") })
            })
            put("max_tokens", 16)
            put("temperature", 0.0)
        }.toString()
        val started = System.currentTimeMillis()
        val response = post("${normalizedEndpoint()}/chat/completions", payload)
            ?: return AIResult(false, "Нет ответа от провайдера для модели $modelId",
                AIBackend.EXTERNAL, latencyMs = System.currentTimeMillis() - started)
        return try {
            val content = json.parseToJsonElement(response).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
            if (content.isNullOrBlank()) {
                AIResult(false, "Модель $modelId вернула пустой ответ", AIBackend.EXTERNAL)
            } else {
                AIResult(true, content, AIBackend.EXTERNAL,
                    latencyMs = System.currentTimeMillis() - started, modelName = modelId)
            }
        } catch (t: Throwable) {
            AIResult(false, "Ошибка разбора ответа модели $modelId: ${t.message}",
                AIBackend.EXTERNAL)
        }
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

/**
 * Нормализация endpoint (аудит п.3 — «тест не проходит для рабочих ключей»).
 *
 * Провайдеры OpenAI-compatible принимают запросы по пути
 * «/v1/chat/completions». Пользователь может ввести:
 *  - «https://api.openai.com» (база)     → нужен «…/v1/chat/completions»
 *  - «https://api.openai.com/v1» (канон) → нужен «…/v1/chat/completions»
 *  - «https://api.groq.com/openai/v1»    → путь /v1 уже внутри
 *
 * Раньше либо приклеивался второй «/v1/v1/…» → 404, либо «/v1» удалялся
 * и запрос уходил на «…/chat/completions» → 404. Теперь гарантируется
 * ровно один сегмент «/v1».
 *
 * Чистая функция — не зависит от Android, полностью покрывается unit-тестами.
 */
fun normalizeEndpoint(baseUrl: String): String {
    val trimmed = baseUrl.trim()
    if (trimmed.isEmpty()) return ""
    // Сохраняем схему «https://», а двойные слеши в пути схлопываем.
    val scheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "https://"
        trimmed.startsWith("http://", ignoreCase = true) -> "http://"
        else -> ""
    }
    var url = if (scheme.isEmpty()) trimmed else trimmed.substring(scheme.length)
    url = url.trimEnd('/')
    if (url.isEmpty()) return scheme.trimEnd('/')
    // Схлопываем двойные слеши в пути (не трогая схему)
    while (url.contains("//")) url = url.replace("//", "/")
    // Убираем дублирующийся «/v1/v1» в конце
    while (url.endsWith("/v1/v1", ignoreCase = true)) {
        url = url.removeSuffix("/v1/v1").removeSuffix("/V1/V1").trimEnd('/') + "/v1"
    }
    // Гарантируем ровно один «/v1»: если его нет в пути вообще — добавляем.
    if (!url.contains("/v1", ignoreCase = true)) url += "/v1"
    return scheme + url
}
