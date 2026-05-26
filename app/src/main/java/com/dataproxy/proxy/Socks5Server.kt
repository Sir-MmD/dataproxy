package com.dataproxy.proxy

import android.util.Log
import com.dataproxy.network.CellularNetworkProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException

/**
 * Single-instance SOCKS5 listener. One [start] / [stop] cycle.
 *
 * `bindAddress` may be `"0.0.0.0"` (all interfaces) or a specific local IP from
 * [com.dataproxy.network.NetworkInterfaceLister]. Every accepted client
 * spawns a [Socks5Connection] coroutine on the supervisor scope so a single
 * failure does not kill the accept loop.
 */
class Socks5Server(
    val bindAddress: String,
    val port: Int,
    private val cellular: CellularNetworkProvider,
    val registry: ConnectionRegistry,
    private val onFatal: (Throwable) -> Unit,
    private val authProvider: () -> AuthConfig = { AuthConfig.Disabled },
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var running: Boolean = false
        private set

    fun start() {
        if (running) return
        registry.reset()

        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                val addr = InetAddress.getByName(bindAddress)
                bind(InetSocketAddress(addr, port), BACKLOG)
            }
        } catch (e: IOException) {
            Log.e(TAG, "bind failed on $bindAddress:$port", e)
            onFatal(e); return
        }

        serverSocket = socket
        running = true
        Log.i(TAG, "listening on ${socket.inetAddress.hostAddress}:${socket.localPort}")

        acceptJob = scope.launch {
            try {
                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (e: SocketException) {
                        if (running) Log.w(TAG, "accept error: ${e.message}")
                        break
                    } catch (e: IOException) {
                        if (running) Log.w(TAG, "accept io error: ${e.message}")
                        break
                    }
                    Socks5Connection(
                        clientSocket = client,
                        cellular = cellular,
                        registry = registry,
                        scope = scope,
                        authProvider = authProvider,
                    ).handle()
                }
            } finally {
                Log.i(TAG, "accept loop exited")
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
        Log.i(TAG, "stopped")
    }

    companion object {
        private const val TAG = "Socks5Server"
        private const val BACKLOG = 64
    }
}
