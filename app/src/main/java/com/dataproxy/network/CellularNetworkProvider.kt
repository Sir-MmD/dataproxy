package com.dataproxy.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramSocket
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Maintains a live handle on the device's cellular network so that outbound sockets
 * can be pinned to mobile data, irrespective of which network is the system default.
 *
 * The proxy listens on the WiFi/LAN side; every outbound socket it creates is
 * bound here with [bindSocket], forcing the egress over cellular.
 */
class CellularNetworkProvider(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var cellular: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // We do NOT call cm.bindProcessToNetwork(network) here.
            // That tags every socket the process creates — including the
            // SOCKS5 listener — with the cellular netId. The kernel then
            // routes the listener's SYN-ACK replies via the cellular
            // route table, so external clients on Wi-Fi never finish the
            // TCP handshake (SYN_RECV → retransmits → time out).
            //
            // All outbound traffic is pinned to cellular explicitly via
            // Network.bindSocket on each created socket
            // (see createBoundSocket / createBoundDatagramSocket), and
            // every DNS lookup goes through Network.getAllByName on this
            // network handle. No code path in this app uses JVM-default
            // DNS, so dropping the process binding doesn't open a leak.
            cellular = network
            _state.value = State.Available(network)
            Log.d(TAG, "cellular available: $network")
        }

        override fun onLost(network: Network) {
            if (cellular == network) {
                cellular = null
                _state.value = State.Lost
                Log.d(TAG, "cellular lost: $network")
            }
        }

        override fun onUnavailable() {
            cellular = null
            _state.value = State.Unavailable
            Log.w(TAG, "cellular unavailable")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            cellular = network
        }
    }

    private var registered = false

    @Synchronized
    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        cm.requestNetwork(request, callback)
        registered = true
        _state.value = State.Requesting
        Log.d(TAG, "requested cellular network")
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        cellular = null
        _state.value = State.Idle
        Log.d(TAG, "released cellular network")
    }

    /** Block-bind a socket to the cellular network. Throws if cellular is not up. */
    fun bindSocket(socket: Socket) {
        val net = cellular
            ?: throw IllegalStateException("Cellular network not available")
        net.bindSocket(socket)
    }

    /** Suspend until cellular is up, or return null on [timeoutMs]. */
    suspend fun awaitAvailable(timeoutMs: Long = 10_000L): Network? = withTimeoutOrNull(timeoutMs) {
        cellular?.let { return@withTimeoutOrNull it }
        suspendCancellableCoroutine<Network?> { cont ->
            val watcher = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { cm.unregisterNetworkCallback(this) }
                    if (cont.isActive) cont.resume(network)
                }
                override fun onUnavailable() {
                    runCatching { cm.unregisterNetworkCallback(this) }
                    if (cont.isActive) cont.resume(null)
                }
            }
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.requestNetwork(req, watcher, timeoutMs.toInt())
            cont.invokeOnCancellation {
                runCatching { cm.unregisterNetworkCallback(watcher) }
            }
        }
    }

    /** Resolve a hostname using the cellular network's DNS (not WiFi DNS). */
    fun resolveHost(host: String): java.net.InetAddress? {
        val net = cellular ?: return null
        return runCatching { net.getAllByName(host).firstOrNull() }.getOrNull()
    }

    /** Create a new outbound socket already bound to the cellular network. */
    fun createBoundSocket(): Socket {
        val socket = Socket()
        bindSocket(socket)
        return socket
    }

    /** Block-bind a DatagramSocket to the cellular network for UDP egress. */
    fun bindDatagram(socket: DatagramSocket) {
        val net = cellular
            ?: throw IllegalStateException("Cellular network not available")
        net.bindSocket(socket)
    }

    /** Create a new UDP socket already bound to the cellular network. */
    fun createBoundDatagramSocket(): DatagramSocket {
        val socket = DatagramSocket()
        bindDatagram(socket)
        return socket
    }

    sealed interface State {
        data object Idle : State
        data object Requesting : State
        data class Available(val network: Network) : State
        data object Lost : State
        data object Unavailable : State
    }

    companion object {
        private const val TAG = "CellularNetwork"
    }
}
