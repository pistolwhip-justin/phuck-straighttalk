package com.datanet.share.common

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object SpeedTestClient {
    private const val TAG = "SpeedTestClient"
    const val DEFAULT_PAYLOAD_SIZE = 10 * 1024 * 1024
    const val TIMEOUT_MS = 30_000

    fun runTest(host: String, port: Int, payloadSize: Int = DEFAULT_PAYLOAD_SIZE,
                onProgress: (String) -> Unit, onComplete: (String) -> Unit) {
        Thread({
            try {
                onProgress("Connecting to $host:$port...")
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.soTimeout = TIMEOUT_MS
                try { sock.receiveBufferSize = 256 * 1024 } catch (_: Exception) {}
                sock.connect(InetSocketAddress(host, port), 10_000)
                onProgress("Connected. Receiving ${payloadSize / 1024 / 1024}MB...")
                val inp = sock.getInputStream()
                val buf = ByteArray(64 * 1024)
                var received = 0L
                val start = System.currentTimeMillis()
                while (received < payloadSize) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    received += n
                    if (received % (1024 * 1024) < buf.size) {
                        val pct = (received * 100 / payloadSize).toInt()
                        onProgress("Receiving: $pct% (${FormatBytes.format(received)})")
                    }
                }
                val elapsed = System.currentTimeMillis() - start
                sock.close()
                if (received < payloadSize) {
                    onComplete("Speed test incomplete: received ${FormatBytes.format(received)} of ${FormatBytes.format(payloadSize.toLong())} in ${elapsed}ms")
                    return@Thread
                }
                val bits = received * 8.0
                val seconds = elapsed / 1000.0
                val mbps = if (seconds > 0) bits / 1_000_000.0 / seconds else 0.0
                val result = "Link speed: ${String.format("%.2f", mbps)} Mbps\n${FormatBytes.format(received)} in ${String.format("%.2f", seconds)}s"
                Log.i(TAG, result)
                onComplete(result)
            } catch (e: IOException) {
                onComplete("Speed test failed: ${e.message}")
            } catch (e: Exception) {
                onComplete("Speed test error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "speedtest-client").apply { isDaemon = true }.start()
    }
}
