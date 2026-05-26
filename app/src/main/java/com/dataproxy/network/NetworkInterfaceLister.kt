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
 * exposed by an up, non-loopback interface — typically the WiFi or
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
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            val ifaceName = iface.displayName ?: iface.name
            val isWifi = ifaceName.startsWith("wlan", ignoreCase = true)

            for (addr: InetAddress in iface.inetAddresses.toList()) {
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
}
