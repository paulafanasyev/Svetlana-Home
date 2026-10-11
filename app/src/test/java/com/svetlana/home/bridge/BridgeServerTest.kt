package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class BridgeServerTest {
    private val code = "ABCD-EFGH-JKLM-NPQR"
    private val handlers = object : BridgeHandlers {
        override fun deviceInfo(): JsonObject = buildJsonObject { put("model", "Loopback") }
        override fun listContacts(query: String?, limit: Int, offset: Int): BridgeResult =
            BridgeResult.Ok(buildJsonObject { put("total", 0) })
        override fun launchApp(packageName: String): BridgeResult = BridgeResult.Ok()
    }
    private val key = BridgeCrypto.deriveKey(code)
    private val server = BridgeServer(0, BridgeRouter({ key }, handlers), InetAddress.getLoopbackAddress())

    @After
    fun tearDown() {
        server.stop()
    }

    private fun open(path: String): HttpURLConnection =
        (URL("http://127.0.0.1:${server.localPort}$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }

    @Test
    fun servesHealthAndEncryptedApiOverRealSockets() {
        server.start()
        assertThat(server.isRunning).isTrue()

        val health = open("/health")
        assertThat(health.responseCode).isEqualTo(200)
        assertThat(health.inputStream.bufferedReader().readText()).contains("svetlana-home")

        val legacy = open("/api/device/info").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $code")
            outputStream.use { it.write("{}".toByteArray()) }
        }
        assertThat(legacy.responseCode).isEqualTo(401)

        val client = BridgeV2TestClient(code) { System.currentTimeMillis() }
        val sent = client.request("device/info", origin = null)
        val ok = open("/api/device/info").apply {
            requestMethod = "POST"
            doOutput = true
            sent.request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            outputStream.use { it.write(sent.request.body.toByteArray()) }
        }
        assertThat(ok.responseCode).isEqualTo(200)
        val raw = ok.inputStream.bufferedReader().readText()
        assertThat(raw).doesNotContain("Loopback")
        val plain = client.open(HttpResponse(200, raw), sent.nonce)
        assertThat(plain["data"]!!.jsonObject["model"]!!.jsonPrimitive.content).isEqualTo("Loopback")
    }

    @Test
    fun refusesToListenOnAllInterfaces() {
        val any = BridgeServer(0, BridgeRouter({ key }, handlers), InetAddress.getByName("0.0.0.0"))
        assertThrows(IllegalArgumentException::class.java) { any.start() }
        assertThat(any.isRunning).isFalse()
    }

    @Test
    fun stopClosesTheSocket() {
        server.start()
        assertThat(server.boundAddress.isLoopbackAddress).isTrue()
        server.stop()
        assertThat(server.isRunning).isFalse()
    }
}
