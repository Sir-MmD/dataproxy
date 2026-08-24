package com.dataproxy.network

import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Lists local bind candidates for the listen-address picker.
 *
 * Returns 0.0.0.0 first ("any interface"), followed by every IPv4 address
 * exposed by an up, non-loopback interface, typically the WiFi or
 * USB-tether IP that LAN clients use to reach the phone.
 */
object NetworkInterfaceLister {

    data class Candidate(
        val address: String,
        val label: String,
        val isWildcard: Boolean = false,
        val isWifi: Boolean = false,
    )

    fun list(): List<Candidate> {
        val out = mutableListOf<Candidate>()
        out += Candidate(
            address = "0.0.0.0",
            label = "Any interface",
            isWildcard = true,
        )

        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            Log.w("NetIface", "enum failed: ${e.message}")
            emptyList()
        }

        for (iface in ifaces) {
            // isUp/isLoopback are live ioctls, not cached reads off the
            // enumeration snapshot: if the interface disappears between
            // getifaddrs() and this call, which rmnet_data* does constantly,
            // driven by this very app, they raise SocketException(ENODEV).
            // Outside a guard that propagates to onCreate and kills the app.
            // isVirtual is a plain field read and stays in the filter:
            // NetworkInterface.getAll() puts alias interfaces ("wlan0:1") in
            // the top-level array with virtual = true, so dropping the check
            // would surface them as extra rows in the picker.
            val usable = runCatching {
                iface.isUp && !iface.isLoopback && !iface.isVirtual
            }.getOrNull() ?: false
            if (!usable) continue
            val ifaceName = runCatching { iface.displayName ?: iface.name }.getOrNull() ?: continue
            val isWifi = WIFI_LIKE_PREFIXES.any { ifaceName.startsWith(it, ignoreCase = true) }

            val addresses = runCatching { iface.inetAddresses.toList() }.getOrNull() ?: continue
            for (addr: InetAddress in addresses) {
                if (addr.isLinkLocalAddress || addr.isAnyLocalAddress) continue
                if (addr is Inet6Address) continue // SOCKS5 server binds via IPv4 here

                val host = addr.hostAddress ?: continue
                if (addr is Inet4Address && host == "127.0.0.1") continue

                out += Candidate(
                    address = host,
                    label = ifaceName,
                    isWifi = isWifi,
                )
            }
        }
        return out
    }

    // wlan0 is the common case; Samsung's hotspot is swlan0 and other OEMs
    // use ap0/softap0. These were previously unbadged, indistinguishable from
    // the cellular interface. USB tethering (rndis0) is deliberately NOT here
    //, isWifi drives a Wi-Fi glyph in the picker, and a cable is not Wi-Fi.
    private val WIFI_LIKE_PREFIXES = listOf("wlan", "swlan", "softap", "ap")
}
