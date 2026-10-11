package com.svetlana.home.server

import android.content.Context
import android.util.Log
import com.svetlana.home.store.SecureKeyStore
import com.svetlana.home.store.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.util.concurrent.TimeUnit

/**
 * Конфигурация персонального сервера пользователя.
 * Токен хранится в SecureKeyStore и никогда не попадает в репозиторий.
 */
@Serializable
data class ServerConfig(
    val id: String = "main",
    val name: String = "Мой сервер Светланы",
    val baseUrl: String = "",
    val tokenKeyAlias: String = SecureKeyStore.SERVER_TOKEN,
    val enabled: Boolean = false
)

/**
 * Возможности удалённого сервера (ТЗ §45).
 * Важно: удалённая RAM/VRAM НЕ являются физической RAM телефона.
 */
@Serializable
data class ServerCapabilities(
    val reachable: Boolean,
    val cpu: String,
    val ramGb: Int,
    val gpu: String,
    val vramGb: Int,
    val models: List<String>,
    val inferenceSupported: Boolean,
    val latencyMs: Long
) {
    companion object {
        val unknown = ServerCapabilities(false, "unknown", 0, "unknown", 0, emptyList(), false, 0L)
    }
}

/**
 * PersonalServerManager — управление персональным AI-сервером (ТЗ §44).
 *
 * Безопасность: адрес проверяется [ServerUrlPolicy] при сохранении и перед КАЖДЫМ запросом,
 * DNS не может увести на сам телефон/metadata, перенаправления запрещены (токен не уйдёт
 * на чужой адрес), ответ ограничен [MAX_RESPONSE] байтами.
 */
class PersonalServerManager(
    private val context: Context,
    private val settings: SettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val keyStore = SecureKeyStore.create(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(ServerUrlPolicy.safeDns)
        .build()

    @Volatile
    private var config: ServerConfig = loadConfig()

    @Volatile
    private var lastCaps: ServerCapabilities = ServerCapabilities.unknown

    fun config(): ServerConfig = config

    fun lastCapabilities(): ServerCapabilities = lastCaps

    /**
     * Сохранить адрес. Пустая строка очищает адрес.
     * @return null — сохранено, иначе причина отказа (адрес не сохранён).
     */
    fun setBaseUrl(url: String): String? {
        val clean = url.trim().trimEnd('/')
        if (clean.isNotEmpty()) ServerUrlPolicy.check(clean)?.let { return it }
        config = config.copy(baseUrl = clean)
        saveConfig()
        return null
    }

    fun setEnabled(enabled: Boolean) {
        config = config.copy(enabled = enabled)
        saveConfig()
    }

    fun setToken(token: String) = keyStore.put(SecureKeyStore.SERVER_TOKEN, token)
    fun token(): String? = keyStore.get(SecureKeyStore.SERVER_TOKEN)
    fun clearToken() = keyStore.remove(SecureKeyStore.SERVER_TOKEN)

    /** Адрес сервера, если он задан и проходит политику (старые сохранённые адреса тоже проверяются). */
    private fun safeBase(): String? {
        val base = config.baseUrl
        if (base.isBlank()) return null
        val problem = ServerUrlPolicy.check(base)
        if (problem != null) {
            Log.w(TAG, "Адрес сервера отклонён политикой: $problem")
            return null
        }
        return base
    }

    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        val base = safeBase() ?: return@withContext false
        try {
            request(base, "/health").use { it.isSuccessful }
        } catch (t: Throwable) {
            Log.w(TAG, "Health check не удался", t)
            false
        }
    }

    suspend fun capabilities(): ServerCapabilities = withContext(Dispatchers.IO) {
        val base = safeBase() ?: return@withContext ServerCapabilities.unknown
        val started = System.currentTimeMillis()
        try {
            val body = requestJson(base, "/capabilities") ?: return@withContext ServerCapabilities.unknown
            val root = json.parseToJsonElement(body).jsonObject
            val hardware = root["hardware"]?.jsonObject ?: JsonObject(emptyMap())
            val gpu = root["gpu"]?.jsonObject ?: JsonObject(emptyMap())
            val caps = ServerCapabilities(
                reachable = true,
                cpu = hardware["cpu"]?.jsonPrimitive?.content?.take(200) ?: "unknown",
                ramGb = hardware["ram_gb"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                gpu = gpu["name"]?.jsonPrimitive?.content?.take(200) ?: "none",
                vramGb = gpu["vram_gb"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                models = root["models"]?.let { el ->
                    json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), el
                    ).take(200).map { it.take(200) }
                } ?: emptyList(),
                inferenceSupported = root["inference"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                latencyMs = System.currentTimeMillis() - started
            )
            lastCaps = caps
            caps
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось получить возможности сервера", t)
            ServerCapabilities.unknown
        }
    }

    suspend fun inference(prompt: String, model: String? = null): String? = withContext(Dispatchers.IO) {
        val base = safeBase() ?: return@withContext null
        try {
            val payload = buildString {
                append("{")
                append("\"prompt\":").append(json.encodeToString(kotlinx.serialization.serializer<String>(), prompt))
                model?.let { append(",\"model\":").append(json.encodeToString(kotlinx.serialization.serializer<String>(), it)) }
                append("}")
            }
            postJson(base, "/inference", payload)
        } catch (t: Throwable) {
            Log.w(TAG, "Inference на сервере не удался", t)
            null
        }
    }

    suspend fun visionInference(prompt: String, imageBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val base = safeBase() ?: return@withContext null
        try {
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart(
                    "image", "vision.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("$base/vlm")
                .apply { token()?.let { addHeader("Authorization", "Bearer $it") } }
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "VLM на сервере: HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.limitedString()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "VLM inference на сервере не удался", t)
            null
        }
    }

    fun isConfigured(): Boolean = config.enabled && config.baseUrl.isNotBlank()

    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        healthCheck()
    }

    @Deprecated("Переименовано в isConfigured() — имя вводило в заблуждение (аудит 18). " +
        "Для реальной проверки доступности используйте suspend isReachable().",
        ReplaceWith("isConfigured()"))
    fun isReachableLegacy(): Boolean = isConfigured()

    private fun request(base: String, path: String) = client.newCall(
        Request.Builder()
            .url(base + path)
            .apply { token()?.let { addHeader("Authorization", "Bearer $it") } }
            .build()
    ).execute()

    private fun requestJson(base: String, path: String): String? {
        request(base, path).use { r ->
            if (!r.isSuccessful) return null
            return r.body?.limitedString()
        }
    }

    private fun postJson(base: String, path: String, payload: String): String? {
        val body = payload.toRequestBody("application/json".toMediaType())
        client.newCall(
            Request.Builder()
                .url(base + path)
                .apply { token()?.let { addHeader("Authorization", "Bearer $it") } }
                .post(body)
                .build()
        ).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.limitedString()
        }
    }

    /** Тело ответа не больше [MAX_RESPONSE]; слишком большой ответ отбрасывается. */
    private fun ResponseBody.limitedString(): String? {
        val len = contentLength()
        if (len > MAX_RESPONSE) return null
        val src = source()
        if (src.request(MAX_RESPONSE + 1)) return null
        return src.buffer.readUtf8()
    }

    private fun loadConfig(): ServerConfig = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ServerConfig(
            baseUrl = prefs.getString(KEY_URL, "") ?: "",
            enabled = prefs.getBoolean(KEY_ENABLED, false)
        )
    } catch (t: Throwable) { ServerConfig() }

    private fun saveConfig() {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_URL, config.baseUrl)
                .putBoolean(KEY_ENABLED, config.enabled)
                .apply()
        } catch (t: Throwable) { /* ignore */ }
    }

    companion object {
        private const val TAG = "PersonalServer"
        private const val PREFS = "svetlana_server"
        private const val KEY_URL = "base_url"
        private const val KEY_ENABLED = "enabled"
        private const val MAX_RESPONSE = 4L * 1024 * 1024
    }
}
