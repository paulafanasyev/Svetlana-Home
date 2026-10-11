package com.svetlana.home.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom

/** Test-side implementation of protocol v2, mirroring Svetlana-2.0 security/BridgeCrypto.ts. */
class BridgeV2TestClient(
    code: String,
    saltB64: String,
    private val clock: () -> Long,
    iterations: Int = TEST_ITERATIONS,
) {
    private val key = BridgeCrypto.deriveKey(code, BridgeCrypto.decode(saltB64)!!, iterations)!!
    private val random = SecureRandom()

    fun newNonce(): String = BridgeCrypto.encode(ByteArray(16).also { random.nextBytes(it) })

    data class Sent(val request: HttpRequest, val nonce: String, val route: String)

    fun request(
        route: String,
        params: String = "{\"params\":{}}",
        nonce: String = newNonce(),
        ts: Long = clock(),
        remote: String = "192.168.1.30",
        origin: String? = "http://tauri.localhost",
        aadRoute: String = route,
    ): Sent {
        val sealed = BridgeCrypto.seal(key, params, BridgeCrypto.requestAad(aadRoute, ts, nonce))
        val body = buildJsonObject {
            put("v", 2)
            put("ts", ts)
            put("nonce", nonce)
            put("iv", sealed.iv)
            put("ct", sealed.ct)
        }.toString()
        val headers = buildMap {
            if (origin != null) put("origin", origin)
            put("content-type", "application/json")
        }
        return Sent(HttpRequest("POST", "/api/$route", headers, body, remote), nonce, route)
    }

    /** Decrypts an authenticated reply; fails the test if it is not a valid v2 envelope. */
    fun open(response: HttpResponse, sent: Sent): JsonObject {
        val env = Json.parseToJsonElement(response.body).jsonObject
        val plain = BridgeCrypto.open(
            key,
            env["iv"]!!.jsonPrimitive.content,
            env["ct"]!!.jsonPrimitive.content,
            BridgeCrypto.responseAad(sent.route, sent.nonce),
        ) ?: error("reply did not decrypt")
        return Json.parseToJsonElement(plain).jsonObject
    }

    companion object {
        /** Fast PBKDF2 for unit tests; production uses BridgeCrypto.ITERATIONS. */
        const val TEST_ITERATIONS = 1_000
    }
}
