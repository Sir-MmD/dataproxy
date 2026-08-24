package com.dataproxy.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-connection book-keeping. Tracks live SOCKS5 sessions, accumulates totals,
 * and surfaces a [grouped] view aggregated by client IP, that's what the UI
 * means by "connected devices".
 */
class ConnectionRegistry {

    /** One live SOCKS5 session. */
    data class Connection(
        val id: Long,
        val clientHost: String,
        val clientPort: Int,
        val target: String,
        val openedAtMs: Long,
        val bytesUp: AtomicLong = AtomicLong(0L),
        val bytesDown: AtomicLong = AtomicLong(0L),
    )

    /** UI-facing aggregate: one row per distinct client IP. */
    data class DeviceSummary(
        val clientHost: String,
        val activeConnections: Int,
        val totalConnections: Int,
        val bytesUp: Long,
        val bytesDown: Long,
        val firstSeenMs: Long,
        val lastSeenMs: Long,
    )

    private val connections = ConcurrentHashMap<Long, Connection>()
    private val deviceStats = ConcurrentHashMap<String, DeviceAccumulator>()
    private val nextId = AtomicLong(1L)

    /**
     * One registry instance is shared across every Socks5Server the service
     * builds, and a stopped connection's relay threads keep unwinding for a
     * while after Stop returns. Membership in [connections] is what makes a
     * report belong to the current cycle: [reset] clears the map, so anything
     * from a previous cycle is already absent by the time it reports in.
     *
     * Without that check a straggler decrements the *new* cycle's accumulator,
     * and since the counter is clamped at zero it pins there and never
     * re-syncs, the Devices tile then reads "0 online" while traffic flows.
     */
    private fun isCurrent(conn: Connection) = connections.containsKey(conn.id)

    private val totalUp = AtomicLong(0L)
    private val totalDown = AtomicLong(0L)

    private val _devices = MutableStateFlow<List<DeviceSummary>>(emptyList())
    val devices: StateFlow<List<DeviceSummary>> = _devices.asStateFlow()

    private val _totals = MutableStateFlow(Totals(0L, 0L, 0))
    val totals: StateFlow<Totals> = _totals.asStateFlow()

    data class Totals(val bytesUp: Long, val bytesDown: Long, val active: Int)

    fun open(clientHost: String, clientPort: Int, target: String): Connection {
        val id = nextId.getAndIncrement()
        val now = System.currentTimeMillis()
        val conn = Connection(id, clientHost, clientPort, target, now)
        connections[id] = conn

        deviceStats.compute(clientHost) { _, prev ->
            val acc = prev ?: DeviceAccumulator(firstSeenMs = now)
            acc.activeConnections.incrementAndGet()
            acc.totalConnections.incrementAndGet()
            acc.lastSeenMs.set(now)
            acc
        }
        refresh()
        return conn
    }

    fun recordUp(conn: Connection, n: Int) {
        if (n <= 0 || !isCurrent(conn)) return
        conn.bytesUp.addAndGet(n.toLong())
        totalUp.addAndGet(n.toLong())
        deviceStats[conn.clientHost]?.let {
            it.bytesUp.addAndGet(n.toLong())
            it.lastSeenMs.set(System.currentTimeMillis())
        }
    }

    fun recordDown(conn: Connection, n: Int) {
        if (n <= 0 || !isCurrent(conn)) return
        conn.bytesDown.addAndGet(n.toLong())
        totalDown.addAndGet(n.toLong())
        deviceStats[conn.clientHost]?.let {
            it.bytesDown.addAndGet(n.toLong())
            it.lastSeenMs.set(System.currentTimeMillis())
        }
    }

    fun close(conn: Connection) {
        // Same predicate as isCurrent, fused with the removal so it is atomic.
        // nextId deliberately survives reset(), so ids never collide across
        // cycles and this cannot retire the wrong accumulator.
        if (connections.remove(conn.id) == null) return
        deviceStats[conn.clientHost]?.let { acc ->
            acc.activeConnections.updateAndGet { (it - 1).coerceAtLeast(0) }
            acc.lastSeenMs.set(System.currentTimeMillis())
        }
        refresh()
    }

    /** Reset all counters. Called when the service is started afresh. */
    fun reset() {
        connections.clear()
        deviceStats.clear()
        totalUp.set(0L)
        totalDown.set(0L)
        refresh()
    }

    /** Snapshot of cumulative byte counters, used by the speed sampler. */
    fun snapshotBytes(): Pair<Long, Long> = totalUp.get() to totalDown.get()

    /** Called periodically (1Hz from the service) to refresh emitted state. */
    fun publish() = refresh()

    private fun refresh() {
        val sorted = deviceStats.entries
            .map { (host, acc) ->
                DeviceSummary(
                    clientHost = host,
                    activeConnections = acc.activeConnections.get(),
                    totalConnections = acc.totalConnections.get(),
                    bytesUp = acc.bytesUp.get(),
                    bytesDown = acc.bytesDown.get(),
                    firstSeenMs = acc.firstSeenMs,
                    lastSeenMs = acc.lastSeenMs.get(),
                )
            }
            .sortedWith(
                compareByDescending<DeviceSummary> { it.activeConnections }
                    .thenByDescending { it.lastSeenMs }
            )
        _devices.value = sorted
        _totals.value = Totals(
            bytesUp = totalUp.get(),
            bytesDown = totalDown.get(),
            active = connections.size,
        )
    }

    // Mutated concurrently from every connection coroutine sharing this
    // client host, so every field that changes after construction needs to
    // be a real atomic, not a plain var, a `+=` here is a lost-update race
    // under concurrent traffic from the same device.
    private class DeviceAccumulator(val firstSeenMs: Long) {
        val activeConnections = AtomicInteger(0)
        val totalConnections = AtomicInteger(0)
        val bytesUp = AtomicLong(0L)
        val bytesDown = AtomicLong(0L)
        val lastSeenMs = AtomicLong(firstSeenMs)
    }
}

/**
 * 1Hz sampler that converts cumulative byte counters into a running rate.
 */
class SpeedSampler {
    data class Rates(val upBps: Long, val downBps: Long)

    private val last = AtomicReference(SampleState(System.nanoTime(), 0L, 0L))
    private val _rates = MutableStateFlow(Rates(0L, 0L))
    val rates: StateFlow<Rates> = _rates.asStateFlow()

    fun sample(bytesUp: Long, bytesDown: Long) {
        val nowNs = System.nanoTime()
        val prev = last.getAndSet(SampleState(nowNs, bytesUp, bytesDown))
        val dtNs = (nowNs - prev.tNs).coerceAtLeast(1L)
        val seconds = dtNs / 1_000_000_000.0
        val upRate = if (seconds <= 0) 0L else ((bytesUp - prev.up).coerceAtLeast(0L) / seconds).toLong()
        val downRate = if (seconds <= 0) 0L else ((bytesDown - prev.down).coerceAtLeast(0L) / seconds).toLong()
        _rates.value = Rates(upRate, downRate)
    }

    fun reset() {
        last.set(SampleState(System.nanoTime(), 0L, 0L))
        _rates.value = Rates(0L, 0L)
    }

    private data class SampleState(val tNs: Long, val up: Long, val down: Long)
}
