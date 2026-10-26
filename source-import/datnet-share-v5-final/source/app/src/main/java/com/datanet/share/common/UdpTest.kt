package com.datanet.share.common

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

object UdpTest {
    private const val TAG = "UdpTest"
    private val STUN_SERVERS = listOf("stun.l.google.com:19302", "stun1.l.google.com:19302", "stun2.l.google.com:19302")

    fun runTest(onResult: (String) -> Unit, onError: (String) -> Unit) {
        Thread({
            try {
                onResult("Starting UDP test...")
                for (server in STUN_SERVERS) {
                    val result = tryStunServer(server, onResult)
                    if (result != null) { onResult("UDP WORKING: $result"); return@Thread }
                }
                onError("UDP FAILED: No STUN server responded within 5s.")
            } catch (t: Throwable) { onError("UDP test crashed: ${t.javaClass.simpleName}: ${t.message}") }
        }, "udp-test").apply { isDaemon = true }.start()
    }

    private fun tryStunServer(server: String, onProgress: (String) -> Unit): String? {
        val parts = server.split(":")
        val host = parts[0]; val port = parts[1].toInt()
        onProgress("Trying $server...")
        val socket = DatagramSocket()
        try {
            socket.soTimeout = 5000
            onProgress("Resolving $host...")
            val addr = try { InetAddress.getByName(host) } catch (e: Exception) { onProgress("DNS failed: ${e.message}"); return null }
            onProgress("Resolved to ${addr.hostAddress}")
            val request = ByteArray(20)
            request[0] = 0x00; request[1] = 0x01; request[2] = 0x00; request[3] = 0x00
            request[4] = 0x21; request[5] = 0x12; request[6] = 0xA4.toByte(); request[7] = 0x42
            val txnId = ByteArray(12); java.security.SecureRandom().nextBytes(txnId)
            System.arraycopy(txnId, 0, request, 8, 12)
            onProgress("Sending STUN request...")
            val sendTime = System.currentTimeMillis()
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(addr, port)))
            val responseBuf = ByteArray(1500)
            val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
            socket.receive(responsePacket)
            val elapsed = System.currentTimeMillis() - sendTime
            val respType = ((responseBuf[0].toInt() and 0xFF) shl 8) or (responseBuf[1].toInt() and 0xFF)
            return "Got STUN response in ${elapsed}ms (type=0x${respType.toString(16)})"
        } catch (e: SocketTimeoutException) { onProgress("No response from $server"); return null }
        catch (e: Exception) { onProgress("Error: ${e.message}"); return null }
        finally { try { socket.close() } catch (_: Exception) {} }
    }
}
