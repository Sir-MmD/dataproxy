package com.dataproxy.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket

/** Shared network adapter. Only outbound sockets are bound, never the listener or process. */
interface ProxyNetworkProvider {
    val state: StateFlow<State>
    fun start()
    fun stop()
    suspend fun awaitAvailable(timeoutMs: Long = 15_000L): Network?
    fun resolveAll(host: String): List<InetAddress>
    fun hasIpv6(): Boolean
    fun createBoundSocket(): Socket
    fun createBoundDatagramSocket(): DatagramSocket

    sealed interface State {
        data object Idle : State
        data object Requesting : State
        data class Available(val network: Network) : State
        data object Lost : State
        data object Unavailable : State
    }
}

/** Cellular reuses its original DNS and socket handling. Wi-Fi and Auto bind by Network handle. */
class AndroidProxyNetworkProvider(
    context: Context,
    val mode: NetworkMode,
    private val cellular: CellularNetworkProvider,
) : ProxyNetworkProvider {
    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ProxyNetworkProvider.State>(ProxyNetworkProvider.State.Idle)
    override val state: StateFlow<ProxyNetworkProvider.State> = _state.asStateFlow()

    @Volatile private var current: Network? = null
    @Volatile private var started = false
    private var watchJob: Job? = null
    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            current = network
            _state.value = ProxyNetworkProvider.State.Available(network)
        }
        override fun onLost(network: Network) {
            if (current == network) {
                current = null
                _state.value = ProxyNetworkProvider.State.Lost
            }
        }
        override fun onUnavailable() {
            current = null
            _state.value = ProxyNetworkProvider.State.Unavailable
        }
    }

    @Synchronized
    override fun start() {
        if (started) return
        started = true
        _state.value = ProxyNetworkProvider.State.Requesting
        try {
            when (mode) {
                NetworkMode.CELLULAR -> {
                    watchJob = scope.launch {
                        cellular.state.collect { value ->
                            _state.value = when (value) {
                                is CellularNetworkProvider.State.Available -> {
                                    current = value.network
                                    ProxyNetworkProvider.State.Available(value.network)
                                }
                                CellularNetworkProvider.State.Requesting -> ProxyNetworkProvider.State.Requesting
                                CellularNetworkProvider.State.Idle -> ProxyNetworkProvider.State.Idle
                                CellularNetworkProvider.State.Lost -> {
                                    current = null
                                    ProxyNetworkProvider.State.Lost
                                }
                                CellularNetworkProvider.State.Unavailable -> {
                                    current = null
                                    ProxyNetworkProvider.State.Unavailable
                                }
                            }
                        }
                    }
                    cellular.start()
                }
                NetworkMode.WIFI -> {
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.requestNetwork(request, callback)
                    callbackRegistered = true
                }
                NetworkMode.AUTO -> {
                    cm.registerDefaultNetworkCallback(callback)
                    callbackRegistered = true
                }
            }
        } catch (e: Exception) {
            started = false
            watchJob?.cancel()
            watchJob = null
            if (mode == NetworkMode.CELLULAR) cellular.stop()
            _state.value = ProxyNetworkProvider.State.Unavailable
            throw e
        }
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        started = false
        if (callbackRegistered) {
            runCatching { cm.unregisterNetworkCallback(callback) }
            callbackRegistered = false
        }
        watchJob?.cancel()
        watchJob = null
        if (mode == NetworkMode.CELLULAR) cellular.stop()
        current = null
        _state.value = ProxyNetworkProvider.State.Idle
    }

    override suspend fun awaitAvailable(timeoutMs: Long): Network? =
        withTimeoutOrNull(timeoutMs) {
            (state.first { it is ProxyNetworkProvider.State.Available }
                as ProxyNetworkProvider.State.Available).network
        }

    private fun activeNetwork(): Network =
        current ?: throw IllegalStateException("Selected outbound network unavailable")

    override fun resolveAll(host: String): List<InetAddress> =
        if (mode == NetworkMode.CELLULAR) cellular.resolveAll(host)
        else runCatching { activeNetwork().getAllByName(host).toList() }
            .getOrElse { emptyList() }

    override fun hasIpv6(): Boolean {
        if (mode == NetworkMode.CELLULAR) return cellular.hasIpv6()
        val lp = runCatching { cm.getLinkProperties(activeNetwork()) }.getOrNull() ?: return false
        return lp.linkAddresses.any {
            it.address is Inet6Address &&
                !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
        }
    }

    override fun createBoundSocket(): Socket {
        if (mode == NetworkMode.CELLULAR) return cellular.createBoundSocket()
        val socket = Socket()
        try {
            activeNetwork().bindSocket(socket)
            return socket
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    override fun createBoundDatagramSocket(): DatagramSocket {
        if (mode == NetworkMode.CELLULAR) return cellular.createBoundDatagramSocket()
        val socket = DatagramSocket()
        try {
            activeNetwork().bindSocket(socket)
            return socket
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }
}
