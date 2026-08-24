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
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SOCKS5 UDP ASSOCIATE relay (RFC 1928 §7).
 *
 * Listens on [listenAddress] for client UDP packets carrying a SOCKS5 UDP
 * header, forwards their payload to the actual destination over the cellular
 * network, then wraps and forwards replies back.
 *
 * One relay per TCP control connection. Only [clientAddress], the peer that
 * opened that control connection, may use it: this socket is never touched
 * by the TCP handshake, so without that check the relay is an unauthenticated
 * egress path onto the user's metered data for anyone on the LAN.
 *
 * The relay shuts down when [close] is called, when the TCP control closes,
 * when the proxy stops, or when either loop ends for any reason.
 */
class Socks5UdpRelay(
    private val cellular: CellularNetworkProvider,
    private val listenAddress: InetAddress,
    private val clientAddress: InetAddress,
    private val scope: CoroutineScope,
    private val onBytes: (up: Int, down: Int) -> Unit = { _, _ -> },
    private val onClosed: () -> Unit = {},
) : Closeable {

    private val clientSocket: DatagramSocket = DatagramSocket(
        InetSocketAddress(listenAddress, 0),
    ).apply { soTimeout = POLL_MS }

    // DatagramSocket's constructor binds, so clientSocket already owns an fd by
    // the time this line runs. If acquiring the cellular socket throws, which
    // it does routinely while Paused, the constructor never returns, no
    // instance exists, and close() is unreachable, leaking that fd. A UDP
    // client retrying ASSOCIATE once a second leaks one per attempt until the
    // process hits RLIMIT_NOFILE.
    private val remoteSocket: DatagramSocket = try {
        cellular.createBoundDatagramSocket().apply { soTimeout = POLL_MS }
    } catch (t: Throwable) {
        runCatching { clientSocket.close() }
        throw t
    }

    @Volatile private var clientLoopJob: Job? = null
    @Volatile private var remoteLoopJob: Job? = null
    private val closed = AtomicBoolean(false)

    /**
     * Last datagram in either direction. Both sockets carry a [POLL_MS] read
     * timeout purely so the loops surface periodically to check this: a client
     * that vanishes without closing its TCP control leaves both loops parked
     * in receive() forever, and a silent orphan generates no traffic, so no
     * failure counter ever trips, parking this connection's slot in the
     * server's cap permanently. It also covers a cellular drop while idle:
     * receive() on a socket pinned to a dead Network blocks rather than
     * throwing, so nothing else here would ever notice.
     */
    @Volatile private var lastActivityMs = System.currentTimeMillis()

    /** True once nothing has flowed either way for [IDLE_TIMEOUT_MS]. */
    private fun idleTooLong(): Boolean =
        System.currentTimeMillis() - lastActivityMs > IDLE_TIMEOUT_MS

    /** Source of the last valid datagram from [clientAddress]; replies go here. */
    @Volatile private var clientReplyAddr: InetSocketAddress? = null

    /**
     * Destinations this association has actually sent to. `Network.bindSocket`
     * marks outbound routing only, it does not stop inbound delivery, so the
     * remote socket's wildcard bind is reachable from the LAN on the phone's
     * Wi-Fi address. Without this set, anything that reaches that ephemeral
     * port gets wrapped and handed to the client as a genuine internet reply.
     * Matched on IP only, since a server may legitimately answer from a
     * different port than the one addressed. Values are last-sent timestamps:
     * peer-churning clients (torrent DHT, WebRTC ICE) would blow any fixed
     * cap in minutes, and wiping the set wholesale would drop in-flight
     * replies for every *active* peer, so stale entries are evicted by age
     * instead.
     *
     * Known limitation: a destination that answers from a different IP than
     * the one addressed (some NTP pools, SSDP multicast) has its replies
     * dropped. Acceptable for a cellular relay.
     */
    private val sentTo = ConcurrentHashMap<InetAddress, Long>()

    /** [addr] is null for a cached miss, see [resolveCached]. */
    private class CachedHost(val addr: InetAddress?, val expiresAtMs: Long)

    /**
     * Short-lived hostname cache, scoped to this association.
     *
     * The JVM-wide resolver cache is disabled on purpose (hijacked carrier DNS
     * must not stick), but that made every ATYP=0x03 datagram issue a fresh
     * blocking lookup on the single receive loop, so one unanswered query
     * stalls *all* UDP forwarding for the resolver's retry window, and steady
     * throughput is capped at one datagram per DNS round trip. A few seconds,
     * discarded when the association ends, restores throughput without letting
     * a poisoned answer outlive the connection.
     */
    private val dnsCache = ConcurrentHashMap<String, CachedHost>()

    val port: Int get() = clientSocket.localPort

    fun start() {
        clientLoopJob = scope.launch(RelayDispatcher) { clientLoop() }
        remoteLoopJob = scope.launch(RelayDispatcher) { remoteLoop() }
    }

    private fun resolveCached(name: String): InetAddress? {
        val now = System.currentTimeMillis()
        dnsCache[name]?.let { if (it.expiresAtMs > now) return it.addr }
        // Same cellular-only resolver as the TCP path, and the same family
        // filter: an address this link cannot reach is not a usable answer.
        val addr = runCatching {
            val all = cellular.resolveAll(name)
            if (cellular.hasIpv6()) all.firstOrNull()
            else all.firstOrNull { it !is java.net.Inet6Address }
        }.getOrNull()
        if (dnsCache.size >= MAX_DNS_ENTRIES) dnsCache.clear()
        // Failures are cached too, on a shorter TTL. A blocked or NXDOMAIN
        // host blocks the resolver for its whole retry budget on the single
        // thread draining clientSocket, and an unrecorded miss re-pays that
        // on every retransmit, which is the head-of-line stall this cache
        // exists to remove.
        dnsCache[name] = CachedHost(addr, now + if (addr != null) DNS_TTL_MS else DNS_MISS_TTL_MS)
        return addr
    }

    /** Both only ever touched from clientLoop, which is single-threaded. */
    private var lastEvictMs = 0L
    private var consecutiveSendFailures = 0

    /**
     * Record a destination, evicting stale ones if the set has outgrown its
     * cap. [addr] is re-added last so it can never be its own victim, sending
     * to a destination and then dropping its reply as unsolicited would be
     * worse than not filtering at all.
     */
    private fun rememberPeer(addr: InetAddress) {
        val now = System.currentTimeMillis()
        sentTo[addr] = now
        if (sentTo.size <= MAX_TRACKED_PEERS) return
        // Rate-limited: without this, a set sitting just over the cap runs a
        // full scan for every datagram to a new destination, on the same
        // thread that drains the client socket.
        if (now - lastEvictMs < EVICT_INTERVAL_MS) return
        lastEvictMs = now

        val cutoff = now - PEER_TTL_MS
        sentTo.entries.removeAll { it.value < cutoff }
        if (sentTo.size > MAX_TRACKED_PEERS) {
            // Nothing was stale, a burst of genuinely fresh peers. Drop the
            // oldest rather than wiping, which would strand every active one.
            val excess = sentTo.size - MAX_TRACKED_PEERS
            sentTo.entries.sortedBy { it.value }.take(excess).forEach { sentTo.remove(it.key) }
        }
        sentTo[addr] = now
    }

    private fun clientLoop() {
        val buf = ByteArray(65535)
        var recvFailures = 0
        try {
            while (!clientSocket.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    clientSocket.receive(pkt)
                    recvFailures = 0
                } catch (e: SocketTimeoutException) {
                    if (idleTooLong()) {
                        Log.i(TAG, "association idle; closing")
                        break
                    }
                    continue
                } catch (e: Exception) {
                    if (clientSocket.isClosed) break
                    if (++recvFailures >= MAX_RECV_FAILURES) {
                        Log.w(TAG, "client recv failing persistently: ${e.message}")
                        break
                    }
                    continue
                }
                val src = pkt.socketAddress as? InetSocketAddress ?: continue

                // RFC 1928 §7: drop datagrams from anywhere but the peer that
                // opened the control connection. Checked before anything else
                // is touched, assigning the reply address first would let one
                // spoofed packet redirect the whole association.
                if (src.address != clientAddress) {
                    Log.w(TAG, "dropping UDP from unexpected source ${src.address}")
                    continue
                }

                val parsed = parseUdpRequest(buf, pkt.length) ?: continue
                clientReplyAddr = src
                // Only now, past the source check and the parse. Refreshing on
                // arrival would let any LAN host hold the association open
                // forever with one junk datagram every few minutes: the packet
                // is dropped, but the idle deadline it just reset is the only
                // thing that reclaims this connection's slot.
                lastActivityMs = System.currentTimeMillis()

                val resolved: InetAddress? = when (parsed.dst) {
                    is UdpDst.Ip -> parsed.dst.addr
                    is UdpDst.Host -> resolveCached(parsed.dst.name)
                }
                if (resolved == null) continue

                try {
                    rememberPeer(resolved)
                    remoteSocket.send(
                        DatagramPacket(
                            buf, parsed.dataOffset, parsed.dataLength,
                            resolved, parsed.dstPort,
                        ),
                    )
                    if (parsed.dataLength > 0) onBytes(parsed.dataLength, 0)
                    consecutiveSendFailures = 0
                } catch (e: Exception) {
                    Log.d(TAG, "remote send failed: ${e.message}")
                    // The remote socket stays pinned to a Network that no
                    // longer exists, so once cellular drops every send fails
                    // with ENETUNREACH forever while receive() blocks, the
                    // client keeps posting into a void because its TCP control
                    // is still open. Unlike CONNECT, whose reads throw on a
                    // drop, nothing here ever surfaces it. Give up and let the
                    // client see the association die so it can re-associate.
                    if (++consecutiveSendFailures >= MAX_SEND_FAILURES) {
                        Log.w(TAG, "remote send failing persistently; closing association")
                        break
                    }
                }
            }
        } finally {
            // Either loop ending takes the association down with it. Leaving
            // the other half alive strands the client: the TCP control stays
            // open so the association still looks valid, while its datagrams
            // go nowhere and no error is ever reported.
            close()
        }
    }

    private fun remoteLoop() {
        val buf = ByteArray(65535)
        var recvFailures = 0
        try {
            while (!remoteSocket.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    remoteSocket.receive(pkt)
                    recvFailures = 0
                } catch (e: SocketTimeoutException) {
                    if (idleTooLong()) {
                        Log.i(TAG, "association idle; closing")
                        break
                    }
                    continue
                } catch (e: Exception) {
                    if (remoteSocket.isClosed) break
                    // Now that either loop ending closes the whole association
                    //, including the client's TCP control, a single failure
                    // must not be treated as death. Linux surfaces a queued
                    // ICMP port-unreachable through recvfrom even on an
                    // unconnected socket, so one unreachable destination would
                    // otherwise tear down every flow the client has.
                    if (++recvFailures >= MAX_RECV_FAILURES) {
                        Log.w(TAG, "remote recv failing persistently: ${e.message}")
                        break
                    }
                    continue
                }
                val reply = clientReplyAddr ?: continue
                if (!sentTo.containsKey(pkt.address)) {
                    Log.w(TAG, "dropping unsolicited UDP from ${pkt.address}")
                    continue
                }
                // Past the solicited-source check, for the same reason as the
                // client loop: this socket's wildcard bind is LAN-reachable.
                lastActivityMs = System.currentTimeMillis()
                val out = wrapUdpResponse(pkt.address, pkt.port, buf, 0, pkt.length)
                try {
                    clientSocket.send(DatagramPacket(out, out.size, reply.address, reply.port))
                    if (pkt.length > 0) onBytes(0, pkt.length)
                } catch (e: Exception) {
                    Log.d(TAG, "client send failed: ${e.message}")
                }
            }
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { clientSocket.close() }
        runCatching { remoteSocket.close() }
        dnsCache.clear()
        sentTo.clear()
        clientLoopJob?.cancel()
        remoteLoopJob?.cancel()
        // Closing the two datagram sockets is not enough to end the
        // conversation: the ASSOCIATE control coroutine is parked in a
        // blocking read() on the TCP socket with no SO_TIMEOUT, which nothing
        // here can reach. Left alone it never returns, so the connection's
        // finally never runs, leaking its registry entry and, worse, one of
        // the server's bounded connection slots on every cellular blip.
        runCatching { onClosed() }
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
                if (dlen == 0) return null // empty host resolves to loopback
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
        // 5s, not the 15s first tried: long enough to kill the per-datagram
        // lookup that stalls the whole receive loop, short enough that a
        // hijacked answer from a censoring resolver cannot outlive a brief
        // burst. The app-wide resolver cache stays off (see
        // DataProxyApplication); this is scoped to one association and dies
        // with it.
        private const val DNS_TTL_MS = 5_000L
        private const val DNS_MISS_TTL_MS = 3_000L
        private const val PEER_TTL_MS = 60_000L
        private const val EVICT_INTERVAL_MS = 1_000L

        /** Consecutive egress failures before the association is declared dead. */
        private const val MAX_SEND_FAILURES = 20

        /** Consecutive receive failures tolerated before giving up on a socket. */
        private const val MAX_RECV_FAILURES = 20

        /** How often the receive loops surface to re-check the idle deadline. */
        private const val POLL_MS = 30_000

        /** Silence in both directions after which the association is retired. */
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MAX_DNS_ENTRIES = 256
        private const val MAX_TRACKED_PEERS = 1024
    }
}
