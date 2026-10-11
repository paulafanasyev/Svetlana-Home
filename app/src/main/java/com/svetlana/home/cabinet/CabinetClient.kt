package com.svetlana.home.cabinet

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.svetlana.home.ai.LocalModelManager
import com.svetlana.home.bridge.BridgeController
import com.svetlana.home.voice.OwnerVoiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Привязка телефона к личному кабинету и «пульс» раз в 10 минут.
 * Токен устройства хранится только в EncryptedSharedPreferences (ключ в Android Keystore).
 * Редиректы запрещены: токен не уйдёт на чужой адрес. 401 от сайта = устройство отключено в кабинете,
 * локальная привязка стирается.
 */
object CabinetClient {
    private const val PREFS = "svetlana_cabinet_secure"
    private const val K_TOKEN = "device_token"
    private const val K_ID = "device_id"
    private const val K_ORIGIN = "origin"
    private const val K_LINKED = "linked_at"
    private const val K_LAST_OK = "last_ok"
    private const val K_LAST_ERR = "last_error"
    private const val K_LAST_TRY = "last_try"
    const val HEARTBEAT_MS = 10 * 60_000L
    private const val MAX_REPLY = 64L * 1024L

    private val JSON_TYPE = "application/json".toMediaType()
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    data class State(val linked: Boolean, val linkedAt: Long, val lastOk: Long, val lastError: String?)

    enum class Beat { OK, UNLINKED, NOT_LINKED, ERROR, SKIPPED }

    private fun prefs(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, PREFS, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun state(context: Context): State = runCatching {
        val p = prefs(context)
        State(
            linked = p.getString(K_TOKEN, null) != null,
            linkedAt = p.getLong(K_LINKED, 0L),
            lastOk = p.getLong(K_LAST_OK, 0L),
            lastError = p.getString(K_LAST_ERR, null),
        )
    }.getOrDefault(State(false, 0L, 0L, null))

    /** Привязать телефон по одноразовому коду из кабинета. Бросает понятную ошибку. */
    suspend fun link(context: Context, code: String, name: String, origin: String = CabinetProtocol.ORIGINS[0]) =
        withContext(Dispatchers.IO) {
            val o = CabinetProtocol.checkOrigin(origin)
            val body = CabinetProtocol.linkBody(code, name)
            val request = Request.Builder().url("$o${CabinetProtocol.API}/link").post(body.toRequestBody(JSON_TYPE)).build()
            val linked = http.newCall(request).execute().use { r ->
                CabinetProtocol.parseLinkReply(r.code, r.peekBody(MAX_REPLY).string())
            }
            prefs(context).edit()
                .putString(K_TOKEN, linked.token)
                .putString(K_ID, linked.deviceId)
                .putString(K_ORIGIN, o)
                .putLong(K_LINKED, System.currentTimeMillis())
                .remove(K_LAST_ERR)
                .commit()
        }

    /** Забыть привязку на этом телефоне. */
    fun unlinkLocal(context: Context) {
        runCatching { prefs(context).edit().clear().commit() }
    }

    /**
     * Флаги защиты, честно: voiceEnrolled = есть записанный шаблон голоса владельца
     * (тогда голосовые команды идут через OwnerVoiceGate), секреты зашифрованы
     * (EncryptedSharedPreferences), опасные действия идут через ActionRiskPolicy.
     */
    fun collectStatus(context: Context): CabinetProtocol.Status = CabinetProtocol.Status(
        bridgeV2 = runCatching { BridgeController.isEnabled(context) }.getOrDefault(false),
        voiceEnrolled = runCatching { OwnerVoiceStore.isEnrolled(context) }.getOrDefault(false),
        secretsEncrypted = true,
        confirmGate = true,
        pairedWithWindows = false,
    )

    /** Установленные модели из согласованного каталога (остальные не передаются). */
    fun installedCatalogModels(context: Context): List<CabinetProtocol.Model> = runCatching {
        LocalModelManager(context).list()
            .filter { it.modelId in CabinetProtocol.CATALOG_IDS }
            .map { CabinetProtocol.Model(it.modelId, (it.sizeBytes / 1_000_000L).toInt()) }
    }.getOrDefault(emptyList())

    /** Пульс не чаще раза в 10 минут (force — кнопка «Обновить»). */
    suspend fun heartbeat(context: Context, force: Boolean = false): Beat = withContext(Dispatchers.IO) {
        val p = runCatching { prefs(context) }.getOrNull() ?: return@withContext Beat.NOT_LINKED
        val token = p.getString(K_TOKEN, null)
        val origin = p.getString(K_ORIGIN, null)
        if (token == null || origin == null || origin !in CabinetProtocol.ORIGINS) return@withContext Beat.NOT_LINKED
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong(K_LAST_TRY, 0L) < HEARTBEAT_MS) return@withContext Beat.SKIPPED
        p.edit().putLong(K_LAST_TRY, now).apply()
        try {
            val body = CabinetProtocol.heartbeatBody(collectStatus(context), installedCatalogModels(context))
            val request = Request.Builder()
                .url("$origin${CabinetProtocol.API}/heartbeat")
                .header("Authorization", "Device $token")
                .post(body.toRequestBody(JSON_TYPE))
                .build()
            http.newCall(request).execute().use { r ->
                when (r.code) {
                    200 -> { p.edit().putLong(K_LAST_OK, now).remove(K_LAST_ERR).apply(); Beat.OK }
                    401 -> { unlinkLocal(context); Beat.UNLINKED }
                    else -> { p.edit().putString(K_LAST_ERR, "HTTP ${r.code}").apply(); Beat.ERROR }
                }
            }
        } catch (e: Exception) {
            p.edit().putString(K_LAST_ERR, (e.message ?: "нет сети").take(120)).apply()
            Beat.ERROR
        }
    }
}
