package com.svetlana.home.bridge

import kotlinx.serialization.json.Json
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
 *
 * Browser protection:
 * - only allow-listed origins (Tauri desktop app, localhost dev) get CORS headers;
 *   any other Origin on /api is rejected with 403 (blocks drive-by websites);
 * - Host is required (HTTP/1.1) and must be an IP literal or localhost (blocks DNS rebinding);
 * - wrong pairing codes are rate limited per client IP.
 */
class BridgeRouter(
    private val tokenCheck: (String?) -> Boolean,
    private val handlers: BridgeHandlers,
    private val version: String = "1",
    private val clock: () -> Long = System::currentTimeMillis,
    private val limiter: BridgeRateLimiter = BridgeRateLimiter(clock),
) {
    fun handle(request: HttpRequest): HttpResponse {
        val origin = request.headers["origin"]
        val originAllowed = origin == null || isAllowedOrigin(origin)
        val cors = if (origin != null && originAllowed) corsHeaders(origin) else emptyMap()

        val host = request.headers["host"]
        if (host == null && request.httpVersion == "HTTP/1.1") {
            return error(400, "BAD_HOST", "Host header is required", emptyMap())
        }
        if (!isAllowedHost(host)) {
            return error(421, "BAD_HOST", "Use the phone IP address", emptyMap())
        }
        if (request.method == "OPTIONS") return HttpResponse(if (originAllowed) 204 else 403, "", cors)

        val path = request.path.substringBefore('?')
        if (path == "/health") {
            if (request.method != "GET") return error(405, "METHOD_NOT_ALLOWED", "Use GET", cors)
            val body = buildJsonObject {
                put("ok", true)
                put("service", "svetlana-home")
            }
            return HttpResponse(200, body.toString(), cors)
        }
        if (!path.startsWith("/api/")) return error(404, "NOT_FOUND", "Unknown path", cors)
        if (!originAllowed) return error(403, "ORIGIN_NOT_ALLOWED", "Origin is not allowed", cors)
        if (request.method != "POST") return error(405, "METHOD_NOT_ALLOWED", "Use POST", cors)

        val client = request.remoteAddress
        if (limiter.isLocked(client)) {
            return error(429, "TOO_MANY_ATTEMPTS", "Слишком много неверных кодов, подождите 10 минут", cors)
        }
        if (!tokenCheck(extractToken(request.headers))) {
            limiter.recordFailure(client)
            return error(401, "UNAUTHORIZED", "Нужен код подключения с главного экрана Светланы", cors)
        }
        limiter.recordSuccess(client)
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
        const val DEFAULT_LIMIT = 500
        const val MAX_LIMIT = 5000
        private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val LOCAL_ORIGIN_RE = Regex("^https?://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?$")
        private val IPV4_HOST_RE = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::(\\d{1,5}))?$")
        private val LOCAL_HOST_RE = Regex("^(localhost|\\[(?:::1|0:0:0:0:0:0:0:1)])(?::(\\d{1,5}))?$")
        private val TAURI_ORIGINS = setOf("http://tauri.localhost", "https://tauri.localhost", "tauri://localhost")

        fun isAllowedOrigin(origin: String): Boolean =
            origin in TAURI_ORIGINS || LOCAL_ORIGIN_RE.matches(origin)

        /**
         * DNS-rebinding guard: only IPv4 literals, localhost and the IPv6 loopback are accepted.
         * A hostname means the request came via someone's DNS. LAN IPv6 literals are not used
         * by the bridge (it advertises an IPv4 address).
         */
        fun isAllowedHost(host: String?): Boolean {
            if (host == null) return true
            LOCAL_HOST_RE.matchEntire(host)?.let { return portOk(it.groupValues[2]) }
            val m = IPV4_HOST_RE.matchEntire(host) ?: return false
            val octetsOk = (1..4).all { (m.groupValues[it].toIntOrNull() ?: 256) <= 255 }
            return octetsOk && portOk(m.groupValues[5])
        }

        private fun portOk(port: String): Boolean = port.isEmpty() || (port.toIntOrNull() ?: 0) in 1..65535

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

        fun corsHeaders(origin: String): Map<String, String> = linkedMapOf(
            "Access-Control-Allow-Origin" to origin,
            "Vary" to "Origin",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type, Authorization, X-Svetlana-Token",
            // Chrome Private Network Access: only for allow-listed origins.
            "Access-Control-Allow-Private-Network" to "true",
            "Access-Control-Max-Age" to "600",
        )

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
