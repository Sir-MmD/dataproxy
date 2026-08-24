package com.dataproxy.network

import android.net.LinkProperties
import android.net.Network
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Minimal DNS client that resolves names using the cellular link's *own*
 * resolvers, over a socket bound to the cellular [Network].
 *
 * Why not `Network.getAllByName`: on a network with no IPv6 connectivity,
 * which is what this carrier's APN provides, Android's resolver suppresses
 * AAAA entirely, so an IPv6-only hostname fails at the *resolve* step. A
 * client that is told "cannot resolve" typically re-resolves the name with its
 * own resolver, over whatever network it can reach, which is exactly the DNS
 * leak this app exists to prevent. Doing the lookup here means the resolve
 * succeeds and the failure lands at connect instead, giving the client an
 * answer and no reason to ask anyone else.
 *
 * It also returns the *complete* address list rather than one record, so a
 * host whose first address is blackholed (routine on a censoring carrier)
 * can still be reached via the others.
 *
 * The servers come from the cellular network's [LinkProperties]. There is no
 * configurable resolver and no fallback to the system one: any query that
 * cannot be answered over cellular fails rather than escaping to Wi-Fi.
 */
class CellularDnsResolver(
    private val networkProvider: () -> Network?,
    private val linkProperties: (Network) -> LinkProperties?,
) {

    /** Resolved addresses, IPv4 first, the only family this link can carry. */
    fun resolve(host: String): List<InetAddress> {
        // Literals must never become a query.
        runCatching { parseLiteral(host) }.getOrNull()?.let { return listOf(it) }

        val net = networkProvider() ?: return emptyList()
        val servers = linkProperties(net)?.dnsServers.orEmpty()
        if (servers.isEmpty()) {
            // No resolver advertised on this link. Fall back to the pinned
            // platform lookup, still cellular-bound, so still no leak, just
            // without AAAA and without the full address list.
            return runCatching { net.getAllByName(host).toList() }.getOrElse { emptyList() }
        }

        val v4 = mutableListOf<InetAddress>()
        val v6 = mutableListOf<InetAddress>()
        for (server in servers) {
            // A and AAAA are separate queries; ask for both from the same
            // server before moving on, so a partial answer is still useful.
            query(net, server, host, TYPE_A)?.let { v4 += it }
            query(net, server, host, TYPE_AAAA)?.let { v6 += it }
            if (v4.isNotEmpty() || v6.isNotEmpty()) break
        }
        return (v4 + v6).distinct()
    }

    private fun query(
        net: Network,
        server: InetAddress,
        host: String,
        type: Int,
    ): List<InetAddress>? {
        var socket: DatagramSocket? = null
        try {
            val s = DatagramSocket()
            socket = s
            net.bindSocket(s)
            s.soTimeout = TIMEOUT_MS
            // The id is only used to reject a stale datagram on this socket;
            // the socket is per-query, so a counter would add nothing.
            val id = (host.hashCode() xor type) and 0xFFFF
            val q = buildQuery(id, host, type)
            s.send(DatagramPacket(q, q.size, InetSocketAddress(server, 53)))

            val buf = ByteArray(1500)
            val pkt = DatagramPacket(buf, buf.size)
            s.receive(pkt)
            return parseAnswers(buf, pkt.length, id, type)
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "dns timeout: $host type=$type via $server")
            return null
        } catch (e: Exception) {
            Log.d(TAG, "dns query failed: $host type=$type via $server: ${e.message}")
            return null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun buildQuery(id: Int, host: String, type: Int): ByteArray {
        val out = ArrayList<Byte>(host.length + 32)
        fun be16(v: Int) { out.add(((v ushr 8) and 0xFF).toByte()); out.add((v and 0xFF).toByte()) }
        be16(id)
        be16(0x0100)   // RD (recursion desired)
        be16(1)        // QDCOUNT
        be16(0); be16(0); be16(0)
        for (label in host.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "bad label" }
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        out.add(0)
        be16(type)
        be16(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * Walks the answer section collecting addresses of [type]. CNAME chains
     * need no special handling: a recursive resolver returns the final address
     * records alongside the CNAMEs, so scanning for the wanted type is enough.
     */
    private fun parseAnswers(buf: ByteArray, len: Int, id: Int, type: Int): List<InetAddress>? {
        if (len < 12) return null
        fun u8(i: Int) = buf[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)

        if (u16(0) != id) return null
        val flags = u16(2)
        if (flags and 0x8000 == 0) return null       // not a response
        if (flags and 0x000F != 0) return null       // RCODE != NOERROR
        val qd = u16(4)
        val an = u16(6)
        if (an == 0) return emptyList()

        var i = 12
        // Skip the question section.
        repeat(qd) {
            i = skipName(buf, len, i) ?: return null
            i += 4
            if (i > len) return null
        }

        val out = mutableListOf<InetAddress>()
        repeat(an) {
            i = skipName(buf, len, i) ?: return out
            if (i + 10 > len) return out
            val rtype = u16(i)
            val rdlen = u16(i + 8)
            i += 10
            if (i + rdlen > len) return out
            if (rtype == type) {
                val size = if (type == TYPE_A) 4 else 16
                if (rdlen == size) {
                    runCatching { InetAddress.getByAddress(buf.copyOfRange(i, i + size)) }
                        .getOrNull()?.let { out += it }
                }
            }
            i += rdlen
        }
        return out
    }

    /** Returns the offset just past a (possibly compressed) name, or null if malformed. */
    private fun skipName(buf: ByteArray, len: Int, start: Int): Int? {
        var i = start
        var guard = 0
        while (i < len) {
            val b = buf[i].toInt() and 0xFF
            when {
                b == 0 -> return i + 1
                b and 0xC0 == 0xC0 -> return if (i + 2 <= len) i + 2 else null
                else -> {
                    i += b + 1
                    // A malformed packet must not spin here.
                    if (++guard > MAX_LABELS) return null
                }
            }
        }
        return null
    }

    /**
     * Recognises IP literals WITHOUT ever calling the platform resolver.
     *
     * This must be exact. "Starts with a digit" is not a literal test, plenty
     * of real hostnames do ("9gag.com", and every browserleaks probe such as
     * "945w2jgiu95z.dns6.browserleaks.org"), and handing one of those to
     * InetAddress.getByName sends it to the *system* resolver on the default
     * network, i.e. straight out over Wi-Fi. That is the exact leak this class
     * exists to close, so getByName is only ever reached once the string is
     * already known to be numeric.
     */
    private fun parseLiteral(host: String): InetAddress? {
        if (host.isEmpty()) return null
        // A colon cannot appear in a hostname, so this is unambiguously an
        // IPv6 literal (or invalid) and never becomes a query.
        if (host.contains(':')) {
            return runCatching { InetAddress.getByName(host) }.getOrNull()
                ?.takeIf { it is Inet6Address }
        }
        val m = IPV4.matchEntire(host) ?: return null
        if (m.groupValues.drop(1).any { it.toInt() !in 0..255 }) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
            ?.takeIf { it is Inet4Address }
    }

    companion object {
        private const val TAG = "CellularDns"
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
        private const val CLASS_IN = 1
        private const val TIMEOUT_MS = 5_000
        private const val MAX_LABELS = 128
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    }
}
