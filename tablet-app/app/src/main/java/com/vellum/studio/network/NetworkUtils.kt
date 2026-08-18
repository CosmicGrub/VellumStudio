package com.vellum.studio.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Best-effort local IPv4 address for display in the Connect screen ("point your PC at this"). */
@Suppress("DEPRECATION")
object NetworkUtils {
    fun localIpAddress(context: Context): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff,
                )
            } else {
                fallbackInterfaceIp()
            }
        } catch (e: Exception) {
            fallbackInterfaceIp()
        }
    }

    private fun fallbackInterfaceIp(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
