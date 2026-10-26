package com.datanet.share.receiver

import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Tun2Socks(
    private val tunFd: FileDescriptor,
    private val socksHost: String,
    private val socksPort: Int,
    private val protect: (Socket) -> Boolean,
    private val protectDatagram: ((java.net.DatagramSocket) -> Boolean)?,
    private val onStats: (active: Int, rx: Long, tx: Long) -> Unit
) {
    companion object { private const val TAG = "Tun2Socks" }

    @Volatile private var running = false
    private val connections = ConcurrentHashMap<FlowKey, TcpFlow>()
    private val flowId = AtomicInteger(0)
    @Volatile private var sharedUdp: SharedUdpFlow? = null
    private val udpLock = Any()
    @Volatile private var udpCreationInProgress = false
    private val rxBytes = AtomicLong(0)
    private val txBytes = AtomicLong(0)
    private val packetCount = AtomicLong(0)
    private val tcpSynCount = AtomicLong(0)
    private lateinit var tunInput: FileInputStream

    fun start() {
        if (running) return
        running = true
        tunInput = FileInputStream(tunFd)
        Thread({ readLoop() }, "tun2socks-read").start()
        Thread({ statsLoop() }, "tun2socks-stats").start()
    }

    fun stop() {
        running = false
        for (f in connections.values) f.close()
        connections.clear()
        try { sharedUdp?.close() } catch (_: Exception) {}
        sharedUdp = null
        try { tunInput.close() } catch (_: Exception) {}
    }

    private fun statsLoop() {
        while (running) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            onStats(connections.size, rxBytes.get(), txBytes.get())
        }
    }

    private fun readLoop() {
        val buf = ByteArray(32 * 1024)
        while (running) {
            val n = try { tunInput.read(buf) } catch (e: Throwable) { if (running) Log.w(TAG, "TUN read failed: ${e.message}"); break }
            if (n <= 0) continue
            packetCount.incrementAndGet()
            try { handlePacket(ByteBuffer.wrap(buf, 0, n).order(ByteOrder.BIG_ENDIAN)) } catch (t: Throwable) { Log.w(TAG, "packet handle: ${t.message}") }
        }
    }

    private fun handlePacket(buf: ByteBuffer) {
        if (buf.limit() < 20) return
        val verIhl = buf.get(0).toInt() and 0xFF
        if ((verIhl shr 4) and 0x0F != 4) return
        val ihl = (verIhl and 0x0F) * 4
        if (ihl < 20) return
        val totalLen = (buf.get(2).toInt() and 0xFF shl 8) or (buf.get(3).toInt() and 0xFF)
        if (totalLen > buf.limit()) return
        buf.limit(totalLen)
        val protocol = buf.get(9).toInt() and 0xFF
        val srcIp = readIpv4(buf, 12); val dstIp = readIpv4(buf, 16)
        when (protocol) { 6 -> handleTcp(buf, ihl, srcIp, dstIp); 17 -> handleUdp(buf, ihl, srcIp, dstIp) }
    }

    private fun handleTcp(buf: ByteBuffer, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val srcPort = ((buf.get(ihl).toInt() and 0xFF) shl 8) or (buf.get(ihl + 1).toInt() and 0xFF)
        val dstPort = ((buf.get(ihl + 2).toInt() and 0xFF) shl 8) or (buf.get(ihl + 3).toInt() and 0xFF)
        val seq = readInt(buf, ihl + 4)
        val dataOff = ((buf.get(ihl + 12).toInt() and 0xFF) shr 4) * 4
        val flags = buf.get(ihl + 13).toInt() and 0xFF
        val key = FlowKey(srcIp, srcPort, dstIp, dstPort)
        val isSyn = (flags and 0x02) != 0; val isFin = (flags and 0x01) != 0; val isRst = (flags and 0x04) != 0
        val payloadStart = ihl + dataOff; val payloadLen = buf.limit() - payloadStart
        if (isRst) { connections.remove(key)?.close(); return }
        if (isSyn) {
            tcpSynCount.incrementAndGet()
            if (connections.containsKey(key)) return
            val flow = TcpFlow(flowId.incrementAndGet(), key, seq, socksHost, socksPort, protect, { n -> txBytes.addAndGet(n.toLong()) }, { n -> rxBytes.addAndGet(n.toLong()) }, this::writePacket)
            connections[key] = flow
            try { flow.sendSynAck(seq + 1) } catch (_: Throwable) {}
            Thread({ try { flow.openUpstream(dstIp, dstPort) } catch (t: Throwable) { try { flow.sendRst() } catch (_: Throwable) {}; connections.remove(key); try { flow.close() } catch (_: Throwable) {} } }, "tcp-${key.hashCode()}").apply { isDaemon = true }.start()
            return
        }
        val flow = connections[key] ?: return
        if (payloadLen > 0) { try { val p = ByteArray(payloadLen); buf.position(payloadStart); buf.get(p); flow.handleData(p, seq) } catch (_: Throwable) {} }
        if (isFin) { try { flow.handleFin(seq + payloadLen + 1) } catch (_: Throwable) {}; connections.remove(key) }
    }

    private fun handleUdp(buf: ByteBuffer, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val srcPort = ((buf.get(ihl).toInt() and 0xFF) shl 8) or (buf.get(ihl + 1).toInt() and 0xFF)
        val dstPort = ((buf.get(ihl + 2).toInt() and 0xFF) shl 8) or (buf.get(ihl + 3).toInt() and 0xFF)
        val len = ((buf.get(ihl + 4).toInt() and 0xFF) shl 8) or (buf.get(ihl + 5).toInt() and 0xFF)
        if (len < 9) return
        val payload = ByteArray(len - 8); buf.position(ihl + 8); buf.get(payload)
        val flow = getOrCreateSharedUdp() ?: return
        flow.send(srcIp, srcPort, dstIp, dstPort, payload)
    }

    private fun getOrCreateSharedUdp(): SharedUdpFlow? {
        sharedUdp?.let { if (!it.isClosed()) return it }
        synchronized(udpLock) { sharedUdp?.let { if (!it.isClosed()) return it }; if (udpCreationInProgress) return null; udpCreationInProgress = true }
        Thread({
            try {
                val flow = SharedUdpFlow(socksHost, socksPort, protect, protectDatagram, this::writePacket, { n -> rxBytes.addAndGet(n.toLong()) }, { n -> txBytes.addAndGet(n.toLong()) })
                flow.start()
                synchronized(udpLock) { sharedUdp = flow; udpCreationInProgress = false }
            } catch (e: Exception) { synchronized(udpLock) { udpCreationInProgress = false } }
        }, "udp-shared-init").start()
        return null
    }

    fun writePacket(packet: ByteArray) { try { android.system.Os.write(tunFd, ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)) } catch (t: Throwable) { if (running) Log.w(TAG, "TUN write: ${t.message}") } }

    private fun readIpv4(buf: ByteBuffer, off: Int): ByteArray = byteArrayOf(buf.get(off), buf.get(off + 1), buf.get(off + 2), buf.get(off + 3))
    private fun readInt(buf: ByteBuffer, off: Int): Int = ((buf.get(off).toInt() and 0xFF) shl 24) or ((buf.get(off + 1).toInt() and 0xFF) shl 16) or ((buf.get(off + 2).toInt() and 0xFF) shl 8) or (buf.get(off + 3).toInt() and 0xFF)

    data class FlowKey(val srcIp: ByteArray, val srcPort: Int, val dstIp: ByteArray, val dstPort: Int) {
        override fun hashCode(): Int { var h = srcPort * 31 + dstPort; for (b in srcIp) h = h * 31 + b; for (b in dstIp) h = h * 31 + b; return h }
        override fun equals(other: Any?): Boolean { if (this === other) return true; if (other !is FlowKey) return false; return srcPort == other.srcPort && dstPort == other.dstPort && srcIp.contentEquals(other.srcIp) && dstIp.contentEquals(other.dstIp) }
    }
}
