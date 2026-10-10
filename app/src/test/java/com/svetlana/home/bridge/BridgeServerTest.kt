package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class BridgeServerTest {
    private val handlers = object : BridgeHandlers {
        override fun deviceInfo(): JsonObject = buildJsonObject { put("model", "Loopback") }
        override fun listContacts(query: String?, limit: Int, offset: Int): BridgeResult =
            BridgeResult.Ok(buildJsonObject { put("total", 0) })
        override fun launchApp(packageName: String): BridgeResult = BridgeResult.Ok()
    }
    private val server = BridgeServer(0, BridgeRouter({ BridgeToken.matches("ABCD-EFGH", it) }, handlers), InetAddress.getLoopbackAddress())

    @After
    fun tearDown() {
        server.stop()
    }

    private fun open(path: String): HttpURLConnection =
        URL("http://127.0.0.1:${server.localPort}$path").openConnection() as HttpURLConnection

    @Test
    fun servesHealthAndAuthorizedApiOverRealSockets() {
        server.start()
        assertThat(server.isRunning).isTrue()

        val health = open("/health")
        assertThat(health.responseCode).isEqualTo(200)
        assertThat(health.inputStream.bufferedReader().readText()).contains("svetlana-home")

        val unauthorized = open("/api/device/info").apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write("{}".toByteArray()) }
        }
        assertThat(unauthorized.responseCode).isEqualTo(401)

        val ok = open("/api/device/info").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer ABCD-EFGH")
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write("""{"params":{}}""".toByteArray()) }
        }
        assertThat(ok.responseCode).isEqualTo(200)
        assertThat(ok.inputStream.bufferedReader().readText()).contains("Loopback")
    }

    @Test
    fun stopClosesTheSocket() {
        server.start()
        server.stop()
        assertThat(server.isRunning).isFalse()
    }
}
