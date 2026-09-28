package com.vellum.studio.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * The address the sync server binds to and shows on the Connect screen. One source for both, so the
 * screen can never advertise an address the server isn't actually listening on.
 */
object NetworkUtils {
    /**
     * The tablet's IPv4 address on its Wi-Fi (or wired-LAN adapter) network, or null when it has none.
     *
     * Null is a real answer the caller must act on, not something to paper over: the previous version
     * fell back to "the first non-loopback interface", which on a tablet with mobile data (or a VPN)
     * can be the cellular or tunnel address -- and, combined with binding the wildcard, that is how the
     * server ended up reachable from networks the user never meant to share on. The server now refuses
     * to start without a LAN address instead of guessing.
     *
     * Read from ConnectivityManager rather than the deprecated WifiManager.connectionInfo, which is
     * unreliable on API 31+ and only ever describes Wi-Fi.
     */
    fun lanIpAddress(context: Context): String? = try {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.allNetworks.orEmpty()
            .filter { network ->
                val caps = cm?.getNetworkCapabilities(network)
                caps != null && isLanTransport(
                    wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                )
            }
            .flatMap { cm?.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    /**
     * Whether a network with these transports counts as "the LAN". A VPN network reports the
     * transports of the network it rides on (so it can look like Wi-Fi as well as VPN), and a network
     * that is also cellular is a mobile-data route; both are excluded so the bind address can only be
     * a plain Wi-Fi/Ethernet address.
     */
    internal fun isLanTransport(wifi: Boolean, ethernet: Boolean, vpn: Boolean, cellular: Boolean): Boolean =
        (wifi || ethernet) && !vpn && !cellular
}
