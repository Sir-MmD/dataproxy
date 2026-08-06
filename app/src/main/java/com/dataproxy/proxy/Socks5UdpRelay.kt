package com.dataproxy.proxy

import android.util.Log
import com.dataproxy.network.CellularNetworkProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * SOCKS5 UDP ASSOCIATE relay (RFC 1928 §7).
 *
 * Listens on [listenAddress] for client UDP packets carrying a SOCKS5 UDP
 * header, forwards their payload to the actual destination over the cellular
 * network, then wraps and forwards replies back.
 *
 * One relay per TCP control connection. The relay shuts down when [close]
 * is called — typically when the TCP control closes.
 */
class Socks5UdpRelay(
    private val cellular: CellularNetworkProvider,
    private val listenAddress: InetAddress,
    private val scope: CoroutineScope,
    private val onBytes: (up: Int, down: Int) -> Unit = { _, _ -> },
) : Closeable {

    private val clientSocket: DatagramSocket = DatagramSocket(
        InetSocketAddress(listenAddress, 0),
    )
    private val remoteSocket: DatagramSocket = cellular.createBoundDatagramSocket()

    private var clientLoopJob: Job? = null
    private var remoteLoopJob: Job? = null

    /** Set to the first packet's source — that's where replies go. */
    @Volatile private var clientReplyAddr: InetSocketAddress? = null

    val port: Int get() = clientSocket.localPort

    fun start() {
        clientLoopJob = scope.launch(RelayDispatcher) { clientLoop() }
        remoteLoopJob = scope.launch(RelayDispatcher) { remoteLoop() }
    }

    private fun clientLoop() {
        val buf = ByteArray(65535)
        while (!clientSocket.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                clientSocket.receive(pkt)
            } catch (e: Exception) {
                if (!clientSocket.isClosed) Log.d(TAG, "client recv ended: ${e.message}")
                break
            }
            val src = pkt.socketAddress as? InetSocketAddress ?: continue
            clientReplyAddr = src

            val parsed = parseUdpRequest(buf, pkt.length) ?: continue
            val resolved: InetAddress? = when (parsed.dst) {
                is UdpDst.Ip -> parsed.dst.addr
                is UdpDst.Host -> runCatching { cellular.resolveHost(parsed.dst.name) }.getOrNull()
            }
            if (resolved == null) continue

            try {
                remoteSocket.send(
                    DatagramPacket(
                        buf, parsed.dataOffset, parsed.dataLength,
                        resolved, parsed.dstPort,
                    ),
                )
                if (parsed.dataLength > 0) onBytes(parsed.dataLength, 0)
            } catch (e: Exception) {
                Log.d(TAG, "remote send failed: ${e.message}")
            }
        }
    }

    private fun remoteLoop() {
        val buf = ByteArray(65535)
        while (!remoteSocket.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                remoteSocket.receive(pkt)
            } catch (e: Exception) {
                if (!remoteSocket.isClosed) Log.d(TAG, "remote recv ended: ${e.message}")
                break
            }
            val reply = clientReplyAddr ?: continue
            val out = wrapUdpResponse(pkt.address, pkt.port, buf, 0, pkt.length)
            try {
                clientSocket.send(DatagramPacket(out, out.size, reply.address, reply.port))
                if (pkt.length > 0) onBytes(0, pkt.length)
            } catch (e: Exception) {
                Log.d(TAG, "client send failed: ${e.message}")
            }
        }
    }

    override fun close() {
        runCatching { clientSocket.close() }
        runCatching { remoteSocket.close() }
        clientLoopJob?.cancel()
        remoteLoopJob?.cancel()
    }

    private sealed interface UdpDst {
        data class Ip(val addr: InetAddress) : UdpDst
        data class Host(val name: String) : UdpDst
    }

    private data class UdpRequest(
        val dst: UdpDst,
        val dstPort: Int,
        val dataOffset: Int,
        val dataLength: Int,
    )

    private fun parseUdpRequest(buf: ByteArray, len: Int): UdpRequest? {
        if (len < 7) return null
        if (buf[0].toInt() != 0 || buf[1].toInt() != 0) return null // RSV must be 00 00
        if (buf[2].toInt() != 0) return null // FRAG: no fragmentation support
        val atyp = buf[3].toInt() and 0xFF
        var pos = 4
        val dst: UdpDst = when (atyp) {
            0x01 -> {
                if (pos + 4 > len) return null
                val raw = buf.copyOfRange(pos, pos + 4)
                pos += 4
                UdpDst.Ip(InetAddress.getByAddress(raw))
            }
            0x03 -> {
                if (pos + 1 > len) return null
                val dlen = buf[pos].toInt() and 0xFF
                pos += 1
                if (pos + dlen > len) return null
                val host = String(buf, pos, dlen, Charsets.US_ASCII)
                pos += dlen
                UdpDst.Host(host)
            }
            0x04 -> {
                if (pos + 16 > len) return null
                val raw = buf.copyOfRange(pos, pos + 16)
                pos += 16
                UdpDst.Ip(InetAddress.getByAddress(raw))
            }
            else -> return null
        }
        if (pos + 2 > len) return null
        val dstPort = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        pos += 2
        return UdpRequest(dst, dstPort, pos, len - pos)
    }

    private fun wrapUdpResponse(
        addr: InetAddress, port: Int,
        data: ByteArray, dataOffset: Int, dataLength: Int,
    ): ByteArray {
        val isV6 = addr is Inet6Address
        val addrBytes = addr.address
        val header = ByteArray(4 + addrBytes.size + 2)
        header[0] = 0
        header[1] = 0
        header[2] = 0
        header[3] = (if (isV6) 0x04 else 0x01).toByte()
        System.arraycopy(addrBytes, 0, header, 4, addrBytes.size)
        header[4 + addrBytes.size] = ((port ushr 8) and 0xFF).toByte()
        header[5 + addrBytes.size] = (port and 0xFF).toByte()
        val out = ByteArray(header.size + dataLength)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(data, dataOffset, out, header.size, dataLength)
        return out
    }

    companion object {
        private const val TAG = "Socks5Udp"
    }
}
