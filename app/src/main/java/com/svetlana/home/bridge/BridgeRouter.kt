package com.svetlana.home.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.Inet6Address
import java.net.InetAddress

/**
 * HTTP routes of the Svetlana bridge (protocol v2). Matches the HTTPHands transport
 * of the Svetlana 2.0 core: GET /health, POST /api/<route>.
 *
 * /api requests are end-to-end encrypted and authenticated with a key derived from
 * the pairing code ([BridgeCrypto]); the code itself is never transmitted:
 *   headers X-Svetlana-Ts (ms) and X-Svetlana-Nonce (base64url, one-time),
 *   body {"v":2,"iv","ct"} whose plaintext is {"params": {...}}.
 * Authenticated replies are encrypted too; the plaintext is
 * {"success": true, "data": ..., "timestamp": ...} or {"success": false, "error", "message"}.
 *
 * Network protection:
 * - only clients from loopback / private / link-local addresses are served;
 * - only allow-listed origins (Tauri desktop app, localhost dev) get CORS headers;
 *   any other Origin on /api is rejected with 403 (blocks drive-by websites);
 * - Host is required (HTTP/1.1) and must be an IP literal or localhost (blocks DNS rebinding);
 * - failed decryptions (wrong code / tampering) are rate limited per client and globally;
 * - timestamps outside the skew window and reused nonces are rejected (no replay).
 */
class BridgeRouter(
    private val keyProvider: () -> ByteArray?,
    private val handlers: BridgeHandlers,
    private val version: String = "2",
    private val clock: () -> Long = System::currentTimeMillis,
    private val limiter: BridgeRateLimiter = BridgeRateLimiter(clock),
    private val replay: BridgeReplayGuard = BridgeReplayGuard(clock),
) {
    fun handle(request: HttpRequest): HttpResponse {
        val origin = request.headers["origin"]
        val originAllowed = origin == null || isAllowedOrigin(origin)
        val cors = if (origin != null && originAllowed) corsHeaders(origin) else emptyMap()

        if (request.remoteAddress.isNotEmpty() && !isLocalNetworkAddress(request.remoteAddress)) {
            return error(403, "NOT_LOCAL", "Only devices from the local network are served", emptyMap())
        }
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
                put("protocol", BridgeCrypto.VERSION)
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
        val key = keyProvider() ?: return error(503, "NOT_READY", "Мост ещё не готов", cors)

        val ts = request.headers["x-svetlana-ts"]?.takeIf { TS_RE.matches(it) }?.toLongOrNull()
        val nonce = request.headers["x-svetlana-nonce"]?.takeIf { NONCE_RE.matches(it) }
        val envelope = parseEnvelope(request.body)
        if (ts == null || nonce == null || envelope == null) {
            // Not a v2 request at all (e.g. an old Svetlana 2.0 sending the code as Bearer).
            // Nothing was checked, so it is not counted as a guess.
            return error(401, "UPGRADE_REQUIRED", "Обновите Светлану 2.0: нужен защищённый протокол v2", cors)
        }
        val plaintext = BridgeCrypto.open(key, envelope.first, envelope.second, BridgeCrypto.requestAad("POST", path, ts, nonce))
        if (plaintext == null) {
            limiter.recordFailure(client)
            return error(401, "UNAUTHORIZED", "Неверный код подключения с главного экрана Светланы", cors)
        }
        when (replay.check(nonce, ts)) {
            BridgeReplayGuard.Verdict.OK -> Unit
            BridgeReplayGuard.Verdict.STALE ->
                return error(401, "STALE_REQUEST", "Часы телефона и компьютера расходятся больше чем на минуту", cors)
            BridgeReplayGuard.Verdict.REPLAY -> return error(409, "REPLAY", "Повтор запроса отклонён", cors)
            BridgeReplayGuard.Verdict.BUSY -> return error(503, "BUSY", "Слишком много запросов, повторите позже", cors)
        }
        limiter.recordSuccess(client)

        val params = parseParams(plaintext)
        val result = if (params == null) {
            BridgeResult.Error("BAD_JSON", "Body must be a JSON object")
        } else {
            try {
                dispatch(path.removePrefix("/api/"), params)
            } catch (e: Exception) {
                BridgeResult.Error("INTERNAL", e.javaClass.simpleName)
            }
        }
        val (status, body) = when (result) {
            is BridgeResult.Ok -> 200 to buildJsonObject {
                put("success", true)
                result.data?.let { put("data", it) }
                put("timestamp", clock())
            }.toString()
            is BridgeResult.Error -> statusFor(result.code) to errorBody(result.code, result.message)
        }
        val sealed = BridgeCrypto.seal(key, body, BridgeCrypto.responseAad(nonce, status))
        val envelopeOut = buildJsonObject {
            put("v", BridgeCrypto.VERSION)
            put("iv", sealed.iv)
            put("ct", sealed.ct)
        }
        return HttpResponse(status, envelopeOut.toString(), cors)
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
        else -> BridgeResult.Error("UNKNOWN_METHOD", "Unknown method")
    }

    private fun errorBody(code: String, message: String): String = buildJsonObject {
        put("success", false)
        put("error", code)
        put("message", message)
        put("timestamp", clock())
    }.toString()

    private fun error(status: Int, code: String, message: String, headers: Map<String, String>): HttpResponse =
        HttpResponse(status, errorBody(code, message), headers)

    companion object {
        const val DEFAULT_LIMIT = 500
        const val MAX_LIMIT = 5000
        private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val LOCAL_ORIGIN_RE = Regex("^https?://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?$")
        private val IPV4_HOST_RE = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::(\\d{1,5}))?$")
        private val LOCAL_HOST_RE = Regex("^(localhost|\\[(?:::1|0:0:0:0:0:0:0:1)])(?::(\\d{1,5}))?$")
        private val TAURI_ORIGINS = setOf("http://tauri.localhost", "https://tauri.localhost", "tauri://localhost")
        private val TS_RE = Regex("^\\d{1,15}$")
        private val NONCE_RE = Regex("^[A-Za-z0-9_-]{16,64}$")
        private val IPV4_LITERAL_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val IPV6_LITERAL_RE = Regex("^[0-9A-Fa-f:.]+$")

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

        /**
         * True for loopback, RFC 1918 private, link-local and IPv6 unique-local peers.
         * Only IP literals are parsed (never a DNS lookup); anything else is rejected.
         */
        fun isLocalNetworkAddress(ip: String): Boolean {
            val literal = ip.removePrefix("/").substringBefore('%')
            if (!IPV4_LITERAL_RE.matches(literal) && !(literal.contains(':') && IPV6_LITERAL_RE.matches(literal))) {
                return false
            }
            val address = try {
                InetAddress.getByName(literal)
            } catch (e: Exception) {
                return false
            }
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
            if (address is Inet6Address) {
                val first = address.address[0].toInt() and 0xFF
                return first and 0xFE == 0xFC // fc00::/7 unique local
            }
            return false
        }

        private fun portOk(port: String): Boolean = port.isEmpty() || (port.toIntOrNull() ?: 0) in 1..65535

        /** {"v":2,"iv":"...","ct":"..."} → (iv, ct), or null for anything else. */
        fun parseEnvelope(body: String): Pair<String, String>? {
            val root = try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return null
            if ((root["v"] as? JsonPrimitive)?.intOrNull != BridgeCrypto.VERSION) return null
            val iv = (root["iv"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val ct = (root["ct"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return iv to ct
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
            "BAD_PARAMS", "BAD_JSON" -> 400
            "PERMISSION_DENIED" -> 403
            "NOT_FOUND", "UNKNOWN_METHOD" -> 404
            else -> 500
        }

        fun corsHeaders(origin: String): Map<String, String> = linkedMapOf(
            "Access-Control-Allow-Origin" to origin,
            "Vary" to "Origin",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type, X-Svetlana-Ts, X-Svetlana-Nonce",
            // Chrome Private Network Access: only for allow-listed origins.
            "Access-Control-Allow-Private-Network" to "true",
            "Access-Control-Max-Age" to "600",
        )

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
