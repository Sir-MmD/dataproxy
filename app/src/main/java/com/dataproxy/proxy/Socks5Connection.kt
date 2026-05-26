package com.dataproxy.proxy

import android.util.Log
import com.dataproxy.network.CellularNetworkProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One SOCKS5 conversation. RFC 1928 — supports CONNECT (TCP) and
 * UDP ASSOCIATE. Username/password auth (RFC 1929) is optional.
 *
 * Two-step lifecycle:
 *   1. handshake() — auth + parse request + open outbound on cellular
 *   2. proxy()     — bidirectional copy until either end closes
 */
class Socks5Connection(
    private val clientSocket: Socket,
    private val cellular: CellularNetworkProvider,
    private val registry: ConnectionRegistry,
    private val scope: CoroutineScope,
    private val authProvider: () -> AuthConfig = { AuthConfig.Disabled },
) {

    private var entry: ConnectionRegistry.Connection? = null
    private var outbound: Socket? = null
    private var udpRelay: Socks5UdpRelay? = null

    fun handle(): Job = scope.launch(Dispatchers.IO) {
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

            val input = DataInputStream(clientSocket.getInputStream())
            val output = DataOutputStream(clientSocket.getOutputStream())

            if (!negotiateMethod(input, output)) return@launch
            val req = readRequest(input, output) ?: return@launch

            when (req.cmd) {
                CMD_CONNECT -> handleConnect(req.target, output)
                CMD_UDP_ASSOCIATE -> handleUdpAssociate(input, output)
                else -> reply(output, REP_COMMAND_NOT_SUPPORTED)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "connection error: ${t.message}")
        } finally {
            closeQuietly()
            entry?.let { registry.close(it) }
        }
    }

    // ---------------------------------------------------------------- handshake

    private fun negotiateMethod(input: DataInputStream, output: DataOutputStream): Boolean {
        val ver = input.readUnsignedByte()
        if (ver != 0x05) {
            Log.d(TAG, "unsupported SOCKS version: $ver"); return false
        }
        val nMethods = input.readUnsignedByte()
        val methods = ByteArray(nMethods)
        input.readFully(methods)
        val methodSet = methods.map { it.toInt() and 0xFF }.toSet()

        val auth = authProvider()
        return if (auth.enabled) {
            if (METHOD_USERPASS !in methodSet) {
                output.write(byteArrayOf(0x05.toByte(), 0xFF.toByte()))
                output.flush(); return false
            }
            output.write(byteArrayOf(0x05.toByte(), METHOD_USERPASS.toByte()))
            output.flush()
            doUserPassAuth(input, output, auth)
        } else {
            if (METHOD_NO_AUTH !in methodSet) {
                output.write(byteArrayOf(0x05.toByte(), 0xFF.toByte()))
                output.flush(); return false
            }
            output.write(byteArrayOf(0x05.toByte(), METHOD_NO_AUTH.toByte()))
            output.flush(); true
        }
    }

    private fun doUserPassAuth(
        input: DataInputStream,
        output: DataOutputStream,
        auth: AuthConfig,
    ): Boolean {
        val ver = input.readUnsignedByte()
        if (ver != 0x01) return false
        val ulen = input.readUnsignedByte()
        val unameBytes = ByteArray(ulen).also(input::readFully)
        val plen = input.readUnsignedByte()
        val passBytes = ByteArray(plen).also(input::readFully)
        val username = String(unameBytes, Charsets.UTF_8)
        val password = String(passBytes, Charsets.UTF_8)

        val ok = username == auth.username && password == auth.password
        output.write(byteArrayOf(0x01.toByte(), (if (ok) 0x00 else 0x01).toByte()))
        output.flush()
        if (!ok) Log.d(TAG, "auth failed for user=$username")
        return ok
    }

    private sealed interface Target {
        data class Ipv4(val addr: InetAddress, val port: Int) : Target
        data class Ipv6(val addr: InetAddress, val port: Int) : Target
        data class Domain(val host: String, val port: Int) : Target

        fun display(): String = when (this) {
            is Ipv4 -> "${addr.hostAddress}:$port"
            is Ipv6 -> "[${addr.hostAddress}]:$port"
            is Domain -> "$host:$port"
        }
    }

    private data class Request(val cmd: Int, val target: Target)

    private fun readRequest(input: DataInputStream, output: DataOutputStream): Request? {
        val ver = input.readUnsignedByte()
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte() // RSV
        val atyp = input.readUnsignedByte()

        if (ver != 0x05) { reply(output, REP_GENERAL_FAILURE); return null }

        val target = when (atyp) {
            ATYP_IPV4 -> {
                val raw = ByteArray(4).also(input::readFully)
                Target.Ipv4(InetAddress.getByAddress(raw), input.readUnsignedShort())
            }
            ATYP_IPV6 -> {
                val raw = ByteArray(16).also(input::readFully)
                Target.Ipv6(InetAddress.getByAddress(raw), input.readUnsignedShort())
            }
            ATYP_DOMAIN -> {
                val len = input.readUnsignedByte()
                val raw = ByteArray(len).also(input::readFully)
                Target.Domain(String(raw, Charsets.US_ASCII), input.readUnsignedShort())
            }
            else -> {
                reply(output, REP_ADDRESS_TYPE_NOT_SUPPORTED); return null
            }
        }
        return Request(cmd, target)
    }

    // ---------------------------------------------------------------- CONNECT

    private suspend fun handleConnect(target: Target, output: DataOutputStream) {
        val remote = openRemote(target, output) ?: return

        // Successful tunnel; clear read timeout for the long-lived stream phase.
        clientSocket.soTimeout = 0
        remote.soTimeout = 0

        val clientHost = (clientSocket.remoteSocketAddress as? InetSocketAddress)
            ?.address?.hostAddress ?: "unknown"
        val clientPort = (clientSocket.remoteSocketAddress as? InetSocketAddress)
            ?.port ?: 0

        entry = registry.open(
            clientHost = clientHost,
            clientPort = clientPort,
            target = target.display(),
        )
        outbound = remote

        relay(remote)
    }

    private suspend fun openRemote(target: Target, output: DataOutputStream): Socket? {
        // Resolve hostnames using the cellular DNS so we don't fall through to WiFi DNS.
        val resolved: InetAddress? = when (target) {
            is Target.Ipv4 -> target.addr
            is Target.Ipv6 -> target.addr
            is Target.Domain -> withContext(Dispatchers.IO) {
                cellular.resolveHost(target.host)
            }
        }
        if (resolved == null) {
            Log.d(TAG, "dns resolve failed via cellular for $target")
            reply(output, REP_HOST_UNREACHABLE); return null
        }

        val remote = try {
            cellular.createBoundSocket().apply {
                tcpNoDelay = true
                soTimeout = CONNECT_TIMEOUT_MS
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cellular unavailable: ${e.message}")
            reply(output, REP_NETWORK_UNREACHABLE); return null
        } catch (e: Exception) {
            Log.w(TAG, "cellular socket create failed: ${e.message}")
            reply(output, REP_NETWORK_UNREACHABLE); return null
        }

        val port = when (target) {
            is Target.Ipv4 -> target.port
            is Target.Ipv6 -> target.port
            is Target.Domain -> target.port
        }

        return try {
            withContext(Dispatchers.IO) {
                remote.connect(InetSocketAddress(resolved, port), CONNECT_TIMEOUT_MS)
            }
            reply(output, REP_SUCCEEDED, remote.localSocketAddress as? InetSocketAddress)
            remote
        } catch (e: IOException) {
            Log.d(TAG, "connect to $target failed: ${e.message}")
            runCatching { remote.close() }
            reply(output, e.toReplyCode())
            null
        }
    }

    // ---------------------------------------------------------------- UDP ASSOCIATE

    private suspend fun handleUdpAssociate(input: DataInputStream, output: DataOutputStream) {
        val localTcp = clientSocket.localSocketAddress as? InetSocketAddress
        val listenAddr = localTcp?.address ?: InetAddress.getByName("0.0.0.0")

        val relay = try {
            Socks5UdpRelay(
                cellular = cellular,
                listenAddress = listenAddr,
                scope = scope,
                onBytes = { up, down ->
                    entry?.let {
                        if (up > 0) registry.recordUp(it, up)
                        if (down > 0) registry.recordDown(it, down)
                    }
                },
            )
        } catch (e: IllegalStateException) {
            Log.w(TAG, "UDP relay: cellular unavailable")
            reply(output, REP_NETWORK_UNREACHABLE); return
        } catch (e: Exception) {
            Log.w(TAG, "UDP relay setup failed: ${e.message}")
            reply(output, REP_GENERAL_FAILURE); return
        }

        relay.start()
        udpRelay = relay

        val clientHost = (clientSocket.remoteSocketAddress as? InetSocketAddress)
            ?.address?.hostAddress ?: "unknown"
        val clientPort = (clientSocket.remoteSocketAddress as? InetSocketAddress)
            ?.port ?: 0
        entry = registry.open(
            clientHost = clientHost,
            clientPort = clientPort,
            target = "udp:${relay.port}",
        )

        reply(output, REP_SUCCEEDED, InetSocketAddress(listenAddr, relay.port))

        // Hold the TCP control open. When the client closes it (read returns
        // EOF or throws), tear down the UDP relay.
        clientSocket.soTimeout = 0
        runCatching {
            withContext(Dispatchers.IO) {
                val buf = ByteArray(64)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                }
            }
        }
        relay.close()
    }

    // ----------------------------------------------------------------- relay

    private suspend fun relay(remote: Socket) = withContext(Dispatchers.IO) {
        val client = clientSocket
        val tracker = entry

        val up = async {
            copyStream(
                source = client.getInputStream(),
                sink = remote.getOutputStream(),
                sinkSocket = remote,
            ) { n -> tracker?.let { registry.recordUp(it, n) } }
        }
        val down = async {
            copyStream(
                source = remote.getInputStream(),
                sink = client.getOutputStream(),
                sinkSocket = client,
            ) { n -> tracker?.let { registry.recordDown(it, n) } }
        }
        runCatching { listOf(up, down).awaitAll() }
    }

    private fun copyStream(
        source: java.io.InputStream,
        sink: java.io.OutputStream,
        sinkSocket: Socket,
        onBytes: (Int) -> Unit,
    ) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = source.read(buf)
                if (n < 0) break
                if (n == 0) continue
                sink.write(buf, 0, n)
                sink.flush()
                onBytes(n)
            }
        } catch (_: IOException) {
            // peer closed
        } finally {
            runCatching { sink.flush() }
            // Half-close so the peer's reader sees EOF without us closing the
            // socket — the other direction may still be carrying data.
            runCatching { if (!sinkSocket.isOutputShutdown) sinkSocket.shutdownOutput() }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun reply(
        output: DataOutputStream,
        code: Int,
        bound: InetSocketAddress? = null,
    ) {
        val addr = bound?.address
        val port = bound?.port ?: 0
        val header = byteArrayOf(
            0x05.toByte(),
            code.toByte(),
            0x00.toByte(),
            (when (addr) {
                is java.net.Inet6Address -> ATYP_IPV6
                else -> ATYP_IPV4
            }).toByte(),
        )
        val body = when (addr) {
            is java.net.Inet4Address -> addr.address + port.toShort16()
            is java.net.Inet6Address -> addr.address + port.toShort16()
            else -> ByteArray(6) // BND.ADDR (4) + BND.PORT (2), all zero
        }
        val response = header + body
        runCatching {
            output.write(response)
            output.flush()
        }
    }

    private fun Int.toShort16(): ByteArray =
        byteArrayOf(((this ushr 8) and 0xFF).toByte(), (this and 0xFF).toByte())

    private fun IOException.toReplyCode(): Int = when {
        message?.contains("refused", ignoreCase = true) == true -> REP_CONNECTION_REFUSED
        message?.contains("unreachable", ignoreCase = true) == true -> REP_HOST_UNREACHABLE
        message?.contains("network", ignoreCase = true) == true -> REP_NETWORK_UNREACHABLE
        message?.contains("timed out", ignoreCase = true) == true -> REP_TTL_EXPIRED
        else -> REP_GENERAL_FAILURE
    }

    private fun closeQuietly() {
        runCatching { clientSocket.close() }
        runCatching { outbound?.close() }
        runCatching { udpRelay?.close() }
    }

    companion object {
        private const val TAG = "Socks5Conn"

        private const val BUFFER_SIZE = 16 * 1024
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 15_000

        private const val METHOD_NO_AUTH = 0x00
        private const val METHOD_USERPASS = 0x02

        private const val CMD_CONNECT = 0x01
        private const val CMD_UDP_ASSOCIATE = 0x03

        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04

        private const val REP_SUCCEEDED = 0x00
        private const val REP_GENERAL_FAILURE = 0x01
        private const val REP_NETWORK_UNREACHABLE = 0x03
        private const val REP_HOST_UNREACHABLE = 0x04
        private const val REP_CONNECTION_REFUSED = 0x05
        private const val REP_TTL_EXPIRED = 0x06
        private const val REP_COMMAND_NOT_SUPPORTED = 0x07
        private const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
    }
}
