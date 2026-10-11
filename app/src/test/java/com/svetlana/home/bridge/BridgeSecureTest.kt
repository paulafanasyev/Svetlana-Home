package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class BridgeSecureTest {
    /** Deterministic "random": salt = 00 01 02 … 0F, the same as in the Svetlana 2.0 test vector. */
    private val counting = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = i.toByte()
        }
    }
    private val now = 1_760_000_000_000L

    /** Produced by BridgeCrypto.ts (WebCrypto) for code ABCD-EFGH, salt 000102…0F, route device/info. */
    private val vector =
        """{"v":2,"ts":1760000000000,"nonce":"BwcHBwcHBwcHBwcHBwcHBw==","iv":"CQkJCQkJCQkJCQkJ","ct":"w/IG3LsfvEi6pZsEIe/f2V9TnyBoZPuJl/Tm8DY="}"""

    private fun secure(code: String = "ABCD-EFGH", clock: () -> Long = { now }) =
        BridgeSecure(code = { code }, clock = clock, random = counting)

    @Test
    fun opensRequestSealedByTheDesktopApp() {
        val s = secure()
        assertThat(s.salt()).isEqualTo("AAECAwQFBgcICQoLDA0ODw==")
        val opened = s.open("device/info", vector)
        assertThat(opened).isInstanceOf(BridgeSecure.Opened.Ok::class.java)
        assertThat((opened as BridgeSecure.Opened.Ok).plaintext).isEqualTo("""{"params":{}}""")
    }

    @Test
    fun replyIsEncryptedAndBoundToTheRequest() {
        val s = secure()
        val ok = s.open("device/info", vector) as BridgeSecure.Opened.Ok
        val sealed = s.seal(ok, "device/info", """{"success":true}""")
        assertThat(sealed).doesNotContain("success")
        val o = kotlinx.serialization.json.Json.parseToJsonElement(sealed) as JsonObject
        val iv = Base64.getDecoder().decode(o["iv"].toString().trim('"'))
        val ct = Base64.getDecoder().decode(o["ct"].toString().trim('"'))
        val key = BridgeSecure.deriveKey("ABCDEFGH", ByteArray(16) { it.toByte() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(BridgeSecure.responseAad("device/info", "BwcHBwcHBwcHBwcHBwcHBw=="))
        assertThat(String(cipher.doFinal(ct), StandardCharsets.UTF_8)).isEqualTo("""{"success":true}""")
    }

    @Test
    fun rejectsReplayWrongCodeStaleTamperedAndPlainRequests() {
        val s = secure()
        assertThat(s.open("device/info", vector)).isInstanceOf(BridgeSecure.Opened.Ok::class.java)
        assertThat((s.open("device/info", vector) as BridgeSecure.Opened.Fail).code).isEqualTo("REPLAY")

        assertThat((secure("WRON-GCOD").open("device/info", vector) as BridgeSecure.Opened.Fail).code).isEqualTo("UNAUTHORIZED")
        assertThat((secure(clock = { now + 10 * 60_000 }).open("device/info", vector) as BridgeSecure.Opened.Fail).code).isEqualTo("STALE")
        // Same request sent to another route: the route is authenticated.
        assertThat((secure().open("app/launch", vector) as BridgeSecure.Opened.Fail).code).isEqualTo("UNAUTHORIZED")
        val tampered = vector.replace("w/IG3Lsf", "w/IG3Lsg")
        assertThat((secure().open("device/info", tampered) as BridgeSecure.Opened.Fail).code).isEqualTo("UNAUTHORIZED")
        assertThat((secure().open("device/info", """{"params":{}}""") as BridgeSecure.Opened.Fail).code).isEqualTo("UPGRADE_REQUIRED")
    }

    @Test
    fun routerRefusesBearerAndServesSecureRequests() {
        val handlers = object : BridgeHandlers {
            override fun deviceInfo(): JsonObject = buildJsonObject { put("model", "Loopback") }
            override fun listContacts(query: String?, limit: Int, offset: Int): BridgeResult = BridgeResult.Ok()
            override fun launchApp(packageName: String): BridgeResult = BridgeResult.Ok()
        }
        val router = BridgeRouter({ BridgeToken.matches("ABCD-EFGH", it) }, handlers, clock = { now }, secure = secure())
        val health = router.handle(HttpRequest("GET", "/health", mapOf("host" to "192.168.1.20:8080"), "", "1.2.3.4", "HTTP/1.1"))
        assertThat(health.body).contains("\"salt\":\"AAECAwQFBgcICQoLDA0ODw==\"")
        assertThat(health.body).contains("\"v\":2")

        val bearer = router.handle(
            HttpRequest("POST", "/api/device/info", mapOf("host" to "192.168.1.20:8080", "authorization" to "Bearer ABCD-EFGH"), """{"params":{}}""", "1.2.3.4", "HTTP/1.1"),
        )
        assertThat(bearer.status).isEqualTo(426)

        val ok = router.handle(HttpRequest("POST", "/api/device/info", mapOf("host" to "192.168.1.20:8080"), vector, "1.2.3.4", "HTTP/1.1"))
        assertThat(ok.status).isEqualTo(200)
        assertThat(ok.body).doesNotContain("Loopback")
        assertThat(ok.body).contains("\"ct\"")
    }
}
