package com.vellum.studio.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bind address may only ever come from a plain Wi-Fi/Ethernet network. The ConnectivityManager
 * plumbing around [NetworkUtils.isLanTransport] needs a device; the selection rule does not.
 */
class NetworkUtilsTest {

    @Test
    fun `plain wifi and plain ethernet are the LAN`() {
        assertTrue(NetworkUtils.isLanTransport(wifi = true, ethernet = false, vpn = false, cellular = false))
        assertTrue(NetworkUtils.isLanTransport(wifi = false, ethernet = true, vpn = false, cellular = false))
    }

    @Test
    fun `cellular, VPN and transport-less networks are never the LAN`() {
        assertFalse(NetworkUtils.isLanTransport(wifi = false, ethernet = false, vpn = false, cellular = true))
        assertFalse(NetworkUtils.isLanTransport(wifi = false, ethernet = false, vpn = false, cellular = false))
        // A VPN riding on Wi-Fi can report both transports; it is still a tunnel, not the LAN.
        assertFalse(NetworkUtils.isLanTransport(wifi = true, ethernet = false, vpn = true, cellular = false))
        assertFalse(NetworkUtils.isLanTransport(wifi = true, ethernet = false, vpn = false, cellular = true))
    }
}
