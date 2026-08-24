package com.dataproxy.proxy

import android.util.Log
import com.dataproxy.network.CellularNetworkProvider
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-instance SOCKS5 listener. One [start] / [stop] cycle.
 *
 * `bindAddress` may be `"0.0.0.0"` (all interfaces) or a specific local IP from
 * [com.dataproxy.network.NetworkInterfaceLister]. Every accepted client spawns
 * a [Socks5Connection] coroutine on the supervisor scope, so neither a single
 * failed conversation nor a transient accept() error kills the listener.
 *
 * Admission is capped at [MAX_CONNECTIONS], and [stop] force-closes every
 * conversation still in flight. Relay threads block in reads that
 * cancellation cannot reach, so nothing short of closing their sockets stops
 * traffic.
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

    /** Connections currently being handled; bounded by [MAX_CONNECTIONS]. */
    private val live = AtomicInteger(0)

    /**
     * Every conversation currently in flight, so [stop] can force them closed.
     *
     * Closing the ServerSocket only stops *new* connections. An established
     * tunnel is two threads parked in a blocking read() with no SO_TIMEOUT,
     * and coroutine cancellation cannot interrupt those, so without this the
     * proxy keeps relaying over cellular after the user has switched it off.
     */
    private val liveConnections =
        ConcurrentHashMap.newKeySet<Socks5Connection>()

    /**
     * Latches once this server has reported a fatal error or been stopped, so
     * an accept loop descheduled mid-teardown cannot fire onFatal into a
     * *later* cycle and tear down the server that replaced it.
     */
    private val fatalSpent = java.util.concurrent.atomic.AtomicBoolean(false)

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
            if (fatalSpent.compareAndSet(false, true)) onFatal(e)
            return
        }

        serverSocket = socket
        running = true
        Log.i(TAG, "listening on ${socket.inetAddress.hostAddress}:${socket.localPort}")

        acceptJob = scope.launch {
            var consecutiveFailures = 0
            try {
                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        // Only a deliberate shutdown ends the loop. A single
                        // ECONNABORTED (client RSTs between SYN-ACK and accept
                        //, cancelled browser preconnects, port scanners) or a
                        // transient EMFILE used to break out while `running`
                        // stayed true and the socket stayed bound: the kernel
                        // kept completing handshakes into the backlog, so
                        // clients connected at TCP level and then hung forever
                        // with no SOCKS5 reply, under a healthy-looking UI.
                        if (!running || socket.isClosed) break
                        // Bounded. A *persistent* error would otherwise spin
                        // here forever holding the wake lock, with the kernel
                        // still completing handshakes the loop never accepts.
                        // Give up and surface a real error instead. (Android
                        // app processes get a 32768 fd limit, so the cap is not
                        // close to exhausting it, this guards the general
                        // case, not a specific one.)
                        if (++consecutiveFailures > MAX_ACCEPT_FAILURES) {
                            Log.e(TAG, "accept failing persistently, giving up: ${e.message}")
                            break
                        }
                        Log.w(TAG, "accept error, continuing: ${e.message}")
                        delay(ACCEPT_RETRY_DELAY_MS * consecutiveFailures)
                        continue
                    }
                    consecutiveFailures = 0
                    // Admission control. Every CONNECT tunnel pins two OS
                    // threads for its lifetime (the up/down copy loops) and
                    // every UDP ASSOCIATE three, on a cached pool that has no
                    // maximum, so accept is the only backpressure there is.
                    // Without this, a client holding thousands of sockets open
                    // takes the process out with OutOfMemoryError:
                    // pthread_create failed. Reject rather than block: blocking
                    // accept would just fill the 64-deep backlog and leave
                    // clients hanging with no SOCKS5 reply.
                    if (live.get() >= MAX_CONNECTIONS) {
                        Log.w(TAG, "at capacity ($MAX_CONNECTIONS), rejecting ${client.remoteSocketAddress}")
                        runCatching { client.close() }
                        continue
                    }
                    live.incrementAndGet()
                    val conn = Socks5Connection(
                        clientSocket = client,
                        cellular = cellular,
                        registry = registry,
                        scope = scope,
                        authProvider = authProvider,
                    )
                    liveConnections.add(conn)
                    // Re-check after publishing. stop() clears `running`
                    // before it snapshots liveConnections, so a connection
                    // accepted concurrently is caught by exactly one side:
                    // either it made the snapshot and stop() aborts it, or it
                    // did not, and this read sees the cleared flag. Without
                    // this, a connection accepted in that window would escape
                    // teardown entirely and keep relaying.
                    if (!running) {
                        liveConnections.remove(conn)
                        live.decrementAndGet()
                        runCatching { conn.abort() }
                        break
                    }
                    // invokeOnCompletion rather than a callback inside the
                    // coroutine body: if the scope is already cancelled, launch
                    // completes without ever running the body (so no finally),
                    // but the completion handler still fires and the slot is
                    // released.
                    conn.handle().invokeOnCompletion {
                        liveConnections.remove(conn)
                        live.decrementAndGet()
                    }
                }
            } finally {
                Log.i(TAG, "accept loop exited")
                // Reaching here while still nominally running means the loop
                // died on something unrecoverable. Surface it instead of
                // sitting in a Running state with no listener.
                //
                // `running` is deliberately NOT cleared first: onFatal routes
                // through ProxyService.fullCleanup() -> stop(), and stop()
                // early-returns when running is already false, which would
                // skip closing every live connection, the exact leak this
                // class now exists to prevent.
                if (running && fatalSpent.compareAndSet(false, true)) {
                    onFatal(IOException("accept loop exited unexpectedly"))
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        // Both before anything else: the accept loop's handoff at the add-site
        // depends on `running` being cleared before the snapshot below, and
        // fatalSpent stops a descheduled loop from reporting into the next
        // cycle.
        running = false
        fatalSpent.set(true)
        runCatching { serverSocket?.close() }
        serverSocket = null

        // Force every established tunnel down. Cancellation alone cannot touch
        // a thread blocked in read(); closing the sockets is what makes that
        // read throw and unwind.
        //
        // Off the caller's thread on purpose. stopProxy() runs on the main
        // thread, and Socket.close() on ART goes through
        // AsynchronousCloseMonitor, which walks every I/O-blocked thread in
        // the process under a global mutex, with two relay threads per tunnel
        // that is quadratic, and at a few hundred tunnels it is a visible
        // freeze on Stop. Teardown is safe to be eventually consistent here:
        // the listener is already closed above, so nothing new arrives, and
        // ConnectionRegistry retires stragglers by connection id, so whenever
        // they land they cannot disturb the next cycle.
        val doomed = liveConnections.toList()
        if (doomed.isNotEmpty()) {
            Log.i(TAG, "closing ${doomed.size} live connection(s)")
            // Deliberately NOT on `scope`, that is cancelled a few lines down
            // and would kill this before it closed anything, resurrecting the
            // very leak it exists to prevent. Bounded fire-and-forget work:
            // one close() per socket, then done.
            // The handler is defensive: everything below is runCatching-wrapped
            // today, but this scope has no parent, so anything that escaped
            // would reach the default uncaught handler and kill the process.
            CoroutineScope(RelayDispatcher + CoroutineExceptionHandler { _, _ -> }).launch {
                doomed.forEach { runCatching { it.abort() } }
                Log.i(TAG, "teardown swept ${doomed.size} connection(s)")
            }
        }

        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
        Log.i(TAG, "stopped")
    }

    companion object {
        private const val TAG = "Socks5Server"
        private const val BACKLOG = 64

        /**
         * Ceiling on simultaneously-handled connections.
         *
         * Relay I/O runs on an unbounded cached thread pool, so this is what
         * bounds thread count: worst case ~2x this many relay threads for
         * CONNECT tunnels. 512 leaves generous headroom over the few-hundred
         * connections a whole-network tun2socks setup actually opens, while
         * still capping the pathological case.
         */
        private const val MAX_CONNECTIONS = 512

        /** Backoff unit after a recoverable accept() error; scales with the failure run. */
        private const val ACCEPT_RETRY_DELAY_MS = 50L

        /** Consecutive accept() failures tolerated before declaring the listener dead. */
        private const val MAX_ACCEPT_FAILURES = 20
    }
}
