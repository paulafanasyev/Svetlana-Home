package com.svetlana.home.cabinet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Протокол личного кабинета «Мир Самозанятых» (тот же, что у «Светланы 2.0» для Windows).
 * Чистая логика без Android — покрыта unit-тестами.
 *
 * В кабинет уходят ТОЛЬКО: версия, флаги защиты и id моделей из согласованного каталога.
 * Контакты, деньги, голос, переписка и ключи не передаются никогда.
 */
object CabinetProtocol {
    val ORIGINS = listOf(
        "https://xn----8sba1acxdkgqms3b9euc.xn--p1ai", // мир-самозанятых.рф
        "https://mir-samozanyatykh-api-frankfurt.onrender.com",
    )
    const val API = "/api/v1/devices"
    const val APP_VERSION = "1.2.1"

    private val CODE = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{12}$")
    private val TOKEN = Regex("^[A-Za-z0-9_-]{40,100}$")
    // Управляющие символы (\p{Cntrl}, C1) и bidi-переключатели вырезаются из названия устройства.
    private val UNSAFE_NAME = Regex("[\\p{Cntrl}\\x{80}-\\x{9F}\\x{200E}\\x{200F}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}]")

    /** id моделей телефона из каталога сайта (backend/src/lib/modelCatalog.js). */
    val CATALOG_IDS = setOf(
        "qwen3-0.6b-litert", "qwen3-1.7b-q4km", "multilingual-e5-small", "vosk-small-ru",
        "whisper-small", "campplus-sv", "silero-v5-cis-base", "pp-ocrv5-cyrillic-mobile",
    )

    data class Status(
        val bridgeV2: Boolean,
        val voiceEnrolled: Boolean,
        val secretsEncrypted: Boolean,
        val confirmGate: Boolean,
        val pairedWithWindows: Boolean,
    )

    data class Model(val id: String, val sizeMb: Int? = null)

    data class Linked(val deviceId: String, val token: String)

    /** Регистр, пробелы и дефисы не важны. Неверный код — понятная ошибка. */
    fun normalizeCode(raw: String): String {
        val s = raw.uppercase().replace(Regex("[\\s-]"), "")
        require(CODE.matches(s)) { "Код должен состоять из 12 символов, как в личном кабинете: XXXX-XXXX-XXXX" }
        return s.chunked(4).joinToString("-")
    }

    fun checkOrigin(origin: String): String {
        require(origin in ORIGINS) { "Неизвестный адрес личного кабинета" }
        return origin
    }

    /** Только печатные символы, до 80. */
    fun safeName(raw: String): String = UNSAFE_NAME.replace(raw, "").trim().take(80).ifEmpty { "Телефон" }

    fun linkBody(code: String, name: String): String = buildJsonObject {
        put("code", normalizeCode(code))
        put("platform", "android")
        put("name", safeName(name))
        put("app_version", APP_VERSION)
    }.toString()

    fun heartbeatBody(status: Status, models: List<Model>): String = buildJsonObject {
        put("app_version", APP_VERSION)
        put("status", buildJsonObject {
            put("bridge_v2", status.bridgeV2)
            put("voice_enrolled", status.voiceEnrolled)
            put("secrets_encrypted", status.secretsEncrypted)
            put("confirm_gate", status.confirmGate)
            if (status.pairedWithWindows) put("paired_with", "windows") else put("paired_with", JsonNull)
        })
        put("models", buildJsonArray {
            models.filter { it.id in CATALOG_IDS }.distinctBy { it.id }.take(50).forEach { m ->
                add(buildJsonObject {
                    put("id", m.id)
                    m.sizeMb?.let { put("size_mb", it) }
                })
            }
        })
    }.toString()

    private fun str(obj: JsonObject?, key: String): String? =
        runCatching { obj?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()

    /** Ответ на привязку. Любая нестыковка (код, формат токена) — ошибка, ничего не сохраняется. */
    fun parseLinkReply(code: Int, body: String?): Linked {
        val obj = runCatching { body?.let { Json.parseToJsonElement(it).jsonObject } }.getOrNull()
        val token = str(obj, "device_token")
        val id = str(obj, "device_id")
        if (code != 200 || token == null || id == null || !TOKEN.matches(token)) {
            throw IllegalStateException(str(obj, "message")?.take(200) ?: "Не удалось привязать (HTTP $code)")
        }
        return Linked(id.take(64), token)
    }
}
