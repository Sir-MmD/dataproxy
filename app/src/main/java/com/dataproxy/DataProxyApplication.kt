package com.dataproxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class DataProxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
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
