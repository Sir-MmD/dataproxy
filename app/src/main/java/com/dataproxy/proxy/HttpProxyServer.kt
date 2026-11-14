package com.dataproxy.proxy

import android.util.Log
import com.dataproxy.network.ProxyNetworkProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Independent HTTP listener; no protocol sniffing on the SOCKS5 port. */
class HttpProxyServer(
    private val bindAddress: String,
    private val port: Int,
    private val network: ProxyNetworkProvider,
    private val registry: ConnectionRegistry,
    private val onFatal: (Throwable) -> Unit,
    private val authProvider: () -> AuthConfig,
    private val maxConnectionsProvider: () -> Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val live = AtomicInteger(0)
    private val liveConnections = ConcurrentHashMap.newKeySet<HttpProxyConnection>()
    private val fatalSpent = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        val listener = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(bindAddress), port), 64)
            }
        } catch (e: IOException) {
            if (fatalSpent.compareAndSet(false, true)) onFatal(e)
            return
        }
        serverSocket = listener
        running = true
        acceptJob = scope.launch {
            var consecutiveFailures = 0
            try {
                while (running) {
                    val client = try {
                        listener.accept()
                    } catch (e: IOException) {
                        if (!running || listener.isClosed) break
                        if (++consecutiveFailures > 20) break
                        delay(50L * consecutiveFailures)
                        continue
                    }
                    consecutiveFailures = 0
                    val cap = maxConnectionsProvider()
                    if (cap > 0 && live.get() >= cap) {
                        runCatching { client.close() }
                        continue
                    }
                    live.incrementAndGet()
                    val conn = HttpProxyConnection(client, network, registry, scope, authProvider)
                    liveConnections.add(conn)
                    if (!running) {
                        liveConnections.remove(conn)
                        live.decrementAndGet()
                        conn.abort()
                        break
                    }
                    conn.handle().invokeOnCompletion {
                        liveConnections.remove(conn)
                        live.decrementAndGet()
                    }
                }
            } finally {
                if (running && fatalSpent.compareAndSet(false, true)) {
                    onFatal(IOException("HTTP accept loop exited unexpectedly"))
                }
            }
        }
        Log.i("HttpProxyServer", "HTTP listening on " + bindAddress + ":" + port)
    }

    fun stop() {
        if (!running) return
        running = false
        fatalSpent.set(true)
        runCatching { serverSocket?.close() }
        serverSocket = null
        val doomed = liveConnections.toList()
        if (doomed.isNotEmpty()) {
            CoroutineScope(RelayDispatcher + CoroutineExceptionHandler { _, _ -> }).launch {
                doomed.forEach { runCatching { it.abort() } }
            }
        }
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
    }
}
