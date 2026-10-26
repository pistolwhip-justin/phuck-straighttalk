package com.datanet.share.receiver

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TcpFlow(
    val id: Int,
    val key: Tun2Socks.FlowKey,
    private val initialSeq: Int,
    private val socksHost: String,
    private val socksPort: Int,
    private val protect: (Socket) -> Boolean,
    private val onTx: (Int) -> Unit,
    private val onRx: (Int) -> Unit,
    private val writePacket: (ByteArray) -> Unit
) {
    companion object { private const val TAG = "TcpFlow"; private const val MSS = 1460; private const val QUEUE_CAPACITY = 500 }

    private val nextExpectedAppSeq = AtomicLong((initialSeq + 1).toLong() and 0xFFFFFFFFL)
    private val ourSeq = AtomicLong(1L)
    private var upstream: Socket? = null
    private var upIn: InputStream? = null
    private var upOut: OutputStream? = null
    @Volatile private var closed = false
    @Volatile private var upstreamReady = false
    private val sendQueue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private var writerThread: Thread? = null

    fun handleData(payload: ByteArray, seq: Int) {
        if (closed || payload.isEmpty()) return
        if (!sendQueue.offer(payload)) return
        val ackNum = ((seq.toLong() and 0xFFFFFFFFL) + payload.size) and 0xFFFFFFFFL
        nextExpectedAppSeq.set(ackNum)
        sendAck(ackNum.toInt())
    }

    fun handleFin(seqAfterFin: Int) {
        if (closed) return
        Thread({
            var waited = 0
            while (sendQueue.isNotEmpty() && waited < 5000 && !closed) { try { Thread.sleep(20) } catch (_: InterruptedException) {}; waited += 20 }
            try { upOut?.flush() } catch (_: IOException) {}
            try { upstream?.shutdownOutput() } catch (_: IOException) {}
            sendFinAck(seqAfterFin); close()
        }, "tcp-${id}-fin").start()
    }

    fun openUpstream(dstIp: ByteArray, dstPort: Int) {
        val host = InetAddress.getByAddress(dstIp).hostAddress
        val sock = Socket()
        try {
            sock.tcpNoDelay = true; sock.soTimeout = 0
            sock.sendBufferSize = 256 * 1024; sock.receiveBufferSize = 256 * 1024
            if (!protect(sock)) Log.w(TAG, "[$id] protect() failed")
            sock.connect(InetSocketAddress(socksHost, socksPort), 15000)
            val out = sock.getOutputStream(); val inp = sock.getInputStream(); val dis = DataInputStream(inp)
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            if (dis.readUnsignedByte() != 5 || dis.readUnsignedByte() != 0) throw IOException("SOCKS5 handshake failed")
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x03))
            val hb = host.toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(hb.size.toByte())); out.write(hb)
            out.write(byteArrayOf((dstPort ushr 8).toByte(), dstPort.toByte())); out.flush()
            if (dis.readUnsignedByte() != 5) throw IOException("bad reply ver")
            if (dis.readUnsignedByte() != 0) throw IOException("SOCKS5 reply code")
            dis.readUnsignedByte()
            val atyp = dis.readUnsignedByte()
            when (atyp) { 1 -> dis.readFully(ByteArray(4)); 3 -> { val l = dis.readUnsignedByte(); dis.readFully(ByteArray(l)) }; 4 -> dis.readFully(ByteArray(16)) }
            dis.readUnsignedShort()
            upstream = sock; upIn = inp; upOut = out; upstreamReady = true
            writerThread = Thread({ writerLoop() }, "tcp-${id}-writer").apply { isDaemon = true; start() }
            Thread({ pumpUpstream() }, "tcp-${id}-reader").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            try { sock.close() } catch (_: IOException) {}
            try { sendRst() } catch (_: Throwable) {}
            close()
        }
    }

    private fun writerLoop() {
        val out = upOut ?: return
        try { while (!closed && upstreamReady) {
            val data = sendQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            try { out.write(data); out.flush(); onRx(data.size) } catch (e: IOException) { if (!closed) Log.d(TAG, "[$id] writer: ${e.message}"); break } catch (t: Throwable) { break }
        } } catch (_: InterruptedException) {} catch (t: Throwable) { Log.w(TAG, "[$id] writerLoop crashed: ${t.message}") }
    }

    private fun pumpUpstream() {
        val buf = ByteArray(64 * 1024)
        try { while (!closed) {
            val n = upIn?.read(buf) ?: -1
            if (n <= 0) break
            var off = 0
            while (off < n && !closed) {
                val chunkLen = minOf(n - off, MSS)
                val chunk = buf.copyOfRange(off, off + chunkLen)
                try { sendData(chunk) } catch (t: Throwable) { break }
                onTx(chunkLen); off += chunkLen
            }
        } } catch (e: IOException) { if (!closed) Log.d(TAG, "[$id] reader EOF: ${e.message}") } catch (t: Throwable) {} finally { try { sendFin() } catch (_: Throwable) {} }
    }

    fun sendSynAck(ackNum: Int) { writePacket(buildTcpPacket(key.dstIp, key.dstPort, key.srcIp, key.srcPort, 0, ackNum, 0x12, ByteArray(0))); ourSeq.set(1L) }
    fun sendAck(ackNum: Int) { writePacket(buildTcpPacket(key.dstIp, key.dstPort, key.srcIp, key.srcPort, ourSeq.get().toInt(), ackNum, 0x10, ByteArray(0))) }
    fun sendData(data: ByteArray) { writePacket(buildTcpPacket(key.dstIp, key.dstPort, key.srcIp, key.srcPort, ourSeq.get().toInt(), nextExpectedAppSeq.get().toInt(), 0x18, data)); ourSeq.addAndGet(data.size.toLong()) }
    fun sendFin() { if (closed) return; writePacket(buildTcpPacket(key.dstIp, key.dstPort, key.srcIp, key.srcPort, ourSeq.get().toInt(), nextExpectedAppSeq.get().toInt(), 0x11, ByteArray(0))); ourSeq.incrementAndGet() }
    fun sendFinAck(ackNum: Int) { writePacket(buildTcpPacket(key.dstIp, key.dstPort, key.srcIp, key.srcPort, ourSeq.get().toInt(), ackNum, 0x11, ByteArray(0))) }
    fun sendRst() { writePacket(buildTcpPacket(key.dstIp, key.dstPort, key.srcIp, key.srcPort, ourSeq.get().toInt(), nextExpectedAppSeq.get().toInt(), 0x04, ByteArray(0))) }

    fun close() { if (closed) return; closed = true; try { upstream?.close() } catch (_: IOException) {}; writerThread?.interrupt() }

    private fun buildTcpPacket(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, seq: Int, ack: Int, flags: Int, payload: ByteArray): ByteArray {
        val tcpLen = 20 + payload.size; val totalLen = 20 + tcpLen
        val pkt = ByteArray(totalLen); val bb = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        bb.put(0, ((4 shl 4) or 5).toByte()); bb.put(1, 0); bb.putShort(2, totalLen.toShort()); bb.putShort(4, 0); bb.putShort(6, 0x4000.toShort()); bb.put(8, 64.toByte()); bb.put(9, 6); bb.putShort(10, 0)
        bb.put(12, srcIp[0]); bb.put(13, srcIp[1]); bb.put(14, srcIp[2]); bb.put(15, srcIp[3]); bb.put(16, dstIp[0]); bb.put(17, dstIp[1]); bb.put(18, dstIp[2]); bb.put(19, dstIp[3])
        bb.putShort(20, srcPort.toShort()); bb.putShort(22, dstPort.toShort()); bb.putInt(24, seq); bb.putInt(28, ack); bb.put(32, ((5 shl 4).toByte())); bb.put(33, flags.toByte()); bb.putShort(34, 0xFFFF.toShort()); bb.putShort(36, 0); bb.putShort(38, 0)
        if (payload.isNotEmpty()) { bb.position(40); bb.put(payload) }
        bb.putShort(36, tcpChecksum(pkt, 20, tcpLen, srcIp, dstIp).toShort())
        bb.putShort(10, ipChecksum(pkt, 20).toShort())
        return pkt
    }

    private fun tcpChecksum(pkt: ByteArray, off: Int, len: Int, srcIp: ByteArray, dstIp: ByteArray): Int {
        val pseudo = ByteArray(12 + len)
        pseudo[0] = srcIp[0]; pseudo[1] = srcIp[1]; pseudo[2] = srcIp[2]; pseudo[3] = srcIp[3]; pseudo[4] = dstIp[0]; pseudo[5] = dstIp[1]; pseudo[6] = dstIp[2]; pseudo[7] = dstIp[3]; pseudo[8] = 0; pseudo[9] = 6
        pseudo[10] = ((len ushr 8) and 0xFF).toByte(); pseudo[11] = (len and 0xFF).toByte()
        System.arraycopy(pkt, off, pseudo, 12, len); pseudo[12 + 16] = 0; pseudo[12 + 17] = 0
        return checksum(pseudo, pseudo.size)
    }

    private fun ipChecksum(pkt: ByteArray, len: Int): Int { val s1 = pkt[10]; val s2 = pkt[11]; pkt[10] = 0; pkt[11] = 0; val c = checksum(pkt, len); pkt[10] = s1; pkt[11] = s2; return c }

    private fun checksum(buf: ByteArray, len: Int): Int {
        var sum = 0L; var i = 0
        while (i + 1 < len) { sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF); i += 2 }
        if (i < len) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }
}
