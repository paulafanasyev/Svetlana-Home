package com.svetlana.home.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Secure bridge protocol v2 (matches src/services/security/BridgeCrypto.ts in Svetlana 2.0).
 *
 * The pairing code never travels over the network. Both sides derive an AES-GCM-256 key:
 * PBKDF2-HMAC-SHA256(code, salt, 100 000 iterations); the random salt is published in /health
 * and changes with every new code. Request body: {"v":2,"ts","nonce","iv","ct"}, AAD
 * "svetlana-bridge-v2\nreq\n<route>\n<ts>\n<nonce>". Reply body: {"v":2,"iv","ct"}, AAD
 * "svetlana-bridge-v2\nres\n<route>\n<nonce>". A sniffed request can't be read, forged or
 * replayed (timestamp window + one-time nonce), and replies are bound to the request, so another
 * device on the Wi-Fi can't impersonate the phone.
 */
class BridgeSecure(
    private val code: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = ITERATIONS,
) {
    private class Session(val code: String, val salt: ByteArray, val key: SecretKeySpec)

    @Volatile
    private var session: Session? = null
    private val seen = LinkedHashMap<String, Long>()

    sealed class Opened {
        class Ok(val plaintext: String, val nonce: String, internal val key: SecretKeySpec) : Opened()
        class Fail(val code: String, val message: String) : Opened()
    }

    @Synchronized
    private fun current(): Session? {
        val c = BridgeToken.normalize(code()) ?: return null
        session?.let { if (it.code == c) return it }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val fresh = Session(c, salt, deriveKey(c, salt, iterations))
        session = fresh
        synchronized(seen) { seen.clear() }
        return fresh
    }

    /** Salt for /health. Not secret. Null when there is no pairing code. */
    fun salt(): String? = current()?.let { B64E.encodeToString(it.salt) }

    fun open(route: String, body: String): Opened {
        val s = current() ?: return Opened.Fail("UNAUTHORIZED", NEED_CODE)
        val parsed: JsonObject? = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        }
        val root = parsed ?: return Opened.Fail("UPGRADE_REQUIRED", UPGRADE)
        if ((root["v"] as? JsonPrimitive)?.intOrNull != PROTOCOL) return Opened.Fail("UPGRADE_REQUIRED", UPGRADE)
        val ts = (root["ts"] as? JsonPrimitive)?.longOrNull
        val nonce = (root["nonce"] as? JsonPrimitive)?.contentOrNull
        val iv = (root["iv"] as? JsonPrimitive)?.contentOrNull?.let { decode(it) }
        val ct = (root["ct"] as? JsonPrimitive)?.contentOrNull?.let { decode(it) }
        if (ts == null || nonce == null || iv == null || ct == null || iv.size != IV_BYTES || decode(nonce)?.size != NONCE_BYTES) {
            return Opened.Fail("BAD_REQUEST", "Malformed secure request")
        }
        if (kotlin.math.abs(clock() - ts) > MAX_SKEW_MS) {
            return Opened.Fail("STALE", "Часы ПК и телефона расходятся больше чем на 2 минуты")
        }
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, s.key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(requestAad(route, ts, nonce))
            String(cipher.doFinal(ct), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return Opened.Fail("UNAUTHORIZED", WRONG_CODE)
        }
        // Only authenticated nonces are remembered: strangers can't fill the table.
        synchronized(seen) {
            val now = clock()
            val iter = seen.entries.iterator()
            while (iter.hasNext()) {
                if (now - iter.next().value > NONCE_TTL_MS) iter.remove() else break
            }
            if (seen.containsKey(nonce)) return Opened.Fail("REPLAY", "Повтор запроса отклонён")
            if (seen.size >= MAX_NONCES) seen.remove(seen.keys.first())
            seen[nonce] = now
        }
        return Opened.Ok(plain, nonce, s.key)
    }

    /** Encrypt the reply for the request that was just opened. */
    fun seal(opened: Opened.Ok, route: String, plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, opened.key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(responseAad(route, opened.nonce))
        val ct = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return buildJsonObject {
            put("v", PROTOCOL)
            put("iv", B64E.encodeToString(iv))
            put("ct", B64E.encodeToString(ct))
        }.toString()
    }

    companion object {
        const val PROTOCOL = 2
        const val ITERATIONS = 100_000
        const val SALT_BYTES = 16
        const val IV_BYTES = 12
        const val NONCE_BYTES = 16
        const val TAG_BITS = 128
        const val MAX_SKEW_MS = 120_000L
        const val NONCE_TTL_MS = 300_000L
        const val MAX_NONCES = 10_000
        private const val LABEL = "svetlana-bridge-v2"
        const val UPGRADE = "Обновите Светлану на ПК: нужен защищённый протокол v2"
        const val WRONG_CODE = "Неверный код подключения"
        const val NEED_CODE = "Нужен код подключения с главного экрана Светланы"
        private val B64E: Base64.Encoder = Base64.getEncoder()

        fun deriveKey(code: String, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(code.toCharArray(), salt, iterations, 256)
            try {
                return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
            } finally {
                spec.clearPassword()
            }
        }

        fun requestAad(route: String, ts: Long, nonce: String): ByteArray =
            "$LABEL\nreq\n$route\n$ts\n$nonce".toByteArray(StandardCharsets.UTF_8)

        fun responseAad(route: String, nonce: String): ByteArray =
            "$LABEL\nres\n$route\n$nonce".toByteArray(StandardCharsets.UTF_8)

        private fun decode(s: String): ByteArray? = try {
            Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
