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
            // That tags every socket the process creates, including the
            // SOCKS5 listener, with the cellular netId. The kernel then
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

    private val dns = CellularDnsResolver(
        networkProvider = { cellular },
        linkProperties = { net -> runCatching { cm.getLinkProperties(net) }.getOrNull() },
    )

    /**
     * Resolve a hostname over the cellular link's own resolvers.
     *
     * Returns every address the resolver gave, IPv4 first, callers are
     * expected to try them in order, since a censoring carrier routinely
     * returns one blackholed address alongside working ones.
     */
    fun resolveAll(host: String): List<java.net.InetAddress> = dns.resolve(host)

    /** Single-address convenience for callers that cannot iterate. */
    fun resolveHost(host: String): java.net.InetAddress? = resolveAll(host).firstOrNull()

    /**
     * Whether the cellular link can carry IPv6 at all.
     *
     * The APN here is IPv4-only, so an AAAA-only host is resolvable but
     * unreachable. Distinguishing the two lets the connect path report
     * REP_NETWORK_UNREACHABLE, a definitive "this network cannot get there",
     * instead of a resolve failure, which invites the client to look the name
     * up again on a network we do not control.
     */
    fun hasIpv6(): Boolean {
        val net = cellular ?: return false
        val lp = runCatching { cm.getLinkProperties(net) }.getOrNull() ?: return false
        return lp.linkAddresses.any {
            it.address is java.net.Inet6Address &&
                !it.address.isLinkLocalAddress &&
                !it.address.isLoopbackAddress
        }
    }

    /**
     * Create a new outbound socket already bound to the cellular network.
     *
     * Network.bindSocket() forces the underlying impl to be created before it
     * can fail, so the fd exists by the time it throws, close it rather than
     * waiting for a finalizer that heap-pressure-driven GC may never run in
     * time to beat RLIMIT_NOFILE.
     */
    fun createBoundSocket(): Socket {
        val socket = Socket()
        try {
            bindSocket(socket)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
        return socket
    }

    /** Block-bind a DatagramSocket to the cellular network for UDP egress. */
    fun bindDatagram(socket: DatagramSocket) {
        val net = cellular
            ?: throw IllegalStateException("Cellular network not available")
        net.bindSocket(socket)
    }

    /**
     * Create a new UDP socket already bound to the cellular network.
     *
     * The no-arg DatagramSocket constructor binds an ephemeral port, so the fd
     * is live before bindDatagram can reject it, most often with
     * IllegalStateException while the service is Paused, which is exactly when
     * a UDP client is retrying ASSOCIATE once a second.
     */
    fun createBoundDatagramSocket(): DatagramSocket {
        val socket = DatagramSocket()
        try {
            bindDatagram(socket)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
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
