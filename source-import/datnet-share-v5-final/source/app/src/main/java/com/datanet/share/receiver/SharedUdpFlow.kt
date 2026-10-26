package com.datanet.share.receiver

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SharedUdpFlow(
    private val socksHost: String,
    private val socksPort: Int,
    private val protect: (Socket) -> Boolean,
    private val protectDatagram: ((java.net.DatagramSocket) -> Boolean)?,
    private val writePacket: (ByteArray) -> Unit,
    private val onRx: (Int) -> Unit,
    private val onTx: (Int) -> Unit
) {
    companion object { private const val TAG = "SharedUdpFlow"; private const val MAX_PACKET = 1500; private const val IDLE_CLEANUP_MS = 120_000L }

    @Volatile private var closed = false
    @Volatile private var ready = false
    private var controlSocket: Socket? = null
    private var localUdp: DatagramSocket? = null
    private var relayAddr: InetSocketAddress? = null

    private data class OutgoingPacket(val srcIp: ByteArray, val srcPort: Int, val dstHost: String, val dstPort: Int, val payload: ByteArray)
    private val sendQueue = LinkedBlockingQueue<OutgoingPacket>(500)
    private data class NatEntry(val srcIp: ByteArray, val srcPort: Int, val lastSeen: Long)
    private val natTable = ConcurrentHashMap<String, NatEntry>()
    private val lastActivity = AtomicLong(System.currentTimeMillis())

    fun start() {
        val ctrl = Socket()
        ctrl.tcpNoDelay = true; ctrl.soTimeout = 0
        if (!protect(ctrl)) Log.w(TAG, "protect() failed for UDP control socket")
        ctrl.connect(InetSocketAddress(socksHost, socksPort), 10000)
        val out = ctrl.getOutputStream(); val inp = ctrl.getInputStream(); val dis = DataInputStream(inp)
        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        if (dis.readUnsignedByte() != 5 || dis.readUnsignedByte() != 0) throw IOException("SOCKS5 handshake failed")
        out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
        if (dis.readUnsignedByte() != 5) throw IOException("bad reply ver")
        if (dis.readUnsignedByte() != 0) throw IOException("UDP_ASSOCIATE failed")
        dis.readUnsignedByte()
        val atyp = dis.readUnsignedByte()
        val relayHost: String = when (atyp) {
            1 -> { val b = ByteArray(4); dis.readFully(b); b.joinToString(".") { (it.toInt() and 0xFF).toString() } }
            3 -> { val len = dis.readUnsignedByte(); val b = ByteArray(len); dis.readFully(b); String(b, Charsets.US_ASCII) }
            4 -> { val b = ByteArray(16); dis.readFully(b); b.joinToString(":") { "%02x".format(it) } }
            else -> throw IOException("bad atyp=$atyp")
        }
        val relayPort = dis.readUnsignedShort()
        controlSocket = ctrl; relayAddr = InetSocketAddress(relayHost, relayPort)
        val udp = DatagramSocket(); udp.soTimeout = 0
        if (protectDatagram != null) { if (!protectDatagram.invoke(udp)) Log.w(TAG, "protect() failed for local UDP socket") }
        localUdp = udp; ready = true
        Thread({ senderLoop() }, "udp-shared-snd").apply { isDaemon = true; start() }
        Thread({ receiverLoop() }, "udp-shared-rcv").apply { isDaemon = true; start() }
        Thread({ controlKeeperLoop(inp) }, "udp-shared-ctrl").apply { isDaemon = true; start() }
        Thread({ cleanerLoop() }, "udp-shared-clean").apply { isDaemon = true; start() }
    }

    fun send(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, payload: ByteArray) {
        if (closed || !ready) return
        val dstHost = try { InetAddress.getByAddress(dstIp).hostAddress ?: return } catch (_: Exception) { return }
        if (!sendQueue.offer(OutgoingPacket(srcIp, srcPort, dstHost, dstPort, payload))) return
        natTable["$dstHost:$dstPort"] = NatEntry(srcIp, srcPort, System.currentTimeMillis())
        lastActivity.set(System.currentTimeMillis())
    }

    private fun senderLoop() {
        val udp = localUdp ?: return; val relay = relayAddr ?: return; var cnt = 0
        try { while (!closed && ready) {
            val pkt = sendQueue.poll(1, TimeUnit.SECONDS) ?: continue
            val wrapped = wrapUdpRequest(pkt.dstHost, pkt.dstPort, pkt.payload)
            try { udp.send(DatagramPacket(wrapped, wrapped.size, relay)); onTx(pkt.payload.size); cnt++; if (cnt % 50 == 1) Log.i(TAG, "UDP sent $cnt packets to relay") } catch (_: IOException) {}
        } } catch (_: InterruptedException) {} catch (t: Throwable) { Log.w(TAG, "senderLoop crashed: ${t.message}") }
    }

    private fun receiverLoop() {
        val udp = localUdp ?: return; val buf = ByteArray(MAX_PACKET); val pkt = DatagramPacket(buf, buf.size); var cnt = 0
        try { while (!closed && ready) {
            udp.receive(pkt)
            val data = pkt.data.copyOfRange(0, pkt.length)
            val unwrapped = unwrapUdpResponse(data) ?: continue
            val (srcHost, srcPort, payload) = unwrapped
            val entry = natTable["$srcHost:$srcPort"] ?: continue
            writePacket(buildUdpPacket(InetAddress.getByName(srcHost).address, srcPort, entry.srcIp, entry.srcPort, payload))
            onRx(payload.size); lastActivity.set(System.currentTimeMillis()); cnt++
        } } catch (e: IOException) { if (!closed) Log.d(TAG, "UDP receiver EOF: ${e.message}") } catch (t: Throwable) { if (!closed) Log.w(TAG, "receiverLoop crashed: ${t.message}") }
    }

    private fun controlKeeperLoop(controlInput: java.io.InputStream) {
        try { val buf = ByteArray(64); while (!closed && ready) { if (controlInput.read(buf) < 0) break } } catch (_: IOException) {}
        if (!closed) closed = true
    }

    private fun cleanerLoop() {
        while (!closed) {
            try { Thread.sleep(30000) } catch (_: InterruptedException) { break }
            val now = System.currentTimeMillis()
            val it = natTable.entries.iterator()
            while (it.hasNext()) { if (now - it.next().value.lastSeen > IDLE_CLEANUP_MS) it.remove() }
        }
    }

    fun close() { if (closed) return; closed = true; try { controlSocket?.close() } catch (_: IOException) {}; try { localUdp?.close() } catch (_: IOException) {}; natTable.clear() }
    fun isClosed(): Boolean = closed

    private fun wrapUdpRequest(host: String, port: Int, payload: ByteArray): ByteArray {
        val addrBytes = InetAddress.getByName(host).address
        val atyp: Byte = if (addrBytes.size == 4) 0x01 else 0x04
        val out = java.io.ByteArrayOutputStream(4 + addrBytes.size + 2 + payload.size)
        out.write(0); out.write(0); out.write(0); out.write(atyp.toInt()); out.write(addrBytes); out.write((port ushr 8) and 0xFF); out.write(port and 0xFF); out.write(payload)
        return out.toByteArray()
    }

    private fun unwrapUdpResponse(data: ByteArray): Triple<String, Int, ByteArray>? {
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

    private fun buildUdpPacket(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size; val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen); val bb = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        bb.put(0, ((4 shl 4) or 5).toByte()); bb.put(1, 0); bb.putShort(2, totalLen.toShort()); bb.putShort(4, 0); bb.putShort(6, 0x4000.toShort()); bb.put(8, 64.toByte()); bb.put(9, 17); bb.putShort(10, 0)
        bb.put(12, srcIp[0]); bb.put(13, srcIp[1]); bb.put(14, srcIp[2]); bb.put(15, srcIp[3]); bb.put(16, dstIp[0]); bb.put(17, dstIp[1]); bb.put(18, dstIp[2]); bb.put(19, dstIp[3])
        bb.putShort(20, srcPort.toShort()); bb.putShort(22, dstPort.toShort()); bb.putShort(24, udpLen.toShort()); bb.putShort(26, 0)
        bb.position(28); bb.put(payload)
        pkt[10] = 0; pkt[11] = 0
        var sum = 0L; var i = 0
        while (i + 1 < 20) { sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF); i += 2 }
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        val c = sum.inv().toInt() and 0xFFFF
        pkt[10] = (c shr 8).toByte(); pkt[11] = (c and 0xFF).toByte()
        return pkt
    }
}
