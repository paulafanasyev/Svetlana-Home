package com.svetlana.home.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * HTTP routes of the Svetlana bridge. Matches the HTTPHands transport of the
 * Svetlana 2.0 core: GET /health, POST /api/<route> with body {"params": {...}}.
 * Successful replies: {"success": true, "data": ..., "timestamp": ...}.
 */
class BridgeRouter(
    private val tokenCheck: (String?) -> Boolean,
    private val handlers: BridgeHandlers,
    private val version: String = "1",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun handle(request: HttpRequest): HttpResponse {
        val cors = corsHeaders(request.headers["origin"])
        if (request.method == "OPTIONS") return HttpResponse(204, "", cors)

        val path = request.path.substringBefore('?')
        if (path == "/health") {
            if (request.method != "GET") return error(405, "METHOD_NOT_ALLOWED", "Use GET", cors)
            val body = buildJsonObject {
                put("ok", true)
                put("service", "svetlana-home")
                put("version", version)
                put("tools", JsonArray(TOOLS.map { JsonPrimitive(it) }))
                put("timestamp", clock())
            }
            return HttpResponse(200, body.toString(), cors)
        }
        if (!path.startsWith("/api/")) return error(404, "NOT_FOUND", "Unknown path", cors)
        if (request.method != "POST") return error(405, "METHOD_NOT_ALLOWED", "Use POST", cors)
        if (!tokenCheck(extractToken(request.headers))) {
            return error(401, "UNAUTHORIZED", "Нужен код подключения из уведомления Светланы", cors)
        }
        val params = parseParams(request.body) ?: return error(400, "BAD_JSON", "Body must be a JSON object", cors)

        val result = try {
            dispatch(path.removePrefix("/api/"), params)
        } catch (e: Exception) {
            BridgeResult.Error("INTERNAL", e.message ?: e.javaClass.simpleName)
        }
        return when (result) {
            is BridgeResult.Ok -> {
                val body = buildJsonObject {
                    put("success", true)
                    result.data?.let { put("data", it) }
                    put("timestamp", clock())
                }
                HttpResponse(200, body.toString(), cors)
            }
            is BridgeResult.Error -> error(statusFor(result.code), result.code, result.message, cors)
        }
    }

    private fun dispatch(route: String, params: JsonObject): BridgeResult = when (route) {
        "device/info" -> BridgeResult.Ok(handlers.deviceInfo())
        "contacts/list" -> {
            val query = params.string("query")?.takeIf { it.isNotBlank() }
            val limit = (params.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
            val offset = (params.int("offset") ?: 0).coerceAtLeast(0)
            handlers.listContacts(query, limit, offset)
        }
        "app/launch" -> {
            val packageName = params.string("packageName")
            if (packageName == null || !PACKAGE_RE.matches(packageName)) {
                BridgeResult.Error("BAD_PARAMS", "packageName is required, e.g. org.telegram.messenger")
            } else {
                handlers.launchApp(packageName)
            }
        }
        else -> BridgeResult.Error("UNKNOWN_METHOD", "Unknown method: $route")
    }

    private fun error(status: Int, code: String, message: String, headers: Map<String, String>): HttpResponse {
        val body = buildJsonObject {
            put("success", false)
            put("error", code)
            put("message", message)
            put("timestamp", clock())
        }
        return HttpResponse(status, body.toString(), headers)
    }

    companion object {
        val TOOLS = listOf("device/info", "contacts/list", "app/launch")
        const val DEFAULT_LIMIT = 500
        const val MAX_LIMIT = 5000
        private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun extractToken(headers: Map<String, String>): String? {
            val auth = headers["authorization"]
            if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) return auth.substring(7).trim()
            return headers["x-svetlana-token"]
        }

        fun parseParams(body: String): JsonObject? {
            if (body.isBlank()) return JsonObject(emptyMap())
            val root = try {
                Json.parseToJsonElement(body)
            } catch (e: Exception) {
                return null
            }
            if (root !is JsonObject) return null
            return root["params"] as? JsonObject ?: if (root.containsKey("params")) JsonObject(emptyMap()) else root
        }

        fun statusFor(code: String): Int = when (code) {
            "BAD_PARAMS" -> 400
            "PERMISSION_DENIED" -> 403
            "NOT_FOUND", "UNKNOWN_METHOD" -> 404
            else -> 500
        }

        fun corsHeaders(origin: String?): Map<String, String> = linkedMapOf(
            "Access-Control-Allow-Origin" to (origin ?: "*"),
            "Vary" to "Origin",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type, Authorization, X-Svetlana-Token",
            // Chrome Private Network Access: allow pages to reach the phone on the LAN.
            "Access-Control-Allow-Private-Network" to "true",
            "Access-Control-Max-Age" to "600",
        )

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
