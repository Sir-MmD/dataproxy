package com.dataproxy.proxy

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Dispatcher for blocking socket I/O on the SOCKS5 data path: handshake
 * reads/writes, TCP relay copy loops, UDP receive loops. Each of these is a
 * synchronous blocking call, so it occupies one OS thread for as long as it
 * runs, for a relay copy loop, that's the lifetime of the connection.
 *
 * Dispatchers.IO caps concurrently-running tasks at its parallelism limit
 * (64 by default). With ~150 tunnels each holding 2 blocking reads open for
 * their whole lifetime, that limit is exhausted well before the connection
 * count is, so most connections queue behind the 64 that got a thread.
 * this was the root cause of throughput collapsing under concurrency. A
 * cached pool grows with actual concurrent load instead of hitting a fixed
 * ceiling, and reclaims idle threads after 60s.
 */
val RelayDispatcher = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "socks5-relay").apply { isDaemon = true }
}.asCoroutineDispatcher()
