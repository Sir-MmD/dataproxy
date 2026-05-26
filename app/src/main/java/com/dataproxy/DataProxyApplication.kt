package com.dataproxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import java.security.Security

class DataProxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Disable JVM-level positive + negative DNS caching. Most of our
        // resolves go through Network#getAllByName on the cellular Network,
        // but any JVM fallback (e.g. java.net.InetAddress.getByName from a
        // library, or a future code path) would otherwise cache a poisoned
        // answer for the whole process lifetime — only force-stopping the
        // app would clear it. Cellular carriers in censorship regions
        // occasionally serve hijacked DNS, so a stale poisoned entry would
        // make TLS look broken to every client.
        Security.setProperty("networkaddress.cache.ttl", "0")
        Security.setProperty("networkaddress.cache.negative.ttl", "0")

        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "DataProxy",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Ongoing proxy status"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "dataproxy.status"
    }
}
