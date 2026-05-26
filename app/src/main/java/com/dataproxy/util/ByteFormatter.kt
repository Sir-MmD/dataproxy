package com.dataproxy.util

object ByteFormatter {

    private val sizeUnits = arrayOf("B", "KB", "MB", "GB", "TB")
    private val rateUnits = arrayOf("B/s", "KB/s", "MB/s", "GB/s")

    fun bytes(n: Long): String = humanise(n, sizeUnits)
    fun rate(bps: Long): String = humanise(bps, rateUnits)

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

    fun elapsed(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val secs = ((nowMs - sinceMs) / 1000L).coerceAtLeast(0L)
        return when {
            secs < 60 -> "${secs}s"
            secs < 3600 -> "${secs / 60}m ${secs % 60}s"
            else -> "%dh %dm".format(secs / 3600, (secs % 3600) / 60)
        }
    }
}
