package com.svetlana.home.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Minimal HTTP request as seen by the bridge. Header names are lower-case. */
data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
    /** Client IP as seen by the server socket; used for rate limiting. */
    val remoteAddress: String = "",
    /** "HTTP/1.1" or "HTTP/1.0" from the request line. */
    val httpVersion: String = "HTTP/1.0",
)

data class HttpResponse(
    val status: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/** Outcome of a device operation requested by the Svetlana 2.0 core. */
sealed class BridgeResult {
    data class Ok(val data: JsonElement? = null) : BridgeResult()
    data class Error(val code: String, val message: String) : BridgeResult()
}

/** Device operations exposed to the core. Android implementation: [AndroidBridgeHandlers]. */
interface BridgeHandlers {
    fun deviceInfo(): JsonObject
    fun listContacts(query: String?, limit: Int, offset: Int): BridgeResult
    fun launchApp(packageName: String): BridgeResult
}
