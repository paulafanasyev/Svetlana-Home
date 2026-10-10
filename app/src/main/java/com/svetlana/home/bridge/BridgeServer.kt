package com.svetlana.home.bridge

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Blocking-socket HTTP server for the bridge. One request per connection. */
class BridgeServer(
    private val port: Int,
    private val router: BridgeRouter,
    private val bindAddress: InetAddress? = null,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null

    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (isRunning) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, port))
        val executor = Executors.newFixedThreadPool(WORKERS)
        serverSocket = socket
        pool = executor
        thread(name = "svetlana-bridge-accept", isDaemon = true) { acceptLoop(socket, executor) }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (ignored: IOException) {
        }
        pool?.shutdownNow()
        serverSocket = null
        pool = null
    }

    private fun acceptLoop(socket: ServerSocket, executor: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (ignored: IOException) {
                break
            }
            try {
                executor.execute { serve(client) }
            } catch (ignored: Exception) {
                closeQuietly(client)
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val response = try {
                router.handle(HttpRequestParser.parse(client.getInputStream()))
            } catch (e: HttpRequestParser.ParseException) {
                HttpResponse(e.status, buildJsonObject {
                    put("success", false)
                    put("error", "BAD_REQUEST")
                    put("message", e.message ?: "Bad request")
                }.toString())
            }
            HttpResponseWriter.write(client.getOutputStream(), response)
        } catch (ignored: IOException) {
            // client went away or timed out
        } finally {
            closeQuietly(client)
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (ignored: IOException) {
        }
    }

    companion object {
        private const val WORKERS = 4
        private const val READ_TIMEOUT_MS = 10_000
    }
}
