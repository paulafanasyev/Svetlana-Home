package com.svetlana.home.bridge

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Blocking-socket HTTP server for the bridge. One request per connection.
 * Hardening: bounded worker pool and queue (excess connections are dropped),
 * per-read timeout and a hard per-connection deadline (slowloris).
 */
class BridgeServer(
    private val port: Int,
    private val router: BridgeRouter,
    private val bindAddress: InetAddress? = null,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    private var watchdog: ScheduledExecutorService? = null

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
        socket.bind(InetSocketAddress(bindAddress, port), BACKLOG)
        val executor = ThreadPoolExecutor(
            WORKERS, WORKERS, 30, TimeUnit.SECONDS,
            ArrayBlockingQueue(QUEUE_CAPACITY),
            ThreadPoolExecutor.AbortPolicy(),
        )
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "svetlana-bridge-watchdog").apply { isDaemon = true } }
        serverSocket = socket
        pool = executor
        watchdog = timer
        thread(name = "svetlana-bridge-accept", isDaemon = true) { acceptLoop(socket, executor, timer) }
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (ignored: IOException) {
        }
        pool?.shutdownNow()
        watchdog?.shutdownNow()
        serverSocket = null
        pool = null
        watchdog = null
    }

    private fun acceptLoop(socket: ServerSocket, executor: ThreadPoolExecutor, timer: ScheduledExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (ignored: IOException) {
                break
            }
            try {
                executor.execute { serve(client, timer) }
            } catch (ignored: RejectedExecutionException) {
                closeQuietly(client)
            }
        }
    }

    private fun serve(client: Socket, timer: ScheduledExecutorService) {
        val deadline = try {
            timer.schedule(Runnable { closeQuietly(client) }, CONNECTION_DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (ignored: RejectedExecutionException) {
            null
        }
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val remote = client.inetAddress?.hostAddress.orEmpty()
            val response = try {
                router.handle(HttpRequestParser.parse(client.getInputStream()).copy(remoteAddress = remote))
            } catch (e: HttpRequestParser.ParseException) {
                HttpResponse(e.status, buildJsonObject {
                    put("success", false)
                    put("error", "BAD_REQUEST")
                    put("message", e.message ?: "Bad request")
                }.toString())
            }
            HttpResponseWriter.write(client.getOutputStream(), response)
        } catch (ignored: IOException) {
            // client went away, timed out or hit the deadline
        } finally {
            deadline?.cancel(false)
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
        private const val QUEUE_CAPACITY = 16
        private const val BACKLOG = 32
        private const val READ_TIMEOUT_MS = 5_000
        private const val CONNECTION_DEADLINE_MS = 15_000L
    }
}
