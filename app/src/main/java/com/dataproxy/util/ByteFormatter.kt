package com.dataproxy.util

object ByteFormatter {

    private val sizeUnits = arrayOf("B", "KB", "MB", "GB", "TB")
    private val bytesPerSecUnits = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    private val mbpsUnits = arrayOf("bps", "Kbps", "Mbps", "Gbps")

    fun bytes(n: Long): String = humanise(n, sizeUnits)

    fun rate(bytesPerSec: Long, unit: RateUnit): Pair<String, String> = when (unit) {
        RateUnit.BytesPerSecond -> humanisePair(bytesPerSec.toDouble(), 1024.0, bytesPerSecUnits)
        RateUnit.Mbps -> humanisePair(bytesPerSec.toDouble() * 8.0, 1000.0, mbpsUnits)
    }

    private fun humanise(n: Long, units: Array<String>): String {
        if (n < 0) return "—"
        var v = n.toDouble()
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
        return when {
            i == 0 -> "$n ${units[i]}"
            v >= 100 -> "%.0f %s".format(v, units[i])
            v >= 10 -> "%.1f %s".format(v, units[i])
            else -> "%.2f %s".format(v, units[i])
        }
    }

    private fun humanisePair(value: Double, base: Double, units: Array<String>): Pair<String, String> {
        if (value <= 0) return "0" to units[0]
        var v = value
        var i = 0
        while (v >= base && i < units.lastIndex) { v /= base; i++ }
        val number = when {
            i == 0 -> v.toLong().toString()
            v >= 100 -> "%.0f".format(v)
            v >= 10 -> "%.1f".format(v)
            else -> "%.2f".format(v)
        }
        return number to units[i]
    }

    fun elapsed(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val secs = ((nowMs - sinceMs) / 1000L).coerceAtLeast(0L)
        return when {
            secs < 60 -> "${secs}s"
            secs < 3600 -> "${secs / 60}m ${secs % 60}s"
            else -> "%dh %dm".format(secs / 3600, (secs % 3600) / 60)
        }
    }
}
