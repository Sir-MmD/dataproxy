package com.dataproxy.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object CellularAvailability {

    /**
     * Cheap synchronous probe: is there any cellular Network with INTERNET
     * capability that the system already knows about?
     *
     * If false, mobile data is almost certainly off (no SIM / airplane mode
     * also report false). Use this before kicking off [com.dataproxy.network
     * .CellularNetworkProvider] so we can fail fast with a friendly prompt
     * instead of waiting on the requestNetwork timeout.
     */
    fun isReachable(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return cm.allNetworks.any { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
