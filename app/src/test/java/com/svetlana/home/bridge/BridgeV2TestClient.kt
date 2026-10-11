package com.svetlana.home.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

/** Test-side implementation of protocol v2, mirroring Svetlana-2.0 BridgeCrypto.ts. */
class BridgeV2TestClient(code: String, private val clock: () -> Long) {
    private val key = BridgeCrypto.deriveKey(code)!!
    private val random = SecureRandom()

    fun newNonce(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    data class Sent(val request: HttpRequest, val nonce: String)

    fun request(
        route: String,
        params: String = "{\"params\":{}}",
        nonce: String = newNonce(),
        ts: Long = clock(),
        remote: String = "192.168.1.30",
        origin: String? = "http://tauri.localhost",
        aadPath: String = "/api/$route",
    ): Sent {
        val sealed = BridgeCrypto.seal(key, params, BridgeCrypto.requestAad("POST", aadPath, ts, nonce))
        val body = buildJsonObject {
            put("v", 2)
            put("iv", sealed.iv)
            put("ct", sealed.ct)
        }.toString()
        val headers = buildMap {
            if (origin != null) put("origin", origin)
            put("x-svetlana-ts", ts.toString())
            put("x-svetlana-nonce", nonce)
            put("content-type", "application/json")
        }
        return Sent(HttpRequest("POST", "/api/$route", headers, body, remote), nonce)
    }

    /** Decrypts an authenticated reply; fails the test if it is not a valid v2 envelope. */
    fun open(response: HttpResponse, nonce: String): JsonObject {
        val env = Json.parseToJsonElement(response.body).jsonObject
        val plain = BridgeCrypto.open(
            key,
            env["iv"]!!.jsonPrimitive.content,
            env["ct"]!!.jsonPrimitive.content,
            BridgeCrypto.responseAad(nonce, response.status),
        ) ?: error("reply did not decrypt")
        return Json.parseToJsonElement(plain).jsonObject
    }
}
