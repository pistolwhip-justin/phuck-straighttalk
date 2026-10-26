package com.datanet.share.sender

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class SpeedTestServer(
    private val bindHost: String,
    private val port: Int = DEFAULT_PORT,
    private val payloadSize: Int = DEFAULT_PAYLOAD_SIZE
) {
    companion object {
        private const val TAG = "SpeedTestServer"
        const val DEFAULT_PORT = 8283
        const val DEFAULT_PAYLOAD_SIZE = 10 * 1024 * 1024
        private const val CHUNK_SIZE = 64 * 1024
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val activeTests = AtomicInteger(0)

    fun start() {
        if (running) return
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindHost, port))
            }
            running = true
            Log.i(TAG, "Speed test server listening on $bindHost:$port")
            Thread({ acceptLoop() }, "speedtest-accept").apply { isDaemon = true; start() }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to bind speed test server on $bindHost:$port", e)
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
    }

    val boundPort: Int get() = serverSocket?.localPort ?: port
    val activeClientCount: Int get() = activeTests.get()

    private fun acceptLoop() {
        val srv = serverSocket ?: return
        while (running) {
            val client = try { srv.accept() } catch (e: IOException) { if (running) Log.w(TAG, "accept failed: ${e.message}"); break }
            activeTests.incrementAndGet()
            Thread({ handleClient(client) }, "speedtest-client").apply { isDaemon = true; start() }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30000
            try { client.sendBufferSize = 256 * 1024 } catch (_: Exception) {}
            val out = client.getOutputStream()
            val chunk = ByteArray(CHUNK_SIZE)
            var sent = 0L
            while (sent < payloadSize) {
                val toWrite = minOf(chunk.size.toLong(), payloadSize - sent).toInt()
                out.write(chunk, 0, toWrite)
                sent += toWrite
            }
            out.flush()
            Log.i(TAG, "Speed test sent ${sent / 1024 / 1024}MB")
        } catch (e: IOException) {
            Log.w(TAG, "Speed test client error: ${e.message}")
        } finally {
            try { client.close() } catch (_: IOException) {}
            activeTests.decrementAndGet()
        }
    }
}
