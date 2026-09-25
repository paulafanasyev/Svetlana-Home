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
 * Пользователь подключает собственный compute: домашний ПК, VPS, NAS,
 * GPU server, бесплатный/условно бесплатный cloud compute.
 *
 * Приложение не гарантирует, что ресурс бесплатен — пользователь сам
 * проверяет условия (ТЗ §43).
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
        .build()

    @Volatile
    private var config: ServerConfig = loadConfig()

    fun config(): ServerConfig = config

    fun setBaseUrl(url: String) {
        config = config.copy(baseUrl = url.trimEnd('/'))
        saveConfig()
    }

    fun setEnabled(enabled: Boolean) {
        config = config.copy(enabled = enabled)
        saveConfig()
    }

    fun setToken(token: String) = keyStore.put(SecureKeyStore.SERVER_TOKEN, token)
    fun token(): String? = keyStore.get(SecureKeyStore.SERVER_TOKEN)
    fun clearToken() = keyStore.remove(SecureKeyStore.SERVER_TOKEN)

    /**
     * Health check: проверяет, что сервер отвечает и аутентифицирует.
     */
    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) return@withContext false
        try {
            val response = request("/health")
            response.isSuccessful
        } catch (t: Throwable) {
            Log.w(TAG, "Health check не удался", t)
            false
        }
    }

    /**
     * Обнаружение возможностей сервера: CPU/RAM/GPU/VRAM/модели/inference.
     */
    suspend fun capabilities(): ServerCapabilities = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) return@withContext ServerCapabilities.unknown
        val started = System.currentTimeMillis()
        try {
            val body = requestJson("/capabilities") ?: return@withContext ServerCapabilities.unknown
            val root = json.parseToJsonElement(body).jsonObject
            val hardware = root["hardware"]?.jsonObject ?: JsonObject(emptyMap())
            val gpu = root["gpu"]?.jsonObject ?: JsonObject(emptyMap())
            ServerCapabilities(
                reachable = true,
                cpu = hardware["cpu"]?.jsonPrimitive?.content ?: "unknown",
                ramGb = hardware["ram_gb"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                gpu = gpu["name"]?.jsonPrimitive?.content ?: "none",
                vramGb = gpu["vram_gb"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                models = root["models"]?.let { el ->
                    json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), el
                    )
                } ?: emptyList(),
                inferenceSupported = root["inference"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                latencyMs = System.currentTimeMillis() - started
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось получить возможности сервера", t)
            ServerCapabilities.unknown
        }
    }

    /**
     * Удалённый inference. Запрос идёт на сервер пользователя.
     */
    suspend fun inference(prompt: String, model: String? = null): String? = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) return@withContext null
        try {
            val payload = buildString {
                append("{")
                append("\"prompt\":").append(json.encodeToString(kotlinx.serialization.serializer<String>(), prompt))
                model?.let { append(",\"model\":").append(json.encodeToString(kotlinx.serialization.serializer<String>(), it)) }
                append("}")
            }
            val response = postJson("/inference", payload)
            response ?: null
        } catch (t: Throwable) {
            Log.w(TAG, "Inference на сервере не удался", t)
            null
        }
    }

    fun isReachable(): Boolean = config.enabled && config.baseUrl.isNotBlank()

    private fun request(path: String) = client.newCall(
        Request.Builder()
            .url(config.baseUrl + path)
            .apply { token()?.let { addHeader("Authorization", "Bearer $it") } }
            .build()
    ).execute()

    private fun requestJson(path: String): String? {
        request(path).use { r ->
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
    }

    private fun postJson(path: String, payload: String): String? {
        val body = payload.toRequestBody("application/json".toMediaType())
        client.newCall(
            Request.Builder()
                .url(config.baseUrl + path)
                .apply { token()?.let { addHeader("Authorization", "Bearer $it") } }
                .post(body)
                .build()
        ).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
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
    }
}
