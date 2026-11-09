package com.northproxy.app.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class Tun2Socks(
    private val vpn: VpnService,
    tun: ParcelFileDescriptor,
    private val socksHost: String,
    private val socksPort: Int,
    private val dnsServer: String
) {
    private val running = AtomicBoolean(true)
    private val input = FileInputStream(tun.fileDescriptor)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val outLock = Any()
    private val conns = ConcurrentHashMap<Long, Conn>()

    private companion object {
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
        const val FIN = 0x01
    }

    private class Conn(
        val key: Long,
        val cIp: Int, val cPort: Int,
        val sIp: Int, val sPort: Int
    ) {
        @Volatile var closed = false
        @Volatile var established = false
        var seqC = 0L
        var seqS = Random.nextLong(1000, 900_000)
        var ackC = 0L
        var sock: Socket? = null
        var sin: InputStream? = null
        var sout: OutputStream? = null
    }

    fun start() {
        Thread { readLoop() }.apply { name = "tun-read"; start() }
    }

    fun stop() {
        running.set(false)
        conns.values.forEach { closeConn(it) }
        conns.clear()
        runCatching { input.close() }
    }

    private fun readLoop() {
        val buf = ByteArray(32767)
        while (running.get()) {
            val n = try { input.read(buf) } catch (e: Exception) { break }
            if (n <= 0) continue
            try { handlePacket(buf, n) } catch (_: Exception) {}
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int) {
        if (len < 20) return
        if (((buf[0].toInt() ushr 4) and 0xF) != 4) return
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (len < ihl + 20) return
        val proto = buf[9].toInt() and 0xFF
        val srcIp = readInt(buf, 12)
        val dstIp = readInt(buf, 16)
        when (proto) {
            6 -> handleTcp(buf, ihl, srcIp, dstIp, len)
            17 -> handleUdp(buf, ihl, srcIp, dstIp, len)
        }
    }

    private fun handleTcp(buf: ByteArray, ihl: Int, srcIp: Int, dstIp: Int, len: Int) {
        val srcPort = readShort(buf, ihl)
        val dstPort = readShort(buf, ihl + 2)
        val seq = readInt(buf, ihl + 4).toLong() and 0xFFFFFFFFL
        val off = ((buf[ihl + 12].toInt() ushr 4) and 0xF) * 4
        val flags = buf[ihl + 13].toInt() and 0xFF
        val payloadOff = ihl + off
        val payloadLen = len - payloadOff
        val key = connKey(srcIp, srcPort, dstIp, dstPort)

        if (flags and SYN != 0) {
            val c = Conn(key, srcIp, srcPort, dstIp, dstPort)
            c.seqC = seq + 1
            c.ackC = seq + 1
            if (conns.putIfAbsent(key, c) == null) openUpstream(c)
            return
        }

        val c = conns[key] ?: run {
            if (flags and RST == 0) sendTcp(dstIp, dstPort, srcIp, srcPort, 0, 0, RST or ACK, null, 0)
            return
        }

        if (flags and RST != 0) { closeConn(c); return }

        if (flags and FIN != 0) {
            c.ackC = seq + payloadLen + 1
            sendTcp(dstIp, dstPort, srcIp, srcPort, c.seqS, c.ackC, FIN or ACK, null, 0)
            runCatching { c.sout?.flush() }
            closeConn(c)
            return
        }

        if (payloadLen > 0 && c.established) {
            val payload = buf.copyOfRange(payloadOff, payloadOff + payloadLen)
            c.ackC = seq + payloadLen
            runCatching { c.sout?.write(payload) }
            sendTcp(dstIp, dstPort, srcIp, srcPort, c.seqS, c.ackC, ACK, null, 0)
        } else if (payloadLen == 0 && flags and ACK != 0 && !c.established) {
            c.established = true
        }
    }

    private fun openUpstream(c: Conn) {
        Thread {
            try {
                // Conexión al servidor SOCKS5 (no directamente al destino)
                val s = Socket()
                vpn.protect(s)
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(socksHost, socksPort), 8000)
                val inS = s.getInputStream()
                val outS = s.getOutputStream()

                // Saludo SOCKS5: sin autenticación
                outS.write(byteArrayOf(0x05, 0x01, 0x00))
                outS.flush()
                val greet = ByteArray(2)
                readFully(inS, greet)
                if (greet[0].toInt() != 0x05 || greet[1].toInt() != 0x00) throw Exception("socks")

                // Petición CONNECT al destino original, en IPv4 (ATYP 0x01)
                val req = ByteArray(10)
                req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01
                writeInt(req, 4, c.sIp)
                writeShort(req, 8, c.sPort)
                outS.write(req)
                outS.flush()
                val rep = ByteArray(10)
                readFully(inS, rep)
                if (rep[1].toInt() != 0x00) throw Exception("socks-rep")
                c.sock = s
                c.sin = inS
                c.sout = outS
                c.established = true
                sendTcp(c.sIp, c.sPort, c.cIp, c.cPort, c.seqS, c.ackC, SYN or ACK, null, 0)
                c.seqS += 1
                Thread { pumpDown(c) }.apply { name = "pump"; start() }
            } catch (_: Exception) {
                sendTcp(c.sIp, c.sPort, c.cIp, c.cPort, 0, 0, RST or ACK, null, 0)
                closeConn(c)
            }
        }.apply { name = "connect"; start() }
    }

    private fun pumpDown(c: Conn) {
        val buffer = ByteArray(16384)
        try {
            while (!c.closed) {
                val n = c.sin!!.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                val data = buffer.copyOf(n)
                sendTcp(c.sIp, c.sPort, c.cIp, c.cPort, c.seqS, c.ackC, PSH or ACK, data, n)
                c.seqS += n
            }
            sendTcp(c.sIp, c.sPort, c.cIp, c.cPort, c.seqS, c.ackC, FIN or ACK, null, 0)
            c.seqS += 1
        } catch (_: Exception) {
            runCatching { sendTcp(c.sIp, c.sPort, c.cIp, c.cPort, 0, 0, RST or ACK, null, 0) }
        }
    }

    private fun closeConn(c: Conn) {
        if (c.closed) return
        c.closed = true
        conns.remove(c.key)
        runCatching { c.sout?.flush() }
        runCatching { c.sock?.close() }
    }

    private fun handleUdp(buf: ByteArray, ihl: Int, srcIp: Int, dstIp: Int, len: Int) {
        val srcPort = readShort(buf, ihl)
        val dstPort = readShort(buf, ihl + 2)
        if (dstPort != 53) return
        val payload = buf.copyOfRange(ihl + 8, len)
        Thread {
            try {
                DatagramSocket().use { ds ->
                    vpn.protect(ds)
                    val addr = InetAddress.getByName(dnsServer)
                    ds.soTimeout = 5000
                    ds.send(DatagramPacket(payload, payload.size, addr, 53))
                    val rb = ByteArray(4096)
                    val resp = DatagramPacket(rb, rb.size)
                    ds.receive(resp)
                    sendUdp(dstIp, dstPort, srcIp, srcPort, resp.data.copyOf(resp.length))
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun sendTcp(
        sIp: Int, sPort: Int, dIp: Int, dPort: Int,
        seq: Long, ack: Long, flags: Int, payload: ByteArray?, payloadLen: Int
    ) {
        val total = 40 + payloadLen
        val p = ByteArray(total)
        p[0] = 0x45
        writeShort(p, 2, total)
        writeShort(p, 6, 0x4000)
        p[8] = 64
        p[9] = 6
        writeInt(p, 12, sIp)
        writeInt(p, 16, dIp)
        writeShort(p, 10, checksum(p, 0, 20))
        writeShort(p, 20, sPort)
        writeShort(p, 22, dPort)
        writeInt(p, 24, seq.toInt())
        writeInt(p, 28, ack.toInt())
        p[32] = 0x50
        p[33] = flags.toByte()
        writeShort(p, 34, 0xFFFF)
        if (payload != null && payloadLen > 0) payload.copyInto(p, 40, 0, payloadLen)
        writeShort(p, 36, tcpChecksum(sIp, dIp, p, 20, total - 20))
        writeTun(p, total)
    }

    private fun sendUdp(sIp: Int, sPort: Int, dIp: Int, dPort: Int, payload: ByteArray) {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        writeShort(p, 2, total)
        p[8] = 64
        p[9] = 17
        writeInt(p, 12, sIp)
        writeInt(p, 16, dIp)
        writeShort(p, 10, checksum(p, 0, 20))
        writeShort(p, 20, sPort)
        writeShort(p, 22, dPort)
        writeShort(p, 24, 8 + payload.size)
        payload.copyInto(p, 28)
        writeTun(p, total)
    }

    private fun writeTun(p: ByteArray, len: Int) {
        synchronized(outLock) { runCatching { output.write(p, 0, len) } }
    }

    private fun connKey(a: Int, ap: Int, b: Int, bp: Int): Long =
        (a.toLong() and 0xFFFFFFFFL shl 32) or
            ((ap.toLong() and 0xFFFF) shl 16) or
            (b.toLong() and 0xFFFF)

    private fun intToIp(v: Int) = InetAddress.getByAddress(
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    )

    private fun readFully(inS: InputStream, b: ByteArray) {
        var r = 0
        while (r < b.size) {
            val n = inS.read(b, r, b.size - r)
            if (n < 0) throw Exception("eof")
            r += n
        }
    }

    private fun readShort(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun readInt(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun writeShort(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    private fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 24) and 0xFF).toByte()
        b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    private fun checksum(data: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun tcpChecksum(sIp: Int, dIp: Int, tcp: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        sum += (sIp ushr 16) and 0xFFFF
        sum += sIp and 0xFFFF
        sum += (dIp ushr 16) and 0xFFFF
        sum += dIp and 0xFFFF
        sum += 6
        sum += len
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((tcp[i].toInt() and 0xFF) shl 8) or (tcp[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (tcp[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
