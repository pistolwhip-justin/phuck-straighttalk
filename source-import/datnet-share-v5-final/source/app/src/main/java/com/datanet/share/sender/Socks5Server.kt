package com.datanet.share.sender

import android.content.Context
import android.util.Log
import com.datanet.share.common.CellularNetworkTracker
import com.datanet.share.common.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Socks5Server(
    private val context: Context,
    private val bindHost: String,
    private val port: Int = Constants.SOCKS5_PORT,
    private val onStats: (clients: Int, rxBytes: Long, txBytes: Long) -> Unit
) {
    companion object { private const val TAG = "Socks5Server" }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var acceptJob: Job? = null
    private val activeClients = AtomicInteger(0)
    private val rxBytes = AtomicLong(0)
    private val txBytes = AtomicLong(0)
    private val udpAssociates = ConcurrentHashMap<String, UdpAssociate>()

    // ========================================================================
    // VPN-AWARE SOCKET CREATION
    //
    // Cached at start() and refreshed when the SOCKS5 server is restarted.
    // We intentionally do NOT refresh this on every connection — VPN state
    // rarely changes mid-session, and if it does (user toggles VPN while
    // sharing is active), existing sockets keep their routing; only new
    // connections will use the new behavior. The user can stop/restart
    // sharing to pick up a VPN state change cleanly.
    // ========================================================================
    @Volatile private var vpnActiveAtStart: Boolean = false

    fun start() {
        if (running) return
        running = true
        CellularNetworkTracker.start(context)
        vpnActiveAtStart = CellularNetworkTracker.isVpnActive(context)
        Log.i(TAG, "SOCKS5 starting; VPN active=$vpnActiveAtStart; ${CellularNetworkTracker.describeVpn(context)}")
        try {
            // PATCH: bind to the specific interface (bindHost) instead of 0.0.0.0.
            // This closes the open-proxy exposure on WiFi/cellular interfaces.
            // - In WiFi Direct mode, bindHost = "192.168.49.1" (p2p interface)
            // - In local-only mode, bindHost = "0.0.0.0" (caller's choice — see SenderService)
            //   TODO: SenderService should pass the specific USB/WiFi interface IP in local-only mode.
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindHost, port))
            }
            Log.i(TAG, "SOCKS5 listening on $bindHost:$port")
        } catch (e: IOException) { running = false; throw e }
        acceptJob = scope.launch { acceptLoop() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        for (assoc in udpAssociates.values) { try { assoc.close() } catch (_: Exception) {} }
        udpAssociates.clear()
        acceptJob?.cancel(); scope.cancel()
        CellularNetworkTracker.stop()
    }

    // Rate limiting (M3) — limits concurrent client connections to prevent FD exhaustion.
    private val acceptSemaphore = java.util.concurrent.Semaphore(64)

    private suspend fun acceptLoop() {
        val srv = serverSocket ?: return
        while (running) {
            val client = try { srv.accept() } catch (e: IOException) { if (running) Log.e(TAG, "accept() failed", e); break }
            acceptSemaphore.acquireUninterruptibly()
            scope.launch {
                try { handleClient(client) }
                finally { acceptSemaphore.release() }
            }
        }
    }

    private fun handleClient(client: Socket) {
        activeClients.incrementAndGet()
        var upstream: Socket? = null
        var udpAssoc: UdpAssociate? = null
        var associateKey: String? = null
        try {
            client.tcpNoDelay = true; client.soTimeout = 60000
            try { client.receiveBufferSize = 512 * 1024; client.sendBufferSize = 512 * 1024 } catch (_: Exception) {}
            val input = client.getInputStream(); val output = client.getOutputStream()
            val dis = DataInputStream(input)
            val ver = dis.readUnsignedByte()
            if (ver != 5) throw IOException("Not SOCKS5: ver=$ver")
            val nMethods = dis.readUnsignedByte()
            val methods = ByteArray(nMethods); dis.readFully(methods)
            if (nMethods == 0 || !methods.contains(0)) { output.write(byteArrayOf(0x05, 0xFF.toByte())); output.flush(); throw IOException("Client did not offer NO_AUTH") }
            output.write(byteArrayOf(0x05, 0x00)); output.flush()
            val rVer = dis.readUnsignedByte()
            if (rVer != 5) throw IOException("Bad request ver=$rVer")
            val cmd = dis.readUnsignedByte(); dis.readUnsignedByte()
            val atyp = dis.readUnsignedByte()
            val dstHost = readAddr(dis, atyp)
            val dstPort = dis.readUnsignedShort()
            when (cmd) {
                1 -> {
                    try { upstream = openTcpUpstream(dstHost, dstPort) } catch (e: IOException) { reply(output, 0x05); return }
                    reply(output, 0x00)
                    val upIn = upstream.getInputStream(); val upOut = upstream.getOutputStream()
                    val t1 = Thread({ pump(input, upOut, rxBytes) }, "socks-c2u"); val t2 = Thread({ pump(upIn, output, txBytes) }, "socks-u2c")
                    t1.start(); t2.start(); t1.join(); t2.join()
                }
                3 -> {
                    val assoc = UdpAssociate(bindHost, client.inetAddress.hostAddress ?: "0.0.0.0")
                    assoc.start(); udpAssoc = assoc
                    associateKey = "${client.remoteSocketAddress}"
                    udpAssociates[associateKey] = assoc
                    val udpPort = assoc.boundPort
                    val addrBytes = InetAddress.getByName(bindHost).address
                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, addrBytes[0], addrBytes[1], addrBytes[2], addrBytes[3], ((udpPort ushr 8) and 0xFF).toByte(), (udpPort and 0xFF).toByte()))
                    output.flush()
                    assoc.awaitIdleOrClientClose(input)
                }
                else -> { reply(output, 0x07); throw IOException("Unsupported CMD=$cmd") }
            }
        } catch (_: Exception) {} finally {
            try { client.close() } catch (_: IOException) {}
            try { upstream?.close() } catch (_: IOException) {}
            udpAssoc?.let { a -> try { a.close() } catch (_: Exception) {}; associateKey?.let { udpAssociates.remove(it) } }
            val n = activeClients.decrementAndGet()
            onStats(n, rxBytes.get(), txBytes.get())
        }
    }

    private fun readAddr(dis: DataInputStream, atyp: Int): String = when (atyp) {
        1 -> { val b = ByteArray(4); dis.readFully(b); b.joinToString(".") { (it.toInt() and 0xFF).toString() } }
        3 -> { val len = dis.readUnsignedByte(); val b = ByteArray(len); dis.readFully(b); String(b, Charsets.US_ASCII) }
        4 -> { val b = ByteArray(16); dis.readFully(b); b.joinToString(":") { "%02x".format(it) } }
        else -> throw IOException("Bad ATYP=$atyp")
    }

    // ========================================================================
    // VPN-AWARE TCP UPSTREAM
    //
    // If a VPN was active when start() was called, we use a plain Socket()
    // which lets the kernel route through tun0 (the VPN's interface). The
    // carrier then sees only the encrypted stream to the VPN endpoint.
    //
    // If no VPN is active, we keep the original behavior: explicitly pin
    // to the cellular Network via socketFactory. This preserves the app's
    // carrier-bypass trick when the user isn't running a VPN.
    // ========================================================================
    private fun openTcpUpstream(host: String, port: Int): Socket {
        val sock: Socket = if (vpnActiveAtStart) {
            // VPN active — let kernel route through tun0
            Log.d(TAG, "openTcpUpstream: VPN active, routing via default route (tun0)")
            Socket()
        } else {
            // No VPN — pin to cellular (original behavior)
            CellularNetworkTracker.createCellularSocket() ?: Socket()
        }
        sock.tcpNoDelay = true; sock.soTimeout = 60000
        // 4MB buffers for high throughput (was 512KB)
        try { sock.receiveBufferSize = 4 * 1024 * 1024; sock.sendBufferSize = 4 * 1024 * 1024 } catch (_: Exception) {}
        try { sock.connect(InetSocketAddress(host, port), 30000) } catch (e: Exception) {
            try { sock.close() } catch (_: IOException) {}
            val fresh = Socket()
            fresh.tcpNoDelay = true; fresh.soTimeout = 60000
            try { fresh.receiveBufferSize = 4 * 1024 * 1024; fresh.sendBufferSize = 4 * 1024 * 1024 } catch (_: Exception) {}
            fresh.connect(InetSocketAddress(host, port), 30000)
            return fresh
        }
        return sock
    }

    private fun reply(output: OutputStream, code: Int) {
        output.write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
    }

    private fun pump(input: InputStream, output: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(64 * 1024)
        try { while (running) { val n = input.read(buf); if (n < 0) break; if (n == 0) continue; output.write(buf, 0, n); counter.addAndGet(n.toLong()) } } catch (_: IOException) {} finally { try { output.close() } catch (_: IOException) {}; try { input.close() } catch (_: IOException) {} }
    }

    inner class UdpAssociate(private val bindHost: String, private val clientHost: String) {
        private val relaySocket = DatagramSocket()
        @Volatile var boundPort: Int = 0; private set
        @Volatile private var running = false
        private var upstreamSocket: DatagramSocket? = null
        private val natTable = ConcurrentHashMap<String, InetSocketAddress>()
        private var relayThread: Thread? = null
        private var upstreamThread: Thread? = null

        fun start() {
            boundPort = relaySocket.localPort; running = true
            val net = CellularNetworkTracker.getCellularNetwork()
            upstreamSocket = DatagramSocket()
            try {
                upstreamSocket?.soTimeout = 0
                // PATCH: VPN-aware UDP upstream.
                // If a VPN is active, do NOT call net.bindSocket() — let the
                // kernel route through tun0. The DatagramSocket() default
                // routes via the kernel's routing table, which the VPN has
                // modified to capture all traffic.
                if (!vpnActiveAtStart && net != null) {
                    net.bindSocket(upstreamSocket)
                }
                Log.d(TAG, "UdpAssociate.start: VPN active=$vpnActiveAtStart, bindSocket=${!vpnActiveAtStart && net != null}")
            } catch (_: Exception) {}
            relayThread = Thread({ relayLoop() }, "socks-udp-$boundPort-relay").apply { isDaemon = true; start() }
            upstreamThread = Thread({ upstreamLoop() }, "socks-udp-$boundPort-upstream").apply { isDaemon = true; start() }
        }

        fun close() { running = false; try { relaySocket.close() } catch (_: Exception) {}; try { upstreamSocket?.close() } catch (_: Exception) {}; natTable.clear() }

        fun awaitIdleOrClientClose(clientInput: InputStream) {
            val idleTimeoutMs = 120_000L
            val lastActivity = AtomicLong(System.currentTimeMillis())
            Thread({ while (running) { try { Thread.sleep(5000) } catch (_: InterruptedException) { break }; if (System.currentTimeMillis() - lastActivity.get() > idleTimeoutMs) { running = false; try { relaySocket.close() } catch (_: Exception) {}; try { upstreamSocket?.close() } catch (_: Exception) {}; return@Thread } } }, "socks-udp-$boundPort-idle").apply { isDaemon = true; start() }
            Thread({ val iRx = rxBytes.get(); val iTx = txBytes.get(); while (running) { try { Thread.sleep(2000) } catch (_: InterruptedException) { break }; if (rxBytes.get() > iRx || txBytes.get() > iTx) lastActivity.set(System.currentTimeMillis()) } }, "socks-udp-$boundPort-hook").apply { isDaemon = true; start() }
            try { val buf = ByteArray(64); while (running) { if (clientInput.read(buf) < 0) break } } catch (_: IOException) {}
            running = false; try { relaySocket.close() } catch (_: Exception) {}; try { upstreamSocket?.close() } catch (_: Exception) {}
        }

        private fun relayLoop() {
            val recvBuf = ByteArray(64 * 1024); val pkt = DatagramPacket(recvBuf, recvBuf.size); var cnt = 0
            while (running) {
                try { relaySocket.receive(pkt) } catch (_: IOException) { break }
                val from = pkt.socketAddress as? InetSocketAddress ?: continue
                // M9 fix: enforce clientHost — only accept UDP relay packets from the client
                // that opened the association.
                val fromHost = from.address.hostAddress
                if (fromHost != null && fromHost != clientHost) continue
                val data = pkt.data.copyOfRange(0, pkt.length)
                val unwrapped = unwrapUdpRequest(data) ?: continue
                val (dstHost, dstPort, payload) = unwrapped
                val dst = InetSocketAddress(dstHost, dstPort)
                try { upstreamSocket?.send(DatagramPacket(payload, payload.size, dst)); rxBytes.addAndGet(payload.size.toLong()); cnt++; natTable["$dstHost:$dstPort"] = from } catch (_: Exception) {}
            }
        }

        private fun upstreamLoop() {
            val upSock = upstreamSocket ?: return
            val recvBuf = ByteArray(64 * 1024); val pkt = DatagramPacket(recvBuf, recvBuf.size); var cnt = 0
            while (running) {
                try { upSock.receive(pkt) } catch (_: IOException) { break }
                val from = pkt.socketAddress as? InetSocketAddress ?: continue
                val data = pkt.data.copyOfRange(0, pkt.length)
                val srcHost = from.address.hostAddress ?: continue
                val srcPort = from.port
                val receiverAddr = natTable["$srcHost:$srcPort"] ?: continue
                val wrapped = wrapUdpResponse(srcHost, srcPort, data)
                try { relaySocket.send(DatagramPacket(wrapped, wrapped.size, receiverAddr)); txBytes.addAndGet(data.size.toLong()); cnt++ } catch (_: IOException) {}
            }
        }

        private fun unwrapUdpRequest(data: ByteArray): Triple<String, Int, ByteArray>? {
            if (data.size < 4) return null
            val frag = data[2].toInt() and 0xFF
            if (frag != 0) return null
            val atyp = data[3].toInt() and 0xFF
            var off = 4
            val host: String = when (atyp) {
                1 -> { if (data.size < off + 4) return null; val b = data.copyOfRange(off, off + 4); off += 4; b.joinToString(".") { (it.toInt() and 0xFF).toString() } }
                3 -> { if (data.size < off + 1) return null; val len = data[off].toInt() and 0xFF; off += 1; if (data.size < off + len) return null; val b = data.copyOfRange(off, off + len); off += len; String(b, Charsets.US_ASCII) }
                4 -> { if (data.size < off + 16) return null; val b = data.copyOfRange(off, off + 16); off += 16; b.joinToString(":") { "%02x".format(it) } }
                else -> return null
            }
            if (data.size < off + 2) return null
            val port = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
            off += 2
            return Triple(host, port, data.copyOfRange(off, data.size))
        }

        private fun wrapUdpResponse(host: String, port: Int, data: ByteArray): ByteArray {
            val addrBytes = InetAddress.getByName(host).address
            val atyp: Byte = if (addrBytes.size == 4) 0x01 else 0x04
            val out = java.io.ByteArrayOutputStream(4 + addrBytes.size + 2 + data.size)
            out.write(0); out.write(0); out.write(0); out.write(atyp.toInt()); out.write(addrBytes); out.write((port ushr 8) and 0xFF); out.write(port and 0xFF); out.write(data)
            return out.toByteArray()
        }
    }
}
