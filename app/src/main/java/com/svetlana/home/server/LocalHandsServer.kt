package com.svetlana.home.server

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.svetlana.home.hands.SvetlanaAccessibilityService
import com.svetlana.home.hands.UiTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * LocalHandsServer - легкий встроенный JSON-RPC 2.0 сервер на устройстве (порт 8765/8080).
 * Позволяет Svetlana-2.0 управлять устройством через реальный SvetlanaAccessibilityService.
 */
class LocalHandsServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "LocalHandsServer started on port $port")
                while (isRunning && serverSocket?.isClosed == false) {
                    val socket = serverSocket?.accept() ?: break
                    scope.launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "LocalHandsServer error: ${e.message}", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverJob?.cancel()
        Log.i(TAG, "LocalHandsServer stopped")
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val writer = PrintWriter(s.getOutputStream(), true)

            // Простая поддержка HTTP POST или raw JSON-RPC over TCP
            var line: String? = reader.readLine() ?: return@withContext
            var body = ""

            if (line!!.startsWith("POST ") || line.startsWith("GET ")) {
                var contentLength = 0
                while (line != null && line.isNotEmpty()) {
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    line = reader.readLine()
                }
                if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buffer, read, contentLength - read)
                        if (n == -1) break
                        read += n
                    }
                    body = String(buffer, 0, read)
                }
            } else {
                body = line
            }

            if (body.isEmpty()) return@withContext

            val responseJson = processJsonRpc(body)
            // HTTP response headers
            writer.print("HTTP/1.1 200 OK\r\n")
            writer.print("Content-Type: application/json; charset=utf-8\r\n")
            writer.print("Access-Control-Allow-Origin: *\r\n")
            writer.print("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
            writer.print("Access-Control-Allow-Headers: Content-Type\r\n")
            writer.print("Content-Length: ${responseJson.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.print("Connection: close\r\n\r\n")
            writer.print(responseJson)
            writer.flush()
        }
    }

    private fun processJsonRpc(requestStr: String): String {
        return try {
            val req = JSONObject(requestStr)
            val id = req.opt("id")
            val method = req.optString("method")
            val params = req.optJSONObject("params") ?: JSONObject()

            val hands = SvetlanaAccessibilityService.instance
            if (hands == null) {
                return errorResponse(id, -32000, "SvetlanaAccessibilityService is not connected/enabled in Android Settings")
            }

            val result: Any = when (method) {
                "app.launch", "open_app" -> {
                    val pkg = params.optString("packageName").ifEmpty { params.optString("package") }
                    if (pkg.isEmpty()) {
                        return errorResponse(id, -32602, "Missing packageName parameter")
                    }
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        JSONObject().put("success", true).put("packageName", pkg)
                    } else {
                        return errorResponse(id, -32001, "Cannot find launch intent for $pkg")
                    }
                }
                "app.getActiveWindow", "getCurrentApp" -> {
                    JSONObject().put("currentPackage", hands.currentPackage())
                }
                "input.click", "ui.tap", "tap" -> {
                    val x = params.optDouble("x", -1.0).toFloat()
                    val y = params.optDouble("y", -1.0).toFloat()
                    if (x >= 0 && y >= 0) {
                        val ok = hands.clickPoint(x, y)
                        JSONObject().put("success", ok)
                    } else {
                        return errorResponse(id, -32602, "Invalid x, y coordinates")
                    }
                }
                "input.type", "type" -> {
                    val text = params.optString("text")
                    // Ввод через AccessibilityNodeInfo активного фокуса
                    val root = hands.rootInActiveWindow
                    val focused = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                    val ok = if (focused != null) {
                        hands.inputText(focused, text)
                    } else false
                    JSONObject().put("success", ok).put("text", text)
                }
                "input.scroll", "ui.swipe", "swipe" -> {
                    val dir = params.optString("direction", "down")
                    val ok = when (dir) {
                        "up" -> hands.swipe(500f, 1500f, 500f, 500f)
                        "down" -> hands.swipe(500f, 500f, 500f, 1500f)
                        "left" -> hands.swipe(800f, 1000f, 200f, 1000f)
                        "right" -> hands.swipe(200f, 1000f, 800f, 1000f)
                        else -> false
                    }
                    JSONObject().put("success", ok)
                }
                "input.key", "pressBack" -> {
                    val key = params.optString("key", "back")
                    val ok = if (key.equals("back", ignoreCase = true) || key.equals("escape", ignoreCase = true)) {
                        hands.pressBack()
                    } else if (key.equals("home", ignoreCase = true)) {
                        hands.pressHome()
                    } else {
                        false
                    }
                    JSONObject().put("success", ok)
                }
                "ui.getAccessibilityTree", "ui.getAutomationTree", "getAccessibilityTree" -> {
                    val tree = hands.snapshotUiTree()
                    treeToJson(tree)
                }
                "screen.capture", "takeScreenshot" -> {
                    val latch = CountDownLatch(1)
                    val bitmapRef = AtomicReference<Bitmap?>(null)
                    hands.captureScreen { bmp ->
                        bitmapRef.set(bmp)
                        latch.countDown()
                    }
                    latch.await(3, TimeUnit.SECONDS)
                    val bmp = bitmapRef.get()
                    if (bmp != null) {
                        val stream = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.PNG, 80, stream)
                        val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                        JSONObject().put("success", true).put("base64Image", b64)
                    } else {
                        return errorResponse(id, -32002, "Screen capture failed or permission denied")
                    }
                }
                else -> {
                    return errorResponse(id, -32601, "Method not found: $method")
                }
            }

            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString()
        } catch (e: Exception) {
            errorResponse(null, -32700, "Parse error: ${e.message}")
        }
    }

    private fun treeToJson(tree: UiTree): JSONObject {
        val json = JSONObject()
        json.put("currentPackage", tree.packageName)
        val nodesArr = JSONArray()
        for (node in tree.allNodes) {
            val nodeObj = JSONObject().apply {
                put("id", node.id)
                put("text", node.text)
                put("contentDescription", node.contentDescription)
                put("className", node.className)
                put("isClickable", node.isClickable)
                put("isScrollable", node.isScrollable)
                put("bounds", JSONObject().apply {
                    put("x", node.bounds.left)
                    put("y", node.bounds.top)
                    put("width", node.bounds.width())
                    put("height", node.bounds.height())
                })
            }
            nodesArr.put(nodeObj)
        }
        json.put("root", JSONObject().put("children", nodesArr))
        return json
    }

    private fun errorResponse(id: Any?, code: Int, message: String): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }.toString()
    }

    companion object {
        private const val TAG = "LocalHandsServer"
        const val DEFAULT_PORT = 8765
    }
}
