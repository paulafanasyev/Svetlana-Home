package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class BridgeRouterTest {
    private val code = "ABCD-EFGH-JKLM-NPQR"
    private var now = 1_700_000_000_000L

    private class FakeHandlers : BridgeHandlers {
        var lastQuery: String? = "unset"
        var lastLimit = -1
        var lastOffset = -1
        var launched: String? = null
        var contactsResult: BridgeResult = BridgeResult.Ok(buildJsonObject { put("total", 2) })

        override fun deviceInfo(): JsonObject = buildJsonObject {
            put("platform", "android")
            put("model", "Test Phone")
        }

        override fun listContacts(query: String?, limit: Int, offset: Int): BridgeResult {
            lastQuery = query
            lastLimit = limit
            lastOffset = offset
            return contactsResult
        }

        override fun launchApp(packageName: String): BridgeResult {
            launched = packageName
            return BridgeResult.Ok()
        }
    }

    private val handlers = FakeHandlers()
    private val key = BridgeCrypto.deriveKey(code)
    private val router = BridgeRouter({ key }, handlers, version = "test", clock = { now })
    private val client = BridgeV2TestClient(code) { now }

    private fun call(route: String, params: String = "{\"params\":{}}"): Pair<HttpResponse, JsonObject> {
        val sent = client.request(route, params)
        val response = router.handle(sent.request)
        return response to client.open(response, sent.nonce)
    }

    private fun json(response: HttpResponse) = Json.parseToJsonElement(response.body).jsonObject

    @Test
    fun healthIsPublicAndMinimal() {
        val response = router.handle(HttpRequest("GET", "/health", emptyMap(), "", "192.168.1.30"))
        assertThat(response.status).isEqualTo(200)
        val body = json(response)
        assertThat(body["ok"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(body.keys).containsExactly("ok", "service", "protocol")
        assertThat(body["protocol"]!!.jsonPrimitive.int).isEqualTo(2)
    }

    @Test
    fun encryptedRoundTripReturnsEncryptedData() {
        val sent = client.request("device/info")
        val response = router.handle(sent.request)
        assertThat(response.status).isEqualTo(200)
        // The wire carries only the envelope: no model name, no code.
        assertThat(response.body).doesNotContain("Test Phone")
        assertThat(sent.request.body).doesNotContain("ABCDEFGHJKLMNPQR")
        assertThat(sent.request.headers.values.joinToString()).doesNotContain("ABCDEFGHJKLMNPQR")
        assertThat(json(response).keys).containsExactly("v", "iv", "ct")
        val plain = client.open(response, sent.nonce)
        assertThat(plain["data"]!!.jsonObject["model"]!!.jsonPrimitive.content).isEqualTo("Test Phone")
    }

    @Test
    fun wrongCodeIsUnauthorizedAndCounted() {
        val attacker = BridgeV2TestClient("WRON-GCOD-EWRO-NGCD") { now }
        repeat(5) {
            val r = router.handle(attacker.request("device/info", remote = "10.0.0.9").request)
            assertThat(r.status).isEqualTo(401)
            assertThat(r.body).doesNotContain("Test Phone")
        }
        val locked = router.handle(client.request("device/info", remote = "10.0.0.9").request)
        assertThat(locked.status).isEqualTo(429)
        val other = router.handle(client.request("device/info", remote = "10.0.0.10").request)
        assertThat(other.status).isEqualTo(200)
    }

    @Test
    fun legacyBearerTokenIsNoLongerAccepted() {
        val response = router.handle(
            HttpRequest("POST", "/api/device/info", mapOf("authorization" to "Bearer $code"), "{}", "192.168.1.30"),
        )
        assertThat(response.status).isEqualTo(401)
        assertThat(json(response)["error"]!!.jsonPrimitive.content).isEqualTo("UPGRADE_REQUIRED")
        assertThat(handlers.lastLimit).isEqualTo(-1)
    }

    @Test
    fun replayedRequestIsRejected() {
        val sent = client.request("contacts/list")
        assertThat(router.handle(sent.request).status).isEqualTo(200)
        assertThat(router.handle(sent.request).status).isEqualTo(409)
    }

    @Test
    fun staleTimestampIsRejected() {
        val old = client.request("device/info", ts = now - 10 * 60_000L)
        assertThat(router.handle(old.request).status).isEqualTo(401)
        val future = client.request("device/info", ts = now + 10 * 60_000L)
        assertThat(router.handle(future.request).status).isEqualTo(401)
    }

    @Test
    fun ciphertextBoundToRouteTimestampAndNonce() {
        val spliced = client.request("app/launch", aadPath = "/api/device/info")
        assertThat(router.handle(spliced.request).status).isEqualTo(401)
        val sent = client.request("device/info")
        val tampered = sent.request.copy(headers = sent.request.headers + ("x-svetlana-ts" to (now + 1).toString()))
        assertThat(router.handle(tampered).status).isEqualTo(401)
        assertThat(handlers.launched).isNull()
    }

    @Test
    fun publicInternetPeersAreRejected() {
        val r = router.handle(client.request("device/info", remote = "8.8.8.8").request)
        assertThat(r.status).isEqualTo(403)
        assertThat(BridgeRouter.isLocalNetworkAddress("192.168.0.5")).isTrue()
        assertThat(BridgeRouter.isLocalNetworkAddress("10.1.2.3")).isTrue()
        assertThat(BridgeRouter.isLocalNetworkAddress("172.16.0.1")).isTrue()
        assertThat(BridgeRouter.isLocalNetworkAddress("127.0.0.1")).isTrue()
        assertThat(BridgeRouter.isLocalNetworkAddress("169.254.10.1")).isTrue()
        assertThat(BridgeRouter.isLocalNetworkAddress("fe80::1%wlan0")).isTrue()
        assertThat(BridgeRouter.isLocalNetworkAddress("fd12:3456::1")).isTrue()
        assertThat(BridgeRouter.isLocalNetworkAddress("::ffff:192.168.1.7")).isTrue()
        assertThat(BridgeRouter.isLocalNetworkAddress("100.64.0.1")).isFalse()
        assertThat(BridgeRouter.isLocalNetworkAddress("2001:4860:4860::8888")).isFalse()
        assertThat(BridgeRouter.isLocalNetworkAddress("evil.example")).isFalse()
    }

    @Test
    fun foreignOriginIsRejectedWithoutCors() {
        val response = router.handle(client.request("device/info", origin = "https://evil.example").request)
        assertThat(response.status).isEqualTo(403)
        assertThat(response.headers).doesNotContainKey("Access-Control-Allow-Origin")
        val preflight = router.handle(HttpRequest("OPTIONS", "/api/device/info", mapOf("origin" to "https://evil.example"), ""))
        assertThat(preflight.status).isEqualTo(403)
        assertThat(preflight.headers).isEmpty()
    }

    @Test
    fun localhostAndTauriOriginsAreAllowed() {
        assertThat(BridgeRouter.isAllowedOrigin("http://tauri.localhost")).isTrue()
        assertThat(BridgeRouter.isAllowedOrigin("https://tauri.localhost")).isTrue()
        assertThat(BridgeRouter.isAllowedOrigin("http://localhost:5173")).isTrue()
        assertThat(BridgeRouter.isAllowedOrigin("http://127.0.0.1:1420")).isTrue()
        assertThat(BridgeRouter.isAllowedOrigin("http://localhost.evil.com")).isFalse()
        assertThat(BridgeRouter.isAllowedOrigin("null")).isFalse()
    }

    @Test
    fun dnsRebindingHostIsRejected() {
        val response = router.handle(HttpRequest("GET", "/health", mapOf("host" to "rebind.evil.example:8080"), ""))
        assertThat(response.status).isEqualTo(421)
        assertThat(BridgeRouter.isAllowedHost("192.168.1.20:8080")).isTrue()
        assertThat(BridgeRouter.isAllowedHost("localhost:8080")).isTrue()
        assertThat(BridgeRouter.isAllowedHost("[::1]:8080")).isTrue()
        assertThat(BridgeRouter.isAllowedHost("300.1.1.1:8080")).isFalse()
        assertThat(BridgeRouter.isAllowedHost("192.168.1.20:99999")).isFalse()
        assertThat(BridgeRouter.isAllowedHost("[fe80::1]:8080")).isFalse()
        assertThat(BridgeRouter.isAllowedHost("localhost:0")).isFalse()
    }

    @Test
    fun http11RequiresHost() {
        val response = router.handle(HttpRequest("GET", "/health", emptyMap(), "", httpVersion = "HTTP/1.1"))
        assertThat(response.status).isEqualTo(400)
    }

    @Test
    fun preflightAllowsV2Headers() {
        val response = router.handle(HttpRequest("OPTIONS", "/api/contacts/list", mapOf("origin" to "http://tauri.localhost"), ""))
        assertThat(response.status).isEqualTo(204)
        assertThat(response.headers["Access-Control-Allow-Origin"]).isEqualTo("http://tauri.localhost")
        assertThat(response.headers["Access-Control-Allow-Private-Network"]).isEqualTo("true")
        assertThat(response.headers["Access-Control-Allow-Headers"]).contains("X-Svetlana-Nonce")
        assertThat(response.headers["Access-Control-Allow-Headers"]).doesNotContain("Authorization")
    }

    @Test
    fun contactsListReadsHttpHandsParamsEnvelope() {
        val (response, body) = call("contacts/list", """{"params":{"query":"Анна","limit":50,"offset":10},"timestamp":1}""")
        assertThat(response.status).isEqualTo(200)
        assertThat(handlers.lastQuery).isEqualTo("Анна")
        assertThat(handlers.lastLimit).isEqualTo(50)
        assertThat(handlers.lastOffset).isEqualTo(10)
        assertThat(body["success"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(body["data"]!!.jsonObject["total"]!!.jsonPrimitive.int).isEqualTo(2)
        assertThat(body["timestamp"]!!.jsonPrimitive.content).isEqualTo(now.toString())
    }

    @Test
    fun contactsListClampsLimitsAndDefaults() {
        call("contacts/list", """{"params":{"limit":999999,"offset":-5,"query":"  "}}""")
        assertThat(handlers.lastLimit).isEqualTo(BridgeRouter.MAX_LIMIT)
        assertThat(handlers.lastOffset).isEqualTo(0)
        assertThat(handlers.lastQuery).isNull()
        call("contacts/list", "")
        assertThat(handlers.lastLimit).isEqualTo(BridgeRouter.DEFAULT_LIMIT)
    }

    @Test
    fun permissionDeniedMapsTo403WithEncryptedMessage() {
        handlers.contactsResult = BridgeResult.Error("PERMISSION_DENIED", "нет доступа")
        val (response, body) = call("contacts/list")
        assertThat(response.status).isEqualTo(403)
        assertThat(body["message"]!!.jsonPrimitive.content).isEqualTo("нет доступа")
    }

    @Test
    fun appLaunchValidatesPackageName() {
        assertThat(call("app/launch", """{"params":{"packageName":"org.telegram.messenger"}}""").first.status).isEqualTo(200)
        assertThat(handlers.launched).isEqualTo("org.telegram.messenger")
        assertThat(call("app/launch", """{"params":{"packageName":"../../etc"}}""").first.status).isEqualTo(400)
        assertThat(call("app/launch", """{"params":{}}""").first.status).isEqualTo(400)
    }

    @Test
    fun badJsonAndUnknownRoutes() {
        assertThat(call("contacts/list", "{not json").first.status).isEqualTo(400)
        assertThat(call("contacts/list", "[1,2]").first.status).isEqualTo(400)
        assertThat(call("files/delete").first.status).isEqualTo(404)
        assertThat(router.handle(HttpRequest("GET", "/api/contacts/list", emptyMap(), "")).status).isEqualTo(405)
        assertThat(router.handle(HttpRequest("GET", "/secret", emptyMap(), "")).status).isEqualTo(404)
    }

    @Test
    fun handlerExceptionBecomes500WithoutLeakingDetails() {
        val exploding = object : BridgeHandlers {
            override fun deviceInfo(): JsonObject = throw IllegalStateException("secret path /data/x")
            override fun listContacts(query: String?, limit: Int, offset: Int): BridgeResult = BridgeResult.Ok()
            override fun launchApp(packageName: String): BridgeResult = BridgeResult.Ok()
        }
        val r = BridgeRouter({ key }, exploding, clock = { now })
        val sent = client.request("device/info")
        val response = r.handle(sent.request)
        assertThat(response.status).isEqualTo(500)
        assertThat(client.open(response, sent.nonce).toString()).doesNotContain("/data/x")
    }
}
