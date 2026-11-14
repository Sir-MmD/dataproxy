package com.dataproxy.proxy

import android.util.Log
import com.dataproxy.network.ProxyNetworkProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** One HTTP/1.x proxy request. CONNECT is a tunnel, absolute-form HTTP is one transaction. */
class HttpProxyConnection(
    private val clientSocket: Socket,
    private val network: ProxyNetworkProvider,
    private val registry: ConnectionRegistry,
    private val scope: CoroutineScope,
    private val authProvider: () -> AuthConfig,
) {
    @Volatile private var upstream: Socket? = null
    @Volatile private var entry: ConnectionRegistry.Connection? = null
    @Volatile private var responseStarted = false

    fun abort() {
        runCatching { clientSocket.close() }
        runCatching { upstream?.close() }
    }

    fun handle(): Job = scope.launch(RelayDispatcher) {
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            val head = readHead(input)
            val request = HttpProxyProtocol.parse(head)
            if (!HttpProxyProtocol.authenticated(request.headers, authProvider())) {
                respond(407, "Proxy Authentication Required",
                    "Proxy-Authenticate: Basic realm=\"DataProxy\"\r\n")
                return@launch
            }
            val remote = connect(request.target) ?: return@launch
            val addr = clientSocket.remoteSocketAddress as? InetSocketAddress
            entry = registry.open(
                clientHost = addr?.address?.hostAddress ?: "unknown",
                clientPort = addr?.port ?: 0,
                target = request.target.label(),
            )
            remote.soTimeout = 0
            if (request.isConnect) {
                responseStarted = true
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                clientSocket.soTimeout = 0
                runCatching { clientSocket.keepAlive = true }
                runCatching { remote.keepAlive = true }
                tunnel(input, output, remote)
            } else {
                val forwarded = HttpProxyProtocol.forwardHeader(request)
                remote.getOutputStream().write(forwarded)
                remote.getOutputStream().flush()
                entry?.let { registry.recordUp(it, forwarded.size) }
                // A client waiting for 100 Continue can now transmit its request body.
                if (request.expectContinue) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
                clientSocket.soTimeout = BODY_TIMEOUT_MS
                relayHttpRequest(input, output, remote, request)
            }
        } catch (e: HttpProxyProtocol.BadRequest) {
            if (!responseStarted) respond(e.status, e.reason)
        } catch (_: SocketTimeoutException) {
            if (!responseStarted) respond(408, "Request Timeout")
        } catch (_: IllegalStateException) {
            if (!responseStarted) respond(503, "Service Unavailable")
        } catch (e: IOException) {
            Log.d(TAG, "HTTP I/O error: " + e.javaClass.simpleName)
            if (!responseStarted) respond(502, "Bad Gateway")
        } catch (e: Exception) {
            Log.w(TAG, "HTTP request failed: " + e.javaClass.simpleName)
            if (!responseStarted) respond(502, "Bad Gateway")
        } finally {
            abort()
            entry?.let { registry.close(it) }
        }
    }

    private fun readHead(input: InputStream): String {
        val data = java.io.ByteArrayOutputStream(1024)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_TIMEOUT_MS.toLong())
        var pattern = 0
        while (data.size() < HttpProxyProtocol.MAX_HEADER_BYTES) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) throw SocketTimeoutException("HTTP handshake expired")
            clientSocket.soTimeout = remaining.coerceAtLeast(1).coerceAtMost(HANDSHAKE_TIMEOUT_MS.toLong()).toInt()
            val b = input.read()
            if (b == -1) throw IOException("Incomplete HTTP headers")
            data.write(b)
            pattern = when {
                pattern == 0 && b == 13 -> 1
                pattern == 1 && b == 10 -> 2
                pattern == 2 && b == 13 -> 3
                pattern == 3 && b == 10 -> 4
                b == 13 -> 1
                else -> 0
            }
            if (pattern == 4) return data.toString("ISO-8859-1")
        }
        throw HttpProxyProtocol.BadRequest(431, "Request Header Fields Too Large")
    }

    private fun connect(target: HttpProxyProtocol.Target): Socket? {
        val candidates = network.resolveAll(target.host)
            .filter { network.hasIpv6() || it !is Inet6Address }
        if (candidates.isEmpty()) {
            respond(502, "Bad Gateway")
            return null
        }
        var timedOut = false
        for (addr in candidates) {
            val remote = network.createBoundSocket()
            upstream = remote
            try {
                remote.tcpNoDelay = true
                remote.connect(InetSocketAddress(addr, target.port), CONNECT_TIMEOUT_MS)
                return remote
            } catch (e: SocketTimeoutException) {
                timedOut = true
            } catch (_: IOException) {
                // Try the next DNS answer, using a fresh per-network-bound socket.
            }
            runCatching { remote.close() }
            upstream = null
        }
        respond(if (timedOut) 504 else 502, if (timedOut) "Gateway Timeout" else "Bad Gateway")
        return null
    }

    private suspend fun tunnel(input: InputStream, output: OutputStream, remote: Socket) =
        coroutineScope {
            val up = launch(RelayDispatcher) {
                copyStream(input, remote.getOutputStream(), remote) { n ->
                    entry?.let { registry.recordUp(it, n) }
                }
            }
            val down = launch(RelayDispatcher) {
                copyStream(remote.getInputStream(), output, clientSocket) { n ->
                    entry?.let { registry.recordDown(it, n) }
                }
            }
            // Preserve TCP half-closes: both relays must finish unless stop aborts sockets.
            up.join()
            down.join()
        }

    private suspend fun relayHttpRequest(
        input: InputStream,
        output: OutputStream,
        remote: Socket,
        request: HttpProxyProtocol.Request,
    ) = coroutineScope {
        val upload = launch(RelayDispatcher) {
            try {
                when {
                    request.chunked -> copyChunked(input, remote.getOutputStream())
                    request.contentLength != null ->
                        copyExact(input, remote.getOutputStream(), request.contentLength)
                }
                remote.getOutputStream().flush()
            } catch (_: IOException) {
                // Response coroutine tears down both sockets when it completes.
            }
        }
        val download = launch(RelayDispatcher) {
            copyStream(remote.getInputStream(), output, clientSocket) { n ->
                responseStarted = true
                entry?.let { registry.recordDown(it, n) }
            }
        }
        try {
            download.join()
        } finally {
            // The origin is required to close after one response. Unblock a client
            // still holding its request body socket open after the response arrives.
            abort()
            upload.cancel()
        }
    }

    private fun copyExact(input: InputStream, output: OutputStream, total: Long) {
        var left = total
        val buf = ByteArray(BUFFER_SIZE)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("Truncated request body")
            output.write(buf, 0, n)
            entry?.let { registry.recordUp(it, n) }
            left -= n
        }
    }

    private fun copyChunked(input: InputStream, output: OutputStream) {
        var trailerBytes = 0
        while (true) {
            val line = readLine(input, 8192)
            val size = line.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("Invalid chunk")
            if (size < 0) throw IOException("Invalid chunk")
            val bytes = (line + "\r\n").toByteArray(Charsets.ISO_8859_1)
            output.write(bytes)
            entry?.let { registry.recordUp(it, bytes.size) }
            if (size > 0) {
                copyExact(input, output, size)
                val end = ByteArray(2)
                readFully(input, end)
                if (end[0] != 13.toByte() || end[1] != 10.toByte())
                    throw IOException("Invalid chunk ending")
                output.write(end)
                entry?.let { registry.recordUp(it, 2) }
            } else {
                do {
                    val trailer = readLine(input, 8192)
                    val trailerBytesArray = (trailer + "\r\n").toByteArray(Charsets.ISO_8859_1)
                    trailerBytes += trailerBytesArray.size
                    if (trailerBytes > HttpProxyProtocol.MAX_HEADER_BYTES)
                        throw IOException("Too many trailers")
                    output.write(trailerBytesArray)
                    entry?.let { registry.recordUp(it, trailerBytesArray.size) }
                    if (trailer.isEmpty()) return
                } while (true)
            }
        }
    }

    private fun readLine(input: InputStream, max: Int): String {
        val data = java.io.ByteArrayOutputStream()
        while (data.size() < max) {
            val b = input.read()
            if (b < 0) throw IOException("Unexpected EOF")
            if (b == 10) {
                val bytes = data.toByteArray()
                if (bytes.isEmpty() || bytes.last() != 13.toByte())
                    throw IOException("Expected CRLF")
                return String(bytes, 0, bytes.size - 1, Charsets.ISO_8859_1)
            }
            data.write(b)
        }
        throw IOException("Line too long")
    }

    private fun readFully(input: InputStream, bytes: ByteArray) {
        var n = 0
        while (n < bytes.size) {
            val next = input.read(bytes, n, bytes.size - n)
            if (next < 0) throw IOException("Unexpected EOF")
            n += next
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream, sinkSocket: Socket, count: (Int) -> Unit) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    output.write(buf, 0, n)
                    count(n)
                }
            }
        } catch (_: IOException) {
            // Peer disconnected or stop() closed the socket.
        } finally {
            runCatching { output.flush() }
            // Half-close the destination stream without killing reverse traffic.
            runCatching { if (!sinkSocket.isOutputShutdown) sinkSocket.shutdownOutput() }
        }
    }

    private fun respond(status: Int, reason: String, extraHeader: String = "") {
        if (responseStarted) return
        responseStarted = true
        runCatching {
            clientSocket.getOutputStream().write(
                ("HTTP/1.1 " + status + " " + reason + "\r\n" +
                    "Connection: close\r\nContent-Length: 0\r\n" + extraHeader +
                    "\r\n").toByteArray(Charsets.US_ASCII)
            )
            clientSocket.getOutputStream().flush()
        }
    }

    companion object {
        private const val TAG = "HttpProxyConnection"
        private const val BUFFER_SIZE = 16 * 1024
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val BODY_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 15_000
    }
}
