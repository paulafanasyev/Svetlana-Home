package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class BridgeRouterTest {
    private val code = "ABCD-EFGH"

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
    private val router = BridgeRouter({ BridgeToken.matches(code, it) }, handlers, version = "test", clock = { 42L })

    private fun post(route: String, body: String, token: String? = code) = router.handle(
        HttpRequest(
            "POST",
            "/api/$route",
            buildMap {
                put("origin", "http://tauri.localhost")
                if (token != null) put("authorization", "Bearer $token")
            },
            body,
        ),
    )

    private fun json(response: HttpResponse) = Json.parseToJsonElement(response.body).jsonObject

    @Test
    fun healthIsPublicAndListsTools() {
        val response = router.handle(HttpRequest("GET", "/health", emptyMap(), ""))
        assertThat(response.status).isEqualTo(200)
        val body = json(response)
        assertThat(body["ok"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(body["tools"]!!.jsonArray.map { it.jsonPrimitive.content }).contains("contacts/list")
    }

    @Test
    fun preflightGetsCorsAndPrivateNetworkHeaders() {
        val response = router.handle(HttpRequest("OPTIONS", "/api/contacts/list", mapOf("origin" to "http://tauri.localhost"), ""))
        assertThat(response.status).isEqualTo(204)
        assertThat(response.headers["Access-Control-Allow-Origin"]).isEqualTo("http://tauri.localhost")
        assertThat(response.headers["Access-Control-Allow-Private-Network"]).isEqualTo("true")
        assertThat(response.headers["Access-Control-Allow-Headers"]).contains("Authorization")
    }

    @Test
    fun apiRequiresPairingCode() {
        assertThat(post("contacts/list", "{}", token = null).status).isEqualTo(401)
        assertThat(post("contacts/list", "{}", token = "WRON-GCOD").status).isEqualTo(401)
        assertThat(handlers.lastLimit).isEqualTo(-1)
    }

    @Test
    fun acceptsCodeInCustomHeader() {
        val response = router.handle(HttpRequest("POST", "/api/device/info", mapOf("x-svetlana-token" to "abcd-efgh"), "{}"))
        assertThat(response.status).isEqualTo(200)
        assertThat(json(response)["data"]!!.jsonObject["model"]!!.jsonPrimitive.content).isEqualTo("Test Phone")
    }

    @Test
    fun contactsListReadsHttpHandsParamsEnvelope() {
        val response = post("contacts/list", """{"params":{"query":"Анна","limit":50,"offset":10},"timestamp":1}""")
        assertThat(response.status).isEqualTo(200)
        assertThat(handlers.lastQuery).isEqualTo("Анна")
        assertThat(handlers.lastLimit).isEqualTo(50)
        assertThat(handlers.lastOffset).isEqualTo(10)
        val body = json(response)
        assertThat(body["success"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(body["data"]!!.jsonObject["total"]!!.jsonPrimitive.int).isEqualTo(2)
        assertThat(body["timestamp"]!!.jsonPrimitive.content).isEqualTo("42")
    }

    @Test
    fun contactsListClampsLimitsAndDefaults() {
        post("contacts/list", """{"params":{"limit":999999,"offset":-5,"query":"  "}}""")
        assertThat(handlers.lastLimit).isEqualTo(BridgeRouter.MAX_LIMIT)
        assertThat(handlers.lastOffset).isEqualTo(0)
        assertThat(handlers.lastQuery).isNull()
        post("contacts/list", "")
        assertThat(handlers.lastLimit).isEqualTo(BridgeRouter.DEFAULT_LIMIT)
    }

    @Test
    fun permissionDeniedMapsTo403WithMessage() {
        handlers.contactsResult = BridgeResult.Error("PERMISSION_DENIED", "нет доступа")
        val response = post("contacts/list", "{}")
        assertThat(response.status).isEqualTo(403)
        assertThat(json(response)["message"]!!.jsonPrimitive.content).isEqualTo("нет доступа")
    }

    @Test
    fun appLaunchValidatesPackageName() {
        assertThat(post("app/launch", """{"params":{"packageName":"org.telegram.messenger"}}""").status).isEqualTo(200)
        assertThat(handlers.launched).isEqualTo("org.telegram.messenger")
        assertThat(post("app/launch", """{"params":{"packageName":"../../etc"}}""").status).isEqualTo(400)
        assertThat(post("app/launch", """{"params":{}}""").status).isEqualTo(400)
    }

    @Test
    fun badJsonAndUnknownRoutes() {
        assertThat(post("contacts/list", "{not json").status).isEqualTo(400)
        assertThat(post("contacts/list", "[1,2]").status).isEqualTo(400)
        assertThat(post("files/delete", "{}").status).isEqualTo(404)
        assertThat(router.handle(HttpRequest("GET", "/api/contacts/list", mapOf("authorization" to "Bearer $code"), "")).status).isEqualTo(405)
        assertThat(router.handle(HttpRequest("GET", "/secret", emptyMap(), "")).status).isEqualTo(404)
    }

    @Test
    fun handlerExceptionBecomes500() {
        val exploding = object : BridgeHandlers {
            override fun deviceInfo(): JsonObject = throw IllegalStateException("boom")
            override fun listContacts(query: String?, limit: Int, offset: Int) = BridgeResult.Ok()
            override fun launchApp(packageName: String) = BridgeResult.Ok()
        }
        val r = BridgeRouter({ true }, exploding)
        val response = r.handle(HttpRequest("POST", "/api/device/info", emptyMap(), "{}"))
        assertThat(response.status).isEqualTo(500)
    }
}
